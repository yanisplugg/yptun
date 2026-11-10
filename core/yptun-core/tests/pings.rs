//! Ported from PingPauseTest, plus the rest of the ping pass (`LocationViewModel.refreshPings`):
//! retries, the budget, targeted refreshes, the estimate and the listeners. Virtual time became
//! real threads held at gates, so nothing here depends on how fast the machine is.

use std::sync::atomic::{AtomicI64, AtomicUsize, Ordering};
use std::sync::{mpsc, Arc, Condvar, Mutex};
use std::thread::sleep;
use std::time::{Duration, Instant};

use yptun_core::location::{LocationConfig, LocationEntry};
use yptun_core::location_list::LocationItem;
use yptun_core::model::EngineType;
use yptun_core::pings::*;
use yptun_core::share_link;

fn config(i: usize) -> LocationConfig {
    let link = format!("vless://11111111-2222-3333-4444-555555555555@h{i}.example.com:443?security=tls&type=tcp#S{i}");
    LocationConfig {
        name: format!("S{i}"),
        engine: EngineType::Standard,
        proxy: Some(share_link::parse(&link).unwrap()),
        ..LocationConfig::default()
    }
}

fn item(i: usize) -> LocationItem {
    LocationItem::from_entry(&LocationEntry::from(
        &format!("loc-{i}"),
        &config(i),
        None,
        None,
    ))
}

fn items(n: usize) -> Vec<LocationItem> {
    (1..=n).map(item).collect()
}

fn wait_until(what: &str, mut done: impl FnMut() -> bool) {
    let deadline = Instant::now() + Duration::from_secs(10);
    while !done() {
        assert!(Instant::now() < deadline, "timed out waiting for {what}");
        sleep(Duration::from_millis(2));
    }
}

/// A gate the test opens once; pings wait at it.
#[derive(Clone, Default)]
struct Gate(Arc<(Mutex<bool>, Condvar)>);

impl Gate {
    fn open(&self) {
        *self.0 .0.lock().unwrap() = true;
        self.0 .1.notify_all();
    }
    fn pass(&self) {
        let mut open = self.0 .0.lock().unwrap();
        while !*open {
            open = self.0 .1.wait(open).unwrap();
        }
    }
}

fn progress(state: PingsState) -> PingProgress {
    match state {
        PingsState::Loading(p) => p,
        other => panic!("not loading: {other:?}"),
    }
}

fn request(ping: PingFn) -> PingRequest {
    PingRequest::new(ping)
        .parallelism(1)
        .budget(Duration::from_secs(5))
}

#[test]
fn pause_holds_new_pings_and_resume_finishes_the_pass() {
    let list = items(6);
    let session = PingSession::new();
    let started = Arc::new(AtomicUsize::new(0));
    let (tx, rx) = mpsc::channel();
    let gate = Gate::default();
    let ping: PingFn = {
        let (started, tx, gate) = (started.clone(), Mutex::new(tx), gate.clone());
        Arc::new(move |_, _| {
            started.fetch_add(1, Ordering::SeqCst);
            tx.lock().unwrap().send(()).unwrap();
            gate.pass();
            Some(40)
        })
    };
    let handle = session.refresh(&list, request(ping));
    rx.recv().unwrap(); // the first ping is in flight
    session.pause();
    assert!(progress(session.state()).paused);

    gate.open(); // the first finishes, nobody starts after it
    wait_until("the first result", || {
        progress(session.state()).completed == 1
    });
    sleep(Duration::from_millis(100));
    assert_eq!(started.load(Ordering::SeqCst), 1);
    let held = progress(session.state());
    assert_eq!(held.pending_location_ids.len(), 5);
    assert_eq!(held.completed, 1);
    assert_eq!(held.total, 6);
    assert!(held.paused);
    assert_eq!(held.eta_seconds, None);

    session.resume();
    handle.wait();
    assert_eq!(started.load(Ordering::SeqCst), 6);
    assert!(matches!(session.state(), PingsState::Success { .. }));
}

#[test]
fn cancel_drops_the_pass_and_a_later_pass_is_not_skipped() {
    let list = items(4);
    let session = PingSession::new();
    let started = Arc::new(AtomicUsize::new(0));
    let (tx, rx) = mpsc::channel();
    let ping: PingFn = {
        let (started, tx) = (started.clone(), Mutex::new(tx));
        Arc::new(move |_, _| {
            started.fetch_add(1, Ordering::SeqCst);
            let _ = tx.lock().unwrap().send(());
            sleep(Duration::from_millis(100));
            Some(40)
        })
    };
    let first = session.refresh(&list, request(ping.clone()));
    rx.recv().unwrap();
    session.pause();
    session.cancel();
    assert_eq!(
        session.state(),
        PingsState::Success {
            pings: Pings::new()
        }
    );

    // The restart right after a cancel must not be skipped as "already running".
    let second = session.refresh(&list, request(ping));
    second.wait();
    first.wait();
    assert!(
        started.load(Ordering::SeqCst) >= 4,
        "restarted pass pinged everything, started={}",
        started.load(Ordering::SeqCst)
    );
    let expected: Pings = (1..=4).map(|i| (format!("loc-{i}"), Some(40))).collect();
    assert_eq!(session.state(), PingsState::Success { pings: expected });
}

