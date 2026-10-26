package main

import (
	"bytes"
	"context"
	"runtime/debug"
	"strings"
	"testing"

	"github.com/openlibrecommunity/olcrtc/internal/app/session"
)

// ai-generated: what a build says it is, from what Go stamped into it.
func TestDescribeBuildNamesTheVersionTheBinaryWasBuiltFrom(t *testing.T) {
	const rev = "b6cb4c417d17dfb7b9b780d7c37280df02e5dc6e"
	for _, tc := range []struct {
		name     string
		version  string
		settings []debug.BuildSetting
		want     string
	}{
		{"a clean checkout of a tag", "v0.12.0",
			[]debug.BuildSetting{{Key: vcsRevisionKey, Value: rev}, {Key: vcsModifiedKey, Value: "false"}},
			"v0.12.0"},
		{"past a tag: the pseudo-version already names the commit", "v0.12.1-0.20261019030000-b6cb4c417d17+dirty",
			[]debug.BuildSetting{{Key: vcsRevisionKey, Value: rev}, {Key: vcsModifiedKey, Value: "true"}},
			"v0.12.1-0.20261019030000-b6cb4c417d17+dirty"},
		{"no module version, a commit", develVersion,
			[]debug.BuildSetting{{Key: vcsRevisionKey, Value: rev}, {Key: vcsModifiedKey, Value: "false"}},
			"(devel) b6cb4c417d17"},
		{"no module version, a commit with changes", develVersion,
			[]debug.BuildSetting{{Key: vcsRevisionKey, Value: rev}, {Key: vcsModifiedKey, Value: "true"}},
			"(devel) b6cb4c417d17, modified"},
		{"nothing stamped at all", "", nil, develVersion},
	} {
		if got := describeBuild(tc.version, tc.settings); got != tc.want {
			t.Errorf("%s: describeBuild(%q) = %q, want %q", tc.name, tc.version, got, tc.want)
		}
	}
}

// ai-generated: -version answers and runs nothing; any other second word is still the usage error.
func TestRunWithArgsPrintsTheVersionAndRunsNothing(t *testing.T) {
	for _, arg := range []string{"-version", "--version"} {
		var out bytes.Buffer
		prev := versionOut
		versionOut = &out
		called := false
		prevRun := runSession
		runSession = func(_ context.Context, _ session.Config) error { called = true; return nil }
		err := runWithArgs([]string{arg})
		versionOut, runSession = prev, prevRun
		if err != nil {
			t.Fatalf("runWithArgs(%s) = %v, want nil", arg, err)
		}
		if called {
			t.Fatalf("runWithArgs(%s) ran a session", arg)
		}
		if line := out.String(); !strings.HasPrefix(line, "olcrtc ") || !strings.HasSuffix(line, "\n") || strings.Count(line, "\n") != 1 {
			t.Fatalf("runWithArgs(%s) printed %q, want one line starting with \"olcrtc \"", arg, line)
		}
	}
}
