package org.olcbox.app.vpn.openflux

import org.olcbox.app.data.model.OpenFluxConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenFluxInstallScriptTest {
    // A real Yandex Docs link is full of %XX (systemd unit specifiers) and may carry & and $.
    private val url = "https://docs.yandex.ru/docs/view?url=ya-disk-public%3A%2F%2Fabc&name=a\"b\$c"

    @Test
    fun carrierSecretsLiveInTheEnvironmentFileNotOnTheCommandLine() {
        val script = buildOpenFluxInstallScript(
            OpenFluxInstallOptions(host = "203.0.113.7", sshPassword = "x", docUrl = url)
        )
        val execStart = script.lines().single { it.startsWith("ExecStart=") }
        assertFalse("%3A" in execStart, execStart)
        assertTrue(execStart.endsWith("--transport \${OPENFLUX_TRANSPORT} --url \${OPENFLUX_DOC_URL}"), execStart)
        // Double-quoted, with the quote escaped; the quoted heredoc keeps $ literal.
        assertTrue(script.lines().any { it == "OPENFLUX_DOC_URL=\"${url.replace("\"", "\\\"")}\"" }, script)
        assertTrue("<<'ENVFILE'" in script && "<<'UNIT'" in script)
    }

    @Test
    fun rstDropRuleIsTiedToTheService() {
        val script = buildOpenFluxInstallScript(
            OpenFluxInstallOptions(
                host = "203.0.113.7", sshPassword = "x",
                transport = OpenFluxConfig.TRANSPORT_MAX, exitMaxToken = "tok",
            )
        )
        assertTrue(script.lines().any { it.startsWith("ExecStartPre=") && "iptables -A OUTPUT -p tcp --tcp-flags RST RST -j DROP" in it })
        assertTrue(script.lines().any { it.startsWith("ExecStopPost=") && "iptables -D OUTPUT -p tcp --tcp-flags RST RST -j DROP" in it })
        assertTrue(script.lines().any { it == "OPENFLUX_TRANSPORT=\"oneme\"" })
        assertTrue(script.lines().any { it == "OPENFLUX_MAX_TOKEN=\"tok\"" })
        val execStart = script.lines().single { it.startsWith("ExecStart=") }
        assertTrue(execStart.endsWith("--transport \${OPENFLUX_TRANSPORT}"), execStart)
        assertFalse("--url" in execStart, "MAX transport should not have --url on ExecStart: $execStart")
    }
}
