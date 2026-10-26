package gate

import (
	"bytes"
	"compress/gzip"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"slices"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

// ai-generated: whole file, unit cover for the memory sampler.

// waitSamples blocks until s holds at least n samples. Waiting on the count
// instead of sleeping keeps a loaded runner from starving the ticker into a
// flaky failure.
func waitSamples(t *testing.T, s *Sampler, n int) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for len(s.Samples()) < n {
		if time.Now().After(deadline) {
			t.Fatalf("%d of %d samples after 5 s", len(s.Samples()), n)
		}
		time.Sleep(time.Millisecond)
	}
}

func TestSamplerRecordsAndPeaksBetweenMarks(t *testing.T) {
	s := NewSampler(5 * time.Millisecond)
	s.Start()
	t.Cleanup(s.Stop)
	s.Mark("a")
	junk := make([][]byte, 0, 64)
	for range 64 {
		junk = append(junk, make([]byte, 256<<10))
	}
	// The count is read after the mark and the allocation. The next sample
	// may have begun before either; the one after it began later, so it lies
	// between the marks and measured a heap holding the junk.
	waitSamples(t, s, len(s.Samples())+2)
	s.Mark("b")
	runtime.KeepAlive(junk)
	waitSamples(t, s, 5)
	s.Stop()
	heap, rss, ok := s.PeakBetween("a", "b")
	if !ok || heap < 16<<20 {
		t.Fatalf("PeakBetween = %d %d %v, want a heap holding the 16 MiB allocated", heap, rss, ok)
	}
	for _, w := range [][2]string{{"a", "nope"}, {"nope", "b"}} {
		if _, _, ok := s.PeakBetween(w[0], w[1]); ok {
			t.Fatalf("PeakBetween(%s, %s) accepted an unknown mark", w[0], w[1])
		}
	}
	for _, sm := range s.Samples() {
		if sm.Goroutines <= 0 {
			t.Fatal("a sample without goroutines")
		}
	}
}

func TestPeakBetweenTakesTheHighestInsideTheWindowOnly(t *testing.T) {
	s := NewSampler(time.Second)
	t0 := time.Now()
	at := func(ms int) time.Time { return t0.Add(time.Duration(ms) * time.Millisecond) }
	s.samples = []Sample{
		{At: at(0), HeapInuse: 90, RSS: 900, Goroutines: 1},
		{At: at(10), HeapInuse: 30, RSS: 100, Goroutines: 1},
		{At: at(20), HeapInuse: 50, RSS: 300, Goroutines: 1},
		{At: at(30), HeapInuse: 40, RSS: 500, Goroutines: 1},
		{At: at(40), HeapInuse: 99, RSS: 999, Goroutines: 1},
	}
	s.marks["from"], s.marks["to"], s.marks["gap-from"], s.marks["gap-to"] = at(10), at(30), at(31), at(39)
	if heap, rss, ok := s.PeakBetween("from", "to"); !ok || heap != 50 || rss != 500 {
		t.Fatalf("PeakBetween(from, to) = %d %d %v, want 50 500 true", heap, rss, ok)
	}
	for _, w := range [][2]string{{"gap-from", "gap-to"}, {"to", "from"}} {
		if heap, rss, ok := s.PeakBetween(w[0], w[1]); ok {
			t.Fatalf("PeakBetween(%s, %s) = %d %d, want no sample in it", w[0], w[1], heap, rss)
		}
	}
}

// TestAMarkTakesASampleOfItsOwnWhileRunning holds a mark to a reading of its
// own moment, S1's baseline before its burst and not a tick into it. A mark
// on a sampler that is not running only names the time and reads the next
// sample, if there is one.
func TestAMarkTakesASampleOfItsOwnWhileRunning(t *testing.T) {
	s := NewSampler(time.Hour)
	s.Mark("early")
	s.Start()
	t.Cleanup(s.Stop)
	waitSamples(t, s, 1)
	s.Mark("idle")
	got := s.Samples()
	if len(got) != 2 {
		t.Fatalf("%d samples after Start and a mark, want 2", len(got))
	}
	if idle, ok := s.sampleAt("idle"); !ok || !idle.At.Equal(got[1].At) {
		t.Fatalf("sampleAt(idle) = %+v %v, want the mark's own sample", idle, ok)
	}
	if early, ok := s.sampleAt("early"); !ok || !early.At.Equal(got[0].At) {
		t.Fatalf("sampleAt(early) = %+v %v, want the first sample after it", early, ok)
	}
	s.Stop()
	n := len(s.Samples())
	s.Mark("stopped")
	if len(s.Samples()) != n {
		t.Fatal("a mark on a stopped sampler took a sample")
	}
	for _, mark := range []string{"stopped", "unknown"} {
		if sm, ok := s.sampleAt(mark); ok {
			t.Fatalf("sampleAt(%s) = %+v, want none", mark, sm)
		}
	}
}

