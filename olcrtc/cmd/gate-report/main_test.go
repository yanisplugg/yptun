package main

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/gate"
)

// ai-generated: whole file, unit cover for the command line: arguments,
// exit codes, and reading what the gate's recorder writes.

// writeReport stores r as a report file and returns its path.
func writeReport(t *testing.T, name string, r gate.Report) string {
	t.Helper()
	raw, err := json.Marshal(r)
	if err != nil {
		t.Fatal(err)
	}
	return writeFile(t, name, raw)
}

// writeFile stores raw under name in a fresh directory.
func writeFile(t *testing.T, name string, raw []byte) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), name)
	if err := os.WriteFile(path, raw, 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

// cli runs the command and returns its exit code, stdout and stderr.
func cli(args ...string) (int, string, string) {
	var stdout, stderr bytes.Buffer
	code := run(args, &stdout, &stderr)
	return code, stdout.String(), stderr.String()
}

func TestRunRenderReadsWhatTheRecorderWrites(t *testing.T) {
	rec := gate.NewRecorder(gate.Report{EngineCommit: "0123456789abcdef", Target: "local", Runner: "test"})
	rec.Plan([]gate.Cell{{ID: "engine-linux/jitsi/datachannel/cli/S0"}, {ID: "engine-linux/jitsi/datachannel/cli/S1"}})
	rec.Finish("engine-linux/jitsi/datachannel/cli/S0", gate.Metrics{gate.MetricHandshakeMs: 1800}, nil, nil, "",
		3*time.Second)
	path := filepath.Join(t.TempDir(), "gate-report.json")
	if err := rec.Write(path); err != nil {
		t.Fatal(err)
	}
	code, stdout, stderr := cli("render", path)
	for _, want := range []string{"engine 0123456789ab\n", "1 of 2 cells failed", "handshake 1800 ms",
		"| `engine-linux/jitsi/datachannel/cli/S1` | ❌ did not run |"} {
		if !strings.Contains(stdout, want) {
			t.Fatalf("stdout lacks %q:\n%s", want, stdout)
		}
	}
	if code != 0 || stderr != "" {
		t.Fatalf("code %d, stderr %q", code, stderr)
	}
}

func TestRunCompareExitCodes(t *testing.T) {
	prev := writeReport(t, "prev.json", sampleReport())
	regressed := sampleReport()
	regressed.Cells[0].Metrics[gate.MetricThroughputDownBps] = 3_000_000
	cur := writeReport(t, "cur.json", regressed)
	for _, tc := range []struct {
		name       string
		args       []string
		code       int
		regression bool
	}{
		{"warn by default", []string{"compare", prev, cur}, 0, true},
		{"warn", []string{"compare", "-severity", "warn", prev, cur}, 0, true},
		{"fail", []string{"compare", "-severity", "fail", prev, cur}, 2, true},
		{"fail after the paths", []string{"compare", prev, cur, "-severity=fail"}, 2, true},
		{"fail between the paths", []string{"compare", prev, "-severity", "fail", cur}, 2, true},
		{"fail without a regression", []string{"compare", "-severity=fail", prev, prev}, 0, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			code, stdout, stderr := cli(tc.args...)
			if code != tc.code || strings.Contains(stdout, "REGRESSION") != tc.regression || stderr != "" {
				t.Fatalf("code %d (want %d), stderr %q, stdout:\n%s", code, tc.code, stderr, stdout)
			}
		})
	}
}

func TestRunUsage(t *testing.T) {
	r := writeReport(t, "r.json", sampleReport())
	for _, args := range [][]string{
		nil,
		{"draw", r},
		{"render"},
		{"render", r, r},
		{"render", "-h"},
		{"compare", "-h"},
		{"compare", r},
		{"compare", r, r, r},
		{"compare", "-severity", "loud", r, r},
		{"compare", "-strict", r, r},
		{"compare", r, r, "-severity"},
	} {
		code, stdout, stderr := cli(args...)
		if code != 64 || stdout != "" || !strings.Contains(stderr, "usage: gate-report") {
			t.Fatalf("%q: code %d, stdout %q, stderr %q", args, code, stdout, stderr)
		}
	}
}

func TestRunRefusesWhatIsNotAReport(t *testing.T) {
	good := writeReport(t, "good.json", sampleReport())
	newer := sampleReport()
	newer.Schema = 2
	for name, path := range map[string]string{
		"missing":    filepath.Join(t.TempDir(), "absent.json"),
		"not json":   writeFile(t, "log.txt", []byte("jitsi: joining\n")),
		"no schema":  writeFile(t, "other.json", []byte(`{"cells": []}`)),
		"new schema": writeReport(t, "newer.json", newer),
	} {
		for _, args := range [][]string{{"render", path}, {"compare", path, good}, {"compare", good, path}} {
			code, stdout, stderr := cli(args...)
			if code != 1 || stdout != "" || !strings.HasPrefix(stderr, "gate-report: ") {
				t.Fatalf("%s %q: code %d, stdout %q, stderr %q", name, args, code, stdout, stderr)
			}
		}
	}
}
