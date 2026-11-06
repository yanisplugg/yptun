//! Shared by the installer tests: a scripted in-memory VPS behind the [`Ssh`] trait, and a shell
//! syntax check for the generated scripts.
#![allow(dead_code)]

use std::cell::RefCell;
use std::collections::HashMap;
use std::io::Write;
use std::process::{Command, Stdio};

use base64::Engine as _;
use yptun_core::installer::ssh::{Ssh, SshAuth, SshError, SshLogin, SshOutput};

/// `bash -n` over `script` (fed on stdin). Returns None when bash is not installed.
pub fn bash_syntax_error(script: &str) -> Option<Option<String>> {
    let mut child = Command::new("bash")
        .arg("-n")
        .stdin(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .ok()?;
    child
        .stdin
        .take()
        .unwrap()
        .write_all(script.as_bytes())
        .unwrap();
    let out = child.wait_with_output().unwrap();
    Some((!out.status.success()).then(|| String::from_utf8_lossy(&out.stderr).into_owned()))
}

/// Asserts `script` parses as bash (skipped without bash).
pub fn assert_bash_syntax(name: &str, script: &str) {
    if let Some(Some(err)) = bash_syntax_error(script) {
        panic!("{name}: bash -n failed: {err}");
    }
}

/// One recorded call.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Call {
    Exec {
        login: SshLogin,
        verbose: bool,
        command: String,
    },
    Sftp {
        login: SshLogin,
        path: String,
        size: usize,
    },
}

#[derive(Default)]
pub struct VpsState {
    pub machine: String,
    pub auth_fail: bool,
    pub bad_key: bool,
    pub sftp_fails: bool,
    pub no_sha256: bool,
    /// Every checksum probe answers with a wrong hash.
    pub corrupt: bool,
    /// (needle, exit code, output): the first rule whose needle the command contains answers it.
    pub rules: Vec<(String, i32, String)>,
    pub files: HashMap<String, Vec<u8>>,
    pub calls: Vec<Call>,
}

/// A VPS that understands the commands the installers send (`uname -m`, the upload chunks and the
/// checksum probe) and answers everything else from [`VpsState::rules`].
pub struct FakeVps(pub RefCell<VpsState>);

impl FakeVps {
    pub fn new() -> Self {
        FakeVps(RefCell::new(VpsState {
            machine: "x86_64".into(),
            ..Default::default()
        }))
    }

    pub fn with(f: impl FnOnce(&mut VpsState)) -> Self {
        let vps = Self::new();
        f(&mut vps.0.borrow_mut());
        vps
    }

    pub fn rule(self, needle: &str, code: i32, output: &str) -> Self {
        self.0
            .borrow_mut()
            .rules
            .push((needle.into(), code, output.into()));
        self
    }

    pub fn calls(&self) -> Vec<Call> {
        self.0.borrow().calls.clone()
    }

    pub fn commands(&self) -> Vec<String> {
        self.calls()
            .into_iter()
            .filter_map(|c| match c {
                Call::Exec { command, .. } => Some(command),
                Call::Sftp { .. } => None,
            })
            .collect()
    }

    pub fn file(&self, path: &str) -> Option<Vec<u8>> {
        self.0.borrow().files.get(path).cloned()
    }

    fn connect(&self, login: &SshLogin, ssh_log: Option<&dyn Fn(&str)>) -> Result<(), SshError> {
        let st = self.0.borrow();
        if st.bad_key && matches!(login.auth, SshAuth::Key { .. }) {
            return Err(SshError::Key("invalid privatekey".into()));
        }
        if let Some(log) = ssh_log {
            log("negotiation");
        }
        if st.auth_fail {
            return Err(SshError::Auth(
                "Auth fail for methods 'publickey,password'".into(),
            ));
        }
        Ok(())
    }
}

