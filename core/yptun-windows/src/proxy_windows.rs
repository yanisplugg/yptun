use yptun_core::desktop_proxy::{
    proxy_enable_text, RegistryEdit, WindowsProxyController, WindowsProxyPlan, WindowsProxyState,
    INTERNET_OPTION_REFRESH, INTERNET_OPTION_SETTINGS_CHANGED, REGISTRY_KEY,
};

#[cfg(windows)]
use windows_sys::Win32::Networking::WinInet::InternetSetOptionW;
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
    notify_wininet();
    Ok(())
}

#[cfg(not(windows))]
fn apply_plan(_plan: &WindowsProxyPlan) -> Result<(), String> {
    Err("Windows system proxy is unavailable on this platform".into())
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
