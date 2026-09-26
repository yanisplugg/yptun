package org.olcbox.app.vpn.ios

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.olcbox.app.data.model.AppBehaviorSettings
import org.olcbox.app.data.model.RoutingProfilesState
import org.olcbox.app.data.model.RoutingRules
import org.olcbox.app.data.model.TrafficSettings
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.closeFile
// Category methods on NSFileHandle (NSFileHandleCreation / the seek+read category) need their own
// import in Kotlin/Native — see the note in IosSettingsController about class properties.
import platform.Foundation.create
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.readDataToEndOfFile
import platform.Foundation.seekToFileOffset
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

    fun ensureDirectory(dirPath: String) {
        NSFileManager.defaultManager.createDirectoryAtPath(dirPath, true, null, null)
    }

    fun readText(fileName: String): String? =
        NSString.stringWithContentsOfFile(path(fileName), NSUTF8StringEncoding, null)

    fun writeText(fileName: String, text: String) {
        NSString.create(string = text).writeToFile(path(fileName), true, NSUTF8StringEncoding, null)
    }

    /** Size in bytes, 0 when the file does not exist. */
    fun fileSize(fileName: String): Long =
        (NSFileManager.defaultManager.attributesOfItemAtPath(path(fileName), null)?.get(NSFileSize) as? NSNumber)
            ?.longLongValue ?: 0L

    /**
     * The last [maxBytes] of a file, starting at the first whole line inside that window.
     *
     * The tunnel log is appended to by two processes and tailed once a second by the app; reading the
     * whole thing every tick made the app's cost grow with the length of the session. Cutting into the
     * middle of a UTF-8 sequence makes NSString reject the ENTIRE buffer, so the offset is nudged
     * forward a few bytes until it decodes (a UTF-8 character is at most 4 bytes long).
     */
    fun readTextTail(fileName: String, maxBytes: Long): String? {
        val size = fileSize(fileName)
        if (size <= maxBytes) return readText(fileName)
        val handle = NSFileHandle.fileHandleForReadingAtPath(path(fileName)) ?: return readText(fileName)
        try {
            for (nudge in 0..3) {
                handle.seekToFileOffset((size - maxBytes + nudge).toULong())
                val data = handle.readDataToEndOfFile()
                val text = NSString.create(data, NSUTF8StringEncoding)?.toString() ?: continue
                // The first line in the window is almost certainly a fragment — drop it.
                return text.substringAfter('\n', text)
            }
        } finally {
            handle.closeFile()
        }
        return null
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

    fun loadUi(): IosUiSettings = load("ui.json", IosUiSettings.serializer()) { IosUiSettings() }
    fun saveUi(value: IosUiSettings) = save("ui.json", IosUiSettings.serializer(), value)

    /**
     * "tun" (default) or "proxy" — read by the extension when it decides whether to capture the
     * device's packets or only advertise an HTTP proxy. A plain file rather than a field on
     * AppBehaviorSettings: that model is shared with every other platform, and this choice is
     * meaningless on them.
     */
    fun loadConnectionMode(): String = MODE_TUN
 
    fun saveConnectionMode(value: String) =
        writeText(CONNECTION_MODE_FILE, MODE_TUN)

    const val MODE_TUN = "tun"
    const val MODE_PROXY = "proxy"
    private const val CONNECTION_MODE_FILE = "connection_mode.txt"

    fun loadOnDemand(): Boolean = readText("on_demand.txt")?.trim() == "true"
    fun saveOnDemand(value: Boolean) = writeText("on_demand.txt", value.toString())

    fun loadLiveActivity(): Boolean = readText("live_activity.txt")?.trim() != "false"
    fun saveLiveActivity(value: Boolean) = writeText("live_activity.txt", value.toString())
}

/**
 * UI preferences that never reach the tunnel process (language, theme, custom colors) — the iOS
 * counterpart of the desktop's `settings/ui.json` (see DesktopUiSettings). Kept out of the models
 * above because the extension has no use for them.
 */
@Serializable
data class IosUiSettings(
    val language: String = "system",
    val lightTheme: Boolean = false,
    val accentArgb: Int? = null,
    val textArgb: Int? = null,
    val backgroundArgb: Int? = null,
)
