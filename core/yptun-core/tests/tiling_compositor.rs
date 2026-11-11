use yptun_core::tiling_compositor::*;

#[test]
fn sockets_win() {
    assert!(tiling_compositor_from_env(Some("/run/niri.sock"), None, None, None));
    assert!(tiling_compositor_from_env(None, Some(""), None, Some("GNOME")));
    assert!(tiling_compositor_from_env(None, None, Some("abc"), None));
}

#[test]
fn xdg_fallback() {
    assert!(!tiling_compositor_from_env(None, None, None, None));
    assert!(!tiling_compositor_from_env(None, None, None, Some("  ")));
    assert!(!tiling_compositor_from_env(None, None, None, Some("KDE")));
    assert!(!tiling_compositor_from_env(None, None, None, Some("GNOME")));
    assert!(tiling_compositor_from_env(None, None, None, Some("sway:wlroots")));
    assert!(tiling_compositor_from_env(None, None, None, Some("Hyprland")));
    assert!(tiling_compositor_from_env(None, None, None, Some("i3")));
}
