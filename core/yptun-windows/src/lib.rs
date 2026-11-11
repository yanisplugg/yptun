//! Native host used by the C# / WinUI 3 application.
//!
//! The ABI intentionally exposes one JSON command function instead of mirroring hundreds of Rust
//! types in C#. JSON is not on the traffic path: it carries only profiles, settings and state. The
//! VPN engines and packet flow stay inside `yptuncore` and `yptun-core`.

mod app;
mod ffi;
mod http;
mod logging;
mod proxy_windows;
mod runtime;
mod sidecars;

pub use app::WindowsApp;
pub use ffi::*;
pub use yptun_core::desktop_api::{CommandRequest, CoreSnapshot};
