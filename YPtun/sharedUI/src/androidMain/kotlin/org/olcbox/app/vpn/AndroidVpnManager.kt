package org.olcbox.app.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.data.model.AppBehaviorSettings
import org.olcbox.app.data.model.SubscriptionUserAgentHolder
import org.olcbox.app.ui.i18n.AppLanguage
import org.olcbox.app.ui.i18n.LocalizationState
import org.olcbox.app.data.model.RoutingProfile
import org.olcbox.app.data.model.RoutingProfilesState
import org.olcbox.app.data.model.RoutingRules
import org.olcbox.app.data.model.TrafficSettings
import org.olcbox.app.data.importer.HappRoutingParser
import org.olcbox.app.vpn.geo.GeoAssetManager
import org.olcbox.app.vpn.data.KEY_ANDROID_APP_BEHAVIOR
import org.olcbox.app.vpn.data.KEY_ANDROID_LANGUAGE
import org.olcbox.app.vpn.data.KEY_ANDROID_ROUTING
import org.olcbox.app.vpn.data.KEY_ANDROID_ROUTING_PROFILES
import org.olcbox.app.vpn.data.KEY_ANDROID_TRAFFIC
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.datasource.LocationsDataSourceImpl
import org.olcbox.app.data.identity.PersistentDeviceIdentityProvider
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.vpn.data.KEY_ANDROID_CONNECTED_SINCE
import org.olcbox.app.vpn.data.KEY_ANDROID_CONNECTION_MODE
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.olcbox.app.ui.theme.ThemeState
import org.olcbox.app.vpn.data.KEY_ANDROID_ACCENT_COLOR
import org.olcbox.app.vpn.data.KEY_ANDROID_TEXT_COLOR
import org.olcbox.app.vpn.data.KEY_ANDROID_BG_COLOR
import org.olcbox.app.vpn.data.KEY_ANDROID_DYNAMIC_THEME
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_BYPASS_APPS
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_MODE
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_PROXY_APPS
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_HOST
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_PASSWORD
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_PORT
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_USERNAME
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_USERNAME_INITIALIZED
import org.olcbox.app.vpn.data.vpnPrefDataStore
import org.olcbox.app.vpn.service.OlcboxVpnActions
import org.olcbox.app.vpn.service.OlcboxVpnState
import org.olcbox.app.vpn.telegram.TelegramProxyCreds
import org.olcbox.app.vpn.telegram.TelegramProxyService
import org.olcbox.app.vpn.telegram.TelegramProxyState
import org.olcbox.app.vpn.telegram.WarpConfigGenerator
import java.security.SecureRandom

class AndroidVpnManager(private val context: Context) : VpnManager {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _connectionMode = MutableStateFlow(AndroidConnectionMode.Tun)
    private val _proxySettings = MutableStateFlow(AndroidSocksProxySettings())
    private val _splitTunnelSettings = MutableStateFlow(AndroidSplitTunnelSettings())
    private val _dynamicThemeEnabled = MutableStateFlow(false)
    private val _installedApps = MutableStateFlow<List<AndroidInstalledApp>>(emptyList())
    private val deviceIdentityProvider = PersistentDeviceIdentityProvider(
        LocationsDataSourceImpl(appContext)
    )

    override val logs: StateFlow<List<String>> = OlcboxVpnState.logs
    override val status: StateFlow<VpnStatus> = OlcboxVpnState.status
    override val isConnected: StateFlow<Boolean> = OlcboxVpnState.isConnected
    override val connectedSinceEpochMs: StateFlow<Long> = OlcboxVpnState.connectedSinceMs
    val connectionMode: StateFlow<AndroidConnectionMode> = _connectionMode.asStateFlow()
    val proxySettings: StateFlow<AndroidSocksProxySettings> = _proxySettings.asStateFlow()
    val splitTunnelSettings: StateFlow<AndroidSplitTunnelSettings> = _splitTunnelSettings.asStateFlow()
    val dynamicThemeEnabled: StateFlow<Boolean> = _dynamicThemeEnabled.asStateFlow()
    val installedApps: StateFlow<List<AndroidInstalledApp>> = _installedApps.asStateFlow()
    private val _hwid = MutableStateFlow("")
    val hwid: StateFlow<String> = _hwid.asStateFlow()
    private val _routing = MutableStateFlow(RoutingRules())
    val routing: StateFlow<RoutingRules> = _routing.asStateFlow()
    private val _routingProfiles = MutableStateFlow(RoutingProfilesState())
    val routingProfiles: StateFlow<RoutingProfilesState> = _routingProfiles.asStateFlow()
    /** Transient status of the geo-database download (null = idle). Surfaced in the routing UI. */
    private val _geoUpdateStatus = MutableStateFlow<GeoUpdateStatus?>(null)
    val geoUpdateStatus: StateFlow<GeoUpdateStatus?> = _geoUpdateStatus.asStateFlow()
    private val _trafficSettings = MutableStateFlow(TrafficSettings())
    val trafficSettings: StateFlow<TrafficSettings> = _trafficSettings.asStateFlow()
    private val _appBehavior = MutableStateFlow(AppBehaviorSettings())
    val appBehavior: StateFlow<AppBehaviorSettings> = _appBehavior.asStateFlow()
    private val _telegramProxyState =
        MutableStateFlow<TelegramProxyState>(TelegramProxyState.Stopped)
    /** State of the Telegram-over-WARP background proxy (for the settings UI). */
    val telegramProxyState: StateFlow<TelegramProxyState> = _telegramProxyState.asStateFlow()

