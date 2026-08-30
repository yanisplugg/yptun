package org.olcbox.app.vpn.desktop

/**
 * Признак тайлингового композитора/WM. Под ним главное окно объявляется нерастягиваемым, чтобы WM
 * оставил его плавающим, а не затянул в тайл (см. `resizable` в desktopApp/main.kt).
 *
 * Смотрим на переменные, которые эти WM выставляют сами: у niri, Sway и Hyprland это путь к их
 * сокету — признак надёжнее и дешевле, чем разбор XDG_CURRENT_DESKTOP, который на части сборок
 * пустой или переопределён. XDG_CURRENT_DESKTOP оставлен запасным вариантом и заодно покрывает
 * X11-тайлинги (i3, river, dwl, qtile), где min==max в WM_NORMAL_HINTS даёт тот же эффект.
 */
fun isTilingCompositor(): Boolean = tilingCompositorFromEnv(
    niriSocket = System.getenv("NIRI_SOCKET"),
    swaySock = System.getenv("SWAYSOCK"),
    hyprlandSignature = System.getenv("HYPRLAND_INSTANCE_SIGNATURE"),
    xdgCurrentDesktop = System.getenv("XDG_CURRENT_DESKTOP"),
)

/** Само правило, отдельно от чтения окружения — так его можно проверить тестом. */
internal fun tilingCompositorFromEnv(
    niriSocket: String?,
    swaySock: String?,
    hyprlandSignature: String?,
    xdgCurrentDesktop: String?,
): Boolean {
    if (niriSocket != null || swaySock != null || hyprlandSignature != null) return true
    val desktop = xdgCurrentDesktop.orEmpty().lowercase()
    if (desktop.isBlank()) return false
    return listOf("niri", "sway", "hyprland", "river", "dwl", "qtile", "i3", "bspwm", "awesome")
        .any { it in desktop }
}
