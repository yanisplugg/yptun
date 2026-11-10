//! Versions of the cores that run as subprocesses or prebuilt blobs and so can't report their own
//! at runtime (unlike libbox, xraybridge and awg). Bump together with a re-vendor.

/// openflux/YPTUN.md: upstream tag + vendored commit.
pub const OPENFLUX: &str = "0.13.0 (4f9c786)";
/// snolc/Cargo.toml.
pub const SNOLC: &str = "0.0.4";
/// csqtt/rust-client/Cargo.toml + the upstream commit it was vendored at (csqtt/UPSTREAM.txt).
pub const CSQTT: &str = "2.5.0 (fd936ef)";
/// Android: sharedUI/libs/trusttunnel-client-android-*.aar.
pub const TRUSTTUNNEL_ANDROID: &str = "1.1.11";
/// Desktop: `trustTunnelVersion` in desktopApp/build.gradle.kts.
pub const TRUSTTUNNEL_DESKTOP: &str = "1.1.11";
