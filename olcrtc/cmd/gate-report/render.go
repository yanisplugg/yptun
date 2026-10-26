package main

import (
	"cmp"
	"fmt"
	"slices"
	"strconv"
	"strings"

	"github.com/openlibrecommunity/olcrtc/internal/gate"
)

// ai-generated: the whole file (the Markdown table of one report).

// Render writes the report as the table the job summary and the release
// show, and under it how many failures are known ones, which fail no gate.
func Render(r gate.Report) string {
	heading := fmt.Sprintf("### Gate: %s target, engine %s", r.Target, cmp.Or(short(r.EngineCommit), "unknown"))
	if r.AppVersion != "" {
		heading += ", app " + r.AppVersion
	}
	if r.DTLSProfile != "" {
		heading += ", DTLS " + r.DTLSProfile
	}
	var b strings.Builder
	_, _ = fmt.Fprintf(&b, "%s\n\n%s\n\n| Cell | Verdict | Key metrics | Took |\n| --- | --- | --- | --- |\n",
		heading, summary(r))
	cells := slices.Clone(r.Cells)
	slices.SortFunc(cells, func(a, b gate.Cell) int { return cmp.Compare(a.ID, b.ID) })
	for _, c := range cells {
		_, _ = fmt.Fprintf(&b, "| `%s` | %s | %s | %.0f s |\n", c.ID, verdict(c), keyMetrics(c), c.DurationS)
	}
	switch n := r.FailedKnown; { // ai-generated: what the known failures do to the gate
	case n == 1:
		_, _ = b.WriteString("\n1 known failure is tracked by an issue and does not fail the gate.\n")
	case n > 1:
		_, _ = fmt.Fprintf(&b, "\n%d known failures are tracked by issues and do not fail the gate.\n", n)
	}
	return b.String()
}

// summary is the line under the heading: how the cells ended, the runner and
// how long the run took. A report with no cells is not a pass.
func summary(r gate.Report) string {
	outcome := fmt.Sprintf("%d of %d cells failed", r.Failed, r.Planned)
	switch {
	case r.Planned == 0:
		outcome = "no cells were planned"
	case r.Failed == 0:
		outcome = fmt.Sprintf("all %d cells passed", r.Planned)
	}
	parts := []string{outcome}
	if r.Runner != "" {
		parts = append(parts, r.Runner)
	}
	parts = append(parts, fmt.Sprintf("%.0f s", r.DurationS))
	return strings.Join(parts, " · ")
}

// verdict is a pass mark, or a failure mark with what missed. Anything but a
// pass is a failure: a report holds no skipped cell. A known cell says so
// with its issue, pass (known: #9) or fail (known: #9).
func verdict(c gate.Cell) string {
	failures := cellText(strings.Join(c.Failures, "; "))
	switch {
	case c.Known != "" && c.Status == gate.StatusPass: // ai-generated: a cell on the engine's known list
		return "✅ pass (known: " + issueRef(c.Known) + ")"
	case c.Known != "":
		return strings.TrimSuffix("❌ fail (known: "+issueRef(c.Known)+"): "+failures, ": ")
	case c.Status == gate.StatusPass:
		return "✅"
	}
	return strings.TrimSpace("❌ " + failures)
}

// issueRef is a known cell's issue as #<number>, the number being its URL's
// last segment, linked to the URL: a bare #9 in the app's release notes would
// link the app's own issue 9. An issue that is no such URL shows as it is.
func issueRef(issue string) string {
	// ai-generated: the issue number from the URL tail.
	number := issue[strings.LastIndexByte(issue, '/')+1:]
	if _, err := strconv.ParseUint(number, 10, 32); err != nil || !strings.HasPrefix(issue, "https://") ||
		strings.ContainsAny(issue, " \t\r\n()[]<>|\\") {
		return cellText(issue)
	}
	return "[#" + number + "](" + issue + ")"
}

// cellText keeps free text inside one table cell: a pipe would end the cell,
// a line break the row, and a scrubbed <room> would read as an HTML tag and
// vanish.
func cellText(s string) string {
	return strings.NewReplacer("|", `\|`, "\r\n", " ", "\n", " ", "\r", " ", "<", "&lt;").Replace(s)
}

// keyMetrics are the numbers a person reads first, in a fixed order, each in
// its unit and only when the cell measured it. S6's 0, a client that never got
// ready, reads as the verdict words it and not as 0 ms.
func keyMetrics(c gate.Cell) string {
	const mbit, mib = 1e-6, 1.0 / (1 << 20)
	figures := []struct {
		key, format string
		scale       float64
	}{
		{gate.MetricThroughputDownBps, "↓ %.1f Mbit/s", mbit},
		{gate.MetricThroughputUpBps, "↑ %.1f Mbit/s", mbit},
		{gate.MetricConnectP95Ms, "connect p95 %.0f ms", 1},
		{gate.MetricOnTopP95Ms, "on-top p95 %.0f ms", 1},
		{gate.MetricHandshakeMs, "handshake %.0f ms", 1},
		{gate.MetricHeapPeakBytes, "heap peak %.1f MiB", mib},
		{gate.MetricRSSPeakBytes, "RSS peak %.1f MiB", mib},
		{gate.MetricMissedPong, "missed pongs %.0f", 1},
		{gate.MetricAnswered1, "burst 1 answered %.0f/64", 1},
		{gate.MetricAnswered2, "burst 2 answered %.0f/64", 1},
		{gate.MetricReady3sMs, "ready %.0f ms (bridge 3 s late)", 1},
		{gate.MetricReady8sMs, "ready %.0f ms (bridge 8 s late)", 1},
	}
	never := map[string]string{
		gate.MetricReady3sMs: "never ready (bridge 3 s late)",
		gate.MetricReady8sMs: "never ready (bridge 8 s late)",
	}
	parts := make([]string, 0, len(figures))
	for _, f := range figures {
		v, ok := c.Metrics[f.key]
		if !ok {
			continue
		}
		if text, zeroIsNever := never[f.key]; zeroIsNever && v <= 0 {
			parts = append(parts, text)
			continue
		}
		parts = append(parts, fmt.Sprintf(f.format, v*f.scale))
	}
	return strings.Join(parts, ", ")
}

// short is a commit as a release quotes it: its first 12 characters.
func short(commit string) string {
	if len(commit) > 12 {
		return commit[:12]
	}
	return commit
}
