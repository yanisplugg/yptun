Official AdGuard `trusttunnel_client` v1.1.11 (linux-aarch64, static musl) from
https://github.com/TrustTunnel/TrustTunnelClient/releases/tag/v1.1.11 — unmodified.
Packaged as lib/arm64-v8a/libtrusttunnel_client.so and run as a SUBPROCESS (SOCKS-only), because the
Android AAR's JNI always passes a TUN fd and the client refuses a SOCKS listener in that mode.
