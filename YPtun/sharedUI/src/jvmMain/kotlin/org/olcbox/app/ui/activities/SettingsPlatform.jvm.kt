package org.olcbox.app.ui.activities

import org.olcbox.app.desktop.DesktopToast
import org.olcbox.app.desktop.DesktopUriLauncher
import org.olcbox.app.vpn.telegram.DesktopTelegramProxy

internal actual object SettingsPlatform {
    actual fun toast(message: String) = DesktopToast.show(message)

    actual fun openUri(uri: String) {
        DesktopUriLauncher.open(uri)
    }

    actual fun schemeRegistered(scheme: String): Boolean = DesktopUriLauncher.schemeRegistered(scheme)

    actual val telegramProxyEndpoint: String
        get() = "${DesktopTelegramProxy.LISTEN_HOST}:${DesktopTelegramProxy.LISTEN_PORT}"

    // AWT's native dialog blocks its caller, so it runs off the UI thread.
    actual fun pickTextFile(title: String, onResult: (String?) -> Unit) {
        Thread {
            val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
            dialog.isVisible = true
            onResult(dialog.files.firstOrNull()?.let { runCatching { it.readText() }.getOrNull() })
        }.apply { isDaemon = true }.start()
    }

    // Every version comes from the running core (yptuncore), like libbox/xraybridge on Android.
    actual fun coreVersion(core: CoreName): String {
        val c = org.olcbox.app.vpn.desktop.YpTunCore
        return when (core) {
            CoreName.Xray -> c.xrayVersion()
            CoreName.SingBox -> c.sbVersion()
            CoreName.OlcRtc -> c.rtcVersion()
            CoreName.Freeturn -> c.ftVersion()
            CoreName.Wdtt -> c.wdttVersion()
            CoreName.AmneziaWg -> c.awgVersion()
        }
    }
}
