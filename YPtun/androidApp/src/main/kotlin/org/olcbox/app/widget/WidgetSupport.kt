package org.olcbox.app.widget

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.widget.RemoteViews
import android.view.View
import androidx.core.content.ContextCompat
import org.olcbox.app.R
import org.olcbox.app.ui.i18n.AppLocale
import org.olcbox.app.ui.i18n.Strings
import org.olcbox.app.data.datasource.LocationsDataSourceImpl
import org.olcbox.app.data.model.LocationViewIndex
import org.olcbox.app.ui.features.locations.components.SpeedSample
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.service.OlcboxVpnActions
import org.olcbox.app.vpn.service.OlcboxVpnState

/**
 * Shared rendering + control helpers for the two home-screen widgets ([ToggleWidgetProvider] and
 * [StatusWidgetProvider]). Lives in androidApp so the KMP/shared modules stay untouched.
 *
 * The power button connects the ACTIVE location straight through the foreground service (which reads
 * the active location from disk — the same one the widget's `‹ ›` switcher persists), so it never
 * opens the app and always starts exactly what's selected. The "Auto = fastest" search also runs
 * headless inside the VPN service. The app is only surfaced when VPN consent is still needed.
 */
object WidgetSupport {

    // Broadcast actions handled by the providers' onReceive.
    const val ACTION_TOGGLE = "org.olcbox.app.widget.TOGGLE"
    const val ACTION_PREV_NODE = "org.olcbox.app.widget.PREV_NODE"
    const val ACTION_NEXT_NODE = "org.olcbox.app.widget.NEXT_NODE"
    const val ACTION_AUTO = "org.olcbox.app.widget.AUTO"

    private const val TOGGLE_PROVIDER = "org.olcbox.app.widget.ToggleWidgetProvider"
    private const val STATUS_PROVIDER = "org.olcbox.app.widget.StatusWidgetProvider"

    // State colours — kept in sync with the app's theme and the notification. Everything else
    // (background, text, idle icons) comes from the per-widget [WidgetStyle].
    private const val COLOR_CONNECTED = 0xFF46C26B.toInt()   // green
    private const val COLOR_CONNECTING = 0xFFE0A030.toInt()  // amber
    private const val COLOR_IDLE = 0xFF6E7176.toInt()        // muted grey
    /** Alpha of the halo behind the power glyph — a tint of the state colour, not a solid disc. */
    private const val HALO_ALPHA = 46

    // Distinct PendingIntent request codes so the entries never overwrite each other.
    private const val RC_TOGGLE_SIMPLE = 1001
    private const val RC_TOGGLE_STATUS = 1002
    private const val RC_PREV = 1003
    private const val RC_NEXT = 1004
    private const val RC_OPEN = 1005
    private const val RC_AUTO = 1006

    private val piFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    // ──────────────────────────────────────────────────────────────────────
    // State snapshot
    // ──────────────────────────────────────────────────────────────────────

    private val status: VpnStatus get() = OlcboxVpnState.status.value
    private val isConnected: Boolean get() = OlcboxVpnState.isConnected.value
    private val isConnecting: Boolean
        get() = status is VpnStatus.Connecting || status is VpnStatus.Reconnecting

    private fun stateColor(): Int = when {
        isConnected -> COLOR_CONNECTED
        isConnecting -> COLOR_CONNECTING
        else -> COLOR_IDLE
    }

    private fun stateLabel(s: Strings): String = when {
        isConnected -> s.notifConnected
        isConnecting -> s.notifConnecting
        else -> s.widgetDisconnected
    }

    private fun speedLine(sample: SpeedSample): String =
        "↓ ${formatRate(sample.downBytesPerSec)}   ↑ ${formatRate(sample.upBytesPerSec)}"

