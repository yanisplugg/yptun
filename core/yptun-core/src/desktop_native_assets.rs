//! Port of DesktopNativeAssets.kt: which bundled native binary each desktop OS/arch uses, where
//! it is copied (`<appData>/bin/`), and the dev-tree fallbacks. Bundled resources are opened by the
//! platform through [`NativeResources`] (the JVM used the classpath, the Rust client embeds them).

use std::fs;
use std::io::{self, Read};
use std::path::{Path, PathBuf};
use std::sync::Mutex;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DesktopOs {
    MacOs,
    Windows,
    Linux,
    Other,
}

impl DesktopOs {
    /// Kotlin enum name (used in error texts).
    pub fn name(self) -> &'static str {
        match self {
            DesktopOs::MacOs => "MacOS",
            DesktopOs::Windows => "Windows",
            DesktopOs::Linux => "Linux",
            DesktopOs::Other => "Other",
        }
    }
}

pub const BIN_DIR: &str = "bin";
pub const OLCRTC_DATA_DIR: &str = "olcrtc-data";
pub const OLCRTC_DATA_FILES: [&str; 2] = ["names", "surnames"];
pub const WINTUN_DLL: &str = "wintun.dll";
pub const UNSUPPORTED_OS_ERROR: &str = "Olcbox desktop supports macOS, Windows and Linux";

pub fn resource_name(file_name: &str) -> String {
    format!("native/{file_name}")
}

pub fn missing_binary_error(resource_name: &str) -> String {
    format!("Bundled native binary is missing: {resource_name}")
}

pub fn desktop_arch(arch: &str) -> Result<&'static str, String> {
    match arch {
        "x86_64" | "amd64" => Ok("amd64"),
        "aarch64" | "arm64" => Ok("arm64"),
        _ => Err(format!("Unsupported desktop architecture: {arch}")),
    }
}

pub fn desktop_arch_fallback(arch: &str) -> Result<&'static str, String> {
    Ok(match desktop_arch(arch)? {
        "arm64" => "amd64",
        _ => "arm64",
    })
}

fn distinct(items: Vec<PathBuf>) -> Vec<PathBuf> {
    let mut out: Vec<PathBuf> = Vec::new();
    for p in items {
        if !out.contains(&p) {
            out.push(p);
        }
    }
    out
}

fn distinct_names(items: Vec<String>) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for n in items {
        if !out.contains(&n) {
            out.push(n);
        }
    }
    out
}

/// olcRTC names in lookup order: native arch first, the other one as fallback (macOS/Windows).
pub fn olcrtc_file_names(os: DesktopOs, arch: &str) -> Result<Vec<String>, String> {
    match os {
        DesktopOs::MacOs => Ok(distinct_names(vec![
            format!("olcrtc-darwin-{}", desktop_arch(arch)?),
            format!("olcrtc-darwin-{}", desktop_arch_fallback(arch)?),
        ])),
        DesktopOs::Windows => Ok(distinct_names(vec![
            format!("olcrtc-windows-{}.exe", desktop_arch(arch)?),
            format!("olcrtc-windows-{}.exe", desktop_arch_fallback(arch)?),
        ])),
        DesktopOs::Linux => Ok(vec![format!("olcrtc-linux-{}", desktop_arch(arch)?)]),
        DesktopOs::Other => Err(UNSUPPORTED_OS_ERROR.into()),
    }
}

pub fn missing_olcrtc_error(file_names: &[String]) -> String {
    missing_binary_error(&file_names.iter().map(|n| resource_name(n)).collect::<Vec<_>>().join(", "))
}

pub fn hev_socks5_tunnel_file_name(os: DesktopOs, arch: &str) -> Result<String, String> {
    match os {
        DesktopOs::Linux => Ok(format!("hev-socks5-tunnel-linux-{}", desktop_arch(arch)?)),
        _ => Err("hev-socks5-tunnel desktop binary is only used for TUN mode".into()),
    }
}

pub fn windows_tun2socks_file_name(os: DesktopOs, arch: &str) -> Result<String, String> {
    match os {
        DesktopOs::Windows => Ok(format!("tun2socks-windows-{}.exe", desktop_arch(arch)?)),
        _ => Err("tun2socks desktop binary is only used for Windows TUN mode".into()),
    }
}

pub fn cronet_file_name(os: DesktopOs) -> &'static str {
    match os {
        DesktopOs::Windows => "libcronet.dll",
        DesktopOs::MacOs => "libcronet.dylib",
        DesktopOs::Linux | DesktopOs::Other => "libcronet.so",
    }
}

