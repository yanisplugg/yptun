package gate

import (
	"cmp"
	"context"
	"errors"
	"flag"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"runtime/debug"
	"slices"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/link"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
	"github.com/openlibrecommunity/olcrtc/mobile"
	"github.com/openlibrecommunity/olcrtc/pkg/olcrtc/client"
)

// ai-generated: whole file, the gate's go test entry (TestMain writes the
// report on the way out; TestGate turns the flags and the environment into a
// target and a flavour and walks the plan) and unit cover for what it reads
// them with.

// What the entry reads from the environment. A secret comes from there and
// never from argv (amendment A7); the WB token (EnvWBStreamToken) only does.
const (
	envLink          = "OLCRTC_GATE_LINK"
	envTelemostRooms = "OLCRTC_GATE_TELEMOST_ROOMS"
	envWBStreamRooms = "OLCRTC_GATE_WBSTREAM_ROOMS"
	envVKCallsRooms  = "OLCRTC_GATE_VKCALLS_ROOMS"
	envMTSLinkRooms  = "OLCRTC_GATE_MTSLINK_ROOMS"
	envMaxRooms      = "OLCRTC_GATE_MAX_ROOMS"
	envJitsiHosts    = "OLCRTC_GATE_JITSI_HOSTS"
	envEngineCommit  = "OLCRTC_GATE_ENGINE_COMMIT"
	envEngineRef     = "OLCRTC_GATE_ENGINE_REF"
	envAppVersion    = "OLCRTC_GATE_APP_VERSION"
	envRunNumber     = "GITHUB_RUN_NUMBER"
	// ai-generated: what a GitHub runner sets and the report's runner names.
	envRunnerOS = "RUNNER_OS"
	envImageOS  = "ImageOS"
)

const (
	// allTransports is the transport flag's default: the transports the
	// real E2E job ran (amendment A1) whose cells can pass. videochannel is
	// left out: one 256-byte fragment a frame at 30 fps is about 7.5 KiB/s,
	// so S0's 10 MiB pull alone outlasts its 5 min; name it to run it. Left
	// at the default, a run keeps those the providers carry and this build
	// links; named on the command line, a transport the build does not link
	// is a plan error.
	allTransports  = "datachannel,seichannel,vp8channel"
	flagTransports = "olcrtc.gate-transports"
	// instancesFile is the repository's Jitsi instance list, the default of
	// -olcrtc.gate-jitsi-instances, from the module root.
	instancesFile = "docs/jitsi.instances.yaml"
	// reportName is the report's file in the artifacts directory.
	reportName = "gate-report.json"
	// reportMargin is how long before the go test deadline the run ends, so
	// the cell in flight can unwind and be recorded with its own reason, and
	// TestMain can write the final report, before -timeout kills the binary
	// with the cell in flight read as not run.
	reportMargin = 90 * time.Second
	// phoneMemoryLimit and phoneGCPercent are the phone's settings, which a
	// mobile run alone is under (amendment A8).
	phoneMemoryLimit = 40 << 20
	phoneGCPercent   = 10
)

var (
	gateOn         = flag.Bool("olcrtc.gate", false, "run the release gate: real relays, minutes")
	gateTargetName = flag.String("olcrtc.gate-target", targetLocal,
		"local (a child server per pair) or link (a server behind an olcrtc:// link)")
	gateLink = flag.String("olcrtc.gate-link", "",
		"olcrtc:// link for the link target; else "+envLink+", which keeps it out of the process list")
	gateDir = flag.String("olcrtc.gate-dir", "gate-artifacts",
		"where the report and the scrubbed logs go; a relative path is taken from the module root")
	gateProviders  = flag.String("olcrtc.gate-providers", strings.Join(localProviders(), ","), "providers for the local target")
	gateTransports = flag.String(flagTransports, allTransports,
		"transports for the local target; left alone, those the providers carry and this build links "+
			"(videochannel runs only when named)")
	gateClients = flag.String("olcrtc.gate-clients", ownFlavour(leanBuild),
		"client flavour, one per process: cli in a default build, mobile in an olcrtc_lean one")
	gateTelemost = flag.String("olcrtc.gate-telemost-rooms", "",
		"Telemost room pool, comma-separated ids or URLs; else "+envTelemostRooms)
	gateWBStream = flag.String("olcrtc.gate-wbstream-rooms", "",
		"WB Stream room pool, comma-separated ids or URLs; else "+envWBStreamRooms)
	gateVKCalls = flag.String("olcrtc.gate-vkcalls-rooms", "",
		"VK Calls room pool, comma-separated join links; else "+envVKCallsRooms)
	gateMTSLink = flag.String("olcrtc.gate-mtslink-rooms", "",
		"MTS Link room pool, comma-separated event join links; else "+envMTSLinkRooms)
	gateMaxRooms = flag.String("olcrtc.gate-max-rooms", "",
		"max room pool, comma-separated join links (max.ru/joincall/<hash>); else "+envMaxRooms)
	gateJitsiHosts = flag.String("olcrtc.gate-jitsi-hosts", "",
		"comma-separated Jitsi hosts to use instead of the instance list; else "+envJitsiHosts)
	gateInstances = flag.String("olcrtc.gate-jitsi-instances", instancesFile,
		"the Jitsi instance list; a relative path is taken from the module root")
	gateRunNumber = flag.Int("olcrtc.gate-run-number", -1,
		"run number that picks a pool room (default: "+envRunNumber+")")
	gateBigMB     = flag.Int64("olcrtc.gate-big-mb", 10, "size of the big pull in MiB")
	gateLinkSmall = flag.String("olcrtc.gate-link-small", "https://proofkit.org/gate/kb",
		"1 KB resource the fleet reaches")
	gateLinkBig = flag.String("olcrtc.gate-link-big", "https://proofkit.org/gate/10mb.bin",
		"resource of -olcrtc.gate-big-mb MiB the fleet reaches")
	gateLinkSink = flag.String("olcrtc.gate-link-sink", "https://speed.cloudflare.com/__up",
		"upload sink the fleet reaches")
	gateDry  = flag.Bool("olcrtc.gate-dry", false, "print the plan and run nothing")
	gateDTLS = flag.String("olcrtc.gate-dtls-profile", "",
		"dtls.profile the local servers and the client handshake with (the link target: the client alone); "+
			"empty or off is the stock handshake")
)

