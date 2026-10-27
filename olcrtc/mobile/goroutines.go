package mobile

import (
	"bytes"
	"runtime"
	"runtime/pprof"
	"sort"
	"strconv"
	"strings"
)

// modulePrefix is what every frame of this module's own code starts with.
const modulePrefix = "github.com/openlibrecommunity/olcrtc/"

// summaryGroups is how many groups the line names before folding the rest
// into "other". Six is enough to see a leak: a leak is one group that grows.
const summaryGroups = 6

// GoroutineSummary reports the live goroutines grouped by where they are, as
// one line.
//
// The count alone cannot say what leaks. An eight-hour trace from a phone
// ended at 704 goroutines and 7.6 MB of stacks with a tunnel carrying a dozen
// connections, which needs about a hundred, and the memory line could only
// count them. This names them: each group is one place in the code, labelled
// by the first frame that belongs to this module, or the first frame outside
// the runtime when none does, so a stuck copy loop reads as
// tunnelcore.copyOneWay and a stuck relay as client.(*Client).handleUDPAssociate.
//
//	goroutines 704: client.(*Client).tunnel 310, tunnelcore.copyOneWay 200,
//	client.(*Client).handleUDPAssociate 60, other 134
//
// It takes the goroutine profile, which stops the world for as long as the
// stacks take to walk: well under a millisecond for a few hundred goroutines.
// Once a minute from the host is the intended rate; it is not for the data
// path, and not for the memory trace's four samples a second.
func GoroutineSummary() string {
	var buf bytes.Buffer
	profile := pprof.Lookup("goroutine")
	if profile == nil {
		return "goroutines " + strconv.Itoa(runtime.NumGoroutine()) + ": profile unavailable"
	}
	// debug=1 groups identical stacks and prints one header per group.
	if err := profile.WriteTo(&buf, 1); err != nil {
		return "goroutines " + strconv.Itoa(runtime.NumGoroutine()) + ": profile unavailable"
	}
	return summarizeGoroutineProfile(buf.String())
}

// summarizeGoroutineProfile turns a debug=1 goroutine profile into the line.
//
// The format is one header per group, "N @ pc...", followed by one
// "#\tpc\tfunc+offset\tfile:line" line per frame, innermost first, and a blank
// line. The first line is "goroutine profile: total N".
func summarizeGoroutineProfile(profile string) string {
	total := 0
	counts := map[string]int{}
	group := 0
	labelled := false
	for line := range strings.SplitSeq(profile, "\n") {
		switch {
		case strings.HasPrefix(line, "goroutine profile: total "):
			total, _ = strconv.Atoi(strings.TrimPrefix(line, "goroutine profile: total "))
		case strings.HasPrefix(line, "#"):
			if labelled || group == 0 {
				continue
			}
			if label, ok := frameLabel(line); ok {
				counts[label] += group
				labelled = true
			}
		case line == "":
			if group > 0 && !labelled {
				counts["runtime"] += group
			}
			group, labelled = 0, false
		default:
			group = groupCount(line)
		}
	}
	if group > 0 && !labelled {
		counts["runtime"] += group
	}
	return formatGoroutineSummary(total, counts)
}

// groupCount reads the count off a group header, "N @ pc...".
func groupCount(header string) int {
	count, _, found := strings.Cut(header, " @ ")
	if !found {
		return 0
	}
	n, err := strconv.Atoi(count)
	if err != nil {
		return 0
	}
	return n
}

// frameLabel names a frame line when it is worth naming: this module's own
// code, stripped to package.Func; otherwise any function outside the runtime
// and the standard library's plumbing, stripped of its host and owner. Frames
// that are only the runtime or the poller waiting are skipped, because every
// blocked goroutine has those on top and they say nothing about whose it is.
func frameLabel(line string) (string, bool) {
	fields := strings.Fields(line)
	if len(fields) < 3 {
		return "", false
	}
	fn := fields[2]
	if i := strings.LastIndex(fn, "+0x"); i > 0 {
		fn = fn[:i]
	}
	if rest, ok := strings.CutPrefix(fn, modulePrefix); ok {
		return rest[strings.LastIndex(rest, "/")+1:], true
	}
	// A third-party path starts with a host: github.com/pion/ice/v4.(*Agent).run.
	// The standard library never does, with or without a slash: net/http.(*x).y,
	// runtime.gopark.
	slash := strings.Index(fn, "/")
	if slash < 0 || !strings.Contains(fn[:slash], ".") {
		// The runtime and the poller are on top of every blocked goroutine;
		// the rest (net/http, crypto/tls) is worth naming when nothing of ours
		// is above it.
		if strings.HasPrefix(fn, "runtime.") || strings.HasPrefix(fn, "internal/") ||
			strings.HasPrefix(fn, "sync.") || strings.HasPrefix(fn, "syscall.") {
			return "", false
		}
		return fn, true
	}
	// github.com/pion/ice/v4.(*Agent).run → pion/ice/v4.(*Agent).run.
	return fn[slash+1:], true
}

// formatGoroutineSummary lays out the top groups by count, the rest as other.
func formatGoroutineSummary(total int, counts map[string]int) string {
	type group struct {
		label string
		count int
	}
	groups := make([]group, 0, len(counts))
	for label, count := range counts {
		groups = append(groups, group{label, count})
	}
	sort.Slice(groups, func(i, j int) bool {
		if groups[i].count != groups[j].count {
			return groups[i].count > groups[j].count
		}
		return groups[i].label < groups[j].label
	})
	parts := make([]string, 0, summaryGroups+1)
	other := 0
	for i, g := range groups {
		if i >= summaryGroups {
			other += g.count
			continue
		}
		parts = append(parts, g.label+" "+strconv.Itoa(g.count))
	}
	if other > 0 {
		parts = append(parts, "other "+strconv.Itoa(other))
	}
	line := "goroutines " + strconv.Itoa(total) + ":"
	if len(parts) == 0 {
		return line
	}
	return line + " " + strings.Join(parts, ", ")
}
