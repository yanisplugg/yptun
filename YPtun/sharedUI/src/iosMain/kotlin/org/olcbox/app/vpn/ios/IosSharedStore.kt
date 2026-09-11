package org.olcbox.app.vpn.ios

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.olcbox.app.data.model.AppBehaviorSettings
import org.olcbox.app.data.model.RoutingProfilesState
import org.olcbox.app.data.model.RoutingRules
import org.olcbox.app.data.model.TrafficSettings
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * Files the app and the packet-tunnel extension both read: the extension is a separate process
 * that runs the cores, so every setting that shapes a connection lives in the App Group container
 * (the desktop keeps the same JSON files in its data dir, see JvmVpnSettings).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
object IosSharedStore {
    const val APP_GROUP = "group.org.yptun.app"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    /**
     * The App Group container, or Documents when the group is unavailable (an unsigned/simulator
     * build without the entitlement) — then the extension can't see the files, which only matters
     * on a build that couldn't run the extension anyway.
     */
    val dir: String by lazy {
        val group = NSFileManager.defaultManager
            .containerURLForSecurityApplicationGroupIdentifier(APP_GROUP)?.path
        val base = group ?: (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: "")
        "$base/yptun".also {
            NSFileManager.defaultManager.createDirectoryAtPath(it, true, null, null)
        }
    }

    fun path(fileName: String): String = "$dir/$fileName"

    fun readText(fileName: String): String? =
        NSString.stringWithContentsOfFile(path(fileName), NSUTF8StringEncoding, null)

    fun writeText(fileName: String, text: String) {
        NSString.create(string = text).writeToFile(path(fileName), true, NSUTF8StringEncoding, null)
    }

    fun <T> load(fileName: String, serializer: KSerializer<T>, default: () -> T): T =
        readText(fileName)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: default()

    fun <T> save(fileName: String, serializer: KSerializer<T>, value: T) =
        writeText(fileName, json.encodeToString(serializer, value))

    fun loadTraffic(): TrafficSettings =
        load("traffic.json", TrafficSettings.serializer()) { TrafficSettings() }.normalized()
    fun saveTraffic(value: TrafficSettings) = save("traffic.json", TrafficSettings.serializer(), value)

    fun loadRouting(): RoutingRules = load("routing.json", RoutingRules.serializer()) { RoutingRules() }
    fun saveRouting(value: RoutingRules) = save("routing.json", RoutingRules.serializer(), value)

    fun loadRoutingProfiles(): RoutingProfilesState =
        load("routing_profiles.json", RoutingProfilesState.serializer()) { RoutingProfilesState() }
    fun saveRoutingProfiles(value: RoutingProfilesState) =
        save("routing_profiles.json", RoutingProfilesState.serializer(), value)

    fun loadAppBehavior(): AppBehaviorSettings =
        load("app_behavior.json", AppBehaviorSettings.serializer()) { AppBehaviorSettings() }
    fun saveAppBehavior(value: AppBehaviorSettings) =
        save("app_behavior.json", AppBehaviorSettings.serializer(), value)
}
