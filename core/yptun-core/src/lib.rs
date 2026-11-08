//! YPtun client core: everything the apps share, written once — share links, subscriptions,
//! panel link families, config generation. Each platform app (Kotlin on Android, Swift on
//! iOS/macOS, Rust on Windows/Linux) is a thin UI over this crate.

pub mod awg;
pub mod core_versions;
pub mod crypto;
pub mod engine;
pub mod geo_assets;
pub mod home;
pub mod i18n;
pub mod i18n_dynamic;
pub mod identity;
pub mod importer;
pub mod installer;
pub mod isotime;
pub mod location;
pub mod location_editor;
pub mod location_list;
pub mod model;
pub mod panel;
pub mod pings;
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
pub mod vless_form;
pub mod warp;
pub mod warp_account;
pub mod xray;
