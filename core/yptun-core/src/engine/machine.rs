//! Connection state machine. The platform feeds it events (user pressed connect, network changed,
//! an engine died, …) and executes the returned actions; it never decides reconnects on its own.
//! This is the piece the iOS Network Extension needs to drop the Kotlin runtime: Swift glue only
//! translates `NWPathMonitor` / engine callbacks into [`Event`]s and [`Action`]s into engine calls.

use super::plan::PlanError;

/// Mirrors the Kotlin `VpnStatus` sealed class.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Status {
    Disconnected,
    Connecting,
    Connected,
    Reconnecting { attempt: u32 },
    Stopping,
    /// Waiting for the user (VK call link). From a widget the platform shows "open the app".
    NeedsUser,
    Error(String),
}

impl Status {
    pub fn is_active(&self) -> bool {
        matches!(self, Self::Connecting | Self::Connected | Self::Reconnecting { .. })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Event {
    /// User (or on-demand rule) asked to connect.
    Start,
    /// User asked to disconnect.
    Stop,
    /// Every runtime of the plan is up and its SOCKS port answers.
    Ready,
    /// Starting failed. `fatal` = retrying cannot help (unsupported engine, no proxy, …).
    StartFailed { message: String, fatal: bool },
    /// The plan itself was rejected.
    PlanRejected(PlanError),
    /// A running engine exited or its health check failed.
    EngineDied { message: String },
    /// The OS reported a network change. `available = false` means no route at all.
    NetworkChanged { available: bool },
    /// User picked another server while connected/connecting.
    ServerSwitched,
    /// The VK call link the plan was waiting for has been provided.
    VkLinkProvided,
    /// The timer from [`Action::ScheduleRetry`] fired.
    RetryTimer,
    /// All runtimes reported stopped after [`Action::StopEngines`].
    Stopped,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Action {
    /// Build the plan for the current server and raise it.
    StartEngines,
    /// Tear every runtime down (reverse start order).
    StopEngines,
    ScheduleRetry { delay_ms: u64 },
    CancelRetry,
    /// Ask the UI for a VK call link (or post the "open the app" notification).
    RequestVkLink,
    /// Publish the new status to the UI / widget / tile.
    PublishStatus(Status),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RetryPolicy {
    pub base_delay_ms: u64,
    pub max_delay_ms: u64,
    /// 0 = retry forever.
    pub max_attempts: u32,
}

impl Default for RetryPolicy {
    fn default() -> Self {
        Self { base_delay_ms: 1_000, max_delay_ms: 30_000, max_attempts: 0 }
    }
}

impl RetryPolicy {
    /// Exponential backoff: base, 2·base, 4·base, … capped at `max_delay_ms`. `attempt` starts at 1.
    pub fn delay_ms(&self, attempt: u32) -> u64 {
        let shift = attempt.saturating_sub(1).min(20);
        self.base_delay_ms.saturating_mul(1u64 << shift).min(self.max_delay_ms)
    }

    pub fn exhausted(&self, attempt: u32) -> bool {
        self.max_attempts != 0 && attempt > self.max_attempts
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum After {
    /// After the current stop finishes: do nothing.
    Idle,
    /// After the current stop finishes: start again (server switch / network back).
    Restart,
}

#[derive(Debug, Clone)]
pub struct ConnectionMachine {
    status: Status,
    policy: RetryPolicy,
    attempt: u32,
    network_available: bool,
    retry_pending: bool,
    after_stop: After,
    /// The user wants to be connected (cleared only by Stop / a fatal error).
    wanted: bool,
}

impl Default for ConnectionMachine {
    fn default() -> Self {
        Self::new(RetryPolicy::default())
    }
}

impl ConnectionMachine {
    pub fn new(policy: RetryPolicy) -> Self {
        Self {
            status: Status::Disconnected,
            policy,
            attempt: 0,
            network_available: true,
            retry_pending: false,
            after_stop: After::Idle,
            wanted: false,
        }
    }

    pub fn status(&self) -> &Status {
        &self.status
    }

    pub fn attempt(&self) -> u32 {
        self.attempt
    }

    fn set(&mut self, status: Status, out: &mut Vec<Action>) {
        if self.status != status {
            self.status = status.clone();
            out.push(Action::PublishStatus(status));
        }
    }

    fn cancel_retry(&mut self, out: &mut Vec<Action>) {
        if self.retry_pending {
            self.retry_pending = false;
            out.push(Action::CancelRetry);
        }
    }

    fn begin_start(&mut self, out: &mut Vec<Action>) {
        self.cancel_retry(out);
        if !self.network_available {
            // Nothing to connect over: wait for NetworkChanged { available: true }.
            self.attempt = self.attempt.max(1);
            self.set(Status::Reconnecting { attempt: self.attempt }, out);
            return;
        }
        let status = if self.attempt == 0 { Status::Connecting } else { Status::Reconnecting { attempt: self.attempt } };
        self.set(status, out);
        out.push(Action::StartEngines);
    }

    /// Stop the engines, then (maybe) start again once [`Event::Stopped`] arrives.
    fn stop_then(&mut self, after: After, out: &mut Vec<Action>) {
        self.cancel_retry(out);
        self.after_stop = after;
        if after == After::Idle {
            self.set(Status::Stopping, out);
        } else {
            self.attempt = self.attempt.max(1);
            self.set(Status::Reconnecting { attempt: self.attempt }, out);
        }
        out.push(Action::StopEngines);
    }

    fn schedule_retry(&mut self, message: String, out: &mut Vec<Action>) {
        self.attempt += 1;
        if self.policy.exhausted(self.attempt) {
            self.wanted = false;
            self.attempt = 0;
            self.set(Status::Error(message), out);
            return;
        }
        self.set(Status::Reconnecting { attempt: self.attempt }, out);
        if self.network_available {
            self.retry_pending = true;
            out.push(Action::ScheduleRetry { delay_ms: self.policy.delay_ms(self.attempt) });
        }
    }

    fn fail(&mut self, message: String, out: &mut Vec<Action>) {
        self.wanted = false;
        self.attempt = 0;
        self.cancel_retry(out);
        out.push(Action::StopEngines);
        self.after_stop = After::Idle;
        self.set(Status::Error(message), out);
    }

    /// Feed one event, get the actions to execute in order.
    pub fn handle(&mut self, event: Event) -> Vec<Action> {
        let mut out = Vec::new();
        match event {
            Event::Start => {
                if !self.status.is_active() && self.status != Status::Stopping {
                    self.wanted = true;
                    self.attempt = 0;
                    self.begin_start(&mut out);
                } else if self.status == Status::Stopping {
                    self.wanted = true;
                    self.after_stop = After::Restart;
                }
            }
            Event::Stop => {
                self.wanted = false;
                self.attempt = 0;
                match self.status {
                    Status::Disconnected => {}
                    Status::Error(_) | Status::NeedsUser => {
                        self.cancel_retry(&mut out);
                        self.set(Status::Disconnected, &mut out);
                    }
                    _ => self.stop_then(After::Idle, &mut out),
                }
            }
            Event::Ready => {
                if self.wanted && matches!(self.status, Status::Connecting | Status::Reconnecting { .. }) {
                    self.attempt = 0;
                    self.set(Status::Connected, &mut out);
                }
            }
            Event::StartFailed { message, fatal } => {
                if !self.wanted {
                    return out;
                }
                if fatal {
                    self.fail(message, &mut out);
                } else {
                    out.push(Action::StopEngines);
                    self.after_stop = After::Idle;
                    self.schedule_retry(message, &mut out);
                }
            }
            Event::PlanRejected(err) => {
                if !self.wanted {
                    return out;
                }
                if err == PlanError::VkLinkRequired {
                    self.cancel_retry(&mut out);
                    self.set(Status::NeedsUser, &mut out);
                    out.push(Action::RequestVkLink);
                } else {
                    self.fail(err.message(), &mut out);
                }
            }
            Event::EngineDied { message } => {
                if self.wanted && self.status.is_active() {
                    out.push(Action::StopEngines);
                    self.after_stop = After::Idle;
                    self.schedule_retry(message, &mut out);
                }
            }
            Event::NetworkChanged { available } => {
                let was = self.network_available;
                self.network_available = available;
                if !self.wanted {
                    return out;
                }
                if !available {
                    // Keep the engines; there is nothing to reconnect over yet.
                    self.cancel_retry(&mut out);
                    if self.status == Status::Connected {
                        self.attempt = 1;
                        self.set(Status::Reconnecting { attempt: 1 }, &mut out);
                    }
                } else if matches!(self.status, Status::Connected | Status::Reconnecting { .. }) || !was {
                    // A new path (Wi-Fi ↔ cellular) breaks every socket: restart the plan.
                    self.stop_then(After::Restart, &mut out);
                }
            }
            Event::ServerSwitched => {
                if self.wanted && self.status != Status::Stopping {
                    self.attempt = 0;
                    if matches!(self.status, Status::NeedsUser | Status::Error(_)) {
                        self.begin_start(&mut out);
                    } else {
                        self.after_stop = After::Restart;
                        self.cancel_retry(&mut out);
                        self.set(Status::Connecting, &mut out);
                        out.push(Action::StopEngines);
                    }
                }
            }
            Event::VkLinkProvided => {
                if self.wanted && self.status == Status::NeedsUser {
                    self.attempt = 0;
                    self.begin_start(&mut out);
                }
            }
            Event::RetryTimer => {
                if self.retry_pending {
                    self.retry_pending = false;
                    if self.wanted {
                        self.begin_start(&mut out);
                    }
                }
            }
            Event::Stopped => {
                let after = std::mem::replace(&mut self.after_stop, After::Idle);
                if after == After::Restart && self.wanted {
                    self.begin_start(&mut out);
                } else if self.status == Status::Stopping {
                    self.set(Status::Disconnected, &mut out);
                }
            }
        }
        out
    }
}
