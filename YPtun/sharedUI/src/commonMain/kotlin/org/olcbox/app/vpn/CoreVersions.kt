package org.olcbox.app.vpn

/**
 * Versions of the cores that run as subprocesses / prebuilt blobs and so can't report their own
 * version at runtime (unlike libbox/xraybridge/awg). Bump together with a re-vendor.
 */
object CoreVersions {
    /** openflux/YPTUN.md — upstream tag + vendored commit. */
    const val OPENFLUX = "0.4.2 (74cac6d)"
    /** snolc/Cargo.toml. */
    const val SNOLC = "0.0.4"
    /** csqtt/rust-client/Cargo.toml + the upstream commit it was vendored at (csqtt/UPSTREAM.txt). */
    const val CSQTT = "2.1.9 (71712b0)"
    /** Android: sharedUI/libs/trusttunnel-client-android-*.aar. */
    const val TRUSTTUNNEL_ANDROID = "1.1.5-rc.1"
    /** Desktop: `trustTunnelVersion` in desktopApp/build.gradle.kts. */
    const val TRUSTTUNNEL_DESKTOP = "1.0.49"
}
