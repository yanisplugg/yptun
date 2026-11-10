//! Port of the Kotlin `UpstreamNetworkSelectorTest`.

use yptun_core::upstream::{select_upstream_index, UpstreamCandidate, UpstreamTransport};

fn candidate(
    is_active: bool,
    is_validated: bool,
    transport: UpstreamTransport,
) -> UpstreamCandidate {
    UpstreamCandidate {
        is_active,
        is_validated,
        transport,
    }
}

#[test]
fn prefers_active_unvalidated_cellular_over_inactive_validated_wifi() {
    let candidates = [
        candidate(false, true, UpstreamTransport::Wifi),
        candidate(true, false, UpstreamTransport::Cellular),
    ];

    assert_eq!(Some(1), select_upstream_index(&candidates));
}

#[test]
fn prefers_validated_when_candidates_have_same_activity_and_transport() {
    let candidates = [
        candidate(false, false, UpstreamTransport::Cellular),
        candidate(false, true, UpstreamTransport::Cellular),
    ];

    assert_eq!(Some(1), select_upstream_index(&candidates));
}

#[test]
fn wifi_beats_cellular_and_ties_keep_the_first() {
    assert_eq!(None, select_upstream_index(&[]));
    assert_eq!(
        Some(1),
        select_upstream_index(&[
            candidate(false, false, UpstreamTransport::Cellular),
            candidate(false, false, UpstreamTransport::Wifi),
            candidate(false, false, UpstreamTransport::Other),
        ])
    );
    // Kotlin's maxByOrNull: the first of equals.
    assert_eq!(
        Some(0),
        select_upstream_index(&[
            candidate(true, true, UpstreamTransport::Other),
            candidate(true, true, UpstreamTransport::Other),
        ])
    );
}
