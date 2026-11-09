package org.olcbox.app.vpn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.importer.ShareLinkParser
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.data.model.RoutingProfile
import org.olcbox.app.data.model.RoutingRules
import org.olcbox.app.data.model.TrafficSettings
import org.olcbox.app.vpn.singbox.SingBoxConfig
import org.olcbox.app.vpn.xray.XrayConfig
import java.io.File
import kotlin.test.Test

/**
 * Exports the reference configs for the Rust core's parity test (core/yptun-core/tests/config_parity.rs):
 * every case of core/yptun-core/tests/parity/cases.json is built here and written to parity/expected/.
 * Runs only with YPTUN_PARITY_EXPORT=1 so ordinary test runs never touch the Rust tree (--rerun because
 * Gradle does not track cases.json as a test input):
 *   YPTUN_PARITY_EXPORT=1 ./gradlew :sharedUI:jvmTest --tests '*ConfigParityExport*' --rerun
 */
class ConfigParityExport {
    private val json = Json { ignoreUnknownKeys = true }
    private val pretty = Json { prettyPrint = true }

    @Test
    fun export() {
        if (System.getenv("YPTUN_PARITY_EXPORT") != "1") return
        val dir = File(System.getenv("YPTUN_PARITY_DIR") ?: "../../core/yptun-core/tests/parity")
        val root = json.parseToJsonElement(dir.resolve("cases.json").readText()).jsonObject
        val links = root.getValue("links").jsonObject
        val out = dir.resolve("expected").apply { deleteRecursively(); mkdirs() }

        fun profile(ref: JsonElement?): ProxyProfile? = when {
            ref == null -> null
            ref is JsonPrimitive -> {
                val link = links[ref.content]?.jsonPrimitive?.content ?: ref.content
                requireNotNull(ShareLinkParser.parse(link)) { "unparsable link ${ref.content}" }
            }
            else -> json.decodeFromJsonElement(ProxyProfile.serializer(), ref)
        }

        for (case in root.getValue("cases").jsonArray.map { it.jsonObject }) {
            val name = case.getValue("name").jsonPrimitive.content
            val p = profile(case["profile"])!!
            val only = case["only"]?.jsonPrimitive?.contentOrNull
            val common = case["opts"]?.jsonObject ?: JsonObject(emptyMap())
            for (target in listOf("xray", "singbox")) {
                if (only != null && only != target) continue
                val o = Opts(JsonObject(common + (case[target]?.jsonObject ?: emptyMap())), ::profile)
                val text = runCatching {
                    val config = if (target == "xray") xray(p, o) else singbox(p, o)
                    pretty.encodeToString(JsonElement.serializer(), json.parseToJsonElement(config))
                }.getOrElse { pretty.encodeToString(JsonElement.serializer(), JsonObject(mapOf("error" to JsonPrimitive(it.message ?: it.toString())))) }
                out.resolve("$name.$target.json").writeText(text + "\n")
            }
        }
    }

    private inner class Opts(val o: JsonObject, val profile: (JsonElement?) -> ProxyProfile?) {
        fun str(k: String, d: String) = o[k]?.jsonPrimitive?.contentOrNull ?: d
        fun strOrNull(k: String) = o[k]?.jsonPrimitive?.contentOrNull
        fun int(k: String) = o[k]?.jsonPrimitive?.intOrNull
        fun bool(k: String, d: Boolean) = o[k]?.jsonPrimitive?.booleanOrNull ?: d
        fun list(k: String) = o[k]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val traffic get() = o["traffic"]?.let { json.decodeFromJsonElement(TrafficSettings.serializer(), it) } ?: TrafficSettings()
        val routingProfile: RoutingProfile?
            get() = when (strOrNull("routingPreset")) {
                "russiaDirect" -> RoutingProfile.russiaDirect()
                "chinaDirect" -> RoutingProfile.chinaDirect()
                else -> o["routingProfile"]?.let { json.decodeFromJsonElement(RoutingProfile.serializer(), it) }
            }
    }