// TestSamplesStayInTimeOrder races marks against a fast ticker: a mark's
// reading and a tick's may reach the lock in either order, and the samples,
// the CSV and a read at a mark still go by time.
func TestSamplesStayInTimeOrder(t *testing.T) {
	s := NewSampler(time.Millisecond)
	s.Start()
	for range 200 {
		s.Mark("m")
	}
	s.Stop()
	if !slices.IsSortedFunc(s.Samples(), func(a, b Sample) int { return a.At.Compare(b.At) }) {
		t.Fatal("samples out of time order")
	}
}

func TestSamplerWritesCSV(t *testing.T) {
	// An hour between ticks leaves exactly the sample Start takes and the one
	// Stop takes.
	s := NewSampler(time.Hour)
	s.Start()
	s.Stop()
	p := filepath.Join(t.TempDir(), "samples.csv")
	if err := s.WriteCSV(p); err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(p)
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimSpace(string(raw)), "\n")
	if lines[0] != "t_ms,heap_inuse,rss,goroutines" || len(lines) != 3 {
		t.Fatalf("csv = %q, want the header, a first and a last sample", string(raw))
	}
	for _, row := range lines[1:] {
		fields := strings.Split(row, ",")
		if len(fields) != 4 {
			t.Fatalf("row %q has %d fields", row, len(fields))
		}
		for _, f := range fields {
			if _, err := strconv.ParseInt(f, 10, 64); err != nil {
				t.Fatalf("row %q: %v", row, err)
			}
		}
		if ms, _ := strconv.ParseInt(fields[0], 10, 64); ms < 0 || ms > 60_000 {
			t.Fatalf("row %q: t_ms does not count from Start", row)
		}
	}
	if err := s.WriteCSV(filepath.Join(t.TempDir(), "missing", "samples.csv")); err == nil {
		t.Fatal("WriteCSV into a missing directory returned nil")
	}
}

func TestSamplerStartAndStopAreForgiving(t *testing.T) {
	if got := NewSampler(0).every; got != time.Second {
		t.Fatalf("NewSampler(0) samples every %s, want 1s", got)
	}
	s := NewSampler(time.Millisecond)
	s.Stop()
	s.Start()
	s.Start()
	waitSamples(t, s, 3)
	s.Stop()
	s.Stop()
	n := len(s.Samples())
	time.Sleep(20 * time.Millisecond)
	if got := len(s.Samples()); got != n {
		t.Fatalf("%d samples after Stop, then %d: a second Start left a loop running", n, got)
	}
}

func TestParseVMRSSReadsTheStatusLine(t *testing.T) {
	for _, c := range []struct {
		name, status string
		want         uint64
	}{
		{"kB to bytes", "Name:\tgate.test\nVmPeak:\t  90000 kB\nVmRSS:\t   51200 kB\nThreads:\t9\n", 50 << 20},
		{"no line", "Name:\tgate.test\nThreads:\t9\n", 0},
		{"no number", "VmRSS:\n", 0},
		{"not a number", "VmRSS:\t12x kB\n", 0},
	} {
		if got := parseVMRSS(c.status); got != c.want {
			t.Errorf("%s: parseVMRSS = %d, want %d", c.name, got, c.want)
		}
	}
	if runtime.GOOS == "linux" && readRSS() == 0 {
		t.Fatal("readRSS = 0 on linux")
	}
}

// ai-generated: the rest of the file (a profile when memory jumps).

// jumpLog collects what a sampler logs about its profiles.
type jumpLog struct {
	mu    sync.Mutex
	lines []string
}

func (l *jumpLog) logf(format string, args ...any) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.lines = append(l.lines, fmt.Sprintf(format, args...))
}

