package org.olcbox.app.vpn.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Окно объявляется нерастягиваемым ТОЛЬКО под тайлингом: иначе на всех платформах разом становится
 * недостижимой широкая раскладка (от 700 dp список локаций уезжает в левую панель). Ошибка в любую
 * сторону тихая — либо окно затягивает в тайл, либо у всех пропадает раскладка, — поэтому правило
 * проверяем тестом, а не глазами.
 */
class TilingCompositorTest {

    @Test
    fun socketsOfTilingCompositorsAreDetected() {
        assertTrue(tilingCompositorFromEnv("/run/user/1000/niri.sock", null, null, null))
        assertTrue(tilingCompositorFromEnv(null, "/run/user/1000/sway-ipc.sock", null, null))
        assertTrue(tilingCompositorFromEnv(null, null, "abc123", null))
    }

    @Test
    fun desktopNameIsTheFallback() {
        for (desktop in listOf("niri", "Hyprland", "sway:wlroots", "i3", "river")) {
            assertTrue(tilingCompositorFromEnv(null, null, null, desktop), desktop)
        }
    }

    /** Обычные окружения обязаны остаться растягиваемыми — там широкая раскладка нужна и доступна. */
    @Test
    fun floatingDesktopsStayResizable() {
        // Windows и macOS не выставляют этих переменных вовсе.
        for (desktop in listOf(null, "", "GNOME", "KDE", "XFCE", "ubuntu:GNOME")) {
            assertFalse(tilingCompositorFromEnv(null, null, null, desktop), desktop.orEmpty())
        }
    }
}
