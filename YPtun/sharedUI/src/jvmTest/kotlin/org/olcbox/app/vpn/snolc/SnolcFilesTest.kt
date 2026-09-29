package org.olcbox.app.vpn.snolc

import org.olcbox.app.data.model.SnolcConfig
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SnolcFilesTest {
    private val key = "ab".repeat(32)

    @Test
    fun uriRoundTrips() {
        val c = SnolcConfig(host = "203.0.113.7", port = 8443, publicKey = key)
        val (parsed, name) = assertNotNull(SnolcConfig.parseUri(c.toUri("My node")))
        assertEquals(c, parsed)
        assertEquals("My node", name)
        assertNotNull(SnolcConfig.parseUri("snolc://[2001:db8::1]:443?key=$key"))
    }

    @Test
    fun installScriptKeepsKeysAndPrintsPublicKey() {
        val script = buildSnolcInstallScript(8443)
        File("build/snolc-install.sh").writeText(script)
        assertTrue("if [ ! -s /etc/snolc/priv.hex ]" in script)
        assertTrue("<<'SNOLCEOF'" in script && "<<'UNIT'" in script)
        assertTrue("echo \"$KEY_MARK" in script)
    }

    /** The generated files must satisfy the real engine (skipped when the prebuilt Windows binary is absent). */
    @Test
    fun generatedConfigsValidateWithTheRealEngine() {
        val exe = File("../../snolc/prebuilt/snolc-windows-amd64.exe").takeIf { it.isFile } ?: return
        val dir = Files.createTempDirectory("snolc-test").toFile()
        fun write(root: File, files: Map<String, String>) = files.forEach { (n, b) ->
            File(root, n).apply { parentFile.mkdirs() }.writeText(b)
        }
        val srv = File(dir, "srv"); val cli = File(dir, "cli")
        File(srv, "priv.hex").apply { parentFile.mkdirs(); writeText(key) }
        val bs = 92.toChar()
        write(srv, SnolcFiles.server(443, File(srv, "priv.hex").absolutePath.replace(bs, '/'), File(srv, "state").absolutePath.replace(bs, '/')))
        write(cli, SnolcFiles.client("127.0.0.1", 1080, "203.0.113.7", 443, false, "u\"1", "p${bs}1"))
        File(cli, "pub.hex").writeText(key)
        for (root in listOf(srv, cli)) {
            val p = ProcessBuilder(exe.absolutePath, "validate", File(root, "snolc.toml").absolutePath).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertEquals(0, p.waitFor(), out)
        }
    }
}
