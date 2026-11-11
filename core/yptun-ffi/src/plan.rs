//! Engine plan: what a location needs to run on a platform, as typed records.

use yptun_core::engine::plan::{self, Platform, PlatformCaps, Step};
use yptun_core::location::LocationConfig;

use crate::{from_json, FfiError};

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum TargetPlatform {
    Android,
    Ios,
    MacOs,
    Desktop,
}

impl From<TargetPlatform> for Platform {
    fn from(p: TargetPlatform) -> Self {
        match p {
            TargetPlatform::Android => Self::Android,
            TargetPlatform::Ios => Self::Ios,
            TargetPlatform::MacOs => Self::MacOs,
            TargetPlatform::Desktop => Self::Desktop,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum StepKind {
    OlcRtc,
    AmneziaWg,
    TrustTunnel,
    VkTurn,
    MasterDns,
    OpenFlux,
    Snolc,
    ProxyCore,
}

/// One runtime to start. upstream_port is set only for ProxyCore (the local hop it dials).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PlanStep {
    pub kind: StepKind,
    pub port: i32,
    pub upstream_port: Option<i32>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PlanTopology {
    pub socks_port: i32,
    /// Start order.
    pub steps: Vec<PlanStep>,
    /// Reverse start order.
    pub stop_order: Vec<PlanStep>,
    pub uses_proxy_core: bool,
}

fn step(s: &Step) -> PlanStep {
    let (kind, upstream_port) = match s {
        Step::OlcRtc { .. } => (StepKind::OlcRtc, None),
        Step::AmneziaWg { .. } => (StepKind::AmneziaWg, None),
        Step::TrustTunnel { .. } => (StepKind::TrustTunnel, None),
        Step::VkTurn { .. } => (StepKind::VkTurn, None),
        Step::MasterDns { .. } => (StepKind::MasterDns, None),
        Step::OpenFlux { .. } => (StepKind::OpenFlux, None),
        Step::Snolc { .. } => (StepKind::Snolc, None),
        Step::ProxyCore { upstream_port, .. } => (StepKind::ProxyCore, *upstream_port),
    };
    PlanStep { kind, port: s.port(), upstream_port }
}

fn caps(platform: TargetPlatform) -> PlatformCaps {
    PlatformCaps::for_platform(platform.into())
}

fn location(location_json: &str) -> Result<LocationConfig, FfiError> {
    Ok(from_json::<LocationConfig>("LocationConfig", location_json)?.normalized())
}

/// Ok when the location can run on the platform; Unsupported(message) otherwise.
#[uniffi::export]
pub fn plan_check_supported(location_json: String, platform: TargetPlatform) -> Result<(), FfiError> {
    let l = location(&location_json)?;
    plan::check_supported(&l, &caps(platform)).map_err(|e| FfiError::Unsupported(e.message()))
}

#[uniffi::export]
pub fn plan_topology(location_json: String, socks_port: i32, platform: TargetPlatform) -> Result<PlanTopology, FfiError> {
    let l = location(&location_json)?;
    let t = plan::topology(&l, socks_port, &caps(platform)).map_err(|e| FfiError::Unsupported(e.message()))?;
    Ok(PlanTopology {
        socks_port: t.ports.socks,
        steps: t.steps.iter().map(step).collect(),
        stop_order: t.stop_order().iter().map(step).collect(),
        uses_proxy_core: t.uses_proxy_core(),
    })
}
