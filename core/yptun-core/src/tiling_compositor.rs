//! Tiling compositor / WM detection (Linux desktop). Under one the main window is declared
//! non-resizable so the WM keeps it floating instead of tiling it. Port of TilingCompositor.kt.
//!
//! niri, Sway and Hyprland export their socket path — more reliable than XDG_CURRENT_DESKTOP,
//! which is kept as a fallback and also covers X11 tilers (i3, river, dwl, qtile).

pub const TILING_DESKTOPS: [&str; 9] =
    ["niri", "sway", "hyprland", "river", "dwl", "qtile", "i3", "bspwm", "awesome"];

/// The rule itself, separate from reading the environment. A set variable counts even if empty.
pub fn tiling_compositor_from_env(
    niri_socket: Option<&str>,
    sway_sock: Option<&str>,
    hyprland_signature: Option<&str>,
    xdg_current_desktop: Option<&str>,
) -> bool {
    if niri_socket.is_some() || sway_sock.is_some() || hyprland_signature.is_some() {
        return true;
    }
    let desktop = xdg_current_desktop.unwrap_or("").to_lowercase();
    if desktop.trim().is_empty() {
        return false;
    }
    TILING_DESKTOPS.iter().any(|d| desktop.contains(d))
}

pub fn is_tiling_compositor() -> bool {
    let get = |k: &str| std::env::var_os(k).map(|v| v.to_string_lossy().into_owned());
    let niri = get("NIRI_SOCKET");
    let sway = get("SWAYSOCK");
    let hypr = get("HYPRLAND_INSTANCE_SIGNATURE");
    let xdg = get("XDG_CURRENT_DESKTOP");
    tiling_compositor_from_env(niri.as_deref(), sway.as_deref(), hypr.as_deref(), xdg.as_deref())
}
