package org.olcbox.app.ui.activities

import org.olcbox.app.ios.IosPlatformHooks
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

internal actual object SettingsPlatform {
    actual fun toast(message: String) {
        IosPlatformHooks.bridge?.showMessage(message)
    }

    actual fun openUri(uri: String) {
        NSURL.URLWithString(uri)?.let { UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any?>(), null) }
    }

    actual fun schemeRegistered(scheme: String): Boolean =
        NSURL.URLWithString("$scheme://")?.let { UIApplication.sharedApplication.canOpenURL(it) } ?: false

    // No Telegram-over-WARP proxy on iOS yet (it is a second tunnel in the app process elsewhere).
    actual val telegramProxyEndpoint: String get() = ""

    actual fun pickTextFile(title: String, onResult: (String?) -> Unit) {
        val bridge = IosPlatformHooks.bridge ?: return onResult(null)
        bridge.pickConfigText(object : org.olcbox.app.ios.IosTextCallback {
            override fun onSuccess(text: String) = onResult(text)
            override fun onError(message: String) = onResult(null)
        })
    }

    actual fun coreVersion(core: CoreName): String {
        val c = IosPlatformHooks.core ?: return ""
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
