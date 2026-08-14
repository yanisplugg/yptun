package org.olcbox.app.vpn.telegram

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.olcbox.app.desktop.DesktopPaths
import java.nio.file.Files
import java.security.SecureRandom
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Desktop persistence for the Telegram-over-WARP proxy: the generated WARP AmneziaWG config and the
 * SOCKS5 credentials. Android keeps both in its DataStore; on desktop they live next to the other
 * settings JSON files.
 *
 * The credentials are generated once and then kept stable across restarts, so whatever the user typed
 * into Telegram's proxy settings keeps working. The listener is loopback-only, but requiring
 * credentials still stops any other local app from quietly riding the WARP tunnel.
 */
internal object TelegramProxyStore {

    @Serializable
    data class Credentials(val user: String, val pass: String)

    private const val USER_LEN = 8
    private const val PASS_LEN = 16
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    private fun dir() = DesktopPaths.appDataDir().resolve("settings")
        .also { runCatching { Files.createDirectories(it) } }

    private fun configFile() = dir().resolve("telegram_warp.conf")
    private fun credsFile() = dir().resolve("telegram_proxy_creds.json")

    /** The cached WARP INI, or null when the proxy has never been enabled. */
    fun loadConfig(): String? = configFile()
        .takeIf { it.exists() }
        ?.let { runCatching { it.readText() }.getOrNull() }
        ?.takeIf { it.isNotBlank() }

    fun saveConfig(ini: String) {
        runCatching { configFile().writeText(ini) }
    }

    /** Stored credentials, generating and persisting a fresh pair on first call. */
    fun getOrCreateCredentials(): Credentials {
        peekCredentials()?.let { return it }
        val created = Credentials(randomToken(USER_LEN), randomToken(PASS_LEN))
        runCatching { credsFile().writeText(json.encodeToString(Credentials.serializer(), created)) }
        return created
    }

    fun peekCredentials(): Credentials? = credsFile()
        .takeIf { it.exists() }
        ?.let { runCatching { json.decodeFromString(Credentials.serializer(), it.readText()) }.getOrNull() }
        ?.takeIf { it.user.isNotBlank() && it.pass.isNotBlank() }

    private fun randomToken(length: Int): String {
        val rnd = SecureRandom()
        return buildString(length) {
            repeat(length) { append(ALPHABET[rnd.nextInt(ALPHABET.length)]) }
        }
    }
}
