//! Typed UniFFI surface shared by the native iOS and macOS clients.
//!
//! Swift owns NetworkExtension and engine calls. Rust owns the deterministic connection state
//! machine, so app, widget, and packet-tunnel entry points all make the same reconnect decisions.

use std::sync::{Arc, Mutex, MutexGuard};

use yptun_core::engine::machine::{Action, ConnectionMachine, Event, RetryPolicy, Status};

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum AppleStatusKind {
    Disconnected,
    Connecting,
    Connected,
    Reconnecting,
    Stopping,
    NeedsUser,
    Error,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AppleConnectionStatus {
    pub kind: AppleStatusKind,
    pub attempt: u32,
    pub message: Option<String>,
}

impl From<&Status> for AppleConnectionStatus {
    fn from(status: &Status) -> Self {
        match status {
            Status::Disconnected => Self::plain(AppleStatusKind::Disconnected),
            Status::Connecting => Self::plain(AppleStatusKind::Connecting),
            Status::Connected => Self::plain(AppleStatusKind::Connected),
            Status::Reconnecting { attempt } => Self {
                kind: AppleStatusKind::Reconnecting,
                attempt: *attempt,
                message: None,
            },
            Status::Stopping => Self::plain(AppleStatusKind::Stopping),
            Status::NeedsUser => Self::plain(AppleStatusKind::NeedsUser),
            Status::Error(message) => Self {
                kind: AppleStatusKind::Error,
                attempt: 0,
                message: Some(message.clone()),
            },
        }
    }
}

impl AppleConnectionStatus {
    fn plain(kind: AppleStatusKind) -> Self {
        Self {
            kind,
            attempt: 0,
            message: None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum AppleActionKind {
    StartEngines,
    StopEngines,
    ScheduleRetry,
    CancelRetry,
    RequestVkLink,
    PublishStatus,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AppleConnectionAction {
    pub kind: AppleActionKind,
    pub delay_ms: Option<u64>,
    pub status: Option<AppleConnectionStatus>,
}

impl From<Action> for AppleConnectionAction {
    fn from(action: Action) -> Self {
        match action {
            Action::StartEngines => Self::plain(AppleActionKind::StartEngines),
            Action::StopEngines => Self::plain(AppleActionKind::StopEngines),
            Action::ScheduleRetry { delay_ms } => Self {
                kind: AppleActionKind::ScheduleRetry,
                delay_ms: Some(delay_ms),
                status: None,
            },
            Action::CancelRetry => Self::plain(AppleActionKind::CancelRetry),
            Action::RequestVkLink => Self::plain(AppleActionKind::RequestVkLink),
            Action::PublishStatus(status) => Self {
                kind: AppleActionKind::PublishStatus,
                delay_ms: None,
                status: Some((&status).into()),
            },
        }
    }
}

impl AppleConnectionAction {
    fn plain(kind: AppleActionKind) -> Self {
        Self {
            kind,
            delay_ms: None,
            status: None,
        }
    }
}

/// Stateful connection decisions shared by the app and packet-tunnel extension.
///
/// The object is internally synchronized because UniFFI may call it from Swift tasks on different
/// executors. Actions are returned as a batch and must be executed by Swift in array order.
#[derive(uniffi::Object)]
pub struct AppleConnectionRuntime {
    machine: Mutex<ConnectionMachine>,
}

impl AppleConnectionRuntime {
    fn lock(&self) -> MutexGuard<'_, ConnectionMachine> {
        self.machine
            .lock()
            .unwrap_or_else(|error| error.into_inner())
    }

    fn handle(&self, event: Event) -> Vec<AppleConnectionAction> {
        self.lock()
            .handle(event)
            .into_iter()
            .map(Into::into)
            .collect()
    }
}

#[uniffi::export]
impl AppleConnectionRuntime {
    #[uniffi::constructor]
    pub fn new(base_retry_ms: u64, max_retry_ms: u64, max_attempts: u32) -> Arc<Self> {
        let policy = RetryPolicy {
            base_delay_ms: base_retry_ms.max(1),
            max_delay_ms: max_retry_ms.max(base_retry_ms.max(1)),
            max_attempts,
        };
        Arc::new(Self {
            machine: Mutex::new(ConnectionMachine::new(policy)),
        })
    }

    pub fn status(&self) -> AppleConnectionStatus {
        self.lock().status().into()
    }

    pub fn start(&self) -> Vec<AppleConnectionAction> {
        self.handle(Event::Start)
    }

    pub fn stop(&self) -> Vec<AppleConnectionAction> {
        self.handle(Event::Stop)
    }

    pub fn ready(&self) -> Vec<AppleConnectionAction> {
        self.handle(Event::Ready)
    }

    pub fn start_failed(&self, message: String, fatal: bool) -> Vec<AppleConnectionAction> {
        self.handle(Event::StartFailed { message, fatal })
    }

    /// A plan requested a VK call link that only the foreground app can obtain.
    pub fn vk_link_required(&self) -> Vec<AppleConnectionAction> {
        self.handle(Event::PlanRejected(
            yptun_core::engine::plan::PlanError::VkLinkRequired,
        ))
    }

    pub fn engine_died(&self, message: String) -> Vec<AppleConnectionAction> {
        self.handle(Event::EngineDied { message })
    }

    pub fn network_changed(&self, available: bool) -> Vec<AppleConnectionAction> {
        self.handle(Event::NetworkChanged { available })
    }

    pub fn server_switched(&self) -> Vec<AppleConnectionAction> {
        self.handle(Event::ServerSwitched)
    }

    pub fn vk_link_provided(&self) -> Vec<AppleConnectionAction> {
        self.handle(Event::VkLinkProvided)
    }

    pub fn retry_timer_fired(&self) -> Vec<AppleConnectionAction> {
        self.handle(Event::RetryTimer)
    }

    pub fn engines_stopped(&self) -> Vec<AppleConnectionAction> {
        self.handle(Event::Stopped)
    }
}