#[test]
fn a_pass_reports_answers_failures_and_hands_the_results_over() {
    let list = items(4);
    let session = PingSession::new();
    let handed = Arc::new(Mutex::new(None));
    session.set_on_completed({
        let handed = handed.clone();
        move |pings| *handed.lock().unwrap() = Some(pings.clone())
    });
    let counts = Arc::new(Mutex::new(None));
    let ping: PingFn = Arc::new(|c, _| (c.name != "S2" && c.name != "S4").then_some(30));
    let req = PingRequest::new(ping).parallelism(3).on_complete({
        let counts = counts.clone();
        move |online, total| *counts.lock().unwrap() = Some((online, total))
    });
    session.refresh(&list, req).wait();
    let expected: Pings = [
        ("loc-1", Some(30)),
        ("loc-2", None),
        ("loc-3", Some(30)),
        ("loc-4", None),
    ]
    .into_iter()
    .map(|(id, ms)| (id.to_string(), ms))
    .collect();
    assert_eq!(*counts.lock().unwrap(), Some((2, 4)));
    assert_eq!(handed.lock().unwrap().as_ref(), Some(&expected));
    assert_eq!(session.pings(), expected);
    assert!(!session.is_running());
}

#[test]
fn a_targeted_refresh_keeps_the_other_results() {
    let list = items(3);
    let session = PingSession::new();
    let seeded: Pings = [("loc-1", Some(10)), ("loc-2", None), ("loc-3", Some(30))]
        .into_iter()
        .map(|(id, ms)| (id.to_string(), ms))
        .collect();
    session.seed(&seeded);
    assert_eq!(
        session.state(),
        PingsState::Success {
            pings: seeded.clone()
        }
    );

    let calls = Arc::new(AtomicUsize::new(0));
    let ping: PingFn = {
        let calls = calls.clone();
        Arc::new(move |_, _| {
            calls.fetch_add(1, Ordering::SeqCst);
            Some(22)
        })
    };
    session
        .refresh(&list, PingRequest::new(ping).targets(vec!["loc-2".into()]))
        .wait();
    assert_eq!(calls.load(Ordering::SeqCst), 1);
    let mut expected = seeded;
    expected.insert("loc-2".into(), Some(22));
    assert_eq!(session.pings(), expected);
}

#[test]
fn nothing_to_ping() {
    let session = PingSession::new();
    let never: PingFn = Arc::new(|_, _| panic!("nothing should be pinged"));
    let counts = Arc::new(Mutex::new(None));
    let record = |counts: &Arc<Mutex<Option<(usize, usize)>>>| {
        let counts = counts.clone();
        move |online, total| *counts.lock().unwrap() = Some((online, total))
    };

    // An empty list: an empty success.
    session
        .refresh(
            &[],
            PingRequest::new(never.clone()).on_complete(record(&counts)),
        )
        .wait();
    assert_eq!(*counts.lock().unwrap(), Some((0, 0)));
    assert_eq!(
        session.state(),
        PingsState::Success {
            pings: Pings::new()
        }
    );

    // Only incomplete or untargeted locations: the earlier results stay.
    let seeded: Pings = [("loc-1".to_string(), Some(5))].into_iter().collect();
    session.seed(&seeded);
    let incomplete = LocationItem {
        storage_id: "draft".into(),
        config: Some(LocationConfig::default()),
        ..Default::default()
    };
    let list = vec![item(1), incomplete];
    *counts.lock().unwrap() = None;
    let req = PingRequest::new(never)
        .targets(vec!["draft".into()])
        .on_complete(record(&counts));
    session.refresh(&list, req).wait();
    assert_eq!(*counts.lock().unwrap(), Some((0, 0)));
    assert_eq!(session.state(), PingsState::Success { pings: seeded });
}

