//! YPtun client core: everything the apps share, written once — share links, subscriptions,
//! panel link families, config generation. Each platform app (Kotlin on Android, Swift on
//! iOS/macOS, Rust on Windows/Linux) is a thin UI over this crate.

pub mod awg;
pub mod crypto;
pub mod location;
pub mod model;
pub mod panel;
pub mod routing;
pub mod settings;
pub mod singbox;
pub mod share;
pub mod share_link;
pub mod subscription;
pub mod transports;
pub mod uri;
pub mod vkturn;
pub mod xray;
