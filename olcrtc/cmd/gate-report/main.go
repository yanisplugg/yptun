// Package main provides gate-report, which renders a gate report as Markdown
// and compares two of them for regressions:
//
//	gate-report render <report.json>
//	gate-report compare [-severity warn|fail] <previous.json> <current.json>
//
// render writes the table the job summary and the release body show. compare
// writes the deltas and prints every regression; it exits 2 on one when
// -severity is fail, and 0 when it is warn, the default. The flag may also
// follow the two paths.
package main

import (
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"github.com/openlibrecommunity/olcrtc/internal/gate"
)

// ai-generated: the whole file (the command line around render and compare).

// Exit codes.
const (
	exitOK         = 0
	exitError      = 1  // a report could not be read or the output written
	exitRegression = 2  // compare found a regression at severity fail
	exitUsage      = 64 // the command line was wrong (EX_USAGE)
)

// Subcommands.
const (
	cmdRender  = "render"
	cmdCompare = "compare"
)

const usageText = "usage: gate-report render <report.json>\n" +
	"       gate-report compare [-severity warn|fail] <previous.json> <current.json>"

var (
	// ErrUsage is returned for a command line the command cannot run.
	ErrUsage = errors.New("usage")
	// ErrSchema is returned for a report of a schema this command cannot read.
	ErrSchema = errors.New("unsupported report schema")
)

func main() {
	os.Exit(run(os.Args[1:], os.Stdout, os.Stderr))
}

// run is the command without the process around it: it writes to the writers
// it is given and returns the exit code.
func run(args []string, stdout, stderr io.Writer) int {
	out, failRun, err := dispatch(args, stderr)
	switch {
	case errors.Is(err, ErrUsage):
		_, _ = fmt.Fprintln(stderr, usageText)
		return exitUsage
	case err != nil:
		_, _ = fmt.Fprintln(stderr, "gate-report:", err)
		return exitError
	}
	if _, err := io.WriteString(stdout, out); err != nil {
		_, _ = fmt.Fprintln(stderr, "gate-report: write:", err)
		return exitError
	}
	if failRun {
		return exitRegression
	}
	return exitOK
}

// dispatch runs the subcommand args name and returns its output and whether a
// regression fails the run. render takes no flag, so a dash argument such as
// -h is a usage error there, as it is for compare.
func dispatch(args []string, stderr io.Writer) (string, bool, error) {
	switch {
	case len(args) == 2 && args[0] == cmdRender && !strings.HasPrefix(args[1], "-"):
		r, err := load(args[1])
		if err != nil {
			return "", false, err
		}
		return Render(r), false, nil
	case len(args) > 0 && args[0] == cmdCompare:
		return compare(args[1:], stderr)
	}
	return "", false, ErrUsage
}

// compare reads two reports and returns their delta table, and whether it
// holds a regression at severity fail.
func compare(args []string, stderr io.Writer) (string, bool, error) {
	fs := flag.NewFlagSet(cmdCompare, flag.ContinueOnError)
	fs.SetOutput(stderr)
	fs.Usage = func() {} // run prints the usage once
	severity := fs.String("severity", severityWarn, "warn or fail: what a regression does to the exit code")
	paths, err := parseAnywhere(fs, args)
	if err != nil || len(paths) != 2 || (*severity != severityWarn && *severity != severityFail) {
		return "", false, ErrUsage
	}
	prev, err := load(paths[0])
	if err != nil {
		return "", false, err
	}
	cur, err := load(paths[1])
	if err != nil {
		return "", false, err
	}
	md, regressed := RenderDeltas(Compare(prev, cur), *severity)
	return md, regressed && *severity == severityFail, nil
}

// parseAnywhere parses fs's flags wherever they stand among the positional
// arguments and returns those in order: the flag package alone stops at the
// first positional one, and the documented call puts -severity last.
func parseAnywhere(fs *flag.FlagSet, args []string) ([]string, error) {
	var positional []string
	for {
		if err := fs.Parse(args); err != nil {
			return nil, fmt.Errorf("parse %s flags: %w", fs.Name(), err)
		}
		if fs.NArg() == 0 {
			return positional, nil
		}
		positional = append(positional, fs.Arg(0))
		args = fs.Args()[1:]
	}
}

// load reads a report of the schema this command knows. Any other file would
// render as an empty table or compare as nothing in common, and pass.
func load(path string) (gate.Report, error) {
	// Any report the command line names may be read. Clean is the check
	// gosec's G703 accepts; a nolint instead goes unused on the runs G703 skips.
	raw, err := os.ReadFile(filepath.Clean(path))
	if err != nil {
		return gate.Report{}, fmt.Errorf("read report: %w", err)
	}
	var r gate.Report
	if err := json.Unmarshal(raw, &r); err != nil {
		return gate.Report{}, fmt.Errorf("parse %s: %w", path, err)
	}
	if r.Schema != gate.ReportSchema {
		return gate.Report{}, fmt.Errorf("%s: %w %d, want %d", path, ErrSchema, r.Schema, gate.ReportSchema)
	}
	return r, nil
}
