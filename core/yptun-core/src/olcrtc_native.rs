//! Port of `OlcRtcNativeLib.kt` (jvmMain vpn): the olcRTC c-shared library (`Ping` / `Check`)
//! loaded in-process.
//!
//! * `olcrtc_native_library_spec` is the pure file-name table (all platforms).
//! * The loader is desktop only (Windows, Linux, macOS) over `libloading`. Signatures mirror
//!   `olcrtc/cmd/olcrtc-cgo/main.go`: C strings and `long long`, -1 on error.
//! * Like `Native.load` after extracting the resource, the library is never unloaded and the
//!   extracted temp file is left in place (Kotlin only marked it delete-on-exit).
//! * Failures are printed and give None, as in Kotlin.

/// Which library file ships for a platform.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OlcRtcNativeLibrarySpec {
    pub file_name: String,
}

/// `olcRtcNativeLibrarySpec(osName, archName)`; takes `os.name` / `os.arch` style values and also
/// the `std::env::consts` spellings (`macos`, `aarch64`, `x86_64`).
pub fn olcrtc_native_library_spec(
    os_name: Option<&str>,
    arch_name: Option<&str>,
) -> Option<OlcRtcNativeLibrarySpec> {
    let os = os_name.unwrap_or("").to_lowercase();
    let raw_arch = arch_name.unwrap_or("").to_lowercase();
    let arch = match raw_arch.as_str() {
        "aarch64" | "arm64" => "arm64".to_string(),
        "x86_64" | "amd64" => "amd64".to_string(),
        _ => raw_arch.clone(),
    };
    let file = if os.contains("mac") || os.contains("darwin") {
        match arch.as_str() {
            "arm64" => "libolcrtc-darwin-arm64.dylib",
            "amd64" => "libolcrtc-darwin-amd64.dylib",
            _ => return None,
        }
    } else if os.contains("linux") {
        match arch.as_str() {
            "arm64" => "libolcrtc-linux-arm64.so",
            "amd64" => "libolcrtc-linux-amd64.so",
            _ => return None,
        }
    } else if os.contains("win") {
        match arch.as_str() {
            "amd64" => "olcrtc-windows-amd64.dll",
            _ => return None,
        }
    } else {
        return None;
    };
    Some(OlcRtcNativeLibrarySpec {
        file_name: file.to_string(),
    })
}

/// The spec for the platform this binary was built for.
pub fn current_library_spec() -> Option<OlcRtcNativeLibrarySpec> {
    olcrtc_native_library_spec(Some(std::env::consts::OS), Some(std::env::consts::ARCH))
}

#[cfg(any(windows, target_os = "linux", target_os = "macos"))]
pub use loader::OlcRtcNative;

#[cfg(any(windows, target_os = "linux", target_os = "macos"))]
mod loader {
    use std::mem::ManuallyDrop;
    use std::os::raw::c_char;
    use std::path::{Path, PathBuf};
    use std::time::{SystemTime, UNIX_EPOCH};

    use libloading::Library;

    use super::current_library_spec;
    use crate::desktop_native_assets::resource_name;
    use crate::yptun_core_native::c_string;

    type S = *const c_char;
    type PingFn = unsafe extern "C" fn(S, S, S, S, S, i64, i64, S, i64, i64) -> i64;
    type CheckFn = unsafe extern "C" fn(S, S, S, S, S, i64, i64, i64, i64) -> i64;

    pub const TEMP_PREFIX: &str = "olcrtc-native-";

    /// The loaded library. Kept for the whole process (a Go runtime can't be unloaded).
    pub struct OlcRtcNative {
        ping: PingFn,
        check: CheckFn,
        path: PathBuf,
        _lib: ManuallyDrop<Library>,
    }

