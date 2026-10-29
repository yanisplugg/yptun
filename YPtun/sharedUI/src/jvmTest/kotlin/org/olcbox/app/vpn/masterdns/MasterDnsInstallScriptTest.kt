package org.olcbox.app.vpn.masterdns

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class MasterDnsInstallScriptTest {
    @Test
    fun upstreamIsSanitisedAndPortDefaulted() {
        val s = buildInstallScript(MasterDnsInstallOptions(host = "h", domain = "v.x", dnsUpstream = "9.9.9.9, 1.1.1.1:5353; \$(rm -rf /)"))
        assertContains(s, "DNS_UPSTREAM_SERVERS = [\"9.9.9.9:53\", \"1.1.1.1:5353\"]")
        assertFalse(s.contains("rm -rf"))
        System.getProperty("mdns.dump")?.let { java.io.File(it).writeText(s) }
    }

    @Test
    fun compressionOffAndPortCheck() {
        val s = buildInstallScript(MasterDnsInstallOptions(host = "h", domain = "v.x", allowCompression = false))
        assertContains(s, "SUPPORTED_UPLOAD_COMPRESSION_TYPES = [0]")
        assertContains(s, "systemctl restart masterdns-server")
        assertContains(s, "sport = :5300")
    }

    @Test
    fun freeingPort53KeepsSystemdResolvedRunning() {
        val s = buildInstallScript(MasterDnsInstallOptions(host = "h", domain = "v.x", udpPort = 53, freePort = true))
        assertContains(s, "DNSStubListener=no")
        assertContains(s, "systemctl restart systemd-resolved")
        assertFalse(s.contains("disable --now systemd-resolved"))
        // `YPTUN_DUMP_MASTERDNS_SCRIPT=<file>` writes the exact script, to rehearse it on a VPS.
        System.getenv("YPTUN_DUMP_MASTERDNS_SCRIPT")?.takeIf { it.isNotBlank() }?.let { java.io.File(it).writeText(s) }
    }
}
