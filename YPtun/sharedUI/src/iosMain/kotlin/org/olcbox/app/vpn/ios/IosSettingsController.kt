package org.olcbox.app.vpn.ios

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.olcbox.app.data.importer.HappRoutingParser
import org.olcbox.app.data.model.AppBehaviorSettings
import org.olcbox.app.data.model.RoutingProfile
import org.olcbox.app.data.model.RoutingProfilesState
import org.olcbox.app.data.model.RoutingRules
import org.olcbox.app.data.model.TrafficSettings
import org.olcbox.app.ui.i18n.AppLanguage
import org.olcbox.app.ui.i18n.LocalizationState
import org.olcbox.app.ui.theme.ThemeState
import org.olcbox.app.vpn.GeoUpdateStatus
import platform.Foundation.NSLocale
// Class properties that Objective-C declares in a category (here NSLocale's NSLocaleGeneralInfo)
// become companion EXTENSIONS in Kotlin/Native, so they need their own import — unlike
// NSFileManager.defaultManager, which lives in the main @interface and resolves from the class alone.
import platform.Foundation.preferredLanguages
import kotlin.random.Random
import kotlin.time.Clock

/**
 * iOS counterpart of DesktopSettingsController: every settings model as a StateFlow for the UI,
 * persisted through [IosSharedStore] on each change. The tunnel extension re-reads the same files
 * in the App Group when it starts, so a saved change applies on the next (re)connect — the same
 * contract as Android's DataStore and the desktop's JSON files.
 *
 * Split tunneling is deliberately absent: iOS has no way to enumerate installed apps or attribute
 * traffic to them, so the settings screen hides that section on this platform.
 */
class IosSettingsController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _routing = MutableStateFlow(IosSharedStore.loadRouting())
    val routing: StateFlow<RoutingRules> = _routing.asStateFlow()

    private val _routingProfiles = MutableStateFlow(IosSharedStore.loadRoutingProfiles())
    val routingProfiles: StateFlow<RoutingProfilesState> = _routingProfiles.asStateFlow()

    private val _traffic = MutableStateFlow(IosSharedStore.loadTraffic())
    val traffic: StateFlow<TrafficSettings> = _traffic.asStateFlow()

    private val _appBehavior = MutableStateFlow(IosSharedStore.loadAppBehavior())
    val appBehavior: StateFlow<AppBehaviorSettings> = _appBehavior.asStateFlow()

    private val _geoUpdateStatus = MutableStateFlow<GeoUpdateStatus?>(null)
    val geoUpdateStatus: StateFlow<GeoUpdateStatus?> = _geoUpdateStatus.asStateFlow()

    private val _language = MutableStateFlow(AppLanguage.System)
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    private val _lightTheme = MutableStateFlow(false)
    val lightTheme: StateFlow<Boolean> = _lightTheme.asStateFlow()

    init {
        // What «Системный» resolves to on this device. Without it the iOS build kept the
        // LocalizationState default (Russian) forever — nothing on this platform ever set it, so an
        // English/Persian/Chinese iPhone still got a Russian UI. Mirrors AndroidVpnManager.init and
        // DesktopSettingsController.init.
        LocalizationState.systemLanguage = systemLanguage()
        val ui = IosSharedStore.loadUi()
        _language.value = AppLanguage.fromId(ui.language)
        LocalizationState.language = _language.value
        _lightTheme.value = ui.lightTheme
        ThemeState.lightMode = ui.lightTheme
        // iOS has no Material You equivalent, so the dynamic theme stays off and the custom
        // swatches always apply (the settings screen hides that switch on this platform).
        ThemeState.dynamicEnabled = false
        ThemeState.accent = ui.accentArgb?.let { Color(it) }
        ThemeState.textColor = ui.textArgb?.let { Color(it) }
        ThemeState.background = ui.backgroundArgb?.let { Color(it) }
    }

    private fun systemLanguage(): AppLanguage {
        val tag = runCatching { NSLocale.preferredLanguages.firstOrNull() as? String }
            .getOrNull()
            .orEmpty()
            .lowercase()
        return when {
            tag.startsWith("ru") -> AppLanguage.Russian
            tag.startsWith("fa") -> AppLanguage.Persian
            tag.startsWith("zh") -> AppLanguage.Chinese
            // Unknown locale reported: keep the historical default rather than flipping an existing
            // install to English on upgrade.
            tag.isBlank() -> AppLanguage.Russian
            else -> AppLanguage.English
        }
    }

    private fun saveUi(transform: (IosUiSettings) -> IosUiSettings) {
        runCatching { IosSharedStore.saveUi(transform(IosSharedStore.loadUi())) }
    }

    // --- routing rules -----------------------------------------------------------------------
    fun setRouting(value: RoutingRules) {
        _routing.value = value
        IosSharedStore.saveRouting(value)
    }

    // --- Happ-style routing profiles ---------------------------------------------------------
    private fun setRoutingProfilesState(value: RoutingProfilesState) {
        _routingProfiles.value = value
        IosSharedStore.saveRoutingProfiles(value)
    }

    /** Desktop uses a UUID; Kotlin/Native has none in the stdlib, so a random hex suffix stands in. */
    private fun newProfileId(): String =
        "rp-" + (0 until 8).joinToString("") { Random.nextInt(16).toString(16) }

    fun saveRoutingProfile(profile: RoutingProfile): String {
        val id = profile.id.ifBlank { newProfileId() }
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
                if (it.id.isBlank()) it.copy(id = newProfileId()) else it
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
            val ok = IosGeoAssets.forceRefresh(state.geoipUrl, state.geositeUrl)
            _geoUpdateStatus.value = if (ok) {
                val now = Clock.System.now().toEpochMilliseconds()
                setRoutingProfilesState(_routingProfiles.value.copy(geoLastUpdated = now))
                GeoUpdateStatus.Success(now, IosGeoAssets.totalBytes())
            } else {
                GeoUpdateStatus.Failed("download failed")
            }
        }
    }

    // --- traffic / behavior ------------------------------------------------------------------
    fun setTrafficSettings(value: TrafficSettings) {
        val normalized = value.normalized()
        _traffic.value = normalized
        IosSharedStore.saveTraffic(normalized)
    }

    fun setAppBehavior(value: AppBehaviorSettings) {
        _appBehavior.value = value
        IosSharedStore.saveAppBehavior(value)
    }

    // --- language / theme --------------------------------------------------------------------
    fun setLanguage(value: AppLanguage) {
        _language.value = value
        LocalizationState.language = value
        saveUi { it.copy(language = value.id) }
    }

    fun setLightTheme(enabled: Boolean) {
        _lightTheme.value = enabled
        ThemeState.lightMode = enabled
        saveUi { it.copy(lightTheme = enabled) }
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