/// [`kind`] = "client" or "wizard".
pub fn trust_tunnel_file_name(os: DesktopOs, arch: &str, kind: &str) -> Result<String, String> {
    match os {
        DesktopOs::Linux => Ok(format!("trusttunnel-{kind}-linux-{}", desktop_arch(arch)?)),
        DesktopOs::Windows => Ok(format!("trusttunnel-{kind}-windows-{}.exe", desktop_arch(arch)?)),
        _ => Err(format!("Trust Tunnel is not bundled for {}", os.name())),
    }
}

pub fn openflux_file_name(os: DesktopOs, arch: &str) -> Result<String, String> {
    match os {
        DesktopOs::Linux => Ok(format!("openflux-linux-{}", desktop_arch(arch)?)),
        DesktopOs::Windows => Ok(format!("openflux-windows-{}.exe", desktop_arch(arch)?)),
        _ => Err(format!("OpenFlux is not bundled for {}", os.name())),
    }
}

/// (bridge, client) = (csqtthost-*, csqtt-*).
pub fn csqtt_file_names(os: DesktopOs, arch: &str) -> Result<(String, String), String> {
    let (name, ext) = match os {
        DesktopOs::Linux => ("linux", ""),
        DesktopOs::Windows => ("windows", ".exe"),
        _ => return Err(format!("csqtt is not bundled for {}", os.name())),
    };
    let a = desktop_arch(arch)?;
    Ok((format!("csqtthost-{name}-{a}{ext}"), format!("csqtt-{name}-{a}{ext}")))
}

pub fn snolc_file_name(os: DesktopOs, arch: &str) -> Result<String, String> {
    match os {
        DesktopOs::Linux => Ok(format!("snolc-linux-{}", desktop_arch(arch)?)),
        DesktopOs::Windows => Ok(format!("snolc-windows-{}.exe", desktop_arch(arch)?)),
        _ => Err(format!("snolc is not bundled for {}", os.name())),
    }
}

fn non_blank(value: Option<&str>) -> Option<PathBuf> {
    value.filter(|v| !v.trim().is_empty()).map(PathBuf::from)
}

fn without_exe(file_name: &str) -> &str {
    file_name.strip_suffix(".exe").unwrap_or(file_name)
}

pub fn repo_candidates(repo: &Path, file_name: &str) -> Vec<PathBuf> {
    vec![repo.join("build").join(file_name), repo.join(without_exe(file_name)), repo.join("olcrtc")]
}

fn default_olcrtc_repo() -> PathBuf {
    Path::new("..").join("olcrtc")
}

/// OLCRTC_BINARY / OLCRTC_REPO (a binary path or a repo), then `../olcrtc`.
pub fn olcrtc_source_candidates(file_name: &str, env_binary: Option<&str>, env_repo: Option<&str>) -> Vec<PathBuf> {
    let mut out = Vec::new();
    for p in [non_blank(env_binary), non_blank(env_repo)].into_iter().flatten() {
        let name = p.file_name().map(|n| n.to_string_lossy().to_string());
        if name.as_deref() == Some(file_name) || name.as_deref() == Some(without_exe(file_name)) {
            out.push(p);
        } else {
            out.extend(repo_candidates(&p, file_name));
        }
    }
    out.extend(repo_candidates(&default_olcrtc_repo(), file_name));
    out
}

pub fn olcrtc_data_source_candidates(file_name: &str, env_repo: Option<&str>) -> Vec<PathBuf> {
    [non_blank(env_repo), Some(default_olcrtc_repo())]
        .into_iter()
        .flatten()
        .map(|repo| repo.join("data").join(file_name))
        .collect()
}

/// The working directory and up to four of its parents.
pub fn project_root_candidates(cwd: &Path) -> Vec<PathBuf> {
    cwd.ancestors().take(5).map(Path::to_path_buf).collect()
}

fn generated_native(root: &Path, file_name: &str) -> [PathBuf; 2] {
    [
        root.join("desktopApp").join("build").join("generated").join("desktopNativeResources").join("native").join(file_name),
        root.join("build").join("generated").join("desktopNativeResources").join("native").join(file_name),
    ]
}

pub fn desktop_native_resource_candidates(file_name: &str, roots: &[PathBuf]) -> Vec<PathBuf> {
    distinct(roots.iter().flat_map(|r| generated_native(r, file_name)).collect())
}

