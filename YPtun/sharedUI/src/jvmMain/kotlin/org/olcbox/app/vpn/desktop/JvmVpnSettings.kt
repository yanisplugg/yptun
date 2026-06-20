package org.olcbox.app.vpn.desktop

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.olcbox.app.data.model.AppBehaviorSettings
import org.olcbox.app.data.model.RoutingProfilesState
import org.olcbox.app.data.model.RoutingRules
import org.olcbox.app.data.model.TrafficSettings
import org.olcbox.app.desktop.DesktopPaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Desktop counterpart of Android's `vpnPrefDataStore` settings: each settings model is one JSON
 * file under `<appData>/settings/`. Read by the engine controller on every connect (so external
 * edits or future settings UI take effect on reconnect, like Android).
 */
internal object JvmVpnSettings {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    private fun settingsDir(): Path =
        DesktopPaths.appDataDir().resolve("settings").also { Files.createDirectories(it) }

    private fun <T> load(fileName: String, serializer: KSerializer<T>, default: () -> T): T {
        val file = settingsDir().resolve(fileName)
        if (!file.exists()) return default()
        return runCatching { json.decodeFromString(serializer, file.readText()) }
            .getOrElse { default() }
    }

    private fun <T> save(fileName: String, serializer: KSerializer<T>, value: T) {
        runCatching { settingsDir().resolve(fileName).writeText(json.encodeToString(serializer, value)) }
    }

    fun loadTraffic(): TrafficSettings =
        load("traffic.json", TrafficSettings.serializer()) { TrafficSettings() }.normalized()

    fun saveTraffic(value: TrafficSettings) = save("traffic.json", TrafficSettings.serializer(), value)

    fun loadRouting(): RoutingRules =
        load("routing.json", RoutingRules.serializer()) { RoutingRules() }

    fun saveRouting(value: RoutingRules) = save("routing.json", RoutingRules.serializer(), value)

    fun loadRoutingProfiles(): RoutingProfilesState =
        load("routing_profiles.json", RoutingProfilesState.serializer()) { RoutingProfilesState() }

    fun saveRoutingProfiles(value: RoutingProfilesState) =
        save("routing_profiles.json", RoutingProfilesState.serializer(), value)

    /** Desktop per-process split tunneling: exe-name lists persisted like the other settings. */
    @kotlinx.serialization.Serializable
    data class SplitTunnelState(
        val mode: String = "all_apps",
        val proxyProcesses: Set<String> = emptySet(),
        val bypassProcesses: Set<String> = emptySet(),
    )

    fun loadSplitTunnel(): SplitTunnelState =
        load("split_tunnel.json", SplitTunnelState.serializer()) { SplitTunnelState() }

    fun saveSplitTunnel(value: SplitTunnelState) =
        save("split_tunnel.json", SplitTunnelState.serializer(), value)

    fun loadAppBehavior(): AppBehaviorSettings =
        load("app_behavior.json", AppBehaviorSettings.serializer()) { AppBehaviorSettings() }

    fun saveAppBehavior(value: AppBehaviorSettings) =
        save("app_behavior.json", AppBehaviorSettings.serializer(), value)
}
