package org.olcbox.app.vpn.service

object OlcboxVpnActions {
    const val SERVICE_CLASS_NAME = "org.olcbox.app.vpn.service.OlcboxVpnService"
    const val ACTION_START_VPN = "org.olcbox.app.vpn.service.OlcboxVpnService.START"
    const val ACTION_STOP_VPN = "org.olcbox.app.vpn.service.OlcboxVpnService.STOP"
    const val EXTRA_CONNECTION_MODE = "org.olcbox.app.vpn.service.OlcboxVpnService.CONNECTION_MODE"
    const val EXTRA_SOCKS_HOST = "org.olcbox.app.vpn.service.OlcboxVpnService.SOCKS_HOST"
    const val EXTRA_SOCKS_PORT = "org.olcbox.app.vpn.service.OlcboxVpnService.SOCKS_PORT"
    const val EXTRA_SOCKS_USERNAME = "org.olcbox.app.vpn.service.OlcboxVpnService.SOCKS_USERNAME"
    const val EXTRA_SOCKS_PASSWORD = "org.olcbox.app.vpn.service.OlcboxVpnService.SOCKS_PASSWORD"
    const val EXTRA_SPLIT_TUNNEL_MODE = "org.olcbox.app.vpn.service.OlcboxVpnService.SPLIT_TUNNEL_MODE"
    const val EXTRA_SPLIT_TUNNEL_PROXY_APPS = "org.olcbox.app.vpn.service.OlcboxVpnService.SPLIT_TUNNEL_PROXY_APPS"
    const val EXTRA_SPLIT_TUNNEL_BYPASS_APPS = "org.olcbox.app.vpn.service.OlcboxVpnService.SPLIT_TUNNEL_BYPASS_APPS"
}
