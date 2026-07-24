package org.olcbox.app.widget

import android.content.Context
import android.content.Intent
import org.olcbox.app.vpn.service.OlcboxVpnActions

/**
 * Repaints the home-screen widgets (in androidApp) from shared code WITHOUT a compile-time reference
 * to the provider classes — sends an explicit broadcast targeted by class name. Called whenever the
 * connection status, live speed, or the ACTIVE LOCATION changes, so the widget always mirrors the
 * app (and vice-versa: a node switched on the widget persists, then this re-syncs the app's view).
 */
object WidgetRefresh {
    private val TARGETS = arrayOf(
        "org.olcbox.app.widget.ToggleWidgetProvider",
        "org.olcbox.app.widget.StatusWidgetProvider"
    )

    fun ping(context: Context) {
        val pkg = context.packageName
        for (cls in TARGETS) {
            runCatching {
                context.sendBroadcast(
                    Intent(OlcboxVpnActions.ACTION_WIDGET_REFRESH).setClassName(pkg, cls)
                )
            }
        }
    }
}
