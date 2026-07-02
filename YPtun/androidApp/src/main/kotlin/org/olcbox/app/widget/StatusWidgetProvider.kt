package org.olcbox.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.olcbox.app.R
import org.olcbox.app.data.datasource.LocationsDataSourceImpl
import org.olcbox.app.data.model.LocationViewIndex
import org.olcbox.app.vpn.service.OlcboxVpnActions

/**
 * Variant 2 — the "full" widget: power toggle + active server name + live speed + a ‹ › server
 * switcher. Reads the live status from [org.olcbox.app.vpn.service.OlcboxVpnState] and the server
 * list from the persisted location view-index; switches the active server through the existing
 * [LocationsDataSourceImpl] (with a tunnel restart when connected).
 */
class StatusWidgetProvider : AppWidgetProvider() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refreshAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            OlcboxVpnActions.ACTION_WIDGET_REFRESH -> refreshAll(context)

            WidgetSupport.ACTION_TOGGLE -> WidgetSupport.handleToggle(context)

            WidgetSupport.ACTION_AUTO -> WidgetSupport.handleAuto(context)

            WidgetSupport.ACTION_PREV_NODE, WidgetSupport.ACTION_NEXT_NODE -> {
                val forward = intent.action == WidgetSupport.ACTION_NEXT_NODE
                val pending = goAsync()
                scope.launch {
                    runCatching { WidgetSupport.switchActiveNode(context, forward) }
                    runCatching { renderAll(context) }
                    pending.finish()
                }
            }
        }
    }

    /** Reads the view-index off the main thread, then repaints every instance. */
    private fun refreshAll(context: Context) {
        val pending = goAsync()
        scope.launch {
            runCatching { renderAll(context) }
            pending.finish()
        }
    }

    private suspend fun renderAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(WidgetSupport.componentStatus(context))
        if (ids.isEmpty()) return
        val index: LocationViewIndex? =
            runCatching { LocationsDataSourceImpl(context.applicationContext).loadLocationViewIndex() }
                .getOrNull()
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_status)
            WidgetSupport.renderStatus(context, views, index)
            manager.updateAppWidget(id, views)
        }
    }
}
