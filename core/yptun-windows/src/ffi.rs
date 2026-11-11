use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::PathBuf;

use crate::WindowsApp;

fn read_utf8(value: *const c_char) -> String {
    if value.is_null() {
        return String::new();
    }
    // SAFETY: the C# caller supplies a NUL-terminated UTF-8 string for the duration of the call.
    unsafe { CStr::from_ptr(value) }
        .to_string_lossy()
        .into_owned()
}

fn into_c_string(value: String) -> *mut c_char {
    let sanitized = value.replace('\0', "");
    CString::new(sanitized)
        .map(CString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

fn panic_response() -> *mut c_char {
    into_c_string(r#"{"ok":false,"data":null,"error":"native core panicked"}"#.into())
}

#[unsafe(no_mangle)]
/// # Safety
/// Every string pointer must be either null or a valid NUL-terminated UTF-8 string for this call.
pub unsafe extern "C" fn yptun_windows_create(
    app_data_directory: *const c_char,
    native_directory: *const c_char,
    locale: *const c_char,
    app_version: *const c_char,
) -> *mut WindowsApp {
    catch_unwind(AssertUnwindSafe(|| {
        let app = WindowsApp::new(
            PathBuf::from(read_utf8(app_data_directory)),
            PathBuf::from(read_utf8(native_directory)),
            &read_utf8(locale),
            &read_utf8(app_version),
        );
        Box::into_raw(Box::new(app))
    }))
    .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
/// # Safety
/// `app` must be a live handle returned by [`yptun_windows_create`], and `request_json` must be a
/// valid NUL-terminated UTF-8 string for this call.
pub unsafe extern "C" fn yptun_windows_command(
    app: *mut WindowsApp,
    request_json: *const c_char,
) -> *mut c_char {
    if app.is_null() {
        return into_c_string(
            r#"{"ok":false,"data":null,"error":"native context is null"}"#.into(),
        );
    }
    catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: the handle comes from Box::into_raw in yptun_windows_create and stays owned by C#.
        let app = unsafe { &*app };
        into_c_string(app.command_json(&read_utf8(request_json)))
    }))
    .unwrap_or_else(|_| panic_response())
}

#[unsafe(no_mangle)]
/// # Safety
/// `value` must be null or a string returned by [`yptun_windows_command`] that has not been freed.
pub unsafe extern "C" fn yptun_windows_string_free(value: *mut c_char) {
    if value.is_null() {
        return;
    }
    // SAFETY: every returned string is allocated with CString::into_raw and freed exactly once.
    unsafe { drop(CString::from_raw(value)) };
}

#[unsafe(no_mangle)]
/// # Safety
/// `app` must be null or a live handle returned by [`yptun_windows_create`] and destroyed once.
pub unsafe extern "C" fn yptun_windows_destroy(app: *mut WindowsApp) {
    if app.is_null() {
        return;
    }
    let _ = catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: the handle comes from Box::into_raw and C# destroys it once.
        unsafe { drop(Box::from_raw(app)) };
    }));
}
