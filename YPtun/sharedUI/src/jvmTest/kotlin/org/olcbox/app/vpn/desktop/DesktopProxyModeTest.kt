package org.olcbox.app.vpn.desktop

import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.vpn.olcRtcNativeLibrarySpec
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopProxyModeTest {

    @Test
    fun pacRoutesLocalTrafficDirectAndEverythingElseThroughSocks() {
        val pac = PacServer.generatePac("127.0.0.1", 10808)

        assertContains(pac, "isPlainHostName(host)")
        assertContains(pac, "host == \"localhost\"")
        assertContains(pac, "SOCKS5 127.0.0.1:10808; SOCKS 127.0.0.1:10808")
    }

    @Test
    fun pacServerUpdatesSocksTargetWhileAlreadyRunning() {
        val server = PacServer(port = 0)

        server.start("127.0.0.1", 10808)
        server.start("127.0.0.1", 10810, "user", "pass")

        val pac = server.currentPacContent()
        assertContains(pac, "SOCKS5 user:pass@127.0.0.1:10810; SOCKS user:pass@127.0.0.1:10810")
        assertTrue("SOCKS5 127.0.0.1:10808" !in pac)

        server.stop()
    }

    @Test
    fun pacEscapesSocksCredentialsInUserInfo() {
        val pac = PacServer.generatePac(
            socksHost = "127.0.0.1",
            socksPort = 10808,
            socksUsername = "user name",
            socksPassword = "p@ss:word"
        )

        assertContains(
            pac,
            "SOCKS5 user%20name:p%40ss%3Aword@127.0.0.1:10808; " +
                    "SOCKS user%20name:p%40ss%3Aword@127.0.0.1:10808"
        )
    }

    @Test
    fun olcRtcCommandUsesLocationProviderRoomAndKey() {
        LocationConfig.supportedBypassProviders.forEach { provider ->
            val expectedTransport = LocationConfig.normalizeTransport(
                LocationConfig.DEFAULT_TRANSPORT,
                provider
            )
            val command = OlcRtcCommand(
                binary = Path.of("/tmp/olcrtc"),
                location = LocationConfig("Test", "room-$provider", "b".repeat(64), provider),
                socksHost = "127.0.0.1",
                socksPort = 10808
            )
            val args = command.args(Path.of("/tmp/client.yaml"))
            val yaml = command.yaml()

            // Path.toString() is OS-dependent (backslashes on Windows) — compare the same way.
            assertEquals(
                listOf(Path.of("/tmp/olcrtc").toString(), Path.of("/tmp/client.yaml").toString()),
                args
            )
            assertContains(yaml, "mode: cnc")
            assertContains(yaml, "provider: '${OlcRtcCommand.desktopProviderArg(provider)}'")
            assertContains(yaml, "transport: '$expectedTransport'")
            assertContains(yaml, "id: 'room-$provider'")
            assertContains(yaml, "port: 10808")
            if (expectedTransport == LocationConfig.TRANSPORT_VP8CHANNEL) {
                assertContains(yaml, "vp8:")
                assertContains(yaml, "fps: 60")
                assertContains(yaml, "batch_size: 64")
            }
            assertTrue("client-id" !in yaml)
        }
    }

    @Test
    fun olcRtcCommandAllowsDatachannelForNonTelemostProviders() {
        val command = OlcRtcCommand(
            binary = Path.of("/tmp/olcrtc"),
            location = LocationConfig(
                name = "WB",
                id = "room-wb",
                key = "b".repeat(64),
                bypassProvider = LocationConfig.PROVIDER_WB_STREAM,
                transport = LocationConfig.TRANSPORT_DATACHANNEL
            ),
            dataDir = Path.of("/tmp/olcbox-data")
        ).yaml()

        assertContains(command, "transport: '${LocationConfig.TRANSPORT_DATACHANNEL}'")
        assertTrue("vp8:" !in command)
        assertContains(command, "data: '${Path.of("/tmp/olcbox-data")}'")
    }

    @Test
    fun olcRtcCommandAddsSeiDefaults() {
        val command = OlcRtcCommand(
            binary = Path.of("/tmp/olcrtc"),
            location = LocationConfig(
                name = "Telemost",
                id = "room",
                key = "c".repeat(64),
                bypassProvider = LocationConfig.PROVIDER_TELEMOST,
                transport = LocationConfig.TRANSPORT_SEICHANNEL
            )
        ).yaml()

        assertContains(command, "transport: '${LocationConfig.TRANSPORT_SEICHANNEL}'")
        assertContains(command, "sei:")
        assertContains(command, "fps: 60")
        assertContains(command, "batch_size: 64")
        assertContains(command, "fragment_size: 900")
        assertContains(command, "ack_timeout_ms: 2000")
        assertTrue("vp8:" !in command)
    }

    @Test
    fun nativeLibrarySpecSelectsPlatformFiles() {
        assertEquals(
            "libolcrtc-darwin-arm64.dylib",
            olcRtcNativeLibrarySpec("Mac OS X", "aarch64")?.fileName
        )
        assertEquals(
            "libolcrtc-linux-amd64.so",
            olcRtcNativeLibrarySpec("Linux", "x86_64")?.fileName
        )
        assertEquals(
            "olcrtc-windows-amd64.dll",
            olcRtcNativeLibrarySpec("Windows 11", "amd64")?.fileName
        )
    }

    @Test
    fun linuxTunConfigCanRunRouteScriptsInsidePrivilegedTunnelProcess() {
        val config = LinuxTunController.configContent(
            socksPort = 10810,
            postUpScript = "/tmp/olcbox-up.sh",
            preDownScript = "/tmp/olcbox-down.sh"
        )

        assertContains(config, "port: 10810")
        assertContains(config, "post-up-script: /tmp/olcbox-up.sh")
        assertContains(config, "pre-down-script: /tmp/olcbox-down.sh")
    }

    @Test
    fun linuxTunConfigCarriesSocksCredentialsWhenSet() {
        // The core's SOCKS inbound is started with these; without them in the yaml it rejects every
        // connection from hev-socks5-tunnel and TUN mode looks dead.
        val config = LinuxTunController.configContent(
            socksPort = 10810,
            socksUsername = "user",
            socksPassword = "it's-secret"
        )

        assertContains(config, "username: 'user'")
        // YAML single-quoted scalars escape a quote by doubling it.
        assertContains(config, "password: 'it''s-secret'")
    }

    @Test
    fun linuxTunConfigOmitsSocksCredentialsWhenUnset() {
        val config = LinuxTunController.configContent(socksPort = 10810)

        assertFalse(config.contains("username:"))
        assertFalse(config.contains("password:"))
    }

    @Test
    fun trustTunnelKeepsOnlyTheEndpointTableFromTheWizardDocument() {
        // The wizard defaults to a TUN listener; keeping it would make the client create an
        // interface and demand root, so everything after [endpoint] has to be dropped.
        val document = """
            loglevel = "info"

            # a comment
            [endpoint]
            hostname = "vpn.example.com"
            addresses = ["1.2.3.4:443", "[2001:db8::1]:443"]
            certificate = ""

            [listener]
            [listener.tun]
            mtu_size = 1350
        """.trimIndent()

        val endpoint = DesktopTrustTunnel.extractEndpointTable(document)

        assertNotNull(endpoint)
        assertTrue(endpoint.startsWith("[endpoint]"))
        assertContains(endpoint, "hostname = \"vpn.example.com\"")
        assertFalse(endpoint.contains("listener"))
        assertFalse(endpoint.contains("mtu_size"))
    }

    @Test
    fun trustTunnelReportsNoEndpointTableWhenAbsent() {
        assertNull(DesktopTrustTunnel.extractEndpointTable("loglevel = \"info\"\n"))
    }

    @Test
    fun olcRtcCommandUsesDesktopWbStreamProviderAlias() {
        listOf(LocationConfig.PROVIDER_WB_STREAM, "wbstream").forEach { provider ->
            val command = OlcRtcCommand(
                binary = Path.of("/tmp/olcrtc"),
                location = LocationConfig(
                    name = "WB",
                    id = "room-wb",
                    key = "b".repeat(64),
                    bypassProvider = provider
                )
            ).yaml()

            assertContains(command, "provider: 'wbstream'")
        }
    }

    @Test
    fun macOsProxyCommandsEnableAndRestorePacPerService() {
        val enable = MacOsProxyController.enableCommands(listOf("Wi-Fi"), "http://127.0.0.1:10809/proxy.pac")
        assertEquals(
            listOf(
                listOf("networksetup", "-setautoproxyurl", "Wi-Fi", "http://127.0.0.1:10809/proxy.pac"),
                listOf("networksetup", "-setautoproxystate", "Wi-Fi", "on")
            ),
            enable
        )

        val restore = MacOsProxyController.restoreCommands(
            listOf(
                MacOsAutoProxyState("Wi-Fi", enabled = true, url = "http://old/proxy.pac"),
                MacOsAutoProxyState("USB", enabled = false, url = null)
            )
        )
        assertEquals(
            listOf(
                listOf("networksetup", "-setautoproxyurl", "Wi-Fi", "http://old/proxy.pac"),
                listOf("networksetup", "-setautoproxystate", "Wi-Fi", "on"),
                listOf("networksetup", "-setautoproxystate", "USB", "off")
            ),
            restore
        )
    }

    @Test
    fun windowsProxyHttpEnableSetsFixedProxyAndClearsPac() {
        val enable = WindowsProxyController.enableHttpCommands("127.0.0.1:10808")
        val flat = enable.flatten()
        // Reliable WinINET path: fixed ProxyServer + ProxyEnable=1, and any stale PAC cleared.
        assertContains(flat, "ProxyServer")
        assertContains(flat, "127.0.0.1:10808")
        assertContains(flat, "ProxyEnable")
        assertContains(flat, "AutoConfigURL")
        assertContains(flat, "delete") // the PAC clear
        assertTrue(enable.any { it.contains("ProxyOverride") })
    }

    @Test
    fun windowsProxyRestoreReproducesOriginalRegistry() {
        val restore = WindowsProxyController.restoreCommands(
            WindowsProxyState(
                proxyEnable = "0x1",
                proxyServer = "127.0.0.1:8888",
                proxyOverride = "<local>",
                autoConfigUrl = null
            )
        )
        val flat = restore.flatten()
        assertContains(flat, "ProxyEnable")
        assertContains(flat, "ProxyServer")
        assertContains(flat, "ProxyOverride")
        assertContains(flat, "AutoConfigURL")
        assertContains(flat, "delete") // null autoConfigUrl => delete that value
    }

    @Test
    fun windowsProxyStateRecognisesOurOwnLoopbackProxy() {
        // Guards the anti-poisoning backup: a loopback proxy is "ours", so it must never be saved as
        // the value to restore to (that would strand the machine offline on disable).
        assertTrue(
            WindowsProxyState("0x1", "127.0.0.1:10808", "<local>", null).looksLikeOurs()
        )
        assertTrue(
            WindowsProxyState("0x1", null, null, "http://127.0.0.1:9/proxy.pac").looksLikeOurs()
        )
        assertTrue(
            !WindowsProxyState("0x1", "corp-proxy.example:3128", "<local>", null).looksLikeOurs()
        )
        assertTrue(!WindowsProxyState("0x0", null, null, null).looksLikeOurs())
    }

    @Test
    fun windowsProxyRefreshCommandUsesFullyQualifiedWinInetSignature() {
        val refresh = WindowsProxyController.refreshCommand()
        val script = refresh.last()

        assertEquals("powershell.exe", refresh.first())
        assertContains(script, "System.Runtime.InteropServices.DllImport")
        assertContains(script, "System.IntPtr")
        assertContains(script, "InternetSetOption")
    }

    @Test
    fun linuxTunConfigUsesLocalSocksAndIpv4MapDns() {
        val config = LinuxTunController.configContent()

        assertContains(config, "name: olcbox0")
        assertContains(config, "ipv4: 10.0.88.88")
        assertContains(config, "address: 127.0.0.1")
        assertContains(config, "port: 10808")
        assertContains(config, "udp: 'tcp'")
        assertContains(config, "mapdns:")
        assertContains(config, "network: 100.64.0.0")
    }

    @Test
    fun windowsTunCommandUsesTun2SocksWintunAndLocalSocks() {
        val command = WindowsTunController.tun2SocksCommand(
            tun2SocksBinary = Path.of("C:/Olcbox/bin/tun2socks-windows-amd64.exe"),
            socksPort = 10812
        )

        assertContains(command, Path.of("C:/Olcbox/bin/tun2socks-windows-amd64.exe").toString())
        assertContains(command, "--device")
        assertContains(command, WindowsTunController.TUN_NAME)
        assertContains(command, "--proxy")
        assertContains(command, "socks5://127.0.0.1:10812")
        assertContains(command, "--mtu")
        assertContains(command, "1500")
    }

    @Test
    fun windowsTunCommandCarriesTheSocksInboundCredentials() {
        // The core's SOCKS inbound requires auth; a credential-less bridge is rejected outright.
        val command = WindowsTunController.tun2SocksCommand(
            tun2SocksBinary = Path.of("C:/Olcbox/bin/tun2socks-windows-amd64.exe"),
            socksPort = 10812,
            socksUsername = "olcbox",
            socksPassword = "p@ss word"
        )

        assertContains(command, "socks5://olcbox:p%40ss%20word@127.0.0.1:10812")
    }

    @Test
    fun windowsTunAdministratorRestartUsesRunAsAndPreservesArguments() {
        val script = WindowsTunController.restartAsAdministratorScript(
            command = "C:/Olc's/Olcbox.exe",
            arguments = listOf("--flag", "C:/Path With Space/data"),
            workingDirectory = "C:/Olcbox Data"
        )

        assertContains(script, "FilePath = 'C:/Olc''s/Olcbox.exe'")
        assertContains(script, "Verb = 'RunAs'")
        assertContains(script, "ArgumentList = '--flag \"C:/Path With Space/data\"'")
        assertContains(script, "WorkingDirectory = 'C:/Olcbox Data'")
        assertContains(script, "Start-Process @startArgs")
    }

    @Test
    fun linuxTunScriptsRouteUserTrafficThroughTunAndKeepRootDirect() {
        val up = LinuxTunController.upScriptContent()
        val down = LinuxTunController.downScriptContent()

        assertContains(up, "ip rule add uidrange 0-0 lookup main pref 10")
        assertContains(up, "ip route add default dev olcbox0 table 51820")
        assertContains(up, "ip rule add lookup 51820 pref 20")
        assertContains(up, "resolvectl dns olcbox0 1.1.1.1")
        assertContains(down, "ip rule del uidrange 0-0 lookup main pref 10")
        assertContains(down, "ip route flush table 51820")
        assertContains(down, "resolvectl revert olcbox0")
    }
}
