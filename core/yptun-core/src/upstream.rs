//! Which underlying network the VPN should ride on when several are up, ported from the Kotlin
//! `UpstreamNetworkSelector`: the active network first, then a validated one, Wi-Fi over cellular.

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum UpstreamTransport {
    Wifi,
    Cellular,
    Other,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct UpstreamCandidate {
    /// The system's default network.
    pub is_active: bool,
    /// The system confirmed it reaches the internet.
    pub is_validated: bool,
    pub transport: UpstreamTransport,
}

/// Index of the best candidate (the first one on a tie), `None` for an empty list.
pub fn select_upstream_index(candidates: &[UpstreamCandidate]) -> Option<usize> {
    let mut best: Option<(usize, i32)> = None;
    for (index, candidate) in candidates.iter().enumerate() {
        let score = priority(candidate);
        if best.is_none_or(|(_, top)| score > top) {
            best = Some((index, score));
        }
    }
    best.map(|(index, _)| index)
}

fn priority(candidate: &UpstreamCandidate) -> i32 {
    let mut score = 0;
    if candidate.is_active {
        score += 100;
    }
    if candidate.is_validated {
        score += 50;
    }
    score
        + match candidate.transport {
            UpstreamTransport::Wifi => 20,
            UpstreamTransport::Cellular => 10,
            UpstreamTransport::Other => 0,
        }
}
