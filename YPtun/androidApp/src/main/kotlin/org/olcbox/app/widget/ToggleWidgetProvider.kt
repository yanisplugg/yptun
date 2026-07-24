package org.olcbox.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.olcbox.app.R
import org.olcbox.app.vpn.service.OlcboxVpnActions

/**
 * Variant 1 — a single power button on the home screen. One tap connects the active server (or opens
 * the app to grant VPN permission) / disconnects. Repaints when the service broadcasts
 * [OlcboxVpnActions.ACTION_WIDGET_REFRESH].
 */
class ToggleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> render(context, manager, id) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            OlcboxVpnActions.ACTION_WIDGET_REFRESH -> {
                val manager = AppWidgetManager.getInstance(context)
                manager.getAppWidgetIds(WidgetSupport.componentToggle(context))
                    .forEach { id -> render(context, manager, id) }
            }
            WidgetSupport.ACTION_TOGGLE -> WidgetSupport.handleToggle(context)
        }
    }

    private fun render(context: Context, manager: AppWidgetManager, id: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_toggle)
        WidgetSupport.renderToggle(context, views)
        manager.updateAppWidget(id, views)
    }
}