#[test]
fn a_failed_probe_is_retried_within_the_budget() {
    let c = config(1);
    let calls = AtomicUsize::new(0);
    let third_time_lucky =
        |_: &LocationConfig, _: Duration| (calls.fetch_add(1, Ordering::SeqCst) == 2).then_some(7);
    assert_eq!(
        check_location_ping(&c, Duration::from_secs(5), &third_time_lucky),
        Some(7)
    );
    assert_eq!(calls.load(Ordering::SeqCst), 3);

    let calls = AtomicUsize::new(0);
    let never = |_: &LocationConfig, _: Duration| {
        calls.fetch_add(1, Ordering::SeqCst);
        None
    };
    assert_eq!(
        check_location_ping(&c, Duration::from_secs(5), &never),
        None
    );
    assert_eq!(
        calls.load(Ordering::SeqCst),
        LOCATION_PING_ATTEMPTS as usize
    );

    // A probe that panics is an unreachable location, not a dead worker.
    let boom = |_: &LocationConfig, _: Duration| -> Option<i64> { panic!("probe crashed") };
    assert_eq!(check_location_ping(&c, Duration::from_secs(5), &boom), None);

    // An incomplete location is never probed.
    let untouched = |_: &LocationConfig, _: Duration| -> Option<i64> { panic!("probed") };
    assert_eq!(
        check_location_ping(
            &LocationConfig::default(),
            Duration::from_secs(5),
            &untouched
        ),
        None
    );
}

#[test]
fn the_budget_cuts_a_slow_probe() {
    let budgets = Mutex::new(Vec::new());
    let slow = |_: &LocationConfig, left: Duration| {
        budgets.lock().unwrap().push(left);
        sleep(Duration::from_millis(250));
        Some(5)
    };
    assert_eq!(
        check_location_ping(&config(1), Duration::from_millis(100), &slow),
        None
    );
    let budgets = budgets.into_inner().unwrap();
    assert_eq!(budgets.len(), 1, "no retry after the budget ran out");
    assert!(budgets[0] <= Duration::from_millis(100));
}

#[test]
fn the_estimate_counts_only_unpaused_time() {
    assert_eq!(eta_seconds(10_000, 5, 5), Some(10));
    assert_eq!(eta_seconds(10_000, 4, 5), None, "too few samples");
    assert_eq!(eta_seconds(0, 5, 5), None);
    assert_eq!(eta_seconds(9_999, 10, 1), Some(0));

    let clock = Arc::new(AtomicI64::new(1_000_000));
    let session = PingSession::with_clock(Box::new({
        let clock = clock.clone();
        move || clock.load(Ordering::SeqCst)
    }));
    let gate = Gate::default();
    let calls = Arc::new(AtomicUsize::new(0));
    let ping: PingFn = {
        let (clock, gate, calls) = (clock.clone(), gate.clone(), calls.clone());
        Arc::new(move |_, _| {
            clock.fetch_add(1_000, Ordering::SeqCst);
            if calls.fetch_add(1, Ordering::SeqCst) == 5 {
                gate.pass();
            }
            Some(1)
        })
    };
    let handle = session.refresh(&items(10), request(ping));
    wait_until("five results and the sixth in flight", || {
        calls.load(Ordering::SeqCst) == 6
    });
    wait_until("five results", || progress(session.state()).completed == 5);
    // 6 s spent on 5 results, 5 to go.
    assert_eq!(progress(session.state()).eta_seconds, Some(6));

    session.pause();
    assert_eq!(progress(session.state()).eta_seconds, None);
    clock.fetch_add(60_000, Ordering::SeqCst);
    session.resume();
    assert_eq!(
        progress(session.state()).eta_seconds,
        Some(6),
        "the minute paused does not count"
    );

    gate.open();
    handle.wait();
    assert!(matches!(session.state(), PingsState::Success { .. }));
}

#[test]
fn the_listener_sees_the_pass_start_and_end() {
    let session = PingSession::new();
    let seen = Arc::new(Mutex::new(Vec::new()));
    session.set_listener({
        let seen = seen.clone();
        move |state: &PingsState| seen.lock().unwrap().push(state.clone())
    });
    let ping: PingFn = Arc::new(|_, _| Some(9));
    session
        .refresh(&items(3), PingRequest::new(ping).parallelism(1))
        .wait();
    let seen = seen.lock().unwrap().clone();
    let first = progress(seen.first().cloned().unwrap());
    assert_eq!(first.pending_location_ids.len(), 3);
    assert_eq!(first.total, 3);
    let expected: Pings = (1..=3).map(|i| (format!("loc-{i}"), Some(9))).collect();
    assert_eq!(seen.last(), Some(&PingsState::Success { pings: expected }));
}

#[test]
fn a_state_shows_its_results() {
    let pings: Pings = [("a".to_string(), Some(1))].into_iter().collect();
    assert!(PingsState::Idle.pings().is_empty());
    let loading = PingsState::Loading(PingProgress {
        last_pings: Some(pings.clone()),
        ..Default::default()
    });
    assert_eq!(loading.pings(), pings);
    assert!(loading.is_loading());
    let error = PingsState::Error {
        message: "x".into(),
        last_pings: Some(pings.clone()),
    };
    assert_eq!(error.pings(), pings);
    assert_eq!(PingSession::new().state(), PingsState::Idle);
}