    /** De-dupe guard for subscription-expiry notifications (keyed by name + days-left), per process. */
    private val notifiedExpiry = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    /** Per-process de-dupe for panel announcements (also persisted across restarts via SharedPreferences). */
    private val notifiedAnnounce = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val _language = MutableStateFlow(AppLanguage.System)
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    init {
        LocalizationState.systemLanguage = when (java.util.Locale.getDefault().language) {
            "ru" -> AppLanguage.Russian
            "fa" -> AppLanguage.Persian
            else -> AppLanguage.English
        }

        scope.launch {
            ensureProxySettings()
            appContext.vpnPrefDataStore.data
                .map { preferences ->
                    val mode = AndroidConnectionMode.fromValue(preferences[KEY_ANDROID_CONNECTION_MODE])
                    val proxy = AndroidSocksProxySettings(
                        host = AndroidSocksProxySettings.sanitizeHost(
                            preferences[KEY_ANDROID_SOCKS_HOST]
                        ),
                        port = AndroidSocksProxySettings.sanitizePort(
                            preferences[KEY_ANDROID_SOCKS_PORT]
                        ),
                        username = preferences[KEY_ANDROID_SOCKS_USERNAME].orEmpty(),
                        password = preferences[KEY_ANDROID_SOCKS_PASSWORD].orEmpty()
                    )
                    val splitTunnel = AndroidSplitTunnelSettings(
                        mode = AndroidSplitTunnelMode.fromValue(
                            preferences[KEY_ANDROID_SPLIT_TUNNEL_MODE]
                        ),
                        proxyPackages = preferences[KEY_ANDROID_SPLIT_TUNNEL_PROXY_APPS].orEmpty(),
                        bypassPackages = preferences[KEY_ANDROID_SPLIT_TUNNEL_BYPASS_APPS].orEmpty()
                    )
                    AndroidAppPreferences(
                        mode = mode,
                        proxy = proxy,
                        splitTunnel = splitTunnel,
                        dynamicThemeEnabled = preferences[KEY_ANDROID_DYNAMIC_THEME] == true
                    )
                }
                .collect { settings ->
                    _connectionMode.value = settings.mode
                    _proxySettings.value = settings.proxy
                    _splitTunnelSettings.value = settings.splitTunnel
                    _dynamicThemeEnabled.value = settings.dynamicThemeEnabled
                }
        }
        refreshInstalledApps()

        scope.launch {
            _hwid.value = runCatching { deviceIdentityProvider.hwid() }.getOrDefault("")
        }

        // First launch: pre-fetch the default geoip.dat/geosite.dat in the background so routing
        // profiles with geosite:/geoip: selectors work immediately (instead of silently doing nothing
        // until the first manual update). Quiet — doesn't touch _geoUpdateStatus. No-op once present.
        scope.launch {
            if (!GeoAssetManager.hasAssets(appContext)) {
                runCatching { GeoAssetManager.ensureAssets(appContext, "", "") }
            }
        }

        // Keep the global ThemeState in sync with persisted custom colors.
        scope.launch {
            appContext.vpnPrefDataStore.data.collect { preferences ->
                ThemeState.accent = preferences[KEY_ANDROID_ACCENT_COLOR]?.let { Color(it.toInt()) }
                ThemeState.textColor = preferences[KEY_ANDROID_TEXT_COLOR]?.let { Color(it.toInt()) }
                _routing.value = preferences[KEY_ANDROID_ROUTING]
                    ?.let { runCatching { Json.decodeFromString(RoutingRules.serializer(), it) }.getOrNull() }
                    ?: RoutingRules()
                _routingProfiles.value = preferences[KEY_ANDROID_ROUTING_PROFILES]
                    ?.let { runCatching { Json.decodeFromString(RoutingProfilesState.serializer(), it) }.getOrNull() }
                    ?: seededRoutingProfilesState()
                _trafficSettings.value = preferences[KEY_ANDROID_TRAFFIC]
                    ?.let { runCatching { Json.decodeFromString(TrafficSettings.serializer(), it) }.getOrNull() }
                    ?.normalized()
                    ?: TrafficSettings()
                _appBehavior.value = preferences[KEY_ANDROID_APP_BEHAVIOR]
                    ?.let { runCatching { Json.decodeFromString(AppBehaviorSettings.serializer(), it) }.getOrNull() }
                    ?: AppBehaviorSettings()
                // Mirror the subscription User-Agent choice into the process holder the fetch reads.
                SubscriptionUserAgentHolder.mode = _appBehavior.value.subscriptionUserAgent
                // Restore the Telegram-over-WARP proxy if the user left it on (config is cached).
                restoreTelegramProxyIfEnabled()
                val lang = AppLanguage.fromId(preferences[KEY_ANDROID_LANGUAGE])
                _language.value = lang
                LocalizationState.language = lang
                ThemeState.background = preferences[KEY_ANDROID_BG_COLOR]?.let { Color(it.toInt()) }
                ThemeState.dynamicEnabled = preferences[KEY_ANDROID_DYNAMIC_THEME] == true
            }
        }
    }

    /** Sets the theme background color; null restores the default black. */
    fun setBackgroundColor(color: Color?) {
        ThemeState.background = color
        scope.launch {
            appContext.vpnPrefDataStore.edit { preferences ->
                if (color == null) preferences.remove(KEY_ANDROID_BG_COLOR)
                else preferences[KEY_ANDROID_BG_COLOR] = color.toArgb().toLong()
            }
        }
    }

    /** Sets the accent (seed) color; null restores the default pink scheme. */
    fun setAccentColor(color: Color?) {
        ThemeState.accent = color
        scope.launch {
            appContext.vpnPrefDataStore.edit { preferences ->
                if (color == null) preferences.remove(KEY_ANDROID_ACCENT_COLOR)
                else preferences[KEY_ANDROID_ACCENT_COLOR] = color.toArgb().toLong()
            }
        }
    }

    /** Sets the primary text color; null restores the scheme default. */
    fun setTextColor(color: Color?) {
        ThemeState.textColor = color
        scope.launch {
            appContext.vpnPrefDataStore.edit { preferences ->
                if (color == null) preferences.remove(KEY_ANDROID_TEXT_COLOR)
                else preferences[KEY_ANDROID_TEXT_COLOR] = color.toArgb().toLong()
            }
        }
    }

    fun setRouting(rules: RoutingRules) {
        _routing.value = rules
        scope.launch {
            appContext.vpnPrefDataStore.edit { preferences ->
                preferences[KEY_ANDROID_ROUTING] = Json.encodeToString(RoutingRules.serializer(), rules)
            }
        }
        // Re-apply immediately if currently connected.
        if (status.value is VpnStatus.Connected || status.value is VpnStatus.Reconnecting) {
            reapplyRunningConfig()
        }
    }

    // --- Happ-style routing profiles ---

    /** Persists the whole routing-profile state (profiles, global selection, geo sources). */
    fun setRoutingProfilesState(state: RoutingProfilesState) {
        _routingProfiles.value = state
        scope.launch {
            appContext.vpnPrefDataStore.edit { preferences ->
                preferences[KEY_ANDROID_ROUTING_PROFILES] =
                    Json.encodeToString(RoutingProfilesState.serializer(), state)
            }
        }
        if (status.value is VpnStatus.Connected || status.value is VpnStatus.Reconnecting) {
            reapplyRunningConfig()
        }
    }

