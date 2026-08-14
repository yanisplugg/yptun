package org.olcbox.app.vpn.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.olcbox.app.data.importer.HappRoutingParser
import org.olcbox.app.data.model.AppBehaviorSettings
import org.olcbox.app.data.model.RoutingProfile
import org.olcbox.app.data.model.RoutingProfilesState
import org.olcbox.app.data.model.RoutingRules
import org.olcbox.app.data.model.TrafficSettings
import org.olcbox.app.desktop.DesktopElevation
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.desktop.DesktopRuntimeMode
import org.olcbox.app.ui.i18n.AppLanguage
import org.olcbox.app.ui.i18n.LocalizationState
import org.olcbox.app.ui.theme.ThemeState
import org.olcbox.app.vpn.AndroidConnectionMode
import org.olcbox.app.vpn.GeoUpdateStatus
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Desktop UI preferences persisted as one JSON file (theme, language, connection mode). */
@Serializable
data class DesktopUiSettings(
    val language: String = "system",
    val connectionMode: String = "tun",
    val dynamicTheme: Boolean = false,
    val accentArgb: Int? = null,
    val textArgb: Int? = null,
    val backgroundArgb: Int? = null,
)

/**
 * Desktop counterpart of AndroidVpnManager's settings surface: holds every settings model as a
 * StateFlow for the UI and persists each change via [JvmVpnSettings] / `settings/ui.json`,
 * mirroring the Android DataStore behavior. The engine controller re-reads the same files on
 * every connect, so saved changes apply on the next (re)connect, like Android.
 */