    private fun xray(p: ProxyProfile, o: Opts): String = XrayConfig.build(
        profile = p,
        listenPort = o.int("listenPort") ?: 10808,
        listenHost = o.str("listenHost", "127.0.0.1"),
        socksUsername = o.str("socksUsername", ""),
        socksPassword = o.str("socksPassword", ""),
        olcrtcChainPort = o.int("olcrtcChainPort"),
        olcrtcChainUser = o.str("olcrtcChainUser", ""),
        olcrtcChainPass = o.str("olcrtcChainPass", ""),
        logLevel = o.str("logLevel", "debug"),
        traffic = o.traffic,
        wireguardBase = o.profile(o.o["wireguardBase"]),
        routingProfile = o.routingProfile,
        blockQuic = o.bool("blockQuic", true),
        forceFamilyResolve = o.bool("forceFamilyResolve", true),
        chainViaDialerProxy = o.bool("chainViaDialerProxy", false),
        directViaBase = o.bool("directViaBase", false),
        bypassLan = o.bool("bypassLan", true),
        secondProfile = o.profile(o.o["secondProfile"]),
        keepFlowOnChain = o.bool("keepFlowOnChain", false),
        handshakeTimeoutSec = o.int("handshakeTimeoutSec"),
    )

    private fun singbox(p: ProxyProfile, o: Opts): String = SingBoxConfig.build(
        profile = p,
        listenPort = o.int("listenPort") ?: 10808,
        listenHost = o.str("listenHost", "127.0.0.1"),
        socksUsername = o.str("socksUsername", ""),
        socksPassword = o.str("socksPassword", ""),
        olcrtcChainPort = o.int("olcrtcChainPort"),
        olcrtcChainUser = o.str("olcrtcChainUser", ""),
        olcrtcChainPass = o.str("olcrtcChainPass", ""),
        logLevel = o.str("logLevel", "debug"),
        autoDetectInterface = o.bool("autoDetectInterface", false),
        routing = o.o["routing"]?.let { json.decodeFromJsonElement(RoutingRules.serializer(), it) }
            ?: RoutingRules(bypassLan = o.bool("bypassLan", true)),
        traffic = o.traffic,
        wireguardBase = o.profile(o.o["wireguardBase"]),
        dnsStrategyOverride = o.strOrNull("dnsStrategyOverride"),
        routingProfile = o.routingProfile,
        singboxGeositeBase = o.str("singboxGeositeBase", ""),
        singboxGeoipBase = o.str("singboxGeoipBase", ""),
        blockQuic = o.bool("blockQuic", true),
        forceFamilyResolve = o.bool("forceFamilyResolve", true),
        allowLocalResolve = o.bool("allowLocalResolve", true),
        directViaBase = o.bool("directViaBase", false),
        sniffOverrideDestination = o.bool("sniffOverrideDestination", false),
        secondProfile = o.profile(o.o["secondProfile"]),
        tunMode = o.bool("tunMode", false),
        splitTunnelMode = o.str("splitTunnelMode", SingBoxConfig.SPLIT_TUNNEL_ALL),
        splitTunnelProcesses = o.list("splitTunnelProcesses"),
        tunExcludeAddresses = o.list("tunExcludeAddresses"),
        hijackDns = o.bool("hijackDns", false),
        logFilePath = o.strOrNull("logFilePath"),
        remoteDnsOverHttps = o.bool("remoteDnsOverHttps", false),
        forceFakeDns = o.bool("forceFakeDns", false),
        mixedInbound = o.bool("mixedInbound", false),
        matchAppsByProcess = o.bool("matchAppsByProcess", false),
        tproxyPort = o.int("tproxyPort"),
        preferTcpRemoteDns = o.bool("preferTcpRemoteDns", false),
        cacheFilePath = o.strOrNull("cacheFilePath"),
    )
}
