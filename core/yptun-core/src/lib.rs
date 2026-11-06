//! YPtun client core: everything the apps share, written once — share links, subscriptions,
//! panel link families, config generation. Each platform app (Kotlin on Android, Swift on
//! iOS/macOS, Rust on Windows/Linux) is a thin UI over this crate.

pub mod awg;
pub mod core_versions;
pub mod crypto;
pub mod identity;
pub mod importer;
pub mod installer;
pub mod isotime;
pub mod location;
pub mod model;
pub mod panel;
pub mod repository;
pub mod routing;
pub mod session;
pub mod settings;
pub mod singbox;
pub mod snolc;
pub mod share;
pub mod share_link;
pub mod subscription;
pub mod transports;
pub mod update;
pub mod upstream;
pub mod uri;
pub mod util;
pub mod vkturn;
pub mod warp;
pub mod xray;