pub fn hev_socks5_tunnel_source_candidates(file_name: &str, env_binary: Option<&str>, roots: &[PathBuf]) -> Vec<PathBuf> {
    let mut out: Vec<PathBuf> = non_blank(env_binary).into_iter().collect();
    let tree: Vec<PathBuf> = roots
        .iter()
        .flat_map(|root| {
            let source_bin = root.join("androidApp").join("src").join("main").join("jni").join("hev-socks5-tunnel").join("bin");
            let [a, b] = generated_native(root, file_name);
            vec![a, b, source_bin.join("hev-socks5-tunnel.exe"), source_bin.join("hev-socks5-tunnel")]
        })
        .collect();
    out.extend(distinct(tree));
    out
}

pub fn windows_tun2socks_source_candidates(file_name: &str, env_binary: Option<&str>, roots: &[PathBuf]) -> Vec<PathBuf> {
    let mut out: Vec<PathBuf> = non_blank(env_binary).into_iter().collect();
    out.extend(desktop_native_resource_candidates(file_name, roots));
    out
}

// ---------------- file side ----------------

/// Bundled resources by name (`native/<file>`, `olcrtc-data/<file>`).
pub trait NativeResources {
    fn open(&self, resource_name: &str) -> Option<Box<dyn Read>>;
}

/// Everything the JVM read from System / DesktopPaths.
#[derive(Debug, Clone)]
pub struct NativeAssetEnv {
    pub app_data_dir: PathBuf,
    pub os: DesktopOs,
    pub arch: String,
    pub cwd: PathBuf,
    pub olcrtc_binary: Option<String>,
    pub olcrtc_repo: Option<String>,
    pub hev_socks5_tunnel_binary: Option<String>,
    pub tun2socks_binary: Option<String>,
}

// Parallel callers (per-location pings) must not copy over each other's running .exe.
static RESOLVE_LOCK: Mutex<()> = Mutex::new(());

fn copy_stream(mut source: impl Read, target: &Path) -> io::Result<()> {
    let mut file = fs::File::create(target)?;
    io::copy(&mut source, &mut file)?;
    Ok(())
}

/// `setExecutable(true, ownerOnly = true)` everywhere but Windows.
pub fn make_executable(path: &Path, os: DesktopOs) {
    if os == DesktopOs::Windows {
        return;
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if let Ok(meta) = fs::metadata(path) {
            let mut perms = meta.permissions();
            perms.set_mode(perms.mode() | 0o100);
            let _ = fs::set_permissions(path, perms);
        }
    }
    #[cfg(not(unix))]
    let _ = path;
}

pub struct DesktopNativeAssets<'a, R: NativeResources> {
    pub env: &'a NativeAssetEnv,
    pub resources: &'a R,
}

impl<'a, R: NativeResources> DesktopNativeAssets<'a, R> {
    pub fn new(env: &'a NativeAssetEnv, resources: &'a R) -> Self {
        Self { env, resources }
    }

    fn bin_dir(&self) -> PathBuf {
        self.env.app_data_dir.join(BIN_DIR)
    }

    fn roots(&self) -> Vec<PathBuf> {
        project_root_candidates(&self.env.cwd)
    }

    /// Copies the bundled resource (else the first existing candidate) to `<appData>/bin/<file>`.
    /// A copy that fails while the target already exists (a running .exe on Windows) keeps it.
    pub fn resolve_binary(&self, file_name: &str, candidates: &[PathBuf]) -> Result<PathBuf, String> {
        let _guard = RESOLVE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let target = self.bin_dir().join(file_name);
        fs::create_dir_all(self.bin_dir()).map_err(|e| e.to_string())?;
        let copy_or_keep = |source: Box<dyn Read>| -> Result<PathBuf, String> {
            if let Err(e) = copy_stream(source, &target) {
                if !target.is_file() {
                    return Err(e.to_string());
                }
            }
            make_executable(&target, self.env.os);
            Ok(target.clone())
        };
        let resource = resource_name(file_name);
        if let Some(source) = self.resources.open(&resource) {
            return copy_or_keep(source);
        }
        if let Some(candidate) = candidates.iter().find(|c| c.exists()) {
            let file = fs::File::open(candidate).map_err(|e| e.to_string())?;
            return copy_or_keep(Box::new(file));
        }
        Err(missing_binary_error(&resource))
    }

    pub fn copy_runtime_asset(&self, file_name: &str) -> Result<PathBuf, String> {
        let target = self.bin_dir().join(file_name);
        fs::create_dir_all(self.bin_dir()).map_err(|e| e.to_string())?;
        let resource = resource_name(file_name);
        if let Some(source) = self.resources.open(&resource) {
            copy_stream(source, &target).map_err(|e| e.to_string())?;
            return Ok(target);
        }
        if let Some(candidate) = desktop_native_resource_candidates(file_name, &self.roots()).into_iter().find(|c| c.exists()) {
            fs::copy(&candidate, &target).map_err(|e| e.to_string())?;
            return Ok(target);
        }
        Err(format!("Bundled runtime asset is missing: {resource}"))
    }

