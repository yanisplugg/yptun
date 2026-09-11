package org.olcbox.app.vpn.wdtt

/**
 * The server's "RAWCONF:ip|dns,dns|mtu" for qWDTT «Raw напрямую»: the address the TUN must carry (the
 * server routes replies to it), its DNS servers and MTU. Mirrors the core's parseRawConf fallbacks.
 */
data class WdttRawTun(val ip: String, val dns: List<String>, val mtu: Int) {
    companion object {
        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        /** Null unless the address is a plain IPv4 — VpnService.Builder would throw on anything else. */
        fun parse(conf: String): WdttRawTun? {
            val parts = conf.removePrefix("RAWCONF:").split('|')
            if (parts.size != 3) return null
            val ip = parts[0].trim().takeIf { IPV4.matches(it) } ?: return null
            val dns = parts[1].split(',').map { it.trim() }.filter { IPV4.matches(it) }.ifEmpty { listOf("1.1.1.1") }
            val mtu = parts[2].trim().toIntOrNull()?.takeIf { it >= 576 } ?: 1280
            return WdttRawTun(ip, dns, mtu)
        }
    }
}