/// The shell-single-quoted word that starts right after `after` (which ends with the opening quote),
/// with `'\''` escapes undone.
fn quoted(cmd: &str, after: &str) -> String {
    let mut rest = &cmd[cmd.find(after).unwrap() + after.len()..];
    let mut out = String::new();
    loop {
        let j = rest.find('\'').unwrap();
        out.push_str(&rest[..j]);
        match rest[j..].strip_prefix(r"'\''") {
            Some(more) => {
                out.push('\'');
                rest = more;
            }
            None => return out,
        }
    }
}

impl Ssh for FakeVps {
    fn exec(
        &self,
        login: &SshLogin,
        command: &str,
        ssh_log: Option<&dyn Fn(&str)>,
    ) -> Result<SshOutput, SshError> {
        self.connect(login, ssh_log)?;
        let mut st = self.0.borrow_mut();
        st.calls.push(Call::Exec {
            login: login.clone(),
            verbose: ssh_log.is_some(),
            command: command.into(),
        });
        let ok = |output: String| {
            Ok(SshOutput {
                exit_code: 0,
                output,
            })
        };
        if command == "uname -m" {
            return ok(format!("{}\n", st.machine));
        }
        if command.starts_with("printf '%s' '") && command.contains("| base64 -d >> '") {
            let part = base64::engine::general_purpose::STANDARD
                .decode(quoted(command, "printf '%s' '"))
                .unwrap();
            st.files
                .entry(quoted(command, "| base64 -d >> '"))
                .or_default()
                .extend(part);
            return ok(String::new());
        }
        if command.starts_with("if command -v sha256sum") {
            let data = st
                .files
                .get(&quoted(command, "sha256sum < '"))
                .cloned()
                .unwrap_or_default();
            if st.corrupt {
                return ok("deadbeef\n".into());
            }
            return ok(if st.no_sha256 {
                format!("size:  {}\n", data.len())
            } else {
                format!("{}\n", sha256_hex(&data))
            });
        }
        if command.starts_with(": > '") {
            st.files.insert(quoted(command, ": > '"), Vec::new());
            return ok(String::new());
        }
        for (needle, code, output) in &st.rules {
            if command.contains(needle.as_str()) {
                return Ok(SshOutput {
                    exit_code: *code,
                    output: output.clone(),
                });
            }
        }
        ok(String::new())
    }

    fn sftp_put(&self, login: &SshLogin, data: &[u8], remote_path: &str) -> Result<(), SshError> {
        self.connect(login, None)?;
        let mut st = self.0.borrow_mut();
        if st.sftp_fails {
            return Err(SshError::Other("sftp subsystem unavailable".into()));
        }
        st.calls.push(Call::Sftp {
            login: login.clone(),
            path: remote_path.into(),
            size: data.len(),
        });
        st.files.insert(remote_path.into(), data.to_vec());
        Ok(())
    }
}

pub fn sha256_hex(data: &[u8]) -> String {
    use sha2::Digest;
    sha2::Sha256::digest(data)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

/// A gzip-looking payload of `len` bytes (passed through untouched by the installers).
pub fn fake_gz(len: usize) -> Vec<u8> {
    let mut out: Vec<u8> = (0..len).map(|i| (i * 7 % 251) as u8).collect();
    out[..2].copy_from_slice(&[0x1f, 0x8b]);
    out
}

/// Binaries for every path that ends in `.gz`.
pub fn any_binary(len: usize) -> impl Fn(&str) -> Option<Vec<u8>> {
    move |p: &str| p.ends_with(".gz").then(|| fake_gz(len))
}

/// Collects the progress log.
#[derive(Default)]
pub struct Log(pub RefCell<Vec<String>>);

impl Log {
    pub fn push(&self, m: &str) {
        self.0.borrow_mut().push(m.into());
    }
    pub fn lines(&self) -> Vec<String> {
        self.0.borrow().clone()
    }
    pub fn text(&self) -> String {
        self.lines().join("\n")
    }
}