func (l *jumpLog) all() []string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return slices.Clone(l.lines)
}

// profilesIn is the profiles a sampler left in dir, by name.
func profilesIn(t *testing.T, dir string) []string {
	t.Helper()
	names, err := filepath.Glob(filepath.Join(dir, "*.pb.gz"))
	if err != nil {
		t.Fatal(err)
	}
	for i, n := range names {
		names[i] = filepath.Base(n)
	}
	return names
}

// checkHeapProfile holds a profile to what it is for: a gzipped pprof
// profile that carries what is in use and what was allocated.
func checkHeapProfile(t *testing.T, path string) {
	t.Helper()
	f, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	zr, err := gzip.NewReader(f)
	if err != nil {
		t.Fatalf("%s: %v", filepath.Base(path), err)
	}
	raw, err := io.ReadAll(zr)
	if err != nil {
		t.Fatalf("%s: %v", filepath.Base(path), err)
	}
	for _, sampleType := range []string{"inuse_space", "alloc_space"} {
		if !bytes.Contains(raw, []byte(sampleType)) {
			t.Fatalf("%s carries no %s samples", filepath.Base(path), sampleType)
		}
	}
}

// series is a sampler on a made-up clock and made-up readings, in MiB, one a
// second: what record sees is what the test says the process read.
func series(t *testing.T, dir string, heap, rss []uint64) (*Sampler, *jumpLog) {
	t.Helper()
	s := NewSampler(time.Hour)
	log := &jumpLog{}
	s.ProfileJumps(dir, "mobile", log.logf)
	t0 := time.Now()
	s.start = t0
	for i := range heap {
		s.record(Sample{At: t0.Add(time.Duration(i) * time.Second), HeapInuse: heap[i] << 20, RSS: rss[i] << 20,
			Goroutines: 10})
	}
	return s, log
}

// TestTheSamplerProfilesEachJumpOnce writes one profile for each rise of more
// than 6 MiB, of the heap or of the RSS, from one sample to the next, named
// by the samples' own clock, and none for a plateau after a jump, a fall or a
// rise of exactly 6 MiB.
func TestTheSamplerProfilesEachJumpOnce(t *testing.T) {
	dir := t.TempDir()
	_, log := series(t, dir,
		[]uint64{10, 11, 10, 17, 18, 18, 12, 12, 12, 18},
		[]uint64{40, 40, 41, 41, 41, 41, 41, 48, 48, 48})
	want := []string{"mobile-heap-3000ms.pb.gz", "mobile-heap-7000ms.pb.gz"}
	if got := profilesIn(t, dir); !slices.Equal(got, want) {
		t.Fatalf("profiles = %q, want %q: one per jump", got, want)
	}
	for _, name := range want {
		checkHeapProfile(t, filepath.Join(dir, name))
	}
	lines := log.all()
	if len(lines) != 2 || !strings.Contains(lines[0], want[0]) || !strings.Contains(lines[1], want[1]) {
		t.Fatalf("logged %q, want a line naming each profile", lines)
	}
}

func TestASteadySeriesWritesNoProfile(t *testing.T) {
	dir := t.TempDir()
	heap, rss := make([]uint64, 120), make([]uint64, 120)
	for i := range heap {
		heap[i], rss[i] = 10+uint64(i%5), 40+uint64(i%7) // a sawtooth under the jump
	}
	if _, log := series(t, dir, heap, rss); len(profilesIn(t, dir)) != 0 || len(log.all()) != 0 {
		t.Fatalf("a steady series left %q and logged %q", profilesIn(t, dir), log.all())
	}
}

// TestEveryJumpIsProfiledHoweverManyCameBefore writes a profile for each of
// twelve jumps. The one S7's peak calls for comes after the regrowth past
// the baseline, S0, S1's burst and the first pulls of S2 have jumped, so no
// count of earlier jumps may use it up.
func TestEveryJumpIsProfiledHoweverManyCameBefore(t *testing.T) {
	dir := t.TempDir()
	heap, rss := make([]uint64, 24), make([]uint64, 24)
	for i := range heap {
		heap[i], rss[i] = 10+uint64(i%2)*20, 40
	}
	_, log := series(t, dir, heap, rss)
	if got := profilesIn(t, dir); len(got) != 12 {
		t.Fatalf("%d profiles for 12 jumps, want 12", len(got))
	}
	lines := log.all()
	if len(lines) != 12 || slices.ContainsFunc(lines, func(l string) bool { return !strings.Contains(l, ".pb.gz") }) {
		t.Fatalf("logged %q, want a line naming each profile", lines)
	}
}

