package org.olcbox.app.data.model

import kotlinx.serialization.Serializable

/** General application behavior toggles (the "Настройки приложения" screen). */
@Serializable
data class AppBehaviorSettings(
    /** Auto-connect the selected configuration when the app is launched. */
    val autoConnectOnLaunch: Boolean = false,
    /** Ask for confirmation before deleting subscriptions / configs. */
    val confirmBeforeDelete: Boolean = true,
    /** Show live download/upload speed in the foreground notification. */
    val showSpeedInNotification: Boolean = false,
    /** Hidden "Experimental" section unlocked by tapping the connection timer 5×. */
    val experimentalUnlocked: Boolean = false,
    /** Yandex auth cookie header for Telemost (e.g. "Session_id=…; yandexuid=…"). */
    val telemostCookies: String = "",
    /** Whether the stored Telemost cookies are applied on connect. */
    val telemostCookiesEnabled: Boolean = false,
    /**
     * EXPERIMENTAL (root): after the tunnel is up, rename the `tun0` interface so apps that detect a
     * VPN by enumerating interface names no longer see it. Requires root (`su`); best-effort.
     */
    val hideTunInterface: Boolean = false,
    /** Subscription group keys whose server list is collapsed (chevron). */
    val collapsedSubscriptionGroups: Set<String> = emptySet(),
    /** Subscription group keys pinned to the top, in pin order. */
    val pinnedSubscriptionGroups: List<String> = emptyList(),
    /** Subscription group keys whose servers are sorted by ping (ascending unless also in [pingSortDescendingSubscriptionGroups]). */
    val pingSortedSubscriptionGroups: Set<String> = emptySet(),
    /** Subset of [pingSortedSubscriptionGroups] sorted in DESCENDING ping order (slowest first). */
    val pingSortDescendingSubscriptionGroups: Set<String> = emptySet(),
    /** Storage ids of "own" (custom, non-subscription) locations pinned to the top, in pin order. */
    val pinnedCustomLocations: List<String> = emptyList(),
    /** Whether the "own locations" section is sorted by ping (ascending unless [customLocationsPingSortDescending]). */
    val customLocationsPingSorted: Boolean = false,
    /** Whether the "own locations" ping sort is DESCENDING (slowest first). */
    val customLocationsPingSortDescending: Boolean = false,
    /**
     * How inbounds/locations are probed by the «Пинг» button. One of [PING_MODES].
     * [PING_AUTO] keeps the per-engine default behaviour (olcRTC handshake / tunnel / TCP).
     */
    val pingMode: String = PING_PROXY_HEAD,
    /**
     * Target site for the chosen [pingMode]. Used as the host for TCP/ICMP and as the URL for the
     * proxy GET/HEAD probes. Blank → a sensible built-in default ([DEFAULT_PING_URL]).
     */
    val pingUrl: String = "",
    /**
     * How a ping result is rendered in the location list. One of [PING_RESULT_MODES]:
     * [PING_RESULT_TIME] shows the latency in ms (classic), [PING_RESULT_ICON] shows a check mark
     * when the probe succeeded (reachable / connectable) and a cross when it failed (offline / error).
     */
    val pingResultDisplay: String = PING_RESULT_ICON,
) {
    companion object {
        const val PING_AUTO = "auto"
        const val PING_TCP = "tcp"
        const val PING_ICMP = "icmp"
        const val PING_PROXY_GET = "proxy_get"
        const val PING_PROXY_HEAD = "proxy_head"

        /** Selectable ping modes (single-choice in the UI). */
        val PING_MODES = listOf(PING_AUTO, PING_TCP, PING_ICMP, PING_PROXY_GET, PING_PROXY_HEAD)

        const val PING_RESULT_TIME = "time"
        const val PING_RESULT_ICON = "icon"

        /** Selectable ping-result display modes (single-choice in the UI). */
        val PING_RESULT_MODES = listOf(PING_RESULT_TIME, PING_RESULT_ICON)

        /** Default target when [pingUrl] is left blank. */
        const val DEFAULT_PING_URL = "https://www.google.com"
    }

    /** [pingUrl] trimmed, or [DEFAULT_PING_URL] when blank. */
    fun effectivePingUrl(): String = pingUrl.trim().ifBlank { DEFAULT_PING_URL }
}
