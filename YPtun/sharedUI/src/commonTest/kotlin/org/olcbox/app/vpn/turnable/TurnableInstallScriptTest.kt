package org.olcbox.app.vpn.turnable

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TurnableInstallScriptTest {
    private val options = TurnableInstallOptions(
        host = "203.0.113.7",
        sshPort = 2222,
        sshPassword = "secret",
        turnablePort = 56123,
        callId = "123456789",
        peers = 7,
    )

    @Test
    fun installCreatesPortScopedTurnableAndWireguardServices() {
        val script = buildTurnableInstallScript(options)
        assertTrue("svc=turnable-${'$'}port" in script)
        assertTrue("iface=trwg${'$'}port" in script)
        assertTrue("\"call_id\":\"123456789\"" in script)
        assertTrue("\"listen_addr\":\"0.0.0.0:56123\"" in script)
        assertTrue("\"peers\":7" in script)
        assertTrue("\"route_id\"" !in script) // server route schema uses id; generated client uses route_id
        assertTrue("turnable config generate main" in script)
        assertTrue("RESULT::${'$'}client64|${'$'}spub|${'$'}cpriv|" in script)
    }

    @Test
    fun untrustedCallIdNeverReachesShellOrJson() {
        assertFailsWith<IllegalArgumentException> {
            buildTurnableInstallScript(options.copy(callId = "x\nEOF\nrm -rf /"))
        }
        assertFailsWith<IllegalArgumentException> {
            buildTurnableInstallScript(options.copy(host = "host; reboot"))
        }
    }

    @Test
    fun uninstallOnlyRemovesTheRequestedPortAndKeepsSharedBinaryWhenNeeded() {
        val script = buildTurnableUninstallScript(56123)
        assertTrue("svc=turnable-${'$'}port" in script)
        assertTrue("rm -f /etc/wireguard/${'$'}iface.conf" in script)
        assertTrue("if ! ls /etc/systemd/system/turnable-*.service" in script)
        assertTrue("REMOVED::${'$'}port" in script)
    }
}
