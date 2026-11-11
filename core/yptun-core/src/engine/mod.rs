//! Engine/session orchestration shared by every platform: the launch plan (which runtimes, which
//! ports, which proxy core) and the connection state machine (reconnects, network changes, server
//! switches, VK link prompts). Platforms only execute the returned steps/actions.

pub mod machine;
pub mod plan;

pub use machine::{Action, ConnectionMachine, Event, RetryPolicy, Status};
pub use plan::{
    check_supported, choose_core, forces_singbox, is_xray_typed, resolve_second, singbox_blocks_quic, topology,
    CascadeNote, CoreChoice, CoreInputs, CoreReason, Platform, PlatformCaps, PlanError, PortLayout, Step,
    Topology, XRAY_SUPPORTED_TYPES,
};
