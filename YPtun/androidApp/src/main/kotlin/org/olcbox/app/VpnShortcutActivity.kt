package org.olcbox.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import androidx.core.content.ContextCompat
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.service.OlcboxVpnActions
import org.olcbox.app.vpn.service.OlcboxVpnState

/** Starts/stops the VPN service in the background — shared by the Quick Settings tile and the app shortcuts. */
internal object VpnServiceControl {
    /** True while the VPN is up or on its way up — what a "toggle" should switch off. */
    fun isActive(): Boolean = when (OlcboxVpnState.status.value) {
        is VpnStatus.Connected, is VpnStatus.Connecting, is VpnStatus.Reconnecting -> true
        else -> false
    }

    /** False when the VPN permission is not granted yet — the caller has to bring the app up for it. */
    fun start(context: Context): Boolean {
        if (VpnService.prepare(context) != null) return false
        ContextCompat.startForegroundService(context, serviceIntent(context, OlcboxVpnActions.ACTION_START_VPN))
        return true
    }

    fun stop(context: Context) {
        context.startService(serviceIntent(context, OlcboxVpnActions.ACTION_STOP_VPN))
    }

    private fun serviceIntent(context: Context, action: String) = Intent().apply {
        setClassName(context.packageName, OlcboxVpnActions.SERVICE_CLASS_NAME)
        this.action = action
    }
}

/**
 * Target of the launcher shortcuts «Включить / Выключить / Вкл/выкл VPN» (res/xml/shortcuts.xml). Those
 * are what Samsung «Режимы и сценарии» (and any launcher) offer as an app action, so a routine can switch
 * the VPN without the app window popping up. No UI: acts and finishes at once; only a missing VPN
 * permission opens the app, where the system dialog can be shown.
 *
 * Not exported: launchers and Routines start shortcuts on the app's behalf, and an exported "VPN off"
 * would let any app silently drop the tunnel.
 */
class VpnShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val turnOn = when (intent?.action) {
            ACTION_ON -> true
            ACTION_OFF -> false
            ACTION_TOGGLE -> !VpnServiceControl.isActive()
            else -> null
        }
        when (turnOn) {
            true -> if (!VpnServiceControl.isActive() && !VpnServiceControl.start(this)) {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("yptun://control/start"), this, AppActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            false -> VpnServiceControl.stop(this)
            null -> Unit
        }
        finish()
    }

    companion object {
        const val ACTION_ON = "org.yptun.app.action.VPN_ON"
        const val ACTION_OFF = "org.yptun.app.action.VPN_OFF"
        const val ACTION_TOGGLE = "org.yptun.app.action.VPN_TOGGLE"
    }
}
