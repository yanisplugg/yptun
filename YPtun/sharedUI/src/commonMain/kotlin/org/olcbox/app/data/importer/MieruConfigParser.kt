package org.olcbox.app.data.importer

import org.olcbox.app.data.model.ProxyProfile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Decodes standard upstream `mieru://<base64-protobuf-ClientConfig>` share links.
 *
 * Mieru's protobuf schema (`pkg/appctl/proto/clientcfg.proto`):
 * - ClientConfig:
 *     repeated ClientProfile profiles = 1;
 *     optional string activeProfile = 2;
 *     optional int32 rpcPort = 3;
 *     optional int32 socks5Port = 4;
 * - ClientProfile:
 *     optional string profileName = 1;
 *     optional User user = 2;
 *     repeated ServerEndpoint servers = 3;
 * - User:
 *     optional string name = 1;
 *     optional string password = 2;
 * - ServerEndpoint:
 *     optional string ipAddress = 1;
 *     optional string domainName = 2;
 *     repeated PortBinding portBindings = 3;
 * - PortBinding:
 *     optional int32 port = 1;
 *     optional TransportProtocol protocol = 2;
 *     optional string portRange = 3;
 */
object MieruConfigParser {

    data class ClientConfig(
        val activeProfile: String?,
        val profiles: List<ClientProfile>,
        val socks5Port: Int? = null
    )

    data class ClientProfile(
        val profileName: String,
        val user: User? = null,
        val servers: List<ServerEndpoint> = emptyList()
    )

    data class User(
        val name: String,
        val password: String
    )

    data class ServerEndpoint(
        val ipAddress: String,
        val domainName: String,
        val portBindings: List<PortBinding> = emptyList()
    )

    data class PortBinding(
        val port: Int?,
        val protocol: Int?,
        val portRange: String?
    )

