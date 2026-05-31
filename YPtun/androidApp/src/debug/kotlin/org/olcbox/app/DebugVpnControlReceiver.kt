package org.olcbox.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.core.content.ContextCompat
import org.olcbox.app.vpn.service.OlcboxVpnActions

class DebugVpnControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!context.isDebuggableApp()) return

        val serviceAction = when (intent.action) {
            ACTION_DEBUG_START_VPN -> OlcboxVpnActions.ACTION_START_VPN
            ACTION_DEBUG_STOP_VPN -> OlcboxVpnActions.ACTION_STOP_VPN
            else -> return
        }

        val serviceIntent = Intent().apply {
            setClassName(context.packageName, OlcboxVpnActions.SERVICE_CLASS_NAME)
            action = serviceAction
        }

        if (serviceAction == OlcboxVpnActions.ACTION_STOP_VPN) {
            context.startService(serviceIntent)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    private fun Context.isDebuggableApp(): Boolean {
        return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    companion object {
        const val ACTION_DEBUG_START_VPN = "org.olcbox.app.DEBUG_START_VPN"
        const val ACTION_DEBUG_STOP_VPN = "org.olcbox.app.DEBUG_STOP_VPN"
    }
}