    /** Inserts or replaces a profile (matched by id), assigning a fresh id when blank. Returns its id. */
    fun saveRoutingProfile(profile: RoutingProfile): String {
        val id = profile.id.ifBlank { "rp-" + java.util.UUID.randomUUID().toString().take(8) }
        val withId = profile.copy(id = id)
        val current = _routingProfiles.value
        val others = current.profiles.filterNot { it.id == id }
        setRoutingProfilesState(current.copy(profiles = others + withId))
        return id
    }

    fun deleteRoutingProfile(id: String) {
        val current = _routingProfiles.value
        setRoutingProfilesState(
            current.copy(
                profiles = current.profiles.filterNot { it.id == id },
                globalProfileId = if (current.globalProfileId == id) "" else current.globalProfileId,
            )
        )
    }

    /** Sets (or clears, with blank) the profile applied to every connection by default. */
    fun setGlobalRoutingProfile(id: String) {
        setRoutingProfilesState(_routingProfiles.value.copy(globalProfileId = id))
    }

    /**
     * Imports a `happ://routing/add/...` link as a new profile. Returns true when [link] was a valid
     * routing link (and a profile was saved); the new id is also returned by [importRoutingProfileId]
     * for callers that want to select it.
     */
    override fun importRoutingProfileLink(link: String): Boolean {
        // A `yptun://routing/...` link carries the ENTIRE routing setup (all profiles + global choice +
        // geo sources) — importing it restores the whole thing at once.
        RoutingProfilesState.fromRoutingLink(link)?.let { imported ->
            val withIds = imported.profiles.map {
                if (it.id.isBlank()) it.copy(id = "rp-" + java.util.UUID.randomUUID().toString().take(8)) else it
            }
            setRoutingProfilesState(imported.copy(profiles = withIds))
            return true
        }
        return importRoutingProfileId(link) != null
    }

    override fun routingProfileChoices(): List<RoutingProfile> = _routingProfiles.value.profiles

    /**
     * Posts a local notification for each subscription nearing expiry (Happ-style, driven by the
     * panel's expiry header). Gated on the user's toggle and POST_NOTIFICATIONS. De-duped in-memory by
     * name + days-left, so the hourly re-check doesn't repeat, but each new day (3→2→1→0) fires once.
     */
    override fun notifyExpiringSubscriptions(subscriptions: List<ExpiringSubscriptionInfo>) {
        if (!_appBehavior.value.notifySubscriptionExpiry) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                appContext, android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val now = System.currentTimeMillis()
        val dayMs = 24L * 60L * 60L * 1_000L
        val thresholdDays = AppBehaviorSettings.SUBSCRIPTION_EXPIRY_NOTIFY_DAYS
        val manager = androidx.core.app.NotificationManagerCompat.from(appContext)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                SUB_EXPIRY_CHANNEL_ID,
                "Окончание подписки",
                android.app.NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }
        // Status-bar icon resolved by name (it lives in androidApp's resources, not sharedUI's R),
        // with a system fallback — same approach as the foreground-service notification.
        val icon = appContext.resources
            .getIdentifier("ic_stat_yptun", "drawable", appContext.packageName)
            .takeIf { it != 0 } ?: android.R.drawable.ic_lock_idle_alarm

