use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, Once};
use std::thread;

use snolc::{Deployment, Engine, EngineHandle, Event, Host};

static INIT: Once = Once::new();

fn init_builtin_modules() {
    INIT.call_once(|| {
        snolc::loader::register_builtin("adapter-socks5", snolc_adapter_socks5::snolc_module_entry);
        snolc::loader::register_builtin("adapter-direct", snolc_adapter_direct::snolc_module_entry);
        snolc::loader::register_builtin("protection-noise", snolc_protection_noise::snolc_module_entry);
        snolc::loader::register_builtin("carrier-tcp", snolc_carrier_tcp::snolc_module_entry);
        snolc::loader::register_builtin("policy-dummy", snolc_policy_dummy::snolc_module_entry);
    });
}

struct FfiHost;

impl Host for FfiHost {
    fn engine_event(&self, _event: &Event) {
        // Events can be logged or forwarded to app if needed
    }
}

struct SnolcState {
    handles: Vec<EngineHandle>,
    threads: Vec<thread::JoinHandle<()>>,
    running: Arc<AtomicBool>,
}

static STATE: Mutex<Option<SnolcState>> = Mutex::new(None);

#[no_mangle]
pub extern "C" fn snolc_ffi_version() -> *const c_char {
    static VERSION: &[u8] = b"0.0.4\0";
    VERSION.as_ptr() as *const c_char
}

#[no_mangle]
pub extern "C" fn snolc_ffi_start(config_path_ptr: *const c_char) -> *mut c_char {
    if config_path_ptr.is_null() {
        return to_c_string("snolc_ffi_start: config path is null");
    }

    let config_path_str = match unsafe { CStr::from_ptr(config_path_ptr) }.to_str() {
        Ok(s) => s,
        Err(e) => return to_c_string(&format!("invalid utf-8 path: {e}")),
    };

    let path = Path::new(config_path_str);
    if !path.exists() {
        return to_c_string(&format!("config file does not exist: {config_path_str}"));
    }

    init_builtin_modules();

    let mut state_guard = match STATE.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };

    if let Some(ref state) = *state_guard {
        if state.running.load(Ordering::Acquire) {
            return to_c_string("snolc engine is already running");
        }
    }

    // Load deployment config from disk
    let deployment = match Deployment::load(path) {
        Ok(d) => d,
        Err(e) => return to_c_string(&format!("failed to load deployment: {e}")),
    };

    let (config, modules) = deployment.into_parts();

    let validated = match Engine::validate(config, modules) {
        Ok(v) => v,
        Err(e) => return to_c_string(&format!("failed to validate engine: {e}")),
    };

    let (mut engine, handle) = match Engine::build(validated, FfiHost) {
        Ok(b) => b,
        Err(e) => return to_c_string(&format!("failed to build engine: {e}")),
    };

    let running = Arc::new(AtomicBool::new(true));
    let running_thread = Arc::clone(&running);

    let thread_handle = match thread::Builder::new()
        .name("snolc-worker".into())
        .spawn(move || {
            let _ = engine.run();
            running_thread.store(false, Ordering::Release);
        }) {
        Ok(th) => th,
        Err(e) => return to_c_string(&format!("failed to spawn worker thread: {e}")),
    };

    *state_guard = Some(SnolcState {
        handles: vec![handle],
        threads: vec![thread_handle],
        running,
    });

    std::ptr::null_mut()
}

#[no_mangle]
pub extern "C" fn snolc_ffi_stop() {
    let mut state_guard = match STATE.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };

    if let Some(state) = state_guard.take() {
        state.running.store(false, Ordering::Release);
        for handle in state.handles {
            let _ = handle.shutdown();
        }
        for thread in state.threads {
            let _ = thread.join();
        }
    }
}

#[no_mangle]
pub extern "C" fn snolc_ffi_is_running() -> bool {
    let state_guard = match STATE.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };

    if let Some(ref state) = *state_guard {
        state.running.load(Ordering::Acquire)
    } else {
        false
    }
}

#[no_mangle]
pub extern "C" fn snolc_ffi_free_string(ptr: *mut c_char) {
    if !ptr.is_null() {
        unsafe {
            let _ = CString::from_raw(ptr);
        }
    }
}

fn to_c_string(msg: &str) -> *mut c_char {
    CString::new(msg)
        .unwrap_or_else(|_| CString::new("internal string conversion error").unwrap())
        .into_raw()
}