// gateRun is what TestMain writes on the way out: the recorder of a run that
// got past its plan, and where its report goes.
var gateRun struct {
	rec  *Recorder
	path string
}

// TestMain writes the report once more when every test has run, with the
// run's whole duration. The runner has kept it on disk after every cell, so
// a process that dies before this point still leaves one. Without
// -olcrtc.gate nothing is written.
func TestMain(m *testing.M) {
	flag.Parse()
	code := m.Run()
	if gateRun.rec != nil {
		if err := gateRun.rec.Write(gateRun.path); err != nil {
			_, _ = fmt.Fprintln(os.Stderr, "gate: write report:", err)
			code = max(code, 1)
		}
	}
	os.Exit(code)
}

// TestGate is the release gate: every cell of the plan is a subtest named
// after it, and the report goes to -olcrtc.gate-dir. A known failure
// (known.go) is logged with its issue and fails neither its subtest nor the
// gate; every other failure fails both.
func TestGate(t *testing.T) {
	if !*gateOn {
		t.Skip("the release gate is off; pass -olcrtc.gate")
	}
	root := moduleRoot(t)
	flavour, err := pickFlavour(*gateClients, leanBuild)
	if err != nil {
		t.Fatal(err)
	}
	dtlsProfile, err := DTLSProfile(*gateDTLS)
	if err != nil {
		t.Fatal(err)
	}
	target, thresholds, secrets := gateTarget(t, root, dtlsProfile, *gateDry)
	if *gateDry {
		for _, c := range PlanCells(target, []string{flavour}) {
			_, _ = fmt.Fprintln(os.Stdout, c.ID)
		}
		return
	}
	ctx := runContext(t)
	dir, err := resolveDir(root, *gateDir)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(dir, artifactsPerm); err != nil {
		t.Fatal(err)
	}
	if flavour == mobileFlavour {
		phoneSettings(t)
	}
	rec := NewRecorder(Report{
		EngineCommit: engineCommit(t, root), EngineRef: os.Getenv(envEngineRef),
		AppVersion: os.Getenv(envAppVersion), Target: target.Name(), Runner: runnerName(),
		DTLSProfile: dtlsProfile,
	})
	gateRun.rec, gateRun.path = rec, filepath.Join(dir, reportName)
	capture := StartCapture()
	defer capture.Stop()
	opt := Options{
		Target: target, Clients: []Client{flavourClient(flavour)}, Thresholds: thresholds, Dir: dir,
		Recorder: rec, Capture: capture, Secrets: &secrets, Logf: t.Logf,
		ReportPath: gateRun.path, // ai-generated: kept on disk after every cell
	}
	RunPlan(ctx, opt, func(name string, cell func() error) {
		t.Run(name, func(t *testing.T) {
			switch err := cell(); {
			case errors.Is(err, ErrKnownFailure): // ai-generated: tracked by an issue, reported, not failed
				t.Log(err)
			case err != nil:
				t.Error(err)
			}
		})
	})
	rep := rec.Report()
	for _, id := range unreported(rep) {
		t.Errorf("%s: %s", id, reasonDidNotRun)
	}
	for _, line := range knownLines(rep) {
		t.Log(line)
	}
	if why := gateFailure(rep); why != "" {
		t.Errorf("gate: %s; the report goes to %s", why, gateRun.path)
	}
}

// gateFailure is why a run's report fails the gate, or "" when it does not:
// every failed cell fails it but a known failure, which ran and is reported
// and whose issue tracks it.
func gateFailure(rep Report) string {
	// ai-generated: the gate's verdict over a report, known failures aside.
	if rep.Failed-rep.FailedKnown <= 0 {
		return ""
	}
	why := fmt.Sprintf("%d of %d cells failed", rep.Failed, rep.Planned)
	if rep.FailedKnown > 0 {
		why += fmt.Sprintf(" (%d known, tracked by issues)", rep.FailedKnown)
	}
	return why
}