    fun parse(uri: String): ProxyProfile? {
        if (!uri.startsWith("mieru://", ignoreCase = true)) return null
        val payload = uri.substring("mieru://".length).substringBefore('#').trim()
        if (payload.isBlank()) return null
        val fragmentRemark = UriCodec.percentDecode(uri.substringAfter('#', "")).trim()

        val parsed = decodeFullClientConfig(payload)
        if (parsed == null) {
            // Graceful fallback for legacy or opaque configs
            val fallbackTag = fragmentRemark.ifBlank { "Mieru" }
            return ProxyProfile(
                tag = fallbackTag,
                type = ProxyProfile.TYPE_MIERU,
                server = "mieru-config",
                serverPort = 1,
                mieruConfig = uri
            )
        }

        val activeName = parsed.activeProfile?.trim().orEmpty()
        val activeProfile = parsed.profiles.firstOrNull { it.profileName == activeName }
            ?: parsed.profiles.firstOrNull()

        val profileName = activeProfile?.profileName?.trim().orEmpty()

        // Give priority to the panel-configured profileName (which carries emojis like 🇺🇸Нью-Йорк (Mieru)),
        // while ignoring generic/system fragment remarks such as "YPTun" or "Mieru".
        val tag = when {
            profileName.isNotBlank() && (fragmentRemark.isBlank() ||
                fragmentRemark.equals("YPTun", ignoreCase = true) ||
                fragmentRemark.equals("Mieru", ignoreCase = true)) -> profileName
            fragmentRemark.isNotBlank() -> fragmentRemark
            profileName.isNotBlank() -> profileName
            else -> "Mieru"
        }

        val serverEndpoint = activeProfile?.servers?.firstOrNull()
        val server = serverEndpoint?.domainName?.trim()?.ifBlank { null }
            ?: serverEndpoint?.ipAddress?.trim()?.ifBlank { null }
            ?: "mieru-config"

        val firstBinding = serverEndpoint?.portBindings?.firstOrNull()
        val port = firstBinding?.port?.takeIf { it in 1..65535 }
            ?: firstBinding?.portRange?.substringBefore('-')?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: 1

        val user = activeProfile?.user?.name.orEmpty()
        val password = activeProfile?.user?.password.orEmpty()

        return ProxyProfile(
            tag = tag,
            type = ProxyProfile.TYPE_MIERU,
            server = server,
            serverPort = port,
            username = user,
            password = password,
            mieruConfig = uri
        )
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun decodeFullClientConfig(base64Payload: String): ClientConfig? {
        val bytes = decodeBase64(base64Payload) ?: return null
        return runCatching { parseClientConfig(bytes) }.getOrNull()
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeBase64(raw: String): ByteArray? {
        val compact = raw.trim().filterNot { it == '\n' || it == '\r' || it == ' ' }
        if (compact.isEmpty()) return null
        val remainder = compact.length % 4
        val padded = if (remainder == 0) compact else compact + "=".repeat(4 - remainder)
        for (codec in listOf(Base64.Default, Base64.UrlSafe, Base64.Mime)) {
            runCatching { codec.decode(padded) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun parseClientConfig(bytes: ByteArray): ClientConfig {
        val reader = ProtoReader(bytes)
        var activeProfile: String? = null
        val profiles = mutableListOf<ClientProfile>()
        var socks5Port: Int? = null

        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break
            val field = tag ushr 3
            val wire = tag and 7
            when (field) {
                1 -> {
                    val len = reader.readVarint().toInt()
                    profiles.add(parseProfile(reader.subReader(len)))
                }
                2 -> {
                    val len = reader.readVarint().toInt()
                    activeProfile = reader.readString(len)
                }
                4 -> socks5Port = reader.readVarint().toInt()
                else -> reader.skipField(wire)
            }
        }
        return ClientConfig(activeProfile, profiles, socks5Port)
    }

    private fun parseProfile(reader: ProtoReader): ClientProfile {
        var profileName = ""
        var user: User? = null
        val servers = mutableListOf<ServerEndpoint>()

        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break
            val field = tag ushr 3
            val wire = tag and 7
            when (field) {
                1 -> {
                    val len = reader.readVarint().toInt()
                    profileName = reader.readString(len)
                }
                2 -> {
                    val len = reader.readVarint().toInt()
                    user = parseUser(reader.subReader(len))
                }
                3 -> {
                    val len = reader.readVarint().toInt()
                    servers.add(parseServer(reader.subReader(len)))
                }
                else -> reader.skipField(wire)
            }
        }
        return ClientProfile(profileName, user, servers)
    }

    private fun parseUser(reader: ProtoReader): User {
        var name = ""
        var password = ""
        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break
            val field = tag ushr 3
            val wire = tag and 7
            when (field) {
                1 -> {
                    val len = reader.readVarint().toInt()
                    name = reader.readString(len)
                }
                2 -> {
                    val len = reader.readVarint().toInt()
                    password = reader.readString(len)
                }
                else -> reader.skipField(wire)
            }
        }
        return User(name, password)
    }

    private fun parseServer(reader: ProtoReader): ServerEndpoint {
        var ip = ""
        var domain = ""
        val portBindings = mutableListOf<PortBinding>()
        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break
            val field = tag ushr 3
            val wire = tag and 7
            when (field) {
                1 -> {
                    val len = reader.readVarint().toInt()
                    ip = reader.readString(len)
                }
                2 -> {
                    val len = reader.readVarint().toInt()
                    domain = reader.readString(len)
                }
                3 -> {
                    val len = reader.readVarint().toInt()
                    portBindings.add(parsePortBinding(reader.subReader(len)))
                }
                else -> reader.skipField(wire)
            }
        }
        return ServerEndpoint(ip, domain, portBindings)
    }

    private fun parsePortBinding(reader: ProtoReader): PortBinding {
        var port: Int? = null
        var protocol: Int? = null
        var portRange: String? = null
        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break
            val field = tag ushr 3
            val wire = tag and 7
            when (field) {
                1 -> port = reader.readVarint().toInt()
                2 -> protocol = reader.readVarint().toInt()
                3 -> {
                    val len = reader.readVarint().toInt()
                    portRange = reader.readString(len)
                }
                else -> reader.skipField(wire)
            }
        }
        return PortBinding(port, protocol, portRange)
    }

    internal class ProtoReader(
        private val bytes: ByteArray,
        private var offset: Int = 0,
        private val limit: Int = bytes.size
    ) {
        fun hasRemaining(): Boolean = offset < limit

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (offset < limit) {
                val b = bytes[offset++].toInt()
                result = result or ((b.toLong() and 0x7FL) shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
                if (shift >= 64) break
            }
            return result
        }

        fun readTag(): Int {
            if (!hasRemaining()) return 0
            return readVarint().toInt()
        }

        fun readString(length: Int): String {
            val actualLen = length.coerceAtMost(limit - offset)
            val str = bytes.decodeToString(offset, offset + actualLen)
            offset += actualLen
            return str
        }

        fun subReader(length: Int): ProtoReader {
            val subLimit = (offset + length).coerceAtMost(limit)
            val reader = ProtoReader(bytes, offset, subLimit)
            offset = subLimit
            return reader
        }

        fun skipField(wireType: Int) {
            when (wireType) {
                0 -> readVarint()
                1 -> offset = (offset + 8).coerceAtMost(limit)
                2 -> {
                    val len = readVarint().toInt().coerceAtLeast(0)
                    offset = (offset + len).coerceAtMost(limit)
                }
                5 -> offset = (offset + 4).coerceAtMost(limit)
                else -> offset = limit
            }
        }
    }
}
