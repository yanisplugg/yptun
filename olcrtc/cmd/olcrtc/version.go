package main

import (
	"fmt"
	"io"
	"os"
	"runtime/debug"
	"strconv"
)

const (
	// shortRevision is how much of a commit the version line names, as Go's
	// pseudo-versions do.
	shortRevision = 12
	// develVersion is what Go stamps for a build that knew no module version.
	develVersion = "(devel)"
	// The build settings Go records from the checkout a binary was built in.
	vcsRevisionKey = "vcs.revision"
	vcsModifiedKey = "vcs.modified"
)

//nolint:gochecknoglobals // Tests read what -version prints.
var versionOut io.Writer = os.Stdout

// isVersionFlag reports whether the one argument asks for the version.
func isVersionFlag(arg string) bool {
	return arg == "-version" || arg == "--version"
}

// writeVersion writes the line -version answers with.
func writeVersion(w io.Writer) error {
	if _, err := fmt.Fprintf(w, "olcrtc %s\n", buildVersion()); err != nil {
		return fmt.Errorf("print version: %w", err)
	}

	return nil
}

// buildVersion names this binary by what Go stamped into it (docs/versioning.md).
func buildVersion() string {
	info, ok := debug.ReadBuildInfo()
	if !ok {
		return describeBuild("", nil)
	}

	return describeBuild(info.Main.Version, info.Settings)
}

// describeBuild is the version of a build: the tag of a clean checkout of
// it (v0.12.0), a pseudo-version past it, which names its commit and a
// change in the tree itself (v0.12.1-0.<time>-<commit>+dirty), or (devel)
// with the commit and whether the tree had changes, when the build knew its
// commit and no version.
//
// ai-generated: this function.
func describeBuild(mainVersion string, settings []debug.BuildSetting) string {
	if mainVersion != "" && mainVersion != develVersion {
		return mainVersion
	}

	var (
		revision string
		modified bool
	)

	for _, s := range settings {
		switch s.Key {
		case vcsRevisionKey:
			revision = s.Value
		case vcsModifiedKey:
			modified, _ = strconv.ParseBool(s.Value)
		}
	}

	if revision == "" {
		return develVersion
	}

	if len(revision) > shortRevision {
		revision = revision[:shortRevision]
	}

	if modified {
		return develVersion + " " + revision + ", modified"
	}

	return develVersion + " " + revision
}