    impl OlcRtcNative {
        pub fn load(path: &Path) -> Result<Self, String> {
            // SAFETY: our own c-shared build; signatures mirror olcrtc-cgo/main.go.
            unsafe {
                let lib = Library::new(path).map_err(|e| format!("{}: {e}", path.display()))?;
                let ping = *lib.get::<PingFn>(b"Ping\0").map_err(|e| format!("Ping: {e}"))?;
                let check = *lib.get::<CheckFn>(b"Check\0").map_err(|e| format!("Check: {e}"))?;
                Ok(Self {
                    ping,
                    check,
                    path: path.to_path_buf(),
                    _lib: ManuallyDrop::new(lib),
                })
            }
        }

        /// Writes `bytes` to `olcrtc-native-<pid>-<nanos>-<file_name>` in the temp dir and loads it.
        pub fn load_from_bytes(file_name: &str, bytes: &[u8]) -> Result<Self, String> {
            let nanos = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map(|d| d.as_nanos())
                .unwrap_or(0);
            let path = std::env::temp_dir().join(format!(
                "{TEMP_PREFIX}{}-{nanos}-{file_name}",
                std::process::id()
            ));
            std::fs::write(&path, bytes).map_err(|e| format!("{}: {e}", path.display()))?;
            Self::load(&path)
        }

        /// The Kotlin lazy `instance`: resolves the platform's library, reads the resource
        /// `native/<file>` through `resource` and loads it; prints why and gives None on failure.
        pub fn load_current(resource: &dyn Fn(&str) -> Option<Vec<u8>>) -> Option<Self> {
            let Some(spec) = current_library_spec() else {
                println!("OlcRtcNativeLib: Unsupported platform");
                return None;
            };
            let name = resource_name(&spec.file_name);
            let Some(bytes) = resource(&name) else {
                println!("OlcRtcNativeLib: Resource not found: {name}");
                return None;
            };
            match Self::load_from_bytes(&spec.file_name, &bytes) {
                Ok(lib) => Some(lib),
                Err(e) => {
                    println!("OlcRtcNativeLib: Failed to load native library: {e}");
                    None
                }
            }
        }

        pub fn path(&self) -> &Path {
            &self.path
        }

        /// Round-trip time in ms, or -1 on error.
        #[allow(clippy::too_many_arguments)]
        pub fn ping(
            &self,
            carrier_name: &str,
            transport_name: &str,
            room_id: &str,
            client_id: &str,
            key_hex: &str,
            socks_port: i64,
            timeout_millis: i64,
            ping_url: &str,
            vp8_fps: i64,
            vp8_batch_size: i64,
        ) -> i64 {
            let (a, b, c, d, e, u) = (
                c_string(carrier_name),
                c_string(transport_name),
                c_string(room_id),
                c_string(client_id),
                c_string(key_hex),
                c_string(ping_url),
            );
            // SAFETY: every pointer outlives the call; Go copies the strings.
            unsafe {
                (self.ping)(
                    a.as_ptr(), b.as_ptr(), c.as_ptr(), d.as_ptr(), e.as_ptr(),
                    socks_port, timeout_millis, u.as_ptr(), vp8_fps, vp8_batch_size,
                )
            }
        }

        /// Connect check result, or -1 on error.
        #[allow(clippy::too_many_arguments)]
        pub fn check(
            &self,
            carrier_name: &str,
            transport_name: &str,
            room_id: &str,
            client_id: &str,
            key_hex: &str,
            socks_port: i64,
            timeout_millis: i64,
            vp8_fps: i64,
            vp8_batch_size: i64,
        ) -> i64 {
            let (a, b, c, d, e) = (
                c_string(carrier_name),
                c_string(transport_name),
                c_string(room_id),
                c_string(client_id),
                c_string(key_hex),
            );
            // SAFETY: as in ping.
            unsafe {
                (self.check)(
                    a.as_ptr(), b.as_ptr(), c.as_ptr(), d.as_ptr(), e.as_ptr(),
                    socks_port, timeout_millis, vp8_fps, vp8_batch_size,
                )
            }
        }
    }
}
