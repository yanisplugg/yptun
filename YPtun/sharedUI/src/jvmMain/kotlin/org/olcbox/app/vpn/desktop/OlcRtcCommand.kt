package org.olcbox.app.vpn.desktop

import org.olcbox.app.data.model.LocationConfig
import java.nio.file.Path

internal data class OlcRtcCommand(
    val binary: Path,
    val location: LocationConfig,
    val socksHost: String = PacServer.LOCAL_SOCKS_HOST,
    val socksPort: Int = PacServer.LOCAL_SOCKS_PORT,
    val socksUser: String = "",
    val socksPass: String = "",
    val dataDir: Path? = null
) {
    fun args(configPath: Path): List<String> {
        return listOf(binary.toString(), configPath.toString())
    }

    fun yaml(): String {
        val config = location.normalized()
        val provider = desktopProviderArg(config.bypassProvider)

        return buildString {
            appendLine("mode: cnc")
            appendLine("link: direct")
            appendLine("auth:")
            appendLine("  provider: ${provider.yamlValue()}")
            appendLine("room:")
            appendLine("  id: ${config.id.yamlValue()}")
            appendLine("crypto:")
            appendLine("  key: ${config.key.yamlValue()}")
            appendLine("net:")
            appendLine("  transport: ${config.transport.yamlValue()}")
            appendLine("  dns: ${DNS_SERVERS.yamlValue()}")
            appendLine("socks:")
            appendLine("  host: ${socksHost.yamlValue()}")
            appendLine("  port: $socksPort")
            if (socksUser.isNotBlank()) {
                appendLine("  user: ${socksUser.yamlValue()}")
                appendLine("  pass: ${socksPass.yamlValue()}")
            }
            when (config.transport) {
                LocationConfig.TRANSPORT_VP8CHANNEL -> {
                    appendLine("vp8:")
                    appendLine("  fps: ${config.vp8Fps}")
                    appendLine("  batch_size: ${config.vp8Batch}")
                }
                // The location's own parameters (from its olcrtc:// link / subscription, docs/uri.md).
                LocationConfig.TRANSPORT_SEICHANNEL -> config.seiOptions().let {
                    appendLine("sei:")
                    appendLine("  fps: ${it.fps}")
                    appendLine("  batch_size: ${it.batch}")
                    appendLine("  fragment_size: ${it.fragmentSize}")
                    appendLine("  ack_timeout_ms: ${it.ackTimeoutMs}")
                }
                LocationConfig.TRANSPORT_VIDEOCHANNEL -> config.videoOptions().let {
                    appendLine("video:")
                    appendLine("  codec: ${it.codec}")
                    appendLine("  width: ${it.width}")
                    appendLine("  height: ${it.height}")
                    appendLine("  fps: ${it.fps}")
                    appendLine("  qr_size: ${it.qrSize}")
                    appendLine("  qr_recovery: ${it.qrRecovery}")
                    appendLine("  tile_module: ${it.tileModule}")
                    appendLine("  tile_rs: ${it.tileRs}")
                }
            }
            appendLine("data: ${(dataDir?.toString() ?: "data").yamlValue()}")
        }
    }

    companion object {
        /**
         * Preference-ordered resolver list for olcRTC signalling, mirroring Android's
         * FALLBACK_OLCRTC_DNS_SERVERS. olcrtc probes these in order with a real query
         * (protect.NewResolver/pickReachableDNS) and sticks to the first that answers; Yandex goes
         * first because Cloudflare/Google UDP/53 are routinely blocked on RU networks. A single
         * pinned resolver (this was "1.1.1.1:53") leaves the provider hostname unresolvable with no
         * fallback at all.
         */
        const val DNS_SERVERS = "77.88.8.8:53,8.8.8.8:53,1.1.1.1:53"

        fun desktopProviderArg(provider: String): String {
            val normalizedProvider = LocationConfig.normalizeProvider(provider)
            return when (normalizedProvider) {
                LocationConfig.PROVIDER_WB_STREAM -> "wbstream"
                else -> normalizedProvider
            }
        }
    }
}

private fun String.yamlValue(): String {
    return "'${replace("'", "''")}'"
}