// knownLines is what the gate logs of the known list: each known failure
// with its issue and what fails, and each known cell that passed, whose
// entry is due to go.
func knownLines(rep Report) []string {
	// ai-generated: the known failures of a run and the known cells that passed.
	var out []string
	for _, c := range rep.Cells {
		switch {
		case c.Known == "":
		case c.Status == StatusPass:
			out = append(out, fmt.Sprintf("known failure passed: %s (%s) - drop it from known.go once the issue is closed",
				c.ID, c.Known))
		default:
			line := fmt.Sprintf("known failure: %s (%s)", c.ID, c.Known)
			if k, ok := knownFailure(c.ID); ok {
				line += ": " + k.Why
			}
			out = append(out, line)
		}
	}
	return out
}

// ownFlavour is the flavour a build ships: the phones' runtime in the lean
// build, the CLI's client path in the default one.
func ownFlavour(lean bool) string {
	if lean {
		return mobileFlavour
	}
	return cliFlavour
}

// pickFlavour reads -olcrtc.gate-clients: one flavour, the one the build
// ships, so the phone's memory settings never weigh a cli cell and the
// mobile flavour runs in the phones' build (amendment A8). Empty is the
// build's own.
func pickFlavour(value string, lean bool) (string, error) {
	own, names := ownFlavour(lean), splitList(value)
	switch {
	case len(names) == 0:
		return own, nil
	case len(names) > 1:
		return "", fmt.Errorf("one flavour per process, not %q: cli runs in a default build, "+
			"mobile in an olcrtc_lean one", value)
	case names[0] == own:
		return own, nil
	case names[0] == mobileFlavour:
		return "", errors.New("the mobile flavour runs in the phones' build: pass -tags olcrtc_lean")
	case names[0] == cliFlavour:
		return "", errors.New("the cli flavour runs in the CLI's build: drop -tags olcrtc_lean")
	}
	return "", fmt.Errorf("unknown client flavour %q: cli or mobile", names[0])
}

// flavourClient is the client of a flavour pickFlavour accepted.
func flavourClient(flavour string) Client {
	if flavour == mobileFlavour {
		return MobileClient()
	}
	return CLIClient()
}

// pickTransports is the local target's transports. A list left at its
// default keeps, in its order, those this build links and at least one
// provider carries, so a run of one provider plans no transport it would
// refuse. A list given is kept as it is, but a transport the gate knows and
// the build does not link (the lean build has no videochannel) is a plan
// error; the target refuses the rest of what cannot run.
func pickTransports(asked []string, explicit bool, providers, linked []string) ([]string, error) {
	known := transportsOf(providerJitsi)
	if explicit {
		for _, tr := range asked {
			if slices.Contains(known, tr) && !slices.Contains(linked, tr) {
				return nil, fmt.Errorf("this build does not link %s (it links %s)", tr, strings.Join(linked, ","))
			}
		}
		return asked, nil
	}
	carried := func(tr string) bool {
		return slices.ContainsFunc(providers, func(p string) bool { return slices.Contains(transportsOf(p), tr) })
	}
	var out []string
	for _, tr := range asked {
		if slices.Contains(linked, tr) && carried(tr) {
			out = append(out, tr)
		}
	}
	return out, nil
}

// linkedTransports is what this build links, as the engine registers it.
func linkedTransports() []string {
	client.RegisterDefaults()
	return transport.Available()
}

// flagGiven says whether a flag was set on the command line.
func flagGiven(name string) bool {
	given := false
	flag.Visit(func(f *flag.Flag) { given = given || f.Name == name })
	return given
}

// gateTarget builds the target -olcrtc.gate-target names, with the
// thresholds it is judged by and the secrets it brings. A dry run starts no
// origin: it only plans.
func gateTarget(t *testing.T, root, dtlsProfile string, dry bool) (Target, Thresholds, []string) {
	t.Helper()
	switch *gateTargetName {
	case targetLocal:
		lt, secrets := localTarget(t, root, dtlsProfile, dry)
		return lt, Local, secrets
	case targetLink:
		lk, secrets := linkTarget(t)
		return lk.WithDTLSProfile(dtlsProfile), Link, secrets
	}
	t.Fatalf("unknown target %q: local or link", *gateTargetName)
	return nil, Thresholds{}, nil
}

