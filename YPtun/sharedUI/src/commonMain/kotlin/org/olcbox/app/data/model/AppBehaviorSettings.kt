package org.olcbox.app.data.model

import kotlinx.serialization.Serializable

/**
 * A user-created group (folder) on the Home list. Holds whole subscriptions and/or individual custom
 * locations, which physically move inside the folder. Members are tagged keys: [MEMBER_SUB_PREFIX] +
 * subscription URL for a whole subscription, or [MEMBER_LOC_PREFIX] + storageId for a custom location.
 */
@Serializable
data class CustomGroup(
    val id: String,
    val name: String,
    val members: List<String> = emptyList(),
    /** Folder pinned to the top of the list (in [AppBehaviorSettings.customGroups] order). */
    val pinned: Boolean = false,
    /** Folder body collapsed (chevron). */
    val collapsed: Boolean = false,
) {
    companion object {
        const val MEMBER_SUB_PREFIX = "sub:"
        const val MEMBER_LOC_PREFIX = "loc:"

        fun subMember(subscriptionUrl: String) = "$MEMBER_SUB_PREFIX$subscriptionUrl"
        fun locMember(storageId: String) = "$MEMBER_LOC_PREFIX$storageId"
    }
}

/** General application behavior toggles (the "Настройки приложения" screen). */
@Serializable
data class AppBehaviorSettings(
    /** Auto-connect the selected configuration when the app is launched. */
    val autoConnectOnLaunch: Boolean = false,
    /** Show the round "Авто = fastest server" satellite button next to the connect button on Home. */
    val showAutoButton: Boolean = true,
    /** Show the per-subscription / folder / custom-list "fastest server" button in the Home list headers. */
    val showGroupAutoPick: Boolean = true,
    /** Ask for confirmation before deleting subscriptions / configs. */
    val confirmBeforeDelete: Boolean = true,
    /** Show live download/upload speed in the foreground notification. */
    val showSpeedInNotification: Boolean = false,
    /**
     * Show a compact live download/upload speed line under the SELECTED configuration on the Home
     * list (same byte counters that feed the notification speed). Off by default.
     */
    val showSpeedOnHome: Boolean = false,
    /** Show "connected/total rooms" in the notification (olcRTC multi-room only). */
    val showRoomsInNotification: Boolean = false,
    /**
     * Where the ping goes in the notification: true = its own line above the speed, false = right
     * after the server name ("Name · 45 ms").
     */
    val pingOwnLineInNotification: Boolean = false,
    /** Notification ping source: true = measured through the tunnel every [notifPingIntervalMin], false = the last ping the user ran by hand. */
    val notifPingAuto: Boolean = false,
    /** Minutes between automatic notification pings (1..999). */
    val notifPingIntervalMin: Int = 5,
    /** Hidden "Experimental" section unlocked by tapping the connection timer 5×. */
    val experimentalUnlocked: Boolean = false,
    val legacyUi: Boolean = false,
    /**
     * Show the one-tap "Автоустановка на VPS" buttons (WDTT / MasterDNS server deploy over SSH) in the
     * location editor. OFF by default — pushing a binary to a remote server over SSH is an advanced,
     * potentially destructive action, so it stays hidden until the user opts in here.
     */
    val allowVpsAutoInstall: Boolean = false,
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
     * How many locations are probed in parallel during a ping pass — the knob controlling ping speed.
     * Clamped to [MIN_PING_PARALLELISM]..[MAX_PING_PARALLELISM]; default [DEFAULT_PING_PARALLELISM].
     * Higher = faster sweeps but more concurrent local proxies (and more chance of a transient
     * port-collision false "недоступен" on huge lists — see custom-locations ping note).
     */
    val pingParallelism: Int = DEFAULT_PING_PARALLELISM,
    /**
     * Persist the last ping results so they are shown again when the app is reopened (instead of a
     * blank list). Off by default. When on, the latest successful results are saved into
     * [lastPingResults] after each ping pass and restored on launch.
     */
    val savePingResults: Boolean = false,
    /**
     * Last saved ping results (storage id → latency ms), used only when [savePingResults] is on.
     * A `null` value means the location was probed but unreachable/failed — those are persisted too
     * so the "недоступен" state survives a restart (not just the successful ones).
     */
    val lastPingResults: Map<String, Int?> = emptyMap(),
    /**
     * Show the subscription expiry date ("до дд.мм.гггг") under the last-refresh line in the
     * subscription group header. Off by default; the urgent (≤2 days) red badge is always shown.
     */
    val showSubscriptionExpiry: Boolean = false,
    /**
     * Post a local notification when a subscription is about to expire (within
     * [SUBSCRIPTION_EXPIRY_NOTIFY_DAYS] days). Driven by the panel's expiry header
     * (`subscription-userinfo` `expire=` / Remnawave `user.expiresAt`), like Happ. Off by default —
     * needs the user's opt-in and the POST_NOTIFICATIONS permission.
     */
    val notifySubscriptionExpiry: Boolean = false,
    /**
     * Post a system notification when the panel broadcasts an announcement (Remnawave `announce`
     * header). De-duplicated by content so the same announcement is shown only once. Off by default —
     * needs the user's opt-in and the POST_NOTIFICATIONS permission, like [notifySubscriptionExpiry].
     */
    val notifyPanelAnnouncements: Boolean = false,
    /**
     * Show a "live/total" badge in each subscription header — how many of its servers responded to
     * the last ping pass out of the total. Off by default.
     */
    val showSubscriptionAliveCount: Boolean = false,
    /**
     * Show the panel's own subscription description (Remnawave/Happ `announce` header) under the
     * subscription group title in the location list, like Happ. Off by default.
     */
    val showSubscriptionDescription: Boolean = false,
    /** How many lines of the subscription description to show: 3, 5, or 0 = unlimited (default). */
    val subscriptionDescriptionLines: Int = 0,
    /**
     * Show the subscription's icon (panel `profile-icon` header) left of its name in the location
     * list. Nothing is drawn for a subscription without one. On by default.
     */
    val showSubscriptionIcons: Boolean = true,
    /**
     * Hide the protocol + server IP (the "endpoint" line) on a location row WHEN that location has a
     * description — so a subscription's human description is shown instead of the technical endpoint.
     * Rows without a description always show the endpoint. On by default.
     */
    val hideEndpointWhenDescription: Boolean = true,
    /** User-created groups (folders) that reorganise the Home list. Empty = no folders. */
    val customGroups: List<CustomGroup> = emptyList(),
    /**
     * Render the configuration list on the Home screen as a 2-column grid of compact cards instead of
     * the default single-column rows. Group/folder headers stay full-width; only the server/location
     * cards pair up. Off by default.
     */
    val twoColumnLayout: Boolean = false,
    /**
     * Which User-Agent the app presents to subscription panels (Settings -> Privacy). Panels do UA
     * content-negotiation, so the choice also decides what they answer with. One of [SUBSCRIPTION_UA_MODES]:
     *  - [SUB_UA_BOTH] (default): "YPtun/x" for the main fetch (clean names and links), plus a second
     *    "Happ/1.0" request for the server's FakeDNS config and as the fallback when a panel refuses YPtun;
     *  - [SUB_UA_HAPP]: only "Happ/1.0" (the rich Xray JSON);
     *  - [SUB_UA_YPTUN_ONLY]: only "YPtun/x" — no request ever presents as Happ, so no FakeDNS from the panel;
     *  - [SUB_UA_V2RAYTUN]: only "v2raytun/<platform>".
     * The legacy stored value [SUB_UA_YPTUN] meant [SUB_UA_BOTH]. Read it through [effectiveSubscriptionUa].
     */
    val subscriptionUserAgent: String = SUB_UA_BOTH,
    /**
     * App-wide default engine for VLESS-like proxy transports (Standard/Chain exit, VK-TURN
     * exit/chain). Applied ONLY when the per-location core is [ProxyCore.Auto]; an explicit
     * per-location choice (e.g. Xray) always wins — the global default ranks BELOW it. A transport
     * that only one core can serve (xhttp/raw-Xray → Xray) also overrides this. [ProxyCore.Auto]
     * (default) keeps the original behaviour: sing-box unless the transport forces Xray.
     */
    val globalProxyCore: ProxyCore = ProxyCore.Auto,
    /**
     * Energy-saver mode: trims the always-on background work the VPN does while connected to cut
     * battery use — the in-app logcat journal capture is skipped, and the health watchdog polls far
     * less often ([ENERGY_SAVER_WATCHDOG_INTERVAL_MS] instead of the default). The trade-off is slower
     * automatic recovery after a network drop and an empty diagnostics journal; live calls / throughput
     * are unaffected. Off by default. Applied on the next connect.
     */
    val energySaver: Boolean = false,
    /**
     * Daily report to the provider of a subscription that carries a Happ/Remnawave `providerid` header
     * (GET check.happ-proxy.com/provider?id=<id> with HWID, app id, OS, device model and app version).
     * On by default — providers that use it expect it; off sends nothing at all. Settings → Privacy.
     */
    val providerReportEnabled: Boolean = true,
    /**
     * Send the device descriptors to subscription panels and the provider report: `x-hwid`, `x-app-id`,
     * `x-device-os`, `x-ver-os`, `x-device-model`. On by default (panels with a device limit need them);
     * off sends none of them (no HWID at all) — such a panel may then refuse the subscription. Settings -> Privacy.
     */
    val sendDeviceInfoToSubscriptions: Boolean = true,
    /** Unused. Build 421 wrote it; kept only so settings saved by that build still load (unknown keys are rejected). */
    val customHwid: String = "",
    /**
     * Telegram-over-WARP background proxy: when on, a lightweight foreground service raises an
     * AmneziaWG (Cloudflare WARP) tunnel and exposes a local SOCKS5 the user points Telegram at. Runs
     * independently of the main VPN. First enable requires internet (to generate + cache the WARP
     * config); afterwards the same config is reused. Off by default.
     */
    val telegramProxyEnabled: Boolean = false,
    /**
     * Deprecated/vestigial. Previously hid the Telegram-proxy foreground-service notification; the toggle
     * was removed and the proxy always uses the normal low-priority notification. Kept only so older saved
     * settings still decode (default Json has no ignoreUnknownKeys — dropping the field would reset the
     * whole AppBehaviorSettings to defaults on first load). Do not re-introduce a UI for it.
     */
    val hideTelegramProxyNotification: Boolean = false,
    /**
     * Desktop Proxy mode only: point the OS system proxy (WinINET / GNOME / KDE / macOS) at our local
     * proxy. ON (default) = the long-standing behaviour. OFF = only the local SOCKS5/HTTP listener is
     * started and the system settings are never touched, so just the apps configured by hand use it.
     */
    val setSystemProxy: Boolean = true,
    /**
     * How many servers the «Бесплатные серверы» list may take in total: the first N by source priority
     * (see FREE_SERVERS_SOURCES). One of [FREE_SERVERS_LIMITS]; [FREE_SERVERS_UNLIMITED] (0) = no cap.
     */
    val freeServersLimit: Int = DEFAULT_FREE_SERVERS_LIMIT,
    /** How long one ping probe may wait for an answer, in seconds: [MIN_PING_TIMEOUT_SEC]..[MAX_PING_TIMEOUT_SEC]. */
    val pingTimeoutSec: Int = DEFAULT_PING_TIMEOUT_SEC,
    /**
     * How many times a location is measured per ping: 1 (single), 2 (double) or 3 (triple); the BEST
     * (lowest) time is shown. A location that does not answer the first time is not measured again.
     */
    val pingPrecision: Int = DEFAULT_PING_PRECISION,
    /**
     * Pause / stop buttons for a running ping pass and the "Tested N of M" progress line under the
     * subscription header. Off by default: the ping button then just starts a pass.
     */
    val showPingControls: Boolean = false,
    /**
     * Where the «Бесплатные серверы» list comes from: false = the built-in sources (default), true = the
     * single hourly-refreshed file on a hidden GitHub Page (up to 20000 vless links harvested from GitHub).
     */
    val freeServersFromPages: Boolean = false,
    /**
     * Fine-tuning of configurations: the location editor shows every AmneziaWG parameter (Jc, Jmin, Jmax,
     * S1..S4, H1..H4, I1..I5, ...) as fields. Off by default — a wrong value silently breaks the tunnel.
     */
    val allowConfigEditing: Boolean = false,
    /** Location share link: true = universal yptun:// (whole inbound), false = the engine's own protocol link. */
    val shareUniversalLink: Boolean = true,
) {
    /**
     * A subscription's group key (`name|url`, also its folder member key) changed because its URL did: carry the
     * pin, collapse, ping-sort and folder choices over to [newKey] instead of dropping them.
     */
    fun withSubscriptionKeyRenamed(oldKey: String, newKey: String): AppBehaviorSettings {
        if (oldKey == newKey) return this
        fun Set<String>.moved() = if (oldKey in this) this - oldKey + newKey else this
        val oldMember = CustomGroup.subMember(oldKey)
        val newMember = CustomGroup.subMember(newKey)
        return copy(
            collapsedSubscriptionGroups = collapsedSubscriptionGroups.moved(),
            pinnedSubscriptionGroups = pinnedSubscriptionGroups.map { if (it == oldKey) newKey else it },
            pingSortedSubscriptionGroups = pingSortedSubscriptionGroups.moved(),
            pingSortDescendingSubscriptionGroups = pingSortDescendingSubscriptionGroups.moved(),
            customGroups = customGroups.map { g ->
                if (oldMember in g.members) g.copy(members = g.members.map { if (it == oldMember) newMember else it }) else g
            },
        )
    }

    /** [subscriptionUserAgent] as one of [SUBSCRIPTION_UA_MODES]: the legacy "yptun" and anything unknown mean [SUB_UA_BOTH]. */
    fun effectiveSubscriptionUa(): String =
        if (subscriptionUserAgent in SUBSCRIPTION_UA_MODES) subscriptionUserAgent else SUB_UA_BOTH

    companion object {
        const val SUB_UA_BOTH = "both"
        const val SUB_UA_HAPP = "happ"
        const val SUB_UA_YPTUN_ONLY = "yptun_only"
        const val SUB_UA_V2RAYTUN = "v2raytun"

        /** The value the old two-way selector stored for "YPtun": it behaved as [SUB_UA_BOTH]. */
        const val SUB_UA_YPTUN = "yptun"

        /** The literal UA string sent for [SUB_UA_HAPP]. */
        const val HAPP_USER_AGENT = "Happ/1.0"

        /** Prefix of the [SUB_UA_V2RAYTUN] UA; the platform ("android", "windows"...) follows. */
        const val V2RAYTUN_UA_PREFIX = "v2raytun/"

        /** Selectable subscription User-Agent modes (single-choice in the UI), default first. */
        val SUBSCRIPTION_UA_MODES = listOf(SUB_UA_BOTH, SUB_UA_HAPP, SUB_UA_YPTUN_ONLY, SUB_UA_V2RAYTUN)

        const val PING_AUTO = "auto"
        const val PING_TCP = "tcp"
        const val PING_ICMP = "icmp"
        const val PING_PROXY_GET = "proxy_get"
        const val PING_PROXY_HEAD = "proxy_head"

        /** Selectable ping modes (single-choice in the UI). */
        val PING_MODES = listOf(PING_AUTO, PING_TCP, PING_ICMP, PING_PROXY_GET, PING_PROXY_HEAD)

        const val PING_RESULT_TIME = "time"
        const val PING_RESULT_ICON = "icon"
        const val PING_RESULT_BOTH = "both"

        /** Selectable ping-result display modes (single-choice in the UI). */
        val PING_RESULT_MODES = listOf(PING_RESULT_TIME, PING_RESULT_ICON, PING_RESULT_BOTH)

        /**
         * Default probe target. A `generate_204` endpoint: a tiny TCP HTTP request that returns an
         * empty 204 — designed for connectivity checks and reliable through the tunnel. Plain
         * `https://google.com` (the [LEGACY_PING_URL]) was a poor probe: Google forces HTTP/3 (QUIC),
         * which the tunnel blocks, so the GET probe got "no response" and falsely marked servers down.
         */
        const val DEFAULT_PING_URL = "https://www.gstatic.com/generate_204"

        /** The previous default; auto-migrated to [DEFAULT_PING_URL] so existing users get the fix. */
        const val LEGACY_PING_URL = "https://google.com"

        /** How many days before expiry the subscription-expiry notification starts firing. */
        const val SUBSCRIPTION_EXPIRY_NOTIFY_DAYS = 3

        const val MIN_PING_TIMEOUT_SEC = 2
        const val MAX_PING_TIMEOUT_SEC = 15
        const val DEFAULT_PING_TIMEOUT_SEC = 7
        const val MIN_PING_PRECISION = 1
        const val MAX_PING_PRECISION = 3
        const val DEFAULT_PING_PRECISION = 1

        const val FREE_SERVERS_UNLIMITED = 0
        const val DEFAULT_FREE_SERVERS_LIMIT = FREE_SERVERS_UNLIMITED

        /** Selectable free-server caps (single-choice chips in the UI). */
        val FREE_SERVERS_LIMITS = listOf(500, 1000, 2500, 5000, FREE_SERVERS_UNLIMITED)

        /** Parallel ping streams: how many locations are probed at once. */
        const val DEFAULT_PING_PARALLELISM = 5
        const val MIN_PING_PARALLELISM = 1
        // 30 (was 20): this one slider now also drives the Auto-connect probe pass, whose old
        // hardcoded parallelism was 16 — give big subscription lists headroom to ping faster.
        const val MAX_PING_PARALLELISM = 30

        /**
         * Health-watchdog poll interval used while [energySaver] is on (vs. the default 15s). Network
         * switches are handled by the event-driven NetworkCallback, so this only delays detection of a
         * silently-dead core / stalled traffic — a 60s backstop is a good power/recovery trade-off.
         */
        const val ENERGY_SAVER_WATCHDOG_INTERVAL_MS = 60_000L
    }

    /**
     * [pingUrl] trimmed, or [DEFAULT_PING_URL] when blank OR still on the legacy google.com default
     * (auto-migrated so users who never customised it stop getting false "unreachable" on google).
     */
    fun effectivePingUrl(): String = pingUrl.trim().let {
        if (it.isBlank() || it == LEGACY_PING_URL) DEFAULT_PING_URL else it
    }

    /** [pingTimeoutSec] clamped to the supported range, in milliseconds. */
    fun effectivePingTimeoutMs(): Int =
        pingTimeoutSec.coerceIn(MIN_PING_TIMEOUT_SEC, MAX_PING_TIMEOUT_SEC) * 1_000

    /** [pingPrecision] clamped to 1..3. */
    fun effectivePingPrecision(): Int = pingPrecision.coerceIn(MIN_PING_PRECISION, MAX_PING_PRECISION)

    /** [pingParallelism] clamped to the supported range. */
    fun effectivePingParallelism(): Int =
        pingParallelism.coerceIn(MIN_PING_PARALLELISM, MAX_PING_PARALLELISM)
}
