package org.olcbox.app.vpn.wdtt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WdttInstallScriptTest {

    private val script = buildInstallScript(
        WdttInstallOptions(host = "203.0.113.7", wdttPort = 56000, wdttPassword = "pa'ss", dns = "1.1.1.1")
    )

    @Test
    fun runsOnTheResolvedPortsAndChecksTheServiceStaysUp() {
        assertTrue("PORT=56000" in script)
        assertTrue("-listen 0.0.0.0:\$PORT -wg-port \$WGPORT" in script)
        assertTrue("-password 'pa'\\''ss'" in script, "password must stay shell-quoted")
        assertTrue("NRestarts" in script && "journalctl -u wdtt" in script)
        assertTrue("echo \"WDTT_PORT=\$PORT\"" in script)
        // The old WDTT is looked for on the requested port and on the server's default WG port.
        assertTrue("for P in 56000 56001; do" in script)
        // The heredoc terminator must land at column 0 or the unit file swallows the rest.
        assertTrue(script.lines().any { it == "UNIT" })
    }

    @Test
    fun scriptIsValidBash() {
        val bash = listOf("bash", "C:/Program Files/Git/bin/bash.exe").firstOrNull { runCatching {
            ProcessBuilder(it, "--version").start().waitFor() == 0
        }.getOrDefault(false) } ?: return // no bash on this machine: nothing to check
        // Via stdin: a Windows path means nothing to WSL's bash.
        val process = ProcessBuilder(bash, "-n").redirectErrorStream(true).start()
        process.outputStream.use { it.write(script.toByteArray()) }
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
    }

    @Test
    fun readsTheReportedPort() {
        assertEquals(56002, installedPort("Версия сервера: 17\nWDTT_PORT=56002\nСлужба активна"))
        assertNull(installedPort("Версия сервера: 17"))
    }
}
