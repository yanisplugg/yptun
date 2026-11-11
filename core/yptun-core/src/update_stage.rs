//! Which step of an app update is running (port of `UpdateStage.kt`). The UI turns it into
//! "Step N of M: ..."; progress within the step goes through the separate progress callback.

/// The kind of work a step does.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum UpdateStageKind {
    DownloadPatch,
    DownloadInstaller,
    ApplyPatch,
    Install,
    Restart,
}

/// `step` of `total`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct UpdateStage {
    pub kind: UpdateStageKind,
    pub step: i32,
    pub total: i32,
}

impl UpdateStage {
    pub fn new(kind: UpdateStageKind, step: i32, total: i32) -> Self {
        Self { kind, step, total }
    }
}
