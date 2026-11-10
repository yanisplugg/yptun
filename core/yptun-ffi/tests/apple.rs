use yptun_ffi::{AppleActionKind, AppleConnectionRuntime, AppleStatusKind};

fn kinds(actions: &[yptun_ffi::AppleConnectionAction]) -> Vec<AppleActionKind> {
    actions.iter().map(|action| action.kind).collect()
}

#[test]
fn connect_ready_stop_is_driven_by_ordered_actions() {
    let runtime = AppleConnectionRuntime::new(1_000, 30_000, 0);

    let start = runtime.start();
    assert_eq!(
        kinds(&start),
        vec![
            AppleActionKind::PublishStatus,
            AppleActionKind::StartEngines
        ]
    );
    assert_eq!(runtime.status().kind, AppleStatusKind::Connecting);

    let ready = runtime.ready();
    assert_eq!(kinds(&ready), vec![AppleActionKind::PublishStatus]);
    assert_eq!(runtime.status().kind, AppleStatusKind::Connected);

    let stop = runtime.stop();
    assert_eq!(
        kinds(&stop),
        vec![AppleActionKind::PublishStatus, AppleActionKind::StopEngines]
    );
    assert_eq!(runtime.status().kind, AppleStatusKind::Stopping);

    let stopped = runtime.engines_stopped();
    assert_eq!(kinds(&stopped), vec![AppleActionKind::PublishStatus]);
    assert_eq!(runtime.status().kind, AppleStatusKind::Disconnected);
}

#[test]
fn network_handoff_restarts_the_running_plan() {
    let runtime = AppleConnectionRuntime::new(1_000, 30_000, 0);
    runtime.start();
    runtime.ready();

    let actions = runtime.network_changed(true);
    assert_eq!(
        kinds(&actions),
        vec![AppleActionKind::PublishStatus, AppleActionKind::StopEngines]
    );
    assert_eq!(runtime.status().kind, AppleStatusKind::Reconnecting);

    let restart = runtime.engines_stopped();
    assert_eq!(kinds(&restart), vec![AppleActionKind::StartEngines]);
}

#[test]
fn retry_and_user_required_states_keep_context() {
    let runtime = AppleConnectionRuntime::new(500, 2_000, 3);
    runtime.start();

    let failure = runtime.start_failed("temporary".into(), false);
    assert_eq!(
        kinds(&failure),
        vec![
            AppleActionKind::StopEngines,
            AppleActionKind::PublishStatus,
            AppleActionKind::ScheduleRetry,
        ]
    );
    assert_eq!(failure.last().and_then(|action| action.delay_ms), Some(500));
    assert_eq!(runtime.status().attempt, 1);

    runtime.stop();
    runtime.engines_stopped();
    runtime.start();
    let needs_user = runtime.vk_link_required();
    assert_eq!(
        kinds(&needs_user),
        vec![
            AppleActionKind::PublishStatus,
            AppleActionKind::RequestVkLink
        ]
    );
    assert_eq!(runtime.status().kind, AppleStatusKind::NeedsUser);
}
