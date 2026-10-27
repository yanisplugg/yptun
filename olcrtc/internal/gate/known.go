package gate

import (
	"errors"
	"slices"
	"strings"
)

// ai-generated: the whole file (the known failures: cells an open engine
// issue fails on every run; they still run and are reported with their
// issue, but their failures fail neither TestGate nor the app's verdict).

// ErrKnownFailure is what a known failure's run returns next to
// ErrCellFailed: the entry logs the cell instead of failing its subtest.
var ErrKnownFailure = errors.New("known failure")

// issues is where the engine's issues are. Kept when the list is empty: the
// next entry writes issues + "<number>", and a constant that comes back with
// every entry is not one to delete with the last one.
const issues = "https://github.com/ghostlane-project/olcrtc/issues/"

// KnownFailure is a cell that fails every run until the issue that tracks it
// is fixed. Cell is a cell id pattern in which "*" is exactly one whole
// segment and any other segment stands for itself; Issue is the open issue's
// URL; Why says what fails, in a few words.
type KnownFailure struct{ Cell, Issue, Why string }

// knownFailures is the list, one entry per line. Every entry needs an open
// issue, and goes once its issue is closed and its cells pass. A wbstream
// cell may be on it for an engine bug: without the WB token, or with a room
// that will not open, the server never comes up, its cells never run, and a
// cell that never ran is never known, so configuration stays blocking.
//
// ai-generated: the salutejazz entries, S2 and S3, both on #49. With the
// relay window bounding what Sber's SFU queues toward a peer, a salutejazz
// session that ends on liveness is a regression again, so S0, S1, S4, S5
// and S7 block. S2 and S3 each saturate one direction and pass on a fast
// Sber leg (S3 at 6 Mbit/s on ca3e856a), but a GitHub runner's leg is often
// slow. S2, download: at 26-62 kB/s delivered and a 3-7 s echo round trip
// its pulls ended on a liveness close before the window was sized for the
// leg. S3, upload: dropped from this list after passing on 7b78fd4a and in
// the Ghostlane 1.0.440 release gate, then failed twice on 85581a3a at
// 55-59 kB/s up with a 1.7-5.3 s echo round trip - no pong missed, but under
// the 1.6 Mbit/s floor, with a connect on top waiting 12-13 s behind the
// pushes. Both stay on #49 until on-top traffic stops waiting behind a
// window of bulk data.
var knownFailures = []KnownFailure{ //nolint:gochecknoglobals // edited by hand as issues open and close; tests swap it
	{Cell: "engine-linux/salutejazz/datachannel/*/S2", Issue: issues + "49",
		Why: "liveness close, throughput and on-top latency on a slow Sber leg"},
	{Cell: "engine-linux/salutejazz/datachannel/*/S3", Issue: issues + "49",
		Why: "upload throughput and on-top latency on a slow Sber leg"},
}

// knownFailure is the entry of the list a cell id matches, the first one if
// several do.
func knownFailure(cellID string) (KnownFailure, bool) {
	for _, k := range knownFailures {
		if cellMatches(k.Cell, cellID) {
			return k, true
		}
	}
	return KnownFailure{}, false
}

// cellMatches says whether a cell id fits a pattern: as many segments, each
// one the pattern's own or under a "*" there. A "*" is a whole segment: it
// matches neither part of one nor several, nor an empty one.
func cellMatches(pattern, cellID string) bool {
	return slices.EqualFunc(strings.Split(pattern, "/"), strings.Split(cellID, "/"), func(p, s string) bool {
		return s != "" && (p == "*" || p == s)
	})
}