class DesktopSettingsController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val uiFile = DesktopPaths.appDataDir().resolve("settings").resolve("ui.json")

    private val _routing = MutableStateFlow(JvmVpnSettings.loadRouting())
    val routing: StateFlow<RoutingRules> = _routing.asStateFlow()

    private val _routingProfiles = MutableStateFlow(JvmVpnSettings.loadRoutingProfiles())
    val routingProfiles: StateFlow<RoutingProfilesState> = _routingProfiles.asStateFlow()

    private val _traffic = MutableStateFlow(JvmVpnSettings.loadTraffic())
    val traffic: StateFlow<TrafficSettings> = _traffic.asStateFlow()

    private val _appBehavior = MutableStateFlow(JvmVpnSettings.loadAppBehavior())
    val appBehavior: StateFlow<AppBehaviorSettings> = _appBehavior.asStateFlow()

    private val _geoUpdateStatus = MutableStateFlow<GeoUpdateStatus?>(null)
    val geoUpdateStatus: StateFlow<GeoUpdateStatus?> = _geoUpdateStatus.asStateFlow()

    private val _language = MutableStateFlow(AppLanguage.System)
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    private val _connectionMode = MutableStateFlow(AndroidConnectionMode.Tun)
    val connectionMode: StateFlow<AndroidConnectionMode> = _connectionMode.asStateFlow()

    private val _dynamicTheme = MutableStateFlow(false)
    val dynamicTheme: StateFlow<Boolean> = _dynamicTheme.asStateFlow()

    init {
        // What «Системный» resolves to on this machine. Without this the desktop kept the
        // LocalizationState default (Russian), so an English/Chinese/Persian Windows still got a
        // Russian UI unless the language was picked by hand. Mirrors AndroidVpnManager.init.
        LocalizationState.systemLanguage = when (java.util.Locale.getDefault().language) {
            "ru" -> AppLanguage.Russian
            "fa" -> AppLanguage.Persian
            "zh" -> AppLanguage.Chinese
            else -> AppLanguage.English
        }
        val ui = loadUi()
        _language.value = AppLanguage.fromId(ui.language)
        LocalizationState.language = _language.value
        // The portable build starts in proxy mode: it is the "don't touch my machine" build, and
        // proxy mode is the only mode that needs no administrator rights, so launching it never
        // raises a UAC prompt. Tunnel mode is one click away — picking «ТУННЕЛЬ» is what asks for
        // rights — and an already-elevated portable (i.e. the copy that came back through UAC for
        // exactly that reason) keeps whatever mode was saved.
        val forceProxy = DesktopRuntimeMode.isPortable && !DesktopElevation.isElevated()
        _connectionMode.value = if (forceProxy) {
            AndroidConnectionMode.Proxy
        } else {
            AndroidConnectionMode.fromValue(ui.connectionMode)
        }
        if (forceProxy && ui.connectionMode != AndroidConnectionMode.Proxy.value) {
            saveUi { it.copy(connectionMode = AndroidConnectionMode.Proxy.value) }
        }
        _dynamicTheme.value = ui.dynamicTheme
        ThemeState.dynamicEnabled = ui.dynamicTheme
        ThemeState.accent = ui.accentArgb?.let { Color(it) }
        ThemeState.textColor = ui.textArgb?.let { Color(it) }
        ThemeState.background = ui.backgroundArgb?.let { Color(it) }
    }

    // --- per-process split tunneling (desktop analog of Android's per-app VPN) --------------
    private val _splitTunnel = MutableStateFlow(splitTunnelSettings(JvmVpnSettings.loadSplitTunnel()))
    val splitTunnel: StateFlow<org.olcbox.app.vpn.AndroidSplitTunnelSettings> = _splitTunnel.asStateFlow()

    private fun splitTunnelSettings(state: JvmVpnSettings.SplitTunnelState) =
        org.olcbox.app.vpn.AndroidSplitTunnelSettings(
            mode = org.olcbox.app.vpn.AndroidSplitTunnelMode.fromValue(state.mode),
            proxyPackages = state.proxyProcesses,
            bypassPackages = state.bypassProcesses,
        )

    private fun persistSplitTunnel(settings: org.olcbox.app.vpn.AndroidSplitTunnelSettings) {
        _splitTunnel.value = settings
        JvmVpnSettings.saveSplitTunnel(
            JvmVpnSettings.SplitTunnelState(
                mode = settings.mode.value,
                proxyProcesses = settings.proxyPackages,
                bypassProcesses = settings.bypassPackages,
            )
        )
    }

    fun selectSplitTunnelMode(mode: org.olcbox.app.vpn.AndroidSplitTunnelMode) {
        persistSplitTunnel(_splitTunnel.value.copy(mode = mode))
    }

    fun toggleSplitTunnelApp(list: org.olcbox.app.vpn.AndroidSplitTunnelList, processName: String) {
        val current = _splitTunnel.value
        val updated = when (list) {
            org.olcbox.app.vpn.AndroidSplitTunnelList.Proxy -> current.copy(
                proxyPackages = current.proxyPackages.toggle(processName)
            )
            org.olcbox.app.vpn.AndroidSplitTunnelList.Bypass -> current.copy(
                bypassPackages = current.bypassPackages.toggle(processName)
            )
        }
        persistSplitTunnel(updated)
    }

    fun setSplitTunnelApps(list: org.olcbox.app.vpn.AndroidSplitTunnelList, processes: Set<String>) {
        val current = _splitTunnel.value
        persistSplitTunnel(
            when (list) {
                org.olcbox.app.vpn.AndroidSplitTunnelList.Proxy -> current.copy(proxyPackages = processes)
                org.olcbox.app.vpn.AndroidSplitTunnelList.Bypass -> current.copy(bypassPackages = processes)
            }
        )
    }

    private fun Set<String>.toggle(value: String): Set<String> =
        if (value in this) this - value else this + value

    /**
     * Running processes as the desktop "installed apps" list: distinct exe names of every process
     * we can see, plus anything already selected (so a non-running app stays in the list).
     */
    fun installedApps(): List<org.olcbox.app.vpn.AndroidInstalledApp> {
        val running = runCatching {
            ProcessHandle.allProcesses()
                .map { it.info().command().orElse("") }
                .filter { it.isNotBlank() }
                .map { it.substringAfterLast('\\').substringAfterLast('/') }
                .filter { it.endsWith(".exe", ignoreCase = true) || !it.contains('.') }
                .distinct()
                .sorted()
                .toList()
        }.getOrDefault(emptyList())
        val selected = _splitTunnel.value.proxyPackages + _splitTunnel.value.bypassPackages
        return (running + selected).distinct().sortedBy { it.lowercase() }
            .map { org.olcbox.app.vpn.AndroidInstalledApp(packageName = it, label = it) }
    }

    /** Dynamic theme on desktop = derive the accent from the Windows system accent color. */
    fun setDynamicTheme(enabled: Boolean) {
        _dynamicTheme.value = enabled
        ThemeState.dynamicEnabled = enabled
        saveUi { it.copy(dynamicTheme = enabled) }
    }

    private fun loadUi(): DesktopUiSettings {
        if (!uiFile.exists()) return DesktopUiSettings()
        return runCatching { json.decodeFromString(DesktopUiSettings.serializer(), uiFile.readText()) }
            .getOrElse { DesktopUiSettings() }
    }

    private fun saveUi(transform: (DesktopUiSettings) -> DesktopUiSettings) {
        runCatching {
            Files.createDirectories(uiFile.parent)
            uiFile.writeText(json.encodeToString(DesktopUiSettings.serializer(), transform(loadUi())))
        }
    }

    // --- routing rules (v2rayNG-style sing-box rules + toggles) ---------------------------
    fun setRouting(value: RoutingRules) {
        _routing.value = value
        JvmVpnSettings.saveRouting(value)
    }

    // --- Happ-style routing profiles -------------------------------------------------------
    private fun setRoutingProfilesState(value: RoutingProfilesState) {
        _routingProfiles.value = value
        JvmVpnSettings.saveRoutingProfiles(value)
    }

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

    fun setGlobalRoutingProfile(id: String) {
        setRoutingProfilesState(_routingProfiles.value.copy(globalProfileId = id))
    }

    fun importRoutingProfileLink(link: String): Boolean {
        RoutingProfilesState.fromRoutingLink(link)?.let { imported ->
            val withIds = imported.profiles.map {
                if (it.id.isBlank()) it.copy(id = "rp-" + java.util.UUID.randomUUID().toString().take(8)) else it
            }
            setRoutingProfilesState(imported.copy(profiles = withIds))
            return true
        }
        val parsed = HappRoutingParser.parseAny(link) ?: return false
        saveRoutingProfile(parsed.copy(id = ""))
        return true
    }

    fun setGeoSources(geoipUrl: String, geositeUrl: String) {
        setRoutingProfilesState(
            _routingProfiles.value.copy(geoipUrl = geoipUrl.trim(), geositeUrl = geositeUrl.trim())
        )
    }

    fun updateGeoAssetsNow() {
        if (_geoUpdateStatus.value is GeoUpdateStatus.Running) return
        val state = _routingProfiles.value
        _geoUpdateStatus.value = GeoUpdateStatus.Running
        scope.launch {
            // Force a refresh: drop the current files so ensureAssets re-downloads.
            runCatching {
                JvmGeoAssets.assetDir().listFiles()?.forEach { it.delete() }
            }
            val ok = JvmGeoAssets.ensureAssets(state.geoipUrl, state.geositeUrl)
            if (ok) {
                val now = System.currentTimeMillis()
                setRoutingProfilesState(_routingProfiles.value.copy(geoLastUpdated = now))
                val bytes = JvmGeoAssets.assetDir().listFiles()?.sumOf { it.length() } ?: 0L
                _geoUpdateStatus.value = GeoUpdateStatus.Success(now, bytes)
            } else {
                _geoUpdateStatus.value = GeoUpdateStatus.Failed("download failed")
            }
        }
    }

    // --- traffic / behavior ----------------------------------------------------------------
    fun setTrafficSettings(value: TrafficSettings) {
        val normalized = value.normalized()
        _traffic.value = normalized
        JvmVpnSettings.saveTraffic(normalized)
    }

    fun setAppBehavior(value: AppBehaviorSettings) {
        _appBehavior.value = value
        JvmVpnSettings.saveAppBehavior(value)
    }

    // --- language / theme / mode -----------------------------------------------------------
    fun setLanguage(value: AppLanguage) {
        _language.value = value
        LocalizationState.language = value
        saveUi { it.copy(language = value.id) }
    }

    fun selectConnectionMode(mode: AndroidConnectionMode) {
        _connectionMode.value = mode
        saveUi { it.copy(connectionMode = mode.value) }
    }

    fun setAccentColor(color: Color?) {
        ThemeState.accent = color
        saveUi { it.copy(accentArgb = color?.toArgb()) }
    }

    fun setTextColor(color: Color?) {
        ThemeState.textColor = color
        saveUi { it.copy(textArgb = color?.toArgb()) }
    }

    fun setBackgroundColor(color: Color?) {
        ThemeState.background = color
        saveUi { it.copy(backgroundArgb = color?.toArgb()) }
    }
}
