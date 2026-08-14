package org.olcbox.app.vpn.desktop

import java.util.Locale

/**
 * Finds other VPN clients running on this machine.
 *
 * Two of them cannot share the machine: whoever raised a TUN adapter first owns the default route,
 * and a second client either fails to create its own adapter or comes up and carries nothing. In
 * proxy mode the same fight happens over the system proxy setting. Either way the user sees "YPtun
 * doesn't connect" with nothing wrong in YPtun — so name the culprit and offer to close it instead
 * of leaving them to guess.
 *
 * Detection is by process name only (no elevation needed to enumerate). Our OWN helper processes
 * (olcrtc, tun2socks, …) are descendants of this process and are excluded, so we never accuse
 * ourselves.
 */
internal object ConflictingVpnDetector {

    data class RunningVpnApp(val displayName: String, val processes: List<ProcessHandle>) {
        val pids: List<Long> get() = processes.map { it.pid() }
    }

    /** exe name (lowercase) → product shown to the user. */
    private val KNOWN: Map<String, String> = buildMap {
        fun add(product: String, vararg exe: String) = exe.forEach { put(it.lowercase(Locale.ROOT), product) }

        add("AmneziaVPN", "amneziavpn.exe", "amneziavpn-service.exe", "amneziavpn-gui.exe")
        add("Hiddify", "hiddify.exe", "hiddifycli.exe", "hiddifynext.exe")
        add("v2rayN", "v2rayn.exe", "v2rayn-core.exe")
        add("Nekoray", "nekoray.exe", "nekobox.exe", "nekobox_core.exe", "nekoray_core.exe")
        add("Clash", "clash-verge.exe", "clash-win64.exe", "clash for windows.exe", "clash.exe", "clash-meta.exe")
        add("Mihomo", "verge-mihomo.exe", "mihomo.exe")
        add("sing-box", "sing-box.exe")
        add("Xray", "xray.exe", "v2ray.exe", "wv2ray.exe")
        add("Happ", "happ.exe")
        add("Outline", "outline.exe", "outline-client.exe")
        add("ProtonVPN", "protonvpn.exe", "protonvpnservice.exe", "protonvpn.client.exe")
        add("NordVPN", "nordvpn.exe", "nordvpn-service.exe")
        add("ExpressVPN", "expressvpn.exe", "expressvpnd.exe")
        add("Surfshark", "surfshark.exe", "surfsharkservice.exe")
        add("Windscribe", "windscribe.exe", "windscribeservice.exe")
        add("WireGuard", "wireguard.exe", "wgnt.exe")
        add("OpenVPN", "openvpn.exe", "openvpn-gui.exe", "openvpnserv.exe", "openvpnconnect.exe")
        add("Cloudflare WARP", "cloudflare warp.exe", "warp-svc.exe", "warp-cli.exe")
        add("Tailscale", "tailscale-ipn.exe", "tailscaled.exe")
        add("ZeroTier", "zerotier one.exe", "zerotier_desktop_ui.exe")
        add("Psiphon", "psiphon3.exe", "psiphon-tunnel-core.exe")
        add("Netch", "netch.exe")
        add("Shadowsocks", "shadowsocks.exe", "ss-local.exe")
        add("Radmin VPN", "radminvpn.exe")
        add("Hola VPN", "hola.exe", "hola_svc.exe")
        add("Lantern", "lantern.exe")
        // Deliberately NOT here: tor.exe. Tor Browser is common, takes no adapter and conflicts with
        // nothing — flagging it would train the user to dismiss this prompt.
    }

    /**
     * Every other VPN client currently running, grouped by product. Empty when the machine is clear
     * (the normal case) — the whole scan is one pass over the process table.
     */
    fun detect(): List<RunningVpnApp> {
        val ours = ourOwnPids()
        val found = LinkedHashMap<String, MutableList<ProcessHandle>>()
        runCatching {
            ProcessHandle.allProcesses().forEach { handle ->
                if (handle.pid() in ours) return@forEach
                val command = handle.info().command().orElse(null) ?: return@forEach
                val exe = command.substringAfterLast('\\').substringAfterLast('/').lowercase(Locale.ROOT)
                val product = KNOWN[exe] ?: return@forEach
                found.getOrPut(product) { mutableListOf() }.add(handle)
            }
        }
        return found.map { (product, handles) -> RunningVpnApp(product, handles) }
    }

    /**
     * Asks each process to quit, then kills what is left. Returns the products that are genuinely
     * gone; anything still alive (a Windows *service* running as SYSTEM, which an unelevated YPtun
     * may not touch) is reported back so the user can be told the truth instead of "done".
     */
    fun terminate(apps: List<RunningVpnApp>): Pair<List<String>, List<String>> {
        val closed = mutableListOf<String>()
        val survived = mutableListOf<String>()
        apps.forEach { app ->
            app.processes.forEach { runCatching { it.destroy() } }
            // Give a GUI client a moment to exit on its own before pulling the plug.
            val deadline = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < deadline && app.processes.any { it.isAlive }) {
                Thread.sleep(150)
            }
            app.processes.filter { it.isAlive }.forEach { runCatching { it.destroyForcibly() } }
            Thread.sleep(300)
            if (app.processes.any { it.isAlive }) survived += app.displayName else closed += app.displayName
        }
        return closed to survived
    }

    /** This process and everything it started (olcrtc, tun2socks, …) — never our own conflict. */
    private fun ourOwnPids(): Set<Long> {
        val self = ProcessHandle.current()
        return buildSet {
            add(self.pid())
            runCatching { self.descendants().forEach { add(it.pid()) } }
        }
    }
}