    fn copy_data_file(&self, file_name: &str, target_dir: &Path) -> Result<(), String> {
        let target = target_dir.join(file_name);
        let resource = format!("{OLCRTC_DATA_DIR}/{file_name}");
        if let Some(source) = self.resources.open(&resource) {
            return copy_stream(source, &target).map_err(|e| e.to_string());
        }
        if let Some(candidate) = olcrtc_data_source_candidates(file_name, self.env.olcrtc_repo.as_deref()).into_iter().find(|c| c.exists()) {
            return fs::copy(&candidate, &target).map(|_| ()).map_err(|e| e.to_string());
        }
        Err(format!("Bundled olcRTC data file is missing: {resource}"))
    }

    pub fn resolve_olcrtc_binary_candidates(&self) -> Result<Vec<PathBuf>, String> {
        let names = olcrtc_file_names(self.env.os, &self.env.arch)?;
        let found: Vec<PathBuf> = names
            .iter()
            .filter_map(|n| {
                let candidates = olcrtc_source_candidates(n, self.env.olcrtc_binary.as_deref(), self.env.olcrtc_repo.as_deref());
                self.resolve_binary(n, &candidates).ok()
            })
            .collect();
        if found.is_empty() {
            return Err(missing_olcrtc_error(&names));
        }
        Ok(found)
    }

    pub fn resolve_olcrtc_binary(&self) -> Result<PathBuf, String> {
        Ok(self.resolve_olcrtc_binary_candidates()?.remove(0))
    }

    pub fn resolve_olcrtc_data_dir(&self) -> Result<PathBuf, String> {
        let target = self.env.app_data_dir.join(OLCRTC_DATA_DIR);
        fs::create_dir_all(&target).map_err(|e| e.to_string())?;
        for f in OLCRTC_DATA_FILES {
            self.copy_data_file(f, &target)?;
        }
        Ok(target)
    }

    pub fn resolve_hev_socks5_tunnel_binary(&self) -> Result<PathBuf, String> {
        let name = hev_socks5_tunnel_file_name(self.env.os, &self.env.arch)?;
        let candidates = hev_socks5_tunnel_source_candidates(&name, self.env.hev_socks5_tunnel_binary.as_deref(), &self.roots());
        self.resolve_binary(&name, &candidates)
    }

    pub fn resolve_windows_tun2socks_binary(&self) -> Result<PathBuf, String> {
        let name = windows_tun2socks_file_name(self.env.os, &self.env.arch)?;
        let candidates = windows_tun2socks_source_candidates(&name, self.env.tun2socks_binary.as_deref(), &self.roots());
        let binary = self.resolve_binary(&name, &candidates)?;
        self.copy_runtime_asset(WINTUN_DLL)?;
        Ok(binary)
    }

    /// Best-effort: without cronet only NaiveProxy is unavailable, so the dir is returned anyway.
    pub fn resolve_cronet_library_dir(&self) -> Result<PathBuf, String> {
        let target = self.bin_dir();
        fs::create_dir_all(&target).map_err(|e| e.to_string())?;
        let _ = self.copy_runtime_asset(cronet_file_name(self.env.os));
        Ok(target)
    }

    pub fn resolve_trust_tunnel_client_binary(&self) -> Result<PathBuf, String> {
        self.resolve_binary(&trust_tunnel_file_name(self.env.os, &self.env.arch, "client")?, &[])
    }

    pub fn resolve_trust_tunnel_wizard_binary(&self) -> Result<PathBuf, String> {
        self.resolve_binary(&trust_tunnel_file_name(self.env.os, &self.env.arch, "wizard")?, &[])
    }

    pub fn resolve_openflux_binary(&self) -> Result<PathBuf, String> {
        self.resolve_binary(&openflux_file_name(self.env.os, &self.env.arch)?, &[])
    }

    /// (bridge, client).
    pub fn resolve_csqtt_binaries(&self) -> Result<(PathBuf, PathBuf), String> {
        let (bridge, client) = csqtt_file_names(self.env.os, &self.env.arch)?;
        Ok((self.resolve_binary(&bridge, &[])?, self.resolve_binary(&client, &[])?))
    }

    pub fn resolve_snolc_binary(&self) -> Result<PathBuf, String> {
        self.resolve_binary(&snolc_file_name(self.env.os, &self.env.arch)?, &[])
    }
}