        subscriptions.forEach { sub ->
            val remainingMs = sub.expiresAtEpochMs - now
            if (remainingMs <= 0L || remainingMs > thresholdDays * dayMs) return@forEach
            val daysLeft = ((remainingMs + dayMs - 1) / dayMs).toInt() // ceil → 3,2,1; 0 = today
            if (!notifiedExpiry.add(sub.name + "|" + daysLeft)) return@forEach

            val text = if (daysLeft <= 0) {
                "«${sub.name}»: подписка заканчивается сегодня"
            } else {
                "«${sub.name}»: до конца подписки $daysLeft дн."
            }
            val notification = androidx.core.app.NotificationCompat.Builder(appContext, SUB_EXPIRY_CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle("Подписка скоро закончится")
                .setContentText(text)
                .setAutoCancel(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                .build()
            runCatching {
                manager.notify(SUB_EXPIRY_NOTIFICATION_BASE_ID + (sub.name.hashCode() and 0xFFFF), notification)
            }
        }
    }

    /**
     * Posts a system notification for each NEW panel announcement (Remnawave `announce` header). Gated
     * on the user's toggle and POST_NOTIFICATIONS. De-duplicated by content (name + text) both
     * in-memory and persisted in SharedPreferences, so the same announcement is shown only once even
     * across app restarts (the auto-refresh re-collects every announcement on each launch).
     */
    override fun notifyPanelAnnouncements(announcements: List<PanelAnnouncementInfo>) {
        if (!_appBehavior.value.notifyPanelAnnouncements) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                appContext, android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val prefs = appContext.getSharedPreferences("olcbox_panel_announce", android.content.Context.MODE_PRIVATE)
        val shown = prefs.getStringSet("shown_hashes", emptySet())?.toMutableSet() ?: mutableSetOf()
        val manager = androidx.core.app.NotificationManagerCompat.from(appContext)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                PANEL_ANNOUNCE_CHANNEL_ID,
                "Уведомления панели",
                android.app.NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }
        val icon = appContext.resources
            .getIdentifier("ic_stat_yptun", "drawable", appContext.packageName)
            .takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info

        var changed = false
        announcements.forEach { ann ->
            val key = (ann.name + "|" + ann.announce).hashCode().toString()
            if (!shown.add(key)) return@forEach // already shown in a previous session
            notifiedAnnounce.add(key)
            changed = true

            val notification = androidx.core.app.NotificationCompat.Builder(appContext, PANEL_ANNOUNCE_CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle(ann.name)
                .setContentText(ann.announce)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(ann.announce))
                .setAutoCancel(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                .build()
            runCatching {
                manager.notify(PANEL_ANNOUNCE_NOTIFICATION_BASE_ID + (key.hashCode() and 0xFFFF), notification)
            }
        }
        if (changed) prefs.edit().putStringSet("shown_hashes", shown).apply()
    }

    /** As [importRoutingProfileLink] but returns the new profile id (or null when not a routing profile). */
    fun importRoutingProfileId(link: String): String? {
        val parsed = HappRoutingParser.parseAny(link) ?: return null
        return saveRoutingProfile(parsed.copy(id = ""))
    }

    /** Updates the geo-database source URLs (blank restores the defaults at build time). */
    fun setGeoSources(geoipUrl: String, geositeUrl: String) {
        setRoutingProfilesState(
            _routingProfiles.value.copy(geoipUrl = geoipUrl.trim(), geositeUrl = geositeUrl.trim())
        )
    }

    /** Downloads the geoip.dat/geosite.dat now (from the configured sources); status flows to [geoUpdateStatus]. */
    fun updateGeoAssetsNow() {
        if (_geoUpdateStatus.value is GeoUpdateStatus.Running) return
        val state = _routingProfiles.value
        _geoUpdateStatus.value = GeoUpdateStatus.Running
        scope.launch {
            val result = GeoAssetManager.download(appContext, state.geoipUrl, state.geositeUrl)
            if (result.success) {
                val now = System.currentTimeMillis()
                setRoutingProfilesState(_routingProfiles.value.copy(geoLastUpdated = now))
                _geoUpdateStatus.value = GeoUpdateStatus.Success(now, result.bytes)
            } else {
                _geoUpdateStatus.value = GeoUpdateStatus.Failed(result.error ?: "download failed")
            }
        }
    }

    fun clearGeoUpdateStatus() {
        _geoUpdateStatus.value = null
    }

    /** First-run routing state: the built-in "Russia → direct" profile, applied globally for convenience. */
    private fun seededRoutingProfilesState() = RoutingProfilesState(
        profiles = listOf(RoutingProfile.russiaDirect()),
        globalProfileId = RoutingProfile.DEFAULT_RU_DIRECT_ID,
    )

    fun setTrafficSettings(settings: TrafficSettings) {
        val normalized = settings.normalized()
        _trafficSettings.value = normalized
        scope.launch {
            appContext.vpnPrefDataStore.edit { preferences ->
                preferences[KEY_ANDROID_TRAFFIC] = Json.encodeToString(TrafficSettings.serializer(), normalized)
            }
        }
        if (status.value is VpnStatus.Connected || status.value is VpnStatus.Reconnecting) {
            reapplyRunningConfig()
        }
    }

    fun setLanguage(language: AppLanguage) {
        _language.value = language
        LocalizationState.language = language
        scope.launch {
            appContext.vpnPrefDataStore.edit { preferences ->
                preferences[KEY_ANDROID_LANGUAGE] = language.id
            }
        }
    }

    fun setAppBehavior(settings: AppBehaviorSettings) {
        val previous = _appBehavior.value
        _appBehavior.value = settings
        SubscriptionUserAgentHolder.mode = settings.subscriptionUserAgent
        scope.launch {
            appContext.vpnPrefDataStore.edit { preferences ->
                preferences[KEY_ANDROID_APP_BEHAVIOR] =
                    Json.encodeToString(AppBehaviorSettings.serializer(), settings)
            }
        }
        if (settings.telegramProxyEnabled != previous.telegramProxyEnabled) {
            if (settings.telegramProxyEnabled) enableTelegramProxy() else disableTelegramProxy()
        }
    }

    /**
     * Brings up the Telegram-over-WARP proxy: ensures a cached WARP config exists (generating one from
     * Cloudflare on first enable — requires internet), then starts the background [TelegramProxyService].
     * On generation failure the toggle is reverted and an error is surfaced via [telegramProxyState].
     */
    private fun enableTelegramProxy() {
        scope.launch {
            // All steps echo into the visible journal (OlcboxVpnState.addLog) — the TG proxy runs
            // independently of the main VPN, so without this the user sees NOTHING in the in-app log.
            OlcboxVpnState.addLog("Telegram proxy: enabling…")
            val ds = LocationsDataSourceImpl(appContext)
            var config = runCatching { ds.loadTelegramWarpConfig() }.getOrNull()
            // Pre-2.5.3 caches came from direct Cloudflare registration (now blocked for some users) or
            // never generated at all. Drop any cached config that points at the bare DNS endpoint with no
            // body so the new server-side generator path runs; a valid cached config is kept as-is.
            if (!config.isNullOrBlank() && !config.contains("PrivateKey", ignoreCase = true)) {
                config = null
            }
            if (config.isNullOrBlank()) {
                OlcboxVpnState.addLog("Telegram proxy: no cached config — generating WARP via generators…")
                _telegramProxyState.value = TelegramProxyState.Generating
                config = runCatching { WarpConfigGenerator.generate() }.getOrElse { e ->
                    OlcboxVpnState.addLog("Telegram proxy: WARP generation FAILED — ${e.message ?: e}")
                    _telegramProxyState.value =
                        TelegramProxyState.Error(e.message ?: "WARP config generation failed")
                    // Revert the toggle (setAppBehavior re-entry will call disableTelegramProxy()).
                    setAppBehavior(_appBehavior.value.copy(telegramProxyEnabled = false))
                    return@launch
                }
                runCatching { ds.saveTelegramWarpConfig(config) }
                OlcboxVpnState.addLog("Telegram proxy: WARP config generated (${config.length} chars)")
            } else {
                OlcboxVpnState.addLog("Telegram proxy: using cached WARP config")
            }
            // Generate/persist the SOCKS credentials BEFORE the service starts so it reads the same
            // pair (getOrCreate is idempotent) and the UI can show them.
            val creds = runCatching { TelegramProxyCreds.getOrCreate(appContext) }.getOrNull()
            runCatching { TelegramProxyService.start(appContext) }
                .onSuccess {
                    OlcboxVpnState.addLog(
                        "Telegram proxy: service started — SOCKS5 ${TelegramProxyService.LISTEN_HOST}:" +
                            "${TelegramProxyService.LISTEN_PORT} (login ${creds?.user.orEmpty()})"
                    )
                    _telegramProxyState.value = TelegramProxyState.Running(
                        TelegramProxyService.LISTEN_HOST,
                        TelegramProxyService.LISTEN_PORT,
                        creds?.user.orEmpty(),
                        creds?.pass.orEmpty()
                    )
                }
                .onFailure {
                    OlcboxVpnState.addLog("Telegram proxy: service FAILED to start — ${it.message ?: it}")
                    _telegramProxyState.value =
                        TelegramProxyState.Error(it.message ?: "Failed to start Telegram proxy")
                }
        }
    }

    private fun disableTelegramProxy() {
        OlcboxVpnState.addLog("Telegram proxy: disabling")
        runCatching { TelegramProxyService.stop(appContext) }
        _telegramProxyState.value = TelegramProxyState.Stopped
    }

    /** Re-starts the Telegram proxy after process restart when the toggle was left on. */
    private fun restoreTelegramProxyIfEnabled() {
        if (_appBehavior.value.telegramProxyEnabled) enableTelegramProxy()
    }

    override fun needsPermission(): Boolean = needsPermission(_connectionMode.value)

    fun needsPermission(mode: AndroidConnectionMode): Boolean {
        return mode == AndroidConnectionMode.Tun && VpnService.prepare(context) != null
    }

    fun selectConnectionMode(mode: AndroidConnectionMode) {
        _connectionMode.value = mode
        scope.launch {
            appContext.vpnPrefDataStore.edit { preferences ->
                preferences[KEY_ANDROID_CONNECTION_MODE] = mode.value
            }
        }
    }

    fun setDynamicThemeEnabled(enabled: Boolean) {
        _dynamicThemeEnabled.value = enabled
        ThemeState.dynamicEnabled = enabled
        scope.launch {
            appContext.vpnPrefDataStore.edit { preferences ->
                preferences[KEY_ANDROID_DYNAMIC_THEME] = enabled
            }
        }
    }

    fun updateProxySettings(
        host: String,
        username: String,
        password: String,
        port: Int = _proxySettings.value.port
    ) {
        val sanitizedHost = AndroidSocksProxySettings.sanitizeHost(host)
        val sanitizedUsername = username.trim().take(MAX_SOCKS_USERNAME_LENGTH)
            .ifBlank { generateProxyUsername() }
        val sanitized = password.trim().take(MAX_SOCKS_PASSWORD_LENGTH)
            .ifBlank { generateProxyPassword() }
        val sanitizedPort = AndroidSocksProxySettings.sanitizePort(port)
        _proxySettings.value = _proxySettings.value.copy(
            host = sanitizedHost,
            port = sanitizedPort,
            username = sanitizedUsername,
            password = sanitized
        )
        scope.launch {
            appContext.vpnPrefDataStore.edit { preferences ->
                preferences[KEY_ANDROID_SOCKS_HOST] = sanitizedHost
                preferences[KEY_ANDROID_SOCKS_PORT] = sanitizedPort
                preferences[KEY_ANDROID_SOCKS_USERNAME] = sanitizedUsername
                preferences[KEY_ANDROID_SOCKS_USERNAME_INITIALIZED] = true
                preferences[KEY_ANDROID_SOCKS_PASSWORD] = sanitized
            }
        }
    }

    fun updateProxyPassword(password: String) {
        updateProxySettings(
            host = _proxySettings.value.host,
            username = _proxySettings.value.username,
            password = password
        )
    }

    fun regenerateProxyPassword() {
        updateProxyPassword(generateProxyPassword())
    }

    fun refreshInstalledApps() {
        scope.launch {
            _installedApps.value = loadInstalledApps()
        }
    }

    fun selectSplitTunnelMode(mode: AndroidSplitTunnelMode) {
        _splitTunnelSettings.value = _splitTunnelSettings.value.copy(mode = mode)
        scope.launch {
            appContext.vpnPrefDataStore.edit { preferences ->
                preferences[KEY_ANDROID_SPLIT_TUNNEL_MODE] = mode.value
            }
        }
    }

    fun toggleSplitTunnelApp(list: AndroidSplitTunnelList, packageName: String) {
        val current = _splitTunnelSettings.value
        val next = when (list) {
            AndroidSplitTunnelList.Proxy -> {
                val packages = current.proxyPackages.toggle(packageName)
                current.copy(proxyPackages = packages)
            }

            AndroidSplitTunnelList.Bypass -> {
                val packages = current.bypassPackages.toggle(packageName)
                current.copy(bypassPackages = packages)
            }
        }

        updateSplitTunnelSettings(next)
    }

    fun setSplitTunnelApps(list: AndroidSplitTunnelList, packages: Set<String>) {
        val normalizedPackages = packages
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        val current = _splitTunnelSettings.value
        val next = when (list) {
            AndroidSplitTunnelList.Proxy -> current.copy(proxyPackages = normalizedPackages)
            AndroidSplitTunnelList.Bypass -> current.copy(bypassPackages = normalizedPackages)
        }

        updateSplitTunnelSettings(next)
    }

    override fun startVpn() {
        // A user-initiated connect resets the connection timer: drop any persisted start time so the
        // service stamps a fresh one. (The auto-restart-after-app-swipe path never calls startVpn,
        // so it keeps the persisted value and the timer continues.)
        scope.launch {
            runCatching { appContext.vpnPrefDataStore.edit { it.remove(KEY_ANDROID_CONNECTED_SINCE) } }
        }
        sendStartVpnIntent()
    }

    /**
     * Re-applies the running config to the live tunnel (routing / traffic / profile change) WITHOUT
     * touching the connection timer: it reuses the same ACTION_START_VPN path the service treats as a
     * restart, but — unlike [startVpn] — it does NOT clear the persisted start time, so the on-screen
     * timer keeps counting from the original connect instead of resetting every time a setting changes
     * mid-session.
     */
    private fun reapplyRunningConfig() {
        sendStartVpnIntent()
    }

    private fun sendStartVpnIntent() {
        val intent = Intent().apply {
            setClassName(context.packageName, OlcboxVpnActions.SERVICE_CLASS_NAME)
            action = OlcboxVpnActions.ACTION_START_VPN
            putExtra(OlcboxVpnActions.EXTRA_CONNECTION_MODE, _connectionMode.value.value)
            putExtra(OlcboxVpnActions.EXTRA_SOCKS_HOST, _proxySettings.value.host)
            putExtra(OlcboxVpnActions.EXTRA_SOCKS_PORT, _proxySettings.value.port)
            putExtra(OlcboxVpnActions.EXTRA_SOCKS_USERNAME, _proxySettings.value.username)
            putExtra(OlcboxVpnActions.EXTRA_SOCKS_PASSWORD, _proxySettings.value.password)
            putExtra(OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_MODE, _splitTunnelSettings.value.mode.value)
            putStringArrayListExtra(
                OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_PROXY_APPS,
                ArrayList(_splitTunnelSettings.value.proxyPackages)
            )
            putStringArrayListExtra(
                OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_BYPASS_APPS,
                ArrayList(_splitTunnelSettings.value.bypassPackages)
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.startService(intent)
        }
    }

    override fun stopVpn() {
        val intent = Intent().apply {
            setClassName(context.packageName, OlcboxVpnActions.SERVICE_CLASS_NAME)
            action = OlcboxVpnActions.ACTION_STOP_VPN
        }
        context.startService(intent)
    }

    override suspend fun ping(locationConfig: LocationConfig): Long? = pingInternal(locationConfig)

    override suspend fun checkConnection(locationConfig: LocationConfig): Long? = pingInternal(locationConfig)

    private suspend fun pingInternal(locationConfig: LocationConfig): Long? {
        // User-selected ping method (Settings → «Пинг») overrides the per-engine default probe.
        // TCP/ICMP probe the location's own server; the URL is used ONLY by the proxy GET/HEAD probes.
        val behavior = _appBehavior.value
        val server = locationConfig.proxy?.server
        val serverPort = locationConfig.proxy?.serverPort
        // olcRTC/Stealth (and other provider engines) carry no vless/proxy profile, so the proxy
        // GET/HEAD probe can't build a throwaway outbound for them. When proxy mode is selected (it's
        // now the default) but this location has no proxy, fall through to the engine-default probe
        // instead of reporting a false "Offline".
        val hasProxy = locationConfig.proxy != null
        when (behavior.pingMode) {
            AppBehaviorSettings.PING_TCP -> if (hasProxy) return tcpPing(server, serverPort)
            AppBehaviorSettings.PING_ICMP -> if (hasProxy) return icmpPing(server)
            AppBehaviorSettings.PING_PROXY_GET -> if (hasProxy) return proxyUrlTest(locationConfig, behavior.effectivePingUrl(), "GET")
            AppBehaviorSettings.PING_PROXY_HEAD -> if (hasProxy) return proxyUrlTest(locationConfig, behavior.effectivePingUrl(), "HEAD")
            else -> { /* PING_AUTO → fall through to the engine-specific default below */ }
        }
        val proxyType = locationConfig.proxy?.type
        return when {
            // Obfuscated transports whose real endpoint is blocked/hidden (VK-TURN, AmneziaWG):
            // the only meaningful probe is end-to-end through the live tunnel.
            locationConfig.engine == EngineType.VkTurn -> tunnelPing()
            // dnstt has no TCP endpoint (its "server" is a DNS resolver), so it only measures
            // end-to-end through the live tunnel once connected.
            locationConfig.engine == EngineType.Dnstt -> tunnelPing()
            proxyType == ProxyProfile.TYPE_AMNEZIAWG ->
                // Connected → measure through the live tunnel; otherwise a standalone WG-handshake
                // probe gives a real RTT even before connecting (the endpoint may be UDP/blocked).
                if (OlcboxVpnState.activeSocks != null) tunnelPing()
                else awgProbePing(locationConfig.proxy?.awgConfig.orEmpty())
            proxyType == ProxyProfile.TYPE_HYSTERIA2 ->
                // Hysteria2 is UDP/QUIC-only (TCP to the port usually fails), so before connecting
                // an ICMP probe to the server host gives the truest path RTT; once up, measure through
                // the live tunnel.
                if (OlcboxVpnState.activeSocks != null) tunnelPing()
                else icmpPing(locationConfig.proxy?.server.orEmpty())
            // Plain proxies: TCP latency to the (reachable) proxy server.
            locationConfig.engine == EngineType.Standard ->
                tcpPing(locationConfig.proxy?.server, locationConfig.proxy?.serverPort)
            else -> OlcRtcConnectionChecker.ping(
                locationConfig = locationConfig,
                deviceId = deviceIdentityProvider.hwid()
            )
        }
    }

    /**
     * Latency of the VK-TURN tunnel measured end-to-end: a SOCKS5 CONNECT through the local proxy
     * to a reliable host (1.1.1.1:53) — the round trip traverses WireGuard over the VK relay. Only
     * meaningful while connected; returns null otherwise (shown as "unknown", not offline).
     */
    /**
     * End-to-end latency through the live tunnel: a SOCKS5 CONNECT to 1.1.1.1:443 via the running
     * core's local SOCKS. Uses the per-session credentials the service publishes (randomized in TUN
     * mode) — without them the handshake is rejected (0xFF) and shows a false "Offline". Returns null
     * only when no core is up (cannot probe an obfuscated/blocked endpoint while disconnected).
     */
    /** Pre-connection AmneziaWG latency: a throwaway WG handshake via the awgproxy probe. */
    private suspend fun awgProbePing(awgConfig: String): Long? = withContext(Dispatchers.IO) {
        if (awgConfig.isBlank()) return@withContext null
        val ms = runCatching { awg.Awg.probe(awgConfig) }.getOrDefault(-1L)
        if (ms >= 0) ms else null
    }

    private suspend fun tunnelPing(): Long? = withContext(Dispatchers.IO) {
        val sock = OlcboxVpnState.activeSocks ?: return@withContext null
        val host = AndroidSocksProxySettings.connectHost(sock.host)

        var best: Long? = null
        var lastError: String? = null
        repeat(TCP_PING_ATTEMPTS) {
            val r = runCatching {
                socks5ConnectRtt(host, sock.port, sock.username, sock.password,
                    TUNNEL_PROBE_HOST, TUNNEL_PROBE_PORT, TUNNEL_PING_TIMEOUT_MS)
            }
            val ms = r.getOrNull()
            if (ms != null && (best == null || ms < best!!)) best = ms
            if (ms == null) lastError = r.exceptionOrNull()?.message ?: "no reply"
        }
        if (best == null) {
            OlcboxVpnState.addLog("Tunnel ping failed via $host:${sock.port} → $TUNNEL_PROBE_HOST:$TUNNEL_PROBE_PORT: $lastError")
        }
        best
    }

    /** Hand-rolled SOCKS5 CONNECT to an IPv4 target through proxyHost:proxyPort; returns RTT ms. */
    private fun socks5ConnectRtt(
        proxyHost: String, proxyPort: Int, username: String, password: String,
        targetHost: String, targetPort: Int, timeoutMs: Int
    ): Long? {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(proxyHost, proxyPort), timeoutMs)
            socket.soTimeout = timeoutMs
            val out = socket.getOutputStream()
            val inp = socket.getInputStream()
            val start = System.nanoTime()

            val useAuth = username.isNotBlank()
            out.write(if (useAuth) byteArrayOf(0x05, 0x02, 0x00, 0x02) else byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val sel = readExactly(inp, 2)
            if (sel[0].toInt() and 0xFF != 0x05) return null
            when (sel[1].toInt() and 0xFF) {
                0x00 -> {}
                0x02 -> {
                    val u = username.encodeToByteArray(); val p = password.encodeToByteArray()
                    val a = ByteArray(3 + u.size + p.size)
                    a[0] = 0x01; a[1] = u.size.toByte(); u.copyInto(a, 2)
                    a[2 + u.size] = p.size.toByte(); p.copyInto(a, 3 + u.size)
                    out.write(a); out.flush()
                    if (readExactly(inp, 2)[1].toInt() and 0xFF != 0x00) return null
                }
                else -> return null
            }

            val ip = targetHost.split('.').map { it.toInt() }
            val req = byteArrayOf(
                0x05, 0x01, 0x00, 0x01,
                ip[0].toByte(), ip[1].toByte(), ip[2].toByte(), ip[3].toByte(),
                ((targetPort shr 8) and 0xFF).toByte(), (targetPort and 0xFF).toByte()
            )
            out.write(req); out.flush()

            val head = readExactly(inp, 4)
            if (head[1].toInt() and 0xFF != 0x00) return null
            val addrLen = when (head[3].toInt() and 0xFF) {
                0x01 -> 4; 0x04 -> 16; 0x03 -> readExactly(inp, 1)[0].toInt() and 0xFF
                else -> return null
            }
            readExactly(inp, addrLen + 2)
            return (System.nanoTime() - start) / 1_000_000L
        }
    }

    private fun readExactly(inp: java.io.InputStream, n: Int): ByteArray {
        val buf = ByteArray(n); var off = 0
        while (off < n) {
            val r = inp.read(buf, off, n - off)
            if (r < 0) throw java.io.EOFException("short read")
            off += r
        }
        return buf
    }

    /** TCP-connect latency to host:port in ms, best of a few attempts, or null if unreachable. */
    private suspend fun tcpPing(host: String?, port: Int?): Long? = withContext(Dispatchers.IO) {
        if (host.isNullOrBlank() || port == null || port !in 1..65535) return@withContext null
        var best: Long? = null
        repeat(TCP_PING_ATTEMPTS) {
            val elapsed = runCatching {
                java.net.Socket().use { socket ->
                    val start = System.nanoTime()
                    socket.connect(java.net.InetSocketAddress(host, port), TCP_PING_TIMEOUT_MS)
                    (System.nanoTime() - start) / 1_000_000L
                }
            }.getOrNull()
            if (elapsed != null && (best == null || elapsed < best!!)) best = elapsed
        }
        best
    }

    // --- User-selectable ping methods (Settings → «Пинг») ---

    /** ICMP/echo reachability latency to [host] (Android uses ICMP or a TCP echo internally). */
    private suspend fun icmpPing(host: String?): Long? = withContext(Dispatchers.IO) {
        if (host.isNullOrBlank()) return@withContext null
        var best: Long? = null
        repeat(TCP_PING_ATTEMPTS) {
            val ms = runCatching {
                val addr = java.net.InetAddress.getByName(host)
                val start = System.nanoTime()
                if (addr.isReachable(TCP_PING_TIMEOUT_MS)) (System.nanoTime() - start) / 1_000_000L else null
            }.getOrNull()
            if (ms != null && (best == null || ms < best!!)) best = ms
        }
        best
    }

    /**
     * Per-server proxy URL test (à la v2rayNG / Happ): builds a throwaway Xray config for
     * [location]'s proxy and fetches [url] THROUGH it inside xray-core, returning the round-trip in
     * ms. Independent of the system VPN, so it works while DISCONNECTED — that's how the whole
     * server list can be probed "via proxy". Returns null when the proxy type isn't xray-serviceable
     * (e.g. AmneziaWG / raw WireGuard) or the request fails.
     */
    private suspend fun proxyUrlTest(location: LocationConfig, url: String, method: String): Long? =
        withContext(Dispatchers.IO) {
            val profile = location.proxy
            if (profile == null) {
                OlcboxVpnState.addLog("Proxy $method ping: location has no proxy")
                return@withContext null
            }
            // AmneziaWG isn't xray-serviceable — raise a throwaway AWG tunnel and fetch through it.
            if (profile.type == ProxyProfile.TYPE_AMNEZIAWG) {
                val awgConfig = profile.awgConfig.orEmpty()
                if (awgConfig.isBlank()) {
                    OlcboxVpnState.addLog("Proxy $method ping: AmneziaWG config missing")
                    return@withContext null
                }
                val ms = runCatching {
                    awg.Awg.measureDelay(awgConfig, url, method, TUNNEL_PING_TIMEOUT_MS.toLong())
                }.getOrElse {
                    OlcboxVpnState.addLog("Proxy $method ping (AmneziaWG) error: ${it.message}")
                    -1L
                }
                return@withContext if (ms >= 0) ms else {
                    OlcboxVpnState.addLog("Proxy $method ping: no response via AmneziaWG for $url")
                    null
                }
            }
            // Hysteria2 is QUIC-based and not xray-serviceable; the via-proxy URL test isn't available
            // (use Auto/ICMP ping mode instead).
            if (profile.type == ProxyProfile.TYPE_HYSTERIA2) {
                OlcboxVpnState.addLog("Proxy $method ping: Hysteria2 not supported (use Auto/ICMP)")
                return@withContext null
            }
            if (profile.server.isBlank()) {
                OlcboxVpnState.addLog("Proxy $method ping: location has no proxy server")
                return@withContext null
            }
            val listenPort = (20_000..60_000).random()
            val configJson = runCatching {
                org.olcbox.app.vpn.xray.XrayConfig.build(
                    profile = profile,
                    listenPort = listenPort,
                    listenHost = "127.0.0.1",
                    logLevel = "none",
                    blockQuic = false,
                )
            }.getOrElse {
                OlcboxVpnState.addLog("Proxy $method ping: failed to build test config: ${it.message}")
                return@withContext null
            }
            val ms = runCatching {
                xraybridge.Xraybridge.measureDelay(configJson, url, method, TUNNEL_PING_TIMEOUT_MS.toLong())
            }.getOrElse {
                OlcboxVpnState.addLog("Proxy $method ping error for ${profile.server}: ${it.message}")
                -1L
            }
            if (ms >= 0) {
                ms
            } else {
                OlcboxVpnState.addLog("Proxy $method ping: no response via ${profile.server} for $url")
                null
            }
        }

    override fun subscriptionFetchProxy(): SubscriptionFetchProxy? {
        val currentStatus = status.value
        if (currentStatus !is VpnStatus.Connected &&
            currentStatus !is VpnStatus.Reconnecting
        ) {
            return null
        }

        val proxy = _proxySettings.value
        return SubscriptionFetchProxy(
            host = AndroidSocksProxySettings.connectHost(proxy.host),
            port = proxy.port,
            username = proxy.username,
            password = proxy.password
        )
    }

    private suspend fun ensureProxySettings() {
        appContext.vpnPrefDataStore.edit { preferences ->
            val username = preferences[KEY_ANDROID_SOCKS_USERNAME]
            val usernameInitialized = preferences[KEY_ANDROID_SOCKS_USERNAME_INITIALIZED] == true
            if (username.isNullOrBlank() || (!usernameInitialized && username == LEGACY_DEFAULT_USERNAME)) {
                preferences[KEY_ANDROID_SOCKS_USERNAME] = generateProxyUsername()
            }
            preferences[KEY_ANDROID_SOCKS_USERNAME_INITIALIZED] = true
            if (preferences[KEY_ANDROID_SOCKS_PASSWORD].isNullOrBlank()) {
                preferences[KEY_ANDROID_SOCKS_PASSWORD] = generateProxyPassword()
            }
            preferences[KEY_ANDROID_SOCKS_HOST] = AndroidSocksProxySettings.sanitizeHost(
                preferences[KEY_ANDROID_SOCKS_HOST]
            )
            preferences[KEY_ANDROID_SOCKS_PORT] = AndroidSocksProxySettings.sanitizePort(
                preferences[KEY_ANDROID_SOCKS_PORT]
            )
        }
    }

    private suspend fun loadInstalledApps(): List<AndroidInstalledApp> = withContext(Dispatchers.IO) {
        val packageManager = appContext.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                launcherIntent,
                PackageManager.ResolveInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launcherIntent, 0)
        }