// localTarget is a child server per pair. Its private directory, the server
// binary and each server's YAML and raw log, is a temporary one that no
// artifact upload reaches (amendment A9).
func localTarget(t *testing.T, root, dtlsProfile string, dry bool) (*LocalTarget, []string) {
	t.Helper()
	providers := splitList(*gateProviders)
	transports, err := pickTransports(splitList(*gateTransports), flagGiven(flagTransports), providers,
		linkedTransports())
	if err != nil {
		t.Fatal(err)
	}
	opts := LocalOptions{
		ModuleRoot: root, WorkDir: t.TempDir(),
		Instances:     instanceList(root, *gateInstances),
		JitsiHosts:    listOf(*gateJitsiHosts, os.Getenv(envJitsiHosts)),
		TelemostRooms: listOf(*gateTelemost, os.Getenv(envTelemostRooms)),
		WBStreamRooms: listOf(*gateWBStream, os.Getenv(envWBStreamRooms)),
		VKCallsRooms:  listOf(*gateVKCalls, os.Getenv(envVKCallsRooms)),
		MTSLinkRooms:  listOf(*gateMTSLink, os.Getenv(envMTSLinkRooms)),
		MaxRooms:      listOf(*gateMaxRooms, os.Getenv(envMaxRooms)),
		// ai-generated: trimmed as the WB token is, one stray line break
		// and the pair joins under half a key.
		MaxKeys: maxKeys(os.Getenv(EnvMaxKeys)),
		// ai-generated: trimmed, as the CI's require step reads it: a line
		// break stored after the token would reach the server's YAML.
		WBStreamToken: strings.TrimSpace(os.Getenv(EnvWBStreamToken)),
		RunNumber:     runNumber(*gateRunNumber, os.Getenv(envRunNumber)),
		Providers:     providers, Transports: transports, DNS: defaultDNS,
		DTLSProfile: dtlsProfile,
	}
	if !dry {
		opts.Origin = startOrigin(t)
	}
	lt, err := NewLocalTarget(opts)
	if err != nil {
		t.Fatal(err)
	}
	return lt, localSecrets(opts)
}

// startOrigin serves the local load for the test's length.
func startOrigin(t *testing.T) *Origin {
	t.Helper()
	if *gateBigMB <= 0 {
		t.Fatalf("-olcrtc.gate-big-mb %d: the big pull needs a size", *gateBigMB)
	}
	origin, err := StartOrigin(*gateBigMB << 20)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(origin.Close)
	return origin
}

// localSecrets is what a local run is handed that its logs and report must
// lose: the WB token, each pool entry as written and in the form its
// provider joins by, and the hosts of a Jitsi override, which come from a
// secret too, in every form a log writes them (see hostForms). The instance
// list is public.
func localSecrets(o LocalOptions) []string {
	out := []string{o.WBStreamToken}
	for _, e := range o.TelemostRooms {
		out = append(out, e, TelemostURL(e))
	}
	for _, e := range o.WBStreamRooms {
		out = append(out, e, WBStreamRoomID(e))
	}
	return append(out, hostForms(jitsiHostList(o.JitsiHosts))...) // ai-generated: bare and lowercased too
}

// linkTarget is the server behind the link in -olcrtc.gate-link or, better,
// the environment. A link the build cannot carry is a plan error.
func linkTarget(t *testing.T) (*LinkTarget, []string) {
	t.Helper()
	raw := strings.TrimSpace(cmp.Or(*gateLink, os.Getenv(envLink)))
	if raw == "" {
		t.Fatalf("the link target needs a link: put it in %s", envLink)
	}
	l, err := link.Parse(raw) // its errors never quote the line
	if err != nil {
		t.Fatalf("link target: %v", err)
	}
	if !slices.Contains(linkedTransports(), l.Transport) {
		t.Fatalf("link target: this build does not link %s", l.Transport)
	}
	load := LoadURLs{Small: *gateLinkSmall, Big: *gateLinkBig, Sink: *gateLinkSink, BigBytes: *gateBigMB << 20}
	return NewLinkTarget(l, load, defaultDNS), linkSecrets(raw, l)
}

// linkSecrets is what of a link its run's logs and report must lose: the
// line, the room, the key and the device, which may name a subscriber. The
// label is what the app shows.
func linkSecrets(raw string, l link.Link) []string {
	return []string{raw, l.Room, l.Key, l.Device}
}

// splitList splits a list on commas and line breaks, as the CI's mask step
// splits a secret, and drops blank entries: a pool stored one room per line
// is the rooms it names, not one room with line breaks in it.
func splitList(v string) []string {
	// ai-generated: line breaks separate entries too.
	sep := func(r rune) bool { return r == ',' || r == '\n' || r == '\r' }
	var out []string
	for s := range strings.FieldsFuncSeq(v, sep) {
		if s = strings.TrimSpace(s); s != "" {
			out = append(out, s)
		}
	}
	return out
}

// listOf is a list from its flag, or from the environment when the flag is
// empty (amendment A7).
func listOf(flagValue, envValue string) []string {
	return splitList(cmp.Or(strings.TrimSpace(flagValue), envValue))
}

// runNumber is the flag's run number, else the environment's, else 0.
func runNumber(flagValue int, envValue string) int {
	if flagValue >= 0 {
		return flagValue
	}
	n, err := strconv.Atoi(strings.TrimSpace(envValue))
	if err != nil {
		return 0
	}
	return n
}

