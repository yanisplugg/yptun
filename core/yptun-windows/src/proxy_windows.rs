use yptun_core::desktop_proxy::{
    proxy_enable_text, PerConnectionOptions, RegistryEdit, WindowsProxyController,
    WindowsProxyPlan, WindowsProxyState, INTERNET_OPTION_PER_CONNECTION_OPTION,
    INTERNET_OPTION_REFRESH, INTERNET_OPTION_SETTINGS_CHANGED, REGISTRY_KEY,
};

#[cfg(windows)]
use windows_sys::Win32::Networking::WinInet::{
    InternetSetOptionW, INTERNET_PER_CONN_FLAGS, INTERNET_PER_CONN_OPTIONW,
    INTERNET_PER_CONN_OPTIONW_0, INTERNET_PER_CONN_OPTION_LISTW, INTERNET_PER_CONN_PROXY_BYPASS,
    INTERNET_PER_CONN_PROXY_SERVER,
};
#[cfg(windows)]
use winreg::enums::{HKEY_CURRENT_USER, KEY_READ, KEY_SET_VALUE};
#[cfg(windows)]
use winreg::RegKey;

pub struct SystemProxy {
    controller: WindowsProxyController,
}

impl Default for SystemProxy {
    fn default() -> Self {
        Self::new()
    }
}

impl SystemProxy {
    pub fn new() -> Self {
        Self {
            controller: WindowsProxyController::new(),
        }
    }

    pub fn clear_stale(&mut self) {
        let current = read_state();
        if let Some(plan) = self.controller.clear_stale_proxy(&current) {
            let _ = apply_plan(&plan);
        }
    }

    pub fn enable(&mut self, host_port: &str) -> Result<(), String> {
        let plan = self.controller.enable(read_state(), host_port);
        apply_plan(&plan)
    }

    pub fn restore(&mut self) -> Result<(), String> {
        match self.controller.restore() {
            Some(plan) => apply_plan(&plan),
            None => Ok(()),
        }
    }
}

#[cfg(windows)]
fn read_state() -> WindowsProxyState {
    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    let Ok(key) = hkcu.open_subkey_with_flags(REGISTRY_KEY, KEY_READ) else {
        return WindowsProxyState::disabled();
    };
    let dword = key
        .get_value::<u32, _>("ProxyEnable")
        .ok()
        .map(|value| proxy_enable_text(value as i32));
    WindowsProxyState {
        proxy_enable: dword,
        proxy_server: key
            .get_value::<String, _>("ProxyServer")
            .ok()
            .filter(|value| !value.trim().is_empty()),
        proxy_override: key
            .get_value::<String, _>("ProxyOverride")
            .ok()
            .filter(|value| !value.trim().is_empty()),
        auto_config_url: key
            .get_value::<String, _>("AutoConfigURL")
            .ok()
            .filter(|value| !value.trim().is_empty()),
    }
}

#[cfg(not(windows))]
fn read_state() -> WindowsProxyState {
    WindowsProxyState::disabled()
}

#[cfg(windows)]
fn apply_plan(plan: &WindowsProxyPlan) -> Result<(), String> {
    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    let (key, _) = hkcu
        .create_subkey_with_flags(REGISTRY_KEY, KEY_READ | KEY_SET_VALUE)
        .map_err(|error| format!("open Internet Settings: {error}"))?;
    for edit in &plan.edits {
        match edit {
            RegistryEdit::SetString { name, value } => key
                .set_value(name, value)
                .map_err(|error| format!("set {name}: {error}"))?,
            RegistryEdit::SetDword { name, value } => key
                .set_value(name, &(*value as u32))
                .map_err(|error| format!("set {name}: {error}"))?,
            RegistryEdit::Delete { name } => {
                let _ = key.delete_value(name);
            }
        }
    }
    apply_per_connection_options(&plan.per_connection)?;
    notify_wininet();
    Ok(())
}

#[cfg(not(windows))]
fn apply_plan(_plan: &WindowsProxyPlan) -> Result<(), String> {
    Err("Windows system proxy is unavailable on this platform".into())
}

#[cfg(windows)]
fn apply_per_connection_options(options: &PerConnectionOptions) -> Result<(), String> {
    let mut server = options.proxy_server.as_deref().map(wide_string);
    let mut bypass = (!options.bypass.trim().is_empty()).then(|| wide_string(&options.bypass));
    let mut values = Vec::with_capacity(options.option_count() as usize);
    values.push(INTERNET_PER_CONN_OPTIONW {
        dwOption: INTERNET_PER_CONN_FLAGS,
        Value: INTERNET_PER_CONN_OPTIONW_0 {
            dwValue: options.flags() as u32,
        },
    });
    if let Some(value) = server.as_mut() {
        values.push(INTERNET_PER_CONN_OPTIONW {
            dwOption: INTERNET_PER_CONN_PROXY_SERVER,
            Value: INTERNET_PER_CONN_OPTIONW_0 {
                pszValue: value.as_mut_ptr(),
            },
        });
        if let Some(value) = bypass.as_mut() {
            values.push(INTERNET_PER_CONN_OPTIONW {
                dwOption: INTERNET_PER_CONN_PROXY_BYPASS,
                Value: INTERNET_PER_CONN_OPTIONW_0 {
                    pszValue: value.as_mut_ptr(),
                },
            });
        }
    }

    let mut list = INTERNET_PER_CONN_OPTION_LISTW {
        dwSize: std::mem::size_of::<INTERNET_PER_CONN_OPTION_LISTW>() as u32,
        pszConnection: std::ptr::null_mut(),
        dwOptionCount: values.len() as u32,
        dwOptionError: 0,
        pOptions: values.as_mut_ptr(),
    };
    // SAFETY: `list`, `values`, and both UTF-16 buffers remain alive and writable for the full
    // synchronous WinINet call. `pszConnection = NULL` selects the default LAN connection.
    let applied = unsafe {
        InternetSetOptionW(
            std::ptr::null(),
            INTERNET_OPTION_PER_CONNECTION_OPTION as u32,
            (&mut list as *mut INTERNET_PER_CONN_OPTION_LISTW).cast(),
            std::mem::size_of::<INTERNET_PER_CONN_OPTION_LISTW>() as u32,
        )
    };
    if applied == 0 {
        Err(format!(
            "WinINet rejected per-connection proxy option {}",
            list.dwOptionError
        ))
    } else {
        Ok(())
    }
}

#[cfg(windows)]
fn wide_string(value: &str) -> Vec<u16> {
    value.encode_utf16().chain(std::iter::once(0)).collect()
}

#[cfg(windows)]
fn notify_wininet() {
    // SAFETY: both calls take no input buffer for these notification-only options.
    unsafe {
        InternetSetOptionW(
            std::ptr::null_mut(),
            INTERNET_OPTION_SETTINGS_CHANGED as u32,
            std::ptr::null_mut(),
            0,
        );
        InternetSetOptionW(
            std::ptr::null_mut(),
            INTERNET_OPTION_REFRESH as u32,
            std::ptr::null_mut(),
            0,
        );
    }
}
