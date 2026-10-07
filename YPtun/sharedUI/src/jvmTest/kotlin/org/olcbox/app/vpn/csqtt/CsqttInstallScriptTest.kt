package org.olcbox.app.vpn.csqtt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CsqttInstallScriptTest {

    private val options = CsqttInstallOptions(host = "203.0.113.7", sshPort = 2222, password = "pa'ss\"w")
    private val ports = CsqttPorts(peer = 46001, web = 46003)

    @Test
    fun deployRunsTheCsqttInstallerWithPortsAndSecrets() {
        val command = buildDeployCommand(options, ports, "admin", "web\\pass", sshPort = 2222)
        assertTrue(
            "CSQTT_PEER_PORT=46001 CSQTT_SSH_PORT=2222 CSQTT_WEB_PORT=46003 CSQTT_DEPLOY_MODE=systemd bash /tmp/deploy.sh install" in command
        )
        // Secrets go through base64, so no password can break the shell quoting.
        assertFalse("pa'ss" in command)
        val b64 = java.util.Base64.getEncoder()
        val overrides = b64.encodeToString("""{"main_password":"pa'ss\"w","device_id":""}""".toByteArray())
        assertTrue("printf '%s' '$overrides' | base64 -d > /tmp/.csqtt-upload-overrides.json" in command)
        val env = b64.encodeToString("CSQTT_WEB_USER=\"admin\"\nCSQTT_WEB_PASS=\"web\\\\pass\"\n".toByteArray())
        assertTrue("printf '%s' '$env' | base64 -d > /tmp/.csqtt-upload-web.env" in command)
        assertTrue("echo \"CSQTT_DEPLOY_EXIT=\$?\"" in command)
    }

    @Test
    fun preparePicksFreePortsButKeepsOwnCsqtt() {
        val script = buildPrepareScript(46000, 46002)
        assertTrue("PEER=46000" in script && "WEB=46002" in script)
        assertTrue("!= csqtt" in script, "a redeploy must be allowed to take over its own ports")
        assertTrue("echo \"CSQTT_PORTS=\$PEER|\$WEB\"" in script)
    }

    @Test
    fun scriptsAreValidBash() {
        val bash = listOf("bash", "C:/Program Files/Git/bin/bash.exe").firstOrNull { runCatching {
            ProcessBuilder(it, "--version").start().waitFor() == 0
        }.getOrDefault(false) } ?: return // no bash on this machine: nothing to check
        val scripts = listOf(
            buildPrepareScript(46000, 46002),
            buildDeployCommand(options, ports, "admin", "x", 22),
            buildVerifyScript(),
            asRoot(buildVerifyScript()),
        )
        for (script in scripts) {
            // Via stdin: a Windows path means nothing to WSL's bash.
            val process = ProcessBuilder(bash, "-n").redirectErrorStream(true).start()
            process.outputStream.use { it.write(script.toByteArray()) }
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), output + "\n---\n" + script)
        }
    }

    @Test
    fun readsPortsAndArchitecture() {
        assertEquals(CsqttPorts(46001, 46003), parsePorts("Порт 46000/udp занят\nCSQTT_PORTS=46001|46003\n"))
        assertNull(parsePorts("CSQTT_PORTS=46001|x"))
        assertNull(parsePorts("CSQTT_PORTS=46001"))
        assertEquals("amd64", serverArch("x86_64"))
        assertEquals("arm64", serverArch("aarch64"))
        assertEquals("armv7", serverArch("armv7l"))
        assertNull(serverArch("riscv64"))
    }

    @Test
    fun readsTheInstallerResult() {
        val ok = readDeployOutput(
            "\u001B[0;32m[✓]\u001B[0m Готово\nCSQTT_PROGRESS|0.05|Очистка...\nGet:1 http://deb.debian.org stable InRelease\n" +
                "CSQTT_DEPLOY_OK\nCSQTT_DEPLOY_EXIT=0\n"
        )
        assertTrue(ok.ok)
        assertEquals(listOf("[✓] Готово", "• Очистка..."), ok.lines)

        // The success marker alone is not enough when the script itself failed.
        assertFalse(readDeployOutput("CSQTT_DEPLOY_OK\nCSQTT_DEPLOY_EXIT=1").ok)
        val failed = readDeployOutput("CSQTT_DEPLOY_ERROR|preflight|порт занят\nCSQTT_DEPLOY_EXIT=20")
        assertFalse(failed.ok)
        assertEquals(listOf("ОШИБКА (preflight): порт занят"), failed.lines)
    }
}