// TestAJumpsLineSaysWhatWritingItsProfileCost: the profile is written inside
// the process S7 weighs, so its line gives what the write allocated and how
// far the heap in use moved over it, and a peak near S7's bound can be put
// down to the tool or to the client.
func TestAJumpsLineSaysWhatWritingItsProfileCost(t *testing.T) {
	_, log := series(t, t.TempDir(), []uint64{10, 17}, []uint64{40, 40})
	lines := log.all()
	cost := regexp.MustCompile(`/mobile-heap-1000ms\.pb\.gz \(writing it allocated \d+\.\d MiB, heap in use [+-]\d+\.\d MiB\)$`)
	if len(lines) != 1 || !cost.MatchString(lines[0]) {
		t.Fatalf("logged %q, want the profile named with what writing it cost", lines)
	}
}

// TestProfilesBetweenCountsTheWritesThatCanMoveAWindowsPeak: a profile is
// written just after its sample, so one at the window's first mark or inside
// it can leave garbage in a later sample of the window, and one at its last
// mark cannot.
func TestProfilesBetweenCountsTheWritesThatCanMoveAWindowsPeak(t *testing.T) {
	s, _ := series(t, t.TempDir(),
		[]uint64{10, 17, 10, 17, 10, 17, 10, 17, 10},
		[]uint64{40, 40, 40, 40, 40, 40, 40, 40, 40})
	at := func(i int) time.Time { return s.start.Add(time.Duration(i) * time.Second) }
	s.marks["from"], s.marks["to"], s.marks["after"] = at(3), at(7), at(8)
	if got := s.ProfilesBetween("from", "to"); got != 2 {
		t.Fatalf("profiles between the marks = %d, want 2: the ones at 3 s and 5 s, not 7 s", got)
	}
	if got := s.ProfilesBetween("to", "after"); got != 1 {
		t.Fatalf("profiles from the last mark on = %d, want the one at 7 s", got)
	}
	if got := s.ProfilesBetween("from", "unknown"); got != 0 {
		t.Fatalf("profiles up to an unknown mark = %d, want 0", got)
	}
}

// TestAJumpInTheProcessIsProfiledAtAMarkAndAtATick reads the process itself:
// a mark's own sample and the loop's are both held to the jump.
func TestAJumpInTheProcessIsProfiledAtAMarkAndAtATick(t *testing.T) {
	dir := t.TempDir()
	runtime.GC() //nolint:revive // garbage freed during the rise would hide it
	s := NewSampler(time.Hour)
	s.ProfileJumps(dir, "cli", (&jumpLog{}).logf)
	s.Start()
	t.Cleanup(s.Stop)
	waitSamples(t, s, 1)
	first := make([]byte, 32<<20)
	s.Mark("after the first")
	if got := profilesIn(t, dir); len(got) != 1 {
		t.Fatalf("profiles after a 32 MiB rise and a mark = %q, want one", got)
	}
	second := make([]byte, 32<<20)
	s.Stop() // the loop's last sample
	if got := profilesIn(t, dir); len(got) != 2 {
		t.Fatalf("profiles after a second rise and the loop's sample = %q, want two", got)
	}
	runtime.KeepAlive(first)
	runtime.KeepAlive(second)
}

// TestTheBaselineSamplerProfilesIntoThePairsDirectory is the runner's side:
// a client's sampler writes its profiles next to the pair's logs, under the
// client's name.
func TestTheBaselineSamplerProfilesIntoThePairsDirectory(t *testing.T) {
	dir := t.TempDir()
	s := baselineSampler(dir, "mobile", (&jumpLog{}).logf)
	s.Stop()
	last := s.Samples()[len(s.Samples())-1]
	s.record(Sample{At: last.At.Add(time.Second), HeapInuse: last.HeapInuse + 7<<20, RSS: last.RSS, Goroutines: 1})
	got := profilesIn(t, dir)
	if len(got) != 1 || !strings.HasPrefix(got[0], "mobile-heap-") {
		t.Fatalf("profiles = %q, want the client's one in the pair's directory", got)
	}
}