        val launcherApps = resolveInfos
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filter { it.packageName != appContext.packageName }
            .distinctBy { it.packageName }
            .map { appInfo ->
                AndroidInstalledApp(
                    packageName = appInfo.packageName,
                    label = appInfo.loadLabel(packageManager).toString(),
                    isSystem = false
                )
            }

        // ALSO list system / background packages that hold the INTERNET permission but have no launcher
        // icon (system services, GMS, carrier apps, WebView, etc.), so they can be excluded from /
        // included in the tunnel too. Marked isSystem=true; the UI hides them behind an off-by-default
        // toggle so the default list is unchanged. Best-effort: any failure just yields no extra apps.
        val launcherPackages = launcherApps.mapTo(mutableSetOf()) { it.packageName }
        val systemApps = runCatching {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledPackages(
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            }
            flags.asSequence()
                .filter { it.packageName != appContext.packageName && it.packageName !in launcherPackages }
                // Only apps that can actually use the network are meaningful to route/bypass.
                .filter { it.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true }
                .mapNotNull { pkg ->
                    val info = pkg.applicationInfo ?: return@mapNotNull null
                    AndroidInstalledApp(
                        packageName = pkg.packageName,
                        label = info.loadLabel(packageManager).toString(),
                        isSystem = true
                    )
                }
                .toList()
        }.getOrElse { emptyList() }