// resolveDir makes the artifacts directory absolute. A relative one is taken
// from the module root, where go test is run and CI collects the artifacts,
// not from internal/gate, where the test binary runs (amendment A9).
func resolveDir(root, dir string) (string, error) {
	if strings.TrimSpace(dir) == "" {
		return "", errors.New("-olcrtc.gate-dir is empty")
	}
	return fromRoot(root, dir), nil
}

// instanceList is the Jitsi instance list -olcrtc.gate-jitsi-instances
// names, the repository's when it is empty. A relative path is taken from
// the module root, as -olcrtc.gate-dir's is: from internal/gate, where the
// test binary runs, the flag's own default names no file.
func instanceList(root, value string) string {
	// ai-generated: read from the module root, not the test's directory.
	return fromRoot(root, cmp.Or(strings.TrimSpace(value), instancesFile))
}

// fromRoot makes a path from the command line absolute: a relative one is
// taken from the module root.
func fromRoot(root, p string) string {
	// ai-generated: shared by the artifacts directory and the instance list.
	if filepath.IsAbs(p) {
		return filepath.Clean(p)
	}
	return filepath.Join(root, p)
}

// moduleRoot is the engine repository: go test runs the package's tests in
// internal/gate.
func moduleRoot(t *testing.T) string {
	t.Helper()
	root, err := filepath.Abs(filepath.Join("..", ".."))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(root, "go.mod")); err != nil {
		t.Fatalf("module root: %v", err)
	}
	return root
}

