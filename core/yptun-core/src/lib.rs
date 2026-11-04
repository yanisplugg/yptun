//! YPtun client core: everything the apps share, written once — share links, subscriptions,
//! panel link families, config generation. Each platform app (Kotlin on Android, Swift on
//! iOS/macOS, Rust on Windows/Linux) is a thin UI over this crate.

pub mod location;
pub mod model;
pub mod share_link;
pub mod subscription;
pub mod transports;
pub mod uri;
