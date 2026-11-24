package org.olcbox.app.vpn.openflux

import org.olcbox.app.data.model.OpenFluxCarrier
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
    fun l4ExitReplacesOldInstallAndDropsRstRule() {
        val script = buildOpenFluxInstallScript(
            OpenFluxInstallOptions(
                host = "203.0.113.7", sshPassword = "x",
                transport = OpenFluxConfig.TRANSPORT_MAX, exitMaxToken = "tok",
            )
        )
        // l4 exit: no host-wide RST drop any more; a leftover rule from an old install is removed.
        assertFalse(script.lines().any { it.startsWith("ExecStartPre=") })
        assertTrue("while iptables -D OUTPUT -p tcp --tcp-flags RST RST -j DROP" in script)
        assertTrue("systemctl disable --now openflux" in script)
        assertTrue("--mode=l4" in script)
        assertTrue(script.lines().any { it == "OPENFLUX_TRANSPORT=\"oneme\"" })
        assertTrue(script.lines().any { it == "OPENFLUX_MAX_TOKEN=\"tok\"" })
        val execStart = script.lines().single { it.startsWith("ExecStart=") }
        assertTrue(execStart.endsWith("--transport \${OPENFLUX_TRANSPORT}"), execStart)
        assertFalse("--url" in execStart, "MAX transport should not have --url on ExecStart: $execStart")
    }

    /** The node must run the SAME transport as the client — the new-editor one used to collapse to "yandex". */
    @Test
    fun newEditorTransportReachesTheNodeWithItsDocument() {
        val script = buildOpenFluxInstallScript(
            OpenFluxInstallOptions(
                host = "203.0.113.7", sshPassword = "x",
                transport = OpenFluxConfig.TRANSPORT_VYANDEX, docUrl = url,
            )
        )
        assertTrue(script.lines().any { it == "OPENFLUX_TRANSPORT=\"vyandex\"" }, script)
        val execStart = script.lines().single { it.startsWith("ExecStart=") }
        assertTrue(execStart.endsWith("--transport \${OPENFLUX_TRANSPORT} --url \${OPENFLUX_DOC_URL}"), execStart)
        assertTrue(OpenFluxConfig(transport = "vyandex").normalized().transport == "vyandex")
        assertTrue(OpenFluxConfig(transport = "bogus").normalized().transport == OpenFluxConfig.TRANSPORT_YANDEX)
    }

    @Test
    fun multiRoomLinksNormalizeIntoCommaSeparatedGroupUrl() {
        val multi = "https://telemost.yandex.ru/j/1111111111\nhttps://telemost.yandex.ru/j/2222222222"
        val cfg = OpenFluxConfig(transport = OpenFluxConfig.TRANSPORT_TELEMOST, docUrl = multi).normalized()
        assertTrue(cfg.docUrl == "https://telemost.yandex.ru/j/1111111111,https://telemost.yandex.ru/j/2222222222")
        assertTrue(cfg.isComplete())

        val script = buildOpenFluxInstallScript(
            OpenFluxInstallOptions(
                host = "203.0.113.7", sshPassword = "x",
                transport = OpenFluxConfig.TRANSPORT_TELEMOST, docUrl = multi,
            )
        )
        assertTrue(
            script.lines().any { it == "OPENFLUX_DOC_URL=\"https://telemost.yandex.ru/j/1111111111,https://telemost.yandex.ru/j/2222222222\"" },
            script
        )
        val execStart = script.lines().single { it.startsWith("ExecStart=") }
        assertTrue(execStart.endsWith("--transport \${OPENFLUX_TRANSPORT} --url \${OPENFLUX_DOC_URL}"), execStart)
    }

    @Test
    fun cupsOnlineAutoCreatesRequestedRoomsAndPersistsThem() {
        val script = buildOpenFluxInstallScript(
            OpenFluxInstallOptions(
                host = "203.0.113.7", sshPassword = "x",
                transport = OpenFluxConfig.TRANSPORT_CUPS, docUrl = "", cupsRoomsCount = 12,
            )
        )
        assertTrue(script.lines().any { it == "CUPS_ROOMS=\"12\"" }, script)
        val execStart = script.lines().single { it.startsWith("ExecStart=") }
        assertFalse("--url" in execStart, execStart)
        assertTrue("RESULT_ROOMS::" in script, script)
        assertTrue("=== COPY THIS TO CLIENT ===" in script, script)
        assertTrue("systemctl daemon-reload" in script, script)
    }

    @Test
    fun multiTransportSessionWritesKeyFileAndCarrierFlags() {
        val script = buildOpenFluxInstallScript(
            OpenFluxInstallOptions(
                host = "203.0.113.7", sshPassword = "x",
                cupsRoomsCount = 6,
                secret = "0123456789abcdef0123456789abcdef",
                negotiate = true,
                carriers = listOf(
                    OpenFluxCarrier(type = OpenFluxConfig.TRANSPORT_DIRECT, dial = "203.0.113.7:9445", priority = 100),
                    OpenFluxCarrier(type = OpenFluxConfig.TRANSPORT_MAILRU, url = "https://cloud.mail.ru/public/a/b\nhttps://cloud.mail.ru/public/c/d", priority = 80),
                    OpenFluxCarrier(type = OpenFluxConfig.TRANSPORT_CUPS, url = "", priority = 60),
                ),
            )
        )
        assertTrue("cat > /etc/openflux/openflux.key <<'KEYFILE'" in script, script)
        assertTrue(script.lines().any { it == "0123456789abcdef0123456789abcdef" }, script)
        assertTrue(script.lines().any { it == "OPENFLUX_TRANSPORTS=\"direct:100,mailru:80,cupsonline:60\"" }, script)
        assertTrue(script.lines().any { it == "OPENFLUX_DIRECT_LISTEN=\"0.0.0.0:9445\"" }, script)
        assertTrue(script.lines().any { it == "OPENFLUX_MAILRU_URL=\"https://cloud.mail.ru/public/a/b,https://cloud.mail.ru/public/c/d\"" }, script)
        assertTrue(script.lines().any { it == "CUPS_ROOMS=\"6\"" }, script)
        assertTrue("ufw allow 9445/tcp" in script, script)
        val execStart = script.lines().single { it.startsWith("ExecStart=") }
        assertTrue("--transports \${OPENFLUX_TRANSPORTS}" in execStart, execStart)
        assertTrue("--direct-listen \${OPENFLUX_DIRECT_LISTEN}" in execStart, execStart)
        assertTrue("--mailru-url \${OPENFLUX_MAILRU_URL}" in execStart, execStart)
        assertTrue("--encryption-key-file /etc/openflux/openflux.key" in execStart, execStart)
        assertTrue("--negotiate" in execStart, execStart)
        assertTrue("OPENFLUX_CUPSONLINE_URL" in script, script)
    }
}