// runContext is the run's context: it ends reportMargin before the go test
// deadline, if there is one.
func runContext(t *testing.T) context.Context {
	t.Helper()
	deadline, ok := t.Deadline()
	if !ok {
		return t.Context()
	}
	cut, err := runCut(deadline, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithDeadline(t.Context(), cut)
	t.Cleanup(cancel)
	return ctx
}

// runCut is when a run with the go test deadline must end: reportMargin
// before it, and an error when that leaves no time to run.
func runCut(deadline, now time.Time) (time.Time, error) {
	if left := deadline.Sub(now); left <= reportMargin {
		return time.Time{}, fmt.Errorf("go test -timeout leaves %s, no more than the %s the report needs: "+
			"give the gate a longer -timeout", left.Round(time.Second), reportMargin)
	}
	return deadline.Add(-reportMargin), nil
}

// phoneSettings puts the process under the phone's memory settings for the
// test and restores what was in force after it.
func phoneSettings(t *testing.T) {
	t.Helper()
	limit := mobile.MemoryLimit()
	mobile.SetMemoryLimit(phoneMemoryLimit)
	gc := debug.SetGCPercent(phoneGCPercent)
	t.Cleanup(func() {
		mobile.SetMemoryLimit(limit)
		debug.SetGCPercent(gc)
	})
}

// engineCommit is the commit under test: the environment's, else the
// repository's HEAD.
func engineCommit(t *testing.T, root string) string {
	t.Helper()
	if v := os.Getenv(envEngineCommit); v != "" {
		return v
	}
	cmd := exec.CommandContext(t.Context(), "git", "rev-parse", "HEAD")
	cmd.Dir = root
	out, err := cmd.Output()
	if err != nil {
		return "unknown"
	}
	return strings.TrimSpace(string(out))
}

// runnerName is the CI runner's OS and image, else this platform.
func runnerName() string {
	if v := os.Getenv(envRunnerOS); v != "" {
		return strings.TrimSuffix(v+"/"+os.Getenv(envImageOS), "/")
	}
	return runtime.GOOS + "/" + runtime.GOARCH
}

// unreported is the cells that failed without a subtest to say so: the ones
// the run never reached and the ones a -run filter left out, which the report
// fails as not run.
func unreported(rep Report) []string {
	var out []string
	for _, c := range rep.Cells {
		if c.Status == StatusFail && slices.Equal(c.Failures, []string{reasonDidNotRun}) {
			out = append(out, c.ID)
		}
	}
	return out
}

// ai-generated: left at its default, -olcrtc.gate-providers runs every
// provider the local target carries, in the target's own order, so a provider
// added to the support table joins the default run (the CI runs the default).
func TestDefaultProvidersAreEveryOneTheLocalTargetCarries(t *testing.T) {
	def := flag.Lookup("olcrtc.gate-providers").DefValue
	if got := splitList(def); !slices.Equal(got, localProviders()) {
		t.Fatalf("default providers %q, want %q", got, localProviders())
	}
	for _, p := range localProviders() {
		if transportsOf(p) == nil {
			t.Fatalf("%s is in the default run but carries no transport", p)
		}
	}
	if !slices.Contains(localProviders(), "salutejazz") {
		t.Fatal("salutejazz is not in the default run")
	}
}

func TestPickFlavourIsTheBuildsOwn(t *testing.T) {
	for _, tc := range []struct {
		value string
		lean  bool
		want  string // "" is a plan error
	}{
		{"", false, "cli"}, {"", true, "mobile"}, {"cli", false, "cli"}, {" mobile ", true, "mobile"},
		{"mobile", false, ""}, {"cli", true, ""}, {"cli,mobile", true, ""}, {"cli,mobile", false, ""},
		{"desktop", false, ""},
	} {
		got, err := pickFlavour(tc.value, tc.lean)
		if got != tc.want || (err != nil) != (tc.want == "") {
			t.Errorf("pickFlavour(%q, lean %t) = %q, %v; want %q", tc.value, tc.lean, got, err, tc.want)
		}
	}
	own := ownFlavour(leanBuild)
	if c := flavourClient(own); c == nil || c.Name() != own {
		t.Fatalf("this build's flavour %s has client %v", own, c)
	}
}

func TestPickTransportsCutsTheDefaultToWhatCanPass(t *testing.T) {
	all := splitList(allTransports)
	full := []string{"datachannel", "seichannel", "videochannel", "vp8channel"} // sorted, as the registry lists
	lean := []string{"datachannel", "seichannel", "vp8channel"}
	three := []string{"jitsi", "telemost", "wbstream"}
	// ai-generated: a default that named videochannel, as the flag's did.
	withVideo := []string{"datachannel", "videochannel", "seichannel", "vp8channel"}
	for _, tc := range []struct {
		name      string
		asked     []string
		explicit  bool
		providers []string
		linked    []string
		want      []string // nil with an error
	}{
		// ai-generated: videochannel only when named, its S0 outlasts its cell.
		{"default build", all, false, three, full, []string{"datachannel", "seichannel", "vp8channel"}},
		{"lean build", all, false, three, lean, []string{"datachannel", "seichannel", "vp8channel"}},
		{"telemost alone", all, false, []string{"telemost"}, full, []string{"vp8channel"}},
		{"telemost alone, lean", all, false, []string{"telemost"}, lean, []string{"vp8channel"}},
		{"a default the lean build cannot link", withVideo, false, three, lean, lean},
		{"videochannel named", []string{"videochannel"}, true, three, full, []string{"videochannel"}},
		{"asked for", []string{"vp8channel", "datachannel"}, true, three, lean, []string{"vp8channel", "datachannel"}},
		{"asked for, not linked", []string{"vp8channel", "videochannel"}, true, three, lean, nil},
		{"an unknown name is the target's to refuse", []string{"vp9channel"}, true, three, lean, []string{"vp9channel"}},
	} {
		got, err := pickTransports(tc.asked, tc.explicit, tc.providers, tc.linked)
		if !slices.Equal(got, tc.want) || (err != nil) != (tc.want == nil) {
			t.Errorf("%s: pickTransports = %q, %v; want %q", tc.name, got, err, tc.want)
		}
	}
}

func TestLinkedTransportsFollowTheBuild(t *testing.T) {
	linked := linkedTransports()
	for _, tr := range []string{"datachannel", "seichannel", "vp8channel"} {
		if !slices.Contains(linked, tr) {
			t.Fatalf("this build links %q, not %s", linked, tr)
		}
	}
	if slices.Contains(linked, "videochannel") == leanBuild {
		t.Fatalf("lean build %t, and it links %q", leanBuild, linked)
	}
}

func TestResolveDirTakesARelativePathFromTheModuleRoot(t *testing.T) {
	root, abs := t.TempDir(), t.TempDir()
	for in, want := range map[string]string{
		"gate-artifacts":    filepath.Join(root, "gate-artifacts"),
		"out/../gate-jitsi": filepath.Join(root, "gate-jitsi"),
		abs:                 abs,
	} {
		if got, err := resolveDir(root, in); err != nil || got != want {
			t.Errorf("resolveDir(%q) = %q, %v; want %q", in, got, err, want)
		}
	}
	if got, err := resolveDir(root, " "); err == nil {
		t.Fatalf("an empty directory resolved to %q", got)
	}
}

// ai-generated: the instance list is read from where -olcrtc.gate-dir is,
// so the flag's own default, given on the command line, names the file.
func TestInstanceListTakesARelativePathFromTheModuleRoot(t *testing.T) {
	root, abs := t.TempDir(), filepath.Join(t.TempDir(), "fake-instances.yaml")
	repository := filepath.Join(root, "docs", "jitsi.instances.yaml")
	for in, want := range map[string]string{
		instancesFile:            repository,
		"":                       repository,
		" ":                      repository,
		"fake/../instances.yaml": filepath.Join(root, "instances.yaml"),
		abs:                      abs,
	} {
		if got := instanceList(root, in); got != want {
			t.Errorf("instanceList(%q) = %q, want %q", in, got, want)
		}
	}
	if _, err := os.Stat(instanceList(moduleRoot(t), instancesFile)); err != nil {
		t.Fatalf("the default names no file: %v", err)
	}
}

func TestListOfPrefersTheFlagToTheEnvironment(t *testing.T) {
	for _, tc := range []struct {
		flag, env string
		want      []string
	}{
		{" a, ,b ", "c", []string{"a", "b"}},
		{"", "c,d", []string{"c", "d"}},
		{"", " , ", nil},
		// ai-generated: a secret stored one entry per line.
		{"", "c\r\nd\n,e\n", []string{"c", "d", "e"}},
	} {
		if got := listOf(tc.flag, tc.env); !slices.Equal(got, tc.want) {
			t.Errorf("listOf(%q, %q) = %q, want %q", tc.flag, tc.env, got, tc.want)
		}
	}
}

func TestRunNumberComesFromTheFlagOrTheEnvironment(t *testing.T) {
	for _, tc := range []struct {
		flag int
		env  string
		want int
	}{
		{5, "9", 5}, {0, "9", 0}, {-1, " 9 ", 9}, {-1, "", 0}, {-1, "nine", 0},
	} {
		if got := runNumber(tc.flag, tc.env); got != tc.want {
			t.Errorf("runNumber(%d, %q) = %d, want %d", tc.flag, tc.env, got, tc.want)
		}
	}
}

func TestLocalSecretsNameEveryFormOfWhatTheRunWasHanded(t *testing.T) {
	got := localSecrets(LocalOptions{
		TelemostRooms: []string{"fake-telemost-1", "https://telemost.yandex.ru/j/fake-telemost-2"},
		WBStreamRooms: []string{"https://stream.wb.ru/room/fake-wb-room-3"},
		WBStreamToken: fakeToken, JitsiHosts: []string{"https://meet.example.invalid/", "Jitsi.Example.Invalid:8443"},
	})
	for _, want := range []string{
		"fake-telemost-1", "https://telemost.yandex.ru/j/fake-telemost-1",
		"https://telemost.yandex.ru/j/fake-telemost-2", "fake-wb-room-3", fakeToken, "meet.example.invalid",
		// ai-generated: a host with a port, as given and bare, as logs carry it.
		"Jitsi.Example.Invalid:8443", "Jitsi.Example.Invalid", "jitsi.example.invalid",
	} {
		if !slices.Contains(got, want) {
			t.Errorf("secrets %q lack %q", got, want)
		}
	}
	// Without an override the Jitsi hosts come from the public instance
	// list, and a run handed nothing has no secret yet.
	if s := localSecrets(LocalOptions{Instances: "docs/jitsi.instances.yaml"}); slices.ContainsFunc(s,
		func(v string) bool { return v != "" }) {
		t.Fatalf("secrets of a run handed nothing = %q", s)
	}
}

// ai-generated: the local target reads its secrets as the CI's mask and
// require step does, so a value that passed that step is the one the run
// joins with and the scrubber looks for.
func TestLocalTargetReadsItsSecretsAsTheCIStepDoes(t *testing.T) {
	t.Setenv(EnvWBStreamToken, " "+fakeToken+"\n")
	t.Setenv(envTelemostRooms, "fake-telemost-1\r\nfake-telemost-2\n")
	t.Setenv(envWBStreamRooms, "")
	t.Setenv(envJitsiHosts, "")
	lt, secrets := localTarget(t, moduleRoot(t), "", true)
	if lt.opts.WBStreamToken != fakeToken {
		t.Errorf("token %q, want %q", lt.opts.WBStreamToken, fakeToken)
	}
	if want := []string{"fake-telemost-1", "fake-telemost-2"}; !slices.Equal(lt.opts.TelemostRooms, want) {
		t.Errorf("telemost pool %q, want %q", lt.opts.TelemostRooms, want)
	}
	if i := slices.IndexFunc(secrets, func(v string) bool { return strings.ContainsAny(v, " \r\n") }); i >= 0 {
		t.Errorf("secret %q keeps what the CI step trims", secrets[i])
	}
}

func TestLinkSecretsAreTheLinkAndWhatItCarries(t *testing.T) {
	raw := "olcrtc://telemost?vp8channel@fake-telemost-room-1#" + fakeKey + "%fake-device-1$DE fake"
	l, err := link.Parse(raw)
	if err != nil {
		t.Fatal(err)
	}
	got := linkSecrets(raw, l)
	for _, want := range []string{raw, "fake-telemost-room-1", fakeKey, "fake-device-1"} {
		if !slices.Contains(got, want) {
			t.Errorf("secrets %q lack %q", got, want)
		}
	}
	if slices.Contains(got, "DE fake") {
		t.Errorf("the label, what the app shows, is no secret: %q", got)
	}
}

func TestRunCutLeavesTheReportItsMargin(t *testing.T) {
	now := time.Now()
	if cut, err := runCut(now.Add(30*time.Minute), now); err != nil || !cut.Equal(now.Add(30*time.Minute-reportMargin)) {
		t.Fatalf("runCut(30 m) = %v, %v", cut, err)
	}
	if _, err := runCut(now.Add(reportMargin), now); err == nil {
		t.Fatal("a deadline that leaves no time past the margin was taken")
	}
}

func TestUnreportedIsWhatNoCellSubtestFailed(t *testing.T) {
	rep := Report{Cells: []Cell{
		{ID: "p/a/b/cli/S0", Status: StatusPass},
		{ID: "p/a/b/cli/S1", Status: StatusFail, Failures: []string{"connect_ok 1 of connect_total 2"}},
		{ID: "p/a/b/cli/S6", Status: StatusFail, Failures: []string{reasonDidNotRun}},
	}}
	if got := unreported(rep); !slices.Equal(got, []string{"p/a/b/cli/S6"}) {
		t.Fatalf("unreported = %q", got)
	}
}

// ai-generated: the gate fails on every failed cell but a known one, and a
// known cell that did not run fails it too.
func TestGateFailureLeavesKnownFailuresOut(t *testing.T) {
	for _, tc := range []struct {
		rep  Report
		want string // "" passes the gate
	}{
		{Report{Planned: 32, Passed: 32}, ""},
		{Report{Planned: 32, Passed: 30, Failed: 2, FailedKnown: 2}, ""},
		{Report{Planned: 32, Passed: 29, Failed: 3, FailedKnown: 2}, "3 of 32 cells failed (2 known, tracked by issues)"},
		{Report{Planned: 32, Passed: 31, Failed: 1}, "1 of 32 cells failed"},
	} {
		if got := gateFailure(tc.rep); got != tc.want {
			t.Errorf("gateFailure(failed %d, known %d) = %q, want %q", tc.rep.Failed, tc.rep.FailedKnown, got, tc.want)
		}
	}
	withKnown(t, KnownFailure{Cell: "p/a/b/cli/S0", Issue: fakeIssue, Why: "fake"},
		KnownFailure{Cell: "p/a/b/cli/S2", Issue: fakeIssue2, Why: "fake"})
	r := NewRecorder(Report{})
	r.Plan([]Cell{{ID: "p/a/b/cli/S0"}, {ID: "p/a/b/cli/S1"}})
	r.Finish("p/a/b/cli/S0", nil, nil, []string{"pull_ok 0 != 1"}, "", time.Second)
	r.Finish("p/a/b/cli/S1", Metrics{MetricPullOK: 1}, nil, nil, "", time.Second)
	if got := gateFailure(r.Report()); got != "" {
		t.Fatalf("a run whose only failure is known fails the gate: %q", got)
	}
	r.Plan([]Cell{{ID: "p/a/b/cli/S2"}})
	if got, want := gateFailure(r.Report()), "2 of 3 cells failed (1 known, tracked by issues)"; got != want {
		t.Fatalf("a known cell that did not run: %q, want %q", got, want)
	}
	r.Finish("p/a/b/cli/S2", nil, nil, []string{"pull_ok 0 != 1"}, "", time.Second)
	r.Plan([]Cell{{ID: "p/a/b/cli/S3"}})
	r.Finish("p/a/b/cli/S3", nil, nil, []string{"connect_ok 1 of connect_total 2"}, "", time.Second)
	if got, want := gateFailure(r.Report()), "3 of 4 cells failed (2 known, tracked by issues)"; got != want {
		t.Fatalf("a failure off the list: %q, want %q", got, want)
	}
}

func TestKnownLinesNameEachKnownCellWithItsIssue(t *testing.T) {
	withKnown(t, KnownFailure{Cell: "p/a/b/*/S0", Issue: fakeIssue, Why: "fake: pulls stall"})
	rep := Report{Cells: []Cell{
		{ID: "p/a/b/cli/S0", Status: StatusFail, Known: fakeIssue},
		{ID: "p/a/b/cli/S1", Status: StatusFail},
		{ID: "p/a/b/mobile/S0", Status: StatusPass, Known: fakeIssue},
		{ID: "p/a/b/mobile/S1", Status: StatusPass},
	}}
	want := []string{
		"known failure: p/a/b/cli/S0 (" + fakeIssue + "): fake: pulls stall",
		"known failure passed: p/a/b/mobile/S0 (" + fakeIssue + ") - drop it from known.go once the issue is closed",
	}
	if got := knownLines(rep); !slices.Equal(got, want) {
		t.Fatalf("knownLines =\n%q\nwant\n%q", got, want)
	}
}

func TestRunnerNameIsTheCIsImageOrThePlatform(t *testing.T) {
	t.Setenv(envRunnerOS, "Linux")
	t.Setenv(envImageOS, "ubuntu24")
	if got := runnerName(); got != "Linux/ubuntu24" {
		t.Fatalf("on a CI runner: %q", got)
	}
	t.Setenv(envRunnerOS, "")
	if got := runnerName(); !strings.Contains(got, "/") || strings.HasPrefix(got, "Linux/ubuntu") {
		t.Fatalf("off CI: %q", got)
	}
}

func TestEngineCommitPrefersTheEnvironment(t *testing.T) {
	t.Setenv(envEngineCommit, "fake-commit-1")
	if got := engineCommit(t, moduleRoot(t)); got != "fake-commit-1" {
		t.Fatalf("engineCommit = %q", got)
	}
	t.Setenv(envEngineCommit, "")
	if got := engineCommit(t, moduleRoot(t)); got == "" {
		t.Fatal("engineCommit named no commit")
	}
}