    private fun formatRate(bytesPerSec: Long): String {
        val b = bytesPerSec.coerceAtLeast(0).toDouble()
        return when {
            b >= 1024 * 1024 -> String.format("%.1f MB/s", b / (1024 * 1024))
            b >= 1024 -> String.format("%.0f KB/s", b / 1024)
            else -> "${b.toLong()} B/s"
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Rendering
    // ──────────────────────────────────────────────────────────────────────

    /** Tints an ImageView acting as a background/halo: colour first, then overall opacity. */
    private fun RemoteViews.paint(viewId: Int, color: Int, alpha: Int = 255) {
        setInt(viewId, "setColorFilter", color)
        setInt(viewId, "setImageAlpha", alpha)
    }

    fun renderToggle(context: Context, views: RemoteViews, style: WidgetStyle) {
        views.paint(R.id.widget_toggle_bg, style.backgroundColor, style.backgroundAlpha)
        views.setInt(R.id.widget_toggle_power, "setColorFilter", stateColor())
        views.setOnClickPendingIntent(
            R.id.widget_toggle_root,
            togglePendingIntent(context, TOGGLE_PROVIDER, RC_TOGGLE_SIMPLE)
        )
    }

    /**
     * Paints the status widget. [index] is the persisted location view-index (cheap to decode); the
     * active server's display name comes from it so the widget shows the right node even while down.
     * [style] is this instance's look, [s] the strings for the language the user picked in the app
     * (NOT the device locale — a widget repaint can be the first thing that starts the process).
     */
    fun renderStatus(
        context: Context,
        views: RemoteViews,
        index: LocationViewIndex?,
        style: WidgetStyle,
        s: Strings,
    ) {
        views.setImageViewResource(R.id.widget_status_bg, style.backgroundDrawable)
        views.paint(R.id.widget_status_bg, style.backgroundColor, style.backgroundAlpha)
        views.paint(R.id.widget_status_power_bg, stateColor(), HALO_ALPHA)
        views.setInt(R.id.widget_status_power, "setColorFilter", stateColor())

        val activeName = index?.items
            ?.firstOrNull { it.storageId == index.activeLocationId }
            ?.name
            ?.takeIf { it.isNotBlank() }
        views.setTextViewText(R.id.widget_status_name, activeName ?: s.widgetNoLocation)
        views.setTextColor(R.id.widget_status_name, style.textPrimary)

        val speed = OlcboxVpnState.speed.value
        val sub: String = if (isConnected && style.showSpeed &&
            (speed.downBytesPerSec > 0 || speed.upBytesPerSec > 0)
        ) {
            speedLine(speed)
        } else {
            stateLabel(s)
        }
        views.setTextViewText(R.id.widget_status_sub, sub)
        views.setTextColor(
            R.id.widget_status_sub,
            if (isConnected) COLOR_CONNECTED else style.textSecondary
        )

        views.setOnClickPendingIntent(
            R.id.widget_status_power,
            togglePendingIntent(context, STATUS_PROVIDER, RC_TOGGLE_STATUS)
        )
        views.setOnClickPendingIntent(R.id.widget_status_name, openAppIntent(context))

        views.setViewVisibility(
            R.id.widget_status_controls,
            if (style.showControls) View.VISIBLE else View.GONE
        )
        if (!style.showControls) return

        // Auto = fastest server — runs entirely in the VPN service (ping pass + connect), no app
        // launch; the app is only opened when VPN consent is still missing.
        views.setInt(R.id.widget_status_auto, "setColorFilter", style.accent)
        views.setOnClickPendingIntent(R.id.widget_status_auto, autoPendingIntent(context))

        val hasNodes = (index?.items?.size ?: 0) > 1
        if (hasNodes) {
            views.setOnClickPendingIntent(R.id.widget_status_prev, switchNodeIntent(context, forward = false))
            views.setOnClickPendingIntent(R.id.widget_status_next, switchNodeIntent(context, forward = true))
            views.setInt(R.id.widget_status_prev, "setColorFilter", style.textSecondary)
            views.setInt(R.id.widget_status_next, "setColorFilter", style.textSecondary)
        } else {
            // Single (or no) server → dim the arrows and route taps to the app instead.
            views.setOnClickPendingIntent(R.id.widget_status_prev, openAppIntent(context))
            views.setOnClickPendingIntent(R.id.widget_status_next, openAppIntent(context))
            views.setInt(R.id.widget_status_prev, "setColorFilter", style.iconIdle)
            views.setInt(R.id.widget_status_next, "setColorFilter", style.iconIdle)
        }
    }

    /** Strings in the language chosen in the app, loading it from disk on first use in this process. */
    fun strings(context: Context): Strings = AppLocale.strings(context)

    // ──────────────────────────────────────────────────────────────────────
    // PendingIntents
    // ──────────────────────────────────────────────────────────────────────

    /** Power button → ACTION_TOGGLE broadcast handled in the provider (decides connect/stop/consent). */
    private fun togglePendingIntent(context: Context, providerClass: String, requestCode: Int): PendingIntent {
        val intent = Intent(ACTION_TOGGLE).setClassName(context.packageName, providerClass)
        return PendingIntent.getBroadcast(context, requestCode, intent, piFlags)
    }

    private fun switchNodeIntent(context: Context, forward: Boolean): PendingIntent {
        val intent = Intent(if (forward) ACTION_NEXT_NODE else ACTION_PREV_NODE)
            .setClassName(context.packageName, STATUS_PROVIDER)
        return PendingIntent.getBroadcast(context, if (forward) RC_NEXT else RC_PREV, intent, piFlags)
    }

    private fun autoPendingIntent(context: Context): PendingIntent {
        val intent = Intent(ACTION_AUTO).setClassName(context.packageName, STATUS_PROVIDER)
        return PendingIntent.getBroadcast(context, RC_AUTO, intent, piFlags)
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP }
            ?: Intent()
        return PendingIntent.getActivity(context, RC_OPEN, launch, piFlags)
    }

    private fun controlActivityIntent(context: Context, verb: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("yptun://control/$verb")).apply {
            setClassName(context.packageName, "org.olcbox.app.AppActivity")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

    // ──────────────────────────────────────────────────────────────────────
    // Click handling (called from the providers' onReceive)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Power tap: stop when up; otherwise connect the active location DIRECTLY via the foreground
     * service (no app launch) — the service reads the active location from disk, which is exactly what
     * the widget's `‹ ›` switcher persists. Only when VPN consent is still missing do we open the app.
     */
    fun handleToggle(context: Context) {
        if (isConnected || isConnecting) {
            stopService(context)
            return
        }
        if (VpnService.prepare(context) != null) {
            // Consent not granted yet → the app must show the system dialog, then it connects.
            runCatching { context.startActivity(controlActivityIntent(context, "start")) }
        } else {
            startServiceConnect(context)
        }
    }

    /**
     * Auto tap: run the fastest-server search + connect INSIDE the foreground VPN service — no app
     * launch, instant amber feedback (the service flips status to Connecting right away). Only when
     * VPN consent is still missing does it fall back to the in-app deep link (the system consent
     * dialog needs an Activity), which then runs the same search in the app.
     */
    fun handleAuto(context: Context) {
        if (VpnService.prepare(context) != null) {
            runCatching { context.startActivity(controlActivityIntent(context, "auto")) }
            return
        }
        val intent = Intent().apply {
            setClassName(context.packageName, OlcboxVpnActions.SERVICE_CLASS_NAME)
            action = OlcboxVpnActions.ACTION_AUTO_CONNECT
        }
        // FGS start is permitted here: the user interacted with a widget (Android 12+ exemption).
        runCatching { ContextCompat.startForegroundService(context, intent) }
    }

    private fun startServiceConnect(context: Context) {
        val intent = Intent().apply {
            setClassName(context.packageName, OlcboxVpnActions.SERVICE_CLASS_NAME)
            action = OlcboxVpnActions.ACTION_START_VPN
        }
        // FGS start is permitted here: the user interacted with a widget (Android 12+ exemption).
        runCatching { ContextCompat.startForegroundService(context, intent) }
    }

    private fun stopService(context: Context) {
        val intent = Intent().apply {
            setClassName(context.packageName, OlcboxVpnActions.SERVICE_CLASS_NAME)
            action = OlcboxVpnActions.ACTION_STOP_VPN
        }
        runCatching { context.startService(intent) }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Node switching (invoked from StatusWidgetProvider.onReceive on a worker thread)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Moves the active server selection one step in [forward] direction and persists it through the
     * existing [LocationsDataSourceImpl] (which rewrites active_location.json + readiness flag, and
     * pings the widgets to re-sync). If the tunnel is up, restarts it onto the new server SILENTLY by
     * re-issuing ACTION_START to the service (it restarts for the newly-selected location). Returns
     * true if the selection actually changed.
     */
    suspend fun switchActiveNode(context: Context, forward: Boolean): Boolean {
        val ds = LocationsDataSourceImpl(context.applicationContext)
        val bundle = ds.loadLocationBundle() ?: return false
        val ids = bundle.locations.map { it.storageId }
        if (ids.size < 2) return false
        val current = ids.indexOf(bundle.activeLocationId).takeIf { it >= 0 } ?: 0
        val next = ((current + if (forward) 1 else -1) + ids.size) % ids.size
        val nextId = ids[next]
        if (nextId == bundle.activeLocationId) return false
        ds.saveLocationBundle(bundle.copy(activeLocationId = nextId))
        if (isConnected || isConnecting) {
            startServiceConnect(context) // service treats START-while-running as a restart for the new node
        }
        return true
    }

    // ──────────────────────────────────────────────────────────────────────

    fun componentToggle(context: Context) = ComponentName(context, ToggleWidgetProvider::class.java)
    fun componentStatus(context: Context) = ComponentName(context, StatusWidgetProvider::class.java)
}
