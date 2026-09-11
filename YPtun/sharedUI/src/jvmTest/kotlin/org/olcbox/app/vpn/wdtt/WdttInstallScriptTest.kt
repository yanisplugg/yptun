package org.olcbox.app.vpn.wdtt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WdttInstallScriptTest {

    private val options = WdttInstallOptions(host = "203.0.113.7", sshPort = 2222, wdttPort = 56000, wdttPassword = "pa'ss", dns = "1.1.1.1, 8.8.8.8")
    private val ports = WdttPorts(dtls = 56003, wg = 56001, admin = 56002, raw = 56004)

    @Test
    fun prepareLooksForOldWdttAndPicksFreePorts() {
        val script = buildPrepareScript(56000)
        assertTrue("for P in 56000 56001; do" in script)
        assertTrue("\"main_password\"" in script, "a WDTT Plus database must be set aside")
        assertTrue("echo \"WDTT_PORTS=\$DTLS|\$WG|\$ADMIN|\$RAW\"" in script)
        assertTrue("RAW=56003" in script, "Raw is always enabled, on qWDTT's default port")
    }

    @Test
    fun deployRunsTheQwdttInstallerWithPortsAndSecrets() {
        val command = buildDeployCommand(options, ports, adminToken = "abc")
        assertTrue("WDTT_DTLS_PORT=56003 WDTT_WG_PORT=56001 WDTT_ADMIN_PORT=56002 WDTT_RAW_PORT=56004 WDTT_SSH_PORT=2222 bash /tmp/deploy.sh" in command)
        assertTrue("WDTT_DNS_SERVERS='1.1.1.1,8.8.8.8'" in command)
        // Secrets go through base64, so no password can break the shell quoting.
        assertFalse("pa'ss" in command)
        val b64 = java.util.Base64.getEncoder().encodeToString("pa'ss".toByteArray())
        assertTrue("printf '%s' '$b64' | base64 -d > /tmp/wdtt-main.password" in command)
        assertTrue("echo \"WDTT_DEPLOY_EXIT=\$?\"" in command)
    }

    @Test
    fun scriptsAreValidBash() {
        val bash = listOf("bash", "C:/Program Files/Git/bin/bash.exe").firstOrNull { runCatching {
            ProcessBuilder(it, "--version").start().waitFor() == 0
        }.getOrDefault(false) } ?: return // no bash on this machine: nothing to check
        for (script in listOf(buildPrepareScript(56000), buildDeployCommand(options, ports, "t"), buildVerifyScript())) {
            // Via stdin: a Windows path means nothing to WSL's bash.
            val process = ProcessBuilder(bash, "-n").redirectErrorStream(true).start()
            process.outputStream.use { it.write(script.toByteArray()) }
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), output + "\n---\n" + script)
        }
    }

    /**
     * `YPTUN_DUMP_WDTT_SCRIPTS=<dir>` writes the exact scripts the installer sends, to run them on a
     * throwaway Linux box (a VPS rehearsal without the app); a no-op otherwise.
     */
    @Test
    fun dumpScriptsForARehearsal() {
        val dir = System.getenv("YPTUN_DUMP_WDTT_SCRIPTS")?.takeIf { it.isNotBlank() } ?: return
        val out = java.io.File(dir).apply { mkdirs() }
        out.resolve("prepare.sh").writeText(buildPrepareScript(56000))
        out.resolve("deploy-cmd.sh").writeText(
            buildDeployCommand(options.copy(sshPort = 2222), WdttPorts(56000, 56001, 56002, 56003), adminToken = "rehearsal")
        )
        out.resolve("verify.sh").writeText(buildVerifyScript())
    }

    @Test
    fun readsPortsAndTheInstallerResult() {
        assertEquals(WdttPorts(56003, 56001, 56002, 56004), parsePorts("Порт 56000/udp занят\nWDTT_PORTS=56003|56001|56002|56004\n"))
        assertNull(parsePorts("WDTT_PORTS=56003|x|56002|56004"))
        assertNull(parsePorts("WDTT_PORTS=56003|56001|56002"))

        val ok = readDeployOutput(
            "\u001B[0;32m[✓]\u001B[0m Готово\nWDTT_PROGRESS|0.05|Очистка...\nGet:1 http://deb.debian.org stable InRelease\n" +
                "WDTT_ADMIN_PIN|sha256/abc=\nWDTT_DEPLOY_OK\nWDTT_DEPLOY_EXIT=0\n"
        )
        assertTrue(ok.ok)
        assertEquals(listOf("[✓] Готово", "• Очистка..."), ok.lines)

        // The success marker alone is not enough when the script itself failed.
        assertFalse(readDeployOutput("WDTT_DEPLOY_OK\nWDTT_DEPLOY_EXIT=1").ok)
        assertFalse(readDeployOutput("WDTT_DEPLOY_SERVICE_FAILED\nWDTT_DEPLOY_EXIT=0").ok)
    }
}
