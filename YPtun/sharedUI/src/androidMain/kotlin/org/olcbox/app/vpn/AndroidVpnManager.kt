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
import org.olcbox.app.data.model.AppBehaviorSettings
import org.olcbox.app.ui.i18n.AppLanguage
import org.olcbox.app.ui.i18n.LocalizationState
import org.olcbox.app.data.model.RoutingRules
import org.olcbox.app.data.model.TrafficSettings
import org.olcbox.app.vpn.data.KEY_ANDROID_APP_BEHAVIOR
import org.olcbox.app.vpn.data.KEY_ANDROID_LANGUAGE
import org.olcbox.app.vpn.data.KEY_ANDROID_ROUTING
import org.olcbox.app.vpn.data.KEY_ANDROID_TRAFFIC
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.datasource.LocationsDataSourceImpl
import org.olcbox.app.data.identity.PersistentDeviceIdentityProvider
import org.olcbox.app.data.repository.SubscriptionFetchProxy
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
    val connectionMode: StateFlow<AndroidConnectionMode> = _connectionMode.asStateFlow()
    val proxySettings: StateFlow<AndroidSocksProxySettings> = _proxySettings.asStateFlow()
    val splitTunnelSettings: StateFlow<AndroidSplitTunnelSettings> = _splitTunnelSettings.asStateFlow()
    val dynamicThemeEnabled: StateFlow<Boolean> = _dynamicThemeEnabled.asStateFlow()
    val installedApps: StateFlow<List<AndroidInstalledApp>> = _installedApps.asStateFlow()
    private val _hwid = MutableStateFlow("")
    val hwid: StateFlow<String> = _hwid.asStateFlow()
    private val _routing = MutableStateFlow(RoutingRules())
    val routing: StateFlow<RoutingRules> = _routing.asStateFlow()
    private val _trafficSettings = MutableStateFlow(TrafficSettings())
    val trafficSettings: StateFlow<TrafficSettings> = _trafficSettings.asStateFlow()
    private val _appBehavior = MutableStateFlow(AppBehaviorSettings())
    val appBehavior: StateFlow<AppBehaviorSettings> = _appBehavior.asStateFlow()
    private val _language = MutableStateFlow(AppLanguage.System)
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    init {
        LocalizationState.systemLanguage =
            if (java.util.Locale.getDefault().language == "ru") AppLanguage.Russian else AppLanguage.English

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

        // Keep the global ThemeState in sync with persisted custom colors.
        scope.launch {
            appContext.vpnPrefDataStore.data.collect { preferences ->
                ThemeState.accent = preferences[KEY_ANDROID_ACCENT_COLOR]?.let { Color(it.toInt()) }
                ThemeState.textColor = preferences[KEY_ANDROID_TEXT_COLOR]?.let { Color(it.toInt()) }
                _routing.value = preferences[KEY_ANDROID_ROUTING]
                    ?.let { runCatching { Json.decodeFromString(RoutingRules.serializer(), it) }.getOrNull() }
                    ?: RoutingRules()
                _trafficSettings.value = preferences[KEY_ANDROID_TRAFFIC]
                    ?.let { runCatching { Json.decodeFromString(TrafficSettings.serializer(), it) }.getOrNull() }
                    ?.normalized()
                    ?: TrafficSettings()
                _appBehavior.value = preferences[KEY_ANDROID_APP_BEHAVIOR]
                    ?.let { runCatching { Json.decodeFromString(AppBehaviorSettings.serializer(), it) }.getOrNull() }
                    ?: AppBehaviorSettings()
                val lang = AppLanguage.fromId(preferences[KEY_ANDROID_LANGUAGE])
                _language.value = lang
                LocalizationState.language = lang
                ThemeState.background = preferences[KEY_ANDROID_BG_COLOR]?.let { Color(it.toInt()) }
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
            startVpn()
        }
    }

    fun setTrafficSettings(settings: TrafficSettings) {
        val normalized = settings.normalized()
        _trafficSettings.value = normalized
        scope.launch {
            appContext.vpnPrefDataStore.edit { preferences ->
                preferences[KEY_ANDROID_TRAFFIC] = Json.encodeToString(TrafficSettings.serializer(), normalized)
            }
        }
        if (status.value is VpnStatus.Connected || status.value is VpnStatus.Reconnecting) {
            startVpn()
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
        _appBehavior.value = settings
        scope.launch {
            appContext.vpnPrefDataStore.edit { preferences ->
                preferences[KEY_ANDROID_APP_BEHAVIOR] =
                    Json.encodeToString(AppBehaviorSettings.serializer(), settings)
            }
        }
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

    override suspend fun ping(locationConfig: LocationConfig): Long? {
        // VLESS/standard locations have no olcRTC room/key — pinging them via olcRTC is unstable.
        // Use a plain TCP latency probe to the proxy server instead (like typical proxy clients).
        if (locationConfig.engine == EngineType.Standard) {
            return tcpPing(locationConfig.proxy?.server, locationConfig.proxy?.serverPort)
        }
        return OlcRtcConnectionChecker.ping(
            locationConfig = locationConfig,
            deviceId = deviceIdentityProvider.hwid()
        )
    }

    override suspend fun checkConnection(locationConfig: LocationConfig): Long? {
        if (locationConfig.engine == EngineType.Standard) {
            return tcpPing(locationConfig.proxy?.server, locationConfig.proxy?.serverPort)
        }
        return OlcRtcConnectionChecker.check(
            locationConfig = locationConfig,
            deviceId = deviceIdentityProvider.hwid()
        )
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

        resolveInfos
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filter { it.packageName != appContext.packageName }
            .distinctBy { it.packageName }
            .map { appInfo ->
                AndroidInstalledApp(
                    packageName = appInfo.packageName,
                    label = appInfo.loadLabel(packageManager).toString()
                )
            }
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
        val random = SecureRandom()
    }
}