        (launcherApps + systemApps)
            .distinctBy { it.packageName }
            .sortedWith(compareBy<AndroidInstalledApp> { it.label.lowercase() }.thenBy { it.packageName })
    }

    private fun generateProxyPassword(): String {
        return buildString(PROXY_PASSWORD_LENGTH) {
            repeat(PROXY_PASSWORD_LENGTH) {
                append(PROXY_PASSWORD_ALPHABET[random.nextInt(PROXY_PASSWORD_ALPHABET.length)])
            }
        }
    }

    private fun generateProxyUsername(): String {
        return buildString(PROXY_USERNAME_PREFIX.length + PROXY_USERNAME_RANDOM_LENGTH) {
            append(PROXY_USERNAME_PREFIX)
            repeat(PROXY_USERNAME_RANDOM_LENGTH) {
                append(PROXY_USERNAME_ALPHABET[random.nextInt(PROXY_USERNAME_ALPHABET.length)])
            }
        }
    }

    private fun Set<String>.toggle(value: String): Set<String> {
        return if (value in this) this - value else this + value
    }

    private fun updateSplitTunnelSettings(settings: AndroidSplitTunnelSettings) {
        _splitTunnelSettings.value = settings
        scope.launch {
            appContext.vpnPrefDataStore.edit { preferences ->
                preferences[KEY_ANDROID_SPLIT_TUNNEL_PROXY_APPS] = settings.proxyPackages
                preferences[KEY_ANDROID_SPLIT_TUNNEL_BYPASS_APPS] = settings.bypassPackages
            }
        }
    }

    private data class AndroidAppPreferences(
        val mode: AndroidConnectionMode,
        val proxy: AndroidSocksProxySettings,
        val splitTunnel: AndroidSplitTunnelSettings,
        val dynamicThemeEnabled: Boolean
    )

    private companion object {
        const val LEGACY_DEFAULT_USERNAME = "olcbox"
        const val PROXY_USERNAME_PREFIX = "olcbox"
        const val PROXY_USERNAME_RANDOM_LENGTH = 8
        const val MAX_SOCKS_USERNAME_LENGTH = 64
        const val PROXY_PASSWORD_LENGTH = 24
        const val MAX_SOCKS_PASSWORD_LENGTH = 64
        const val PROXY_USERNAME_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        const val PROXY_PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        const val DEFAULT_LOCATION_PING_PARALLELISM = 4
        const val TCP_PING_ATTEMPTS = 2
        const val TCP_PING_TIMEOUT_MS = 3_000
        // End-to-end tunnel latency probe target. 1.1.1.1:443 (HTTPS) accepts TCP universally and
        // fast; port 53 can be filtered on some paths.
        const val TUNNEL_PROBE_HOST = "1.1.1.1"
        const val TUNNEL_PROBE_PORT = 443
        // The probe traverses VK relay + WireGuard, so allow more time than a direct TCP ping.
        const val TUNNEL_PING_TIMEOUT_MS = 6_000
        const val SUB_EXPIRY_CHANNEL_ID = "olcbox_sub_expiry"
        const val SUB_EXPIRY_NOTIFICATION_BASE_ID = 47_000
        const val PANEL_ANNOUNCE_CHANNEL_ID = "olcbox_panel_announce"
        const val PANEL_ANNOUNCE_NOTIFICATION_BASE_ID = 48_000
        val random = SecureRandom()
    }
}
