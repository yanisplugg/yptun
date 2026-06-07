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
    /**
     * EXPERIMENTAL (root): route hotspot/tethered clients' traffic through the VPN tunnel. Android
     * normally sends tethering straight to the upstream (bypassing the VPN); with this on, the
     * service installs ip-rules + iptables forwarding/NAT so devices connected to the phone's
     * hotspot also go through the VPN. Requires root (`su`); best-effort, torn down on disconnect.
     */
    val shareVpnHotspot: Boolean = false,
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
    val pingUrl: String = DEFAULT_PING_URL,
    /**
     * How a ping result is rendered in the location list. One of [PING_RESULT_MODES]:
     * [PING_RESULT_TIME] shows the latency in ms (classic), [PING_RESULT_ICON] shows a check mark
     * when the probe succeeded (reachable / connectable) and a cross when it failed (offline / error).
     */
    val pingResultDisplay: String = PING_RESULT_ICON,
    /**
     * Persist the last ping results so they are shown again when the app is reopened (instead of a
     * blank list). Off by default. When on, the latest successful results are saved into
     * [lastPingResults] after each ping pass and restored on launch.
     */
    val savePingResults: Boolean = false,
    /** Last saved ping results (storage id → latency ms), used only when [savePingResults] is on. */
    val lastPingResults: Map<String, Int> = emptyMap(),
    /**
     * Show the subscription expiry date ("до дд.мм.гггг") under the last-refresh line in the
     * subscription group header. Off by default; the urgent (≤2 days) red badge is always shown.
     */
    val showSubscriptionExpiry: Boolean = false,
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

        /** Default target, pre-filled into [pingUrl] (and the fallback when it is cleared). */
        const val DEFAULT_PING_URL = "https://google.com"
    }

    /** [pingUrl] trimmed, or [DEFAULT_PING_URL] when blank. */
    fun effectivePingUrl(): String = pingUrl.trim().ifBlank { DEFAULT_PING_URL }
}
