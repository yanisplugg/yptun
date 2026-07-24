package org.olcbox.app.vpn.service

object OlcboxVpnActions {
    const val SERVICE_CLASS_NAME = "org.olcbox.app.vpn.service.OlcboxVpnService"
    const val ACTION_START_VPN = "org.olcbox.app.vpn.service.OlcboxVpnService.START"
    const val ACTION_STOP_VPN = "org.olcbox.app.vpn.service.OlcboxVpnService.STOP"

    /**
     * Widget "Auto = fastest" without opening the app: the service probes every complete location in
     * parallel (same per-engine probe the in-app ping uses), persists the fastest as the active
     * location and connects it through the normal start path — all inside the foreground service.
     */
    const val ACTION_AUTO_CONNECT = "org.olcbox.app.vpn.service.OlcboxVpnService.AUTO_CONNECT"

    /**
     * Broadcast the running VPN service emits (explicit, targeted at the home-screen widget providers
     * by class name) whenever the connection status or live speed changes, so the widgets repaint
     * without polling. Carries no extras — the widgets read [OlcboxVpnState] directly (same process).
     */
    const val ACTION_WIDGET_REFRESH = "org.olcbox.app.widget.REFRESH"
    const val EXTRA_CONNECTION_MODE = "org.olcbox.app.vpn.service.OlcboxVpnService.CONNECTION_MODE"
    const val EXTRA_SOCKS_HOST = "org.olcbox.app.vpn.service.OlcboxVpnService.SOCKS_HOST"
    const val EXTRA_SOCKS_PORT = "org.olcbox.app.vpn.service.OlcboxVpnService.SOCKS_PORT"
    const val EXTRA_SOCKS_USERNAME = "org.olcbox.app.vpn.service.OlcboxVpnService.SOCKS_USERNAME"
    const val EXTRA_SOCKS_PASSWORD = "org.olcbox.app.vpn.service.OlcboxVpnService.SOCKS_PASSWORD"
    const val EXTRA_SPLIT_TUNNEL_MODE = "org.olcbox.app.vpn.service.OlcboxVpnService.SPLIT_TUNNEL_MODE"
    const val EXTRA_SPLIT_TUNNEL_PROXY_APPS = "org.olcbox.app.vpn.service.OlcboxVpnService.SPLIT_TUNNEL_PROXY_APPS"
    const val EXTRA_SPLIT_TUNNEL_BYPASS_APPS = "org.olcbox.app.vpn.service.OlcboxVpnService.SPLIT_TUNNEL_BYPASS_APPS"
}
