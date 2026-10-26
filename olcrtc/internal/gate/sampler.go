package gate

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"runtime/pprof"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ai-generated: the whole file (the memory sampler).

// Sample is one reading of the test process. The server runs as a child and
// is not in it, but the harness is: the test binary, the origin, the load,
// the log capture and what earlier pairs left behind. So S7 judges what the
// client adds over a baseline read before it starts, not the process's size.
type Sample struct {
	At         time.Time
	HeapInuse  uint64
	RSS        uint64 // 0 where /proc/self/status is not there to read
	Goroutines int
}

// Sampler reads memory and goroutines on a ticker and remembers named
// moments, so a scenario can ask for the peak over a window. It is safe for
// concurrent use.
type Sampler struct {
	every   time.Duration
	mu      sync.Mutex
	samples []Sample
	marks   map[string]time.Time
	start   time.Time
	stop    chan struct{}
	done    chan struct{}
	// ai-generated: where a jump's profile goes, under what name, who hears
	// of it, and the sample each profile written was for; no directory, no
	// profiles (ProfileJumps).
	profileDir    string
	profilePrefix string
	logf          func(format string, args ...any)
	profiled      []time.Time
}

// NewSampler samples every interval once started. An interval that is not
// positive means one second, the gate's rate, instead of a ticker panic.
func NewSampler(every time.Duration) *Sampler {
	if every <= 0 {
		every = time.Second
	}
	return &Sampler{every: every, marks: map[string]time.Time{}}
}

// Start begins sampling in a goroutine, one sample at once and one per tick.
// A sampler already running is left alone, so no loop outlives Stop.
func (s *Sampler) Start() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.stop != nil {
		return
	}
	if s.start.IsZero() {
		s.start = time.Now()
	}
	s.stop, s.done = make(chan struct{}), make(chan struct{})
	go s.loop(s.stop, s.done)
}

// Stop takes a last sample, ends the loop and waits for it. Calling it again,
// or before Start, does nothing.
func (s *Sampler) Stop() {
	s.mu.Lock()
	stop, done := s.stop, s.done
	s.stop, s.done = nil, nil
	s.mu.Unlock()
	if stop == nil {
		return
	}
	close(stop)
	<-done
}

// loop samples at once, on every tick, and a last time when stop closes.
func (s *Sampler) loop(stop <-chan struct{}, done chan<- struct{}) {
	defer close(done)
	t := time.NewTicker(s.every)
	defer t.Stop()
	s.take()
	for {
		select {
		case <-stop:
			s.take()
			return
		case <-t.C:
			s.take()
		}
	}
}

// take adds one reading of the process.
func (s *Sampler) take() {
	s.record(readSample())
}

// record adds a reading, and writes a profile when it jumped over the
// reading before it (ProfileJumps), outside the lock.
func (s *Sampler) record(sm Sample) {
	s.mu.Lock()
	jump := s.jumpAt(s.add(sm))
	s.mu.Unlock()
	s.profile(jump)
}

// add puts a reading among the samples in time order and returns where: a
// tick's and a mark's are read before the lock is taken and may reach it in
// either order. The caller holds the lock.
func (s *Sampler) add(sm Sample) int {
	i := len(s.samples)
	for i > 0 && s.samples[i-1].At.After(sm.At) {
		i--
	}
	s.samples = slices.Insert(s.samples, i, sm)
	return i
}

// readSample reads the process once.
func readSample() Sample {
	var m runtime.MemStats
	runtime.ReadMemStats(&m)
	return Sample{At: time.Now(), HeapInuse: m.HeapInuse, RSS: readRSS(), Goroutines: runtime.NumGoroutine()}
}

// Mark names now; marking a name again moves it. While the sampler runs, a
// mark also takes a sample of its own, so what is read at a mark is the
// process at that moment: S7 reads the goroutines before S1's burst and 60 s
// after the load, not up to a tick into whatever runs next.
func (s *Sampler) Mark(name string) {
	sm := readSample()
	s.mu.Lock()
	s.marks[name] = sm.At
	var jump jumpProfile
	if s.stop != nil {
		jump = s.jumpAt(s.add(sm))
	}
	s.mu.Unlock()
	s.profile(jump)
}

// PeakBetween returns the highest heap and the highest RSS sampled between
// two marks, both ends included, each on its own. It reports false when a
// mark is unknown or no sample falls inside the window.
func (s *Sampler) PeakBetween(fromMark, toMark string) (uint64, uint64, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	from, okFrom := s.marks[fromMark]
	to, okTo := s.marks[toMark]
	if !okFrom || !okTo {
		return 0, 0, false
	}
	var heap, rss uint64
	seen := false
	for _, sm := range s.samples {
		if sm.At.Before(from) || sm.At.After(to) {
			continue
		}
		seen = true
		heap = max(heap, sm.HeapInuse)
		rss = max(rss, sm.RSS)
	}
	return heap, rss, seen
}

// sampleAt returns the first sample taken at or after a mark: the mark's own
// reading when the sampler ran at the mark, else the next tick's. It reports
// false for an unknown mark and for a mark nothing was sampled after.
func (s *Sampler) sampleAt(mark string) (Sample, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	at, ok := s.marks[mark]
	if !ok {
		return Sample{}, false
	}
	for _, sm := range s.samples {
		if !sm.At.Before(at) {
			return sm, true
		}
	}
	return Sample{}, false
}

// Samples returns a copy of everything sampled so far, in time order.
func (s *Sampler) Samples() []Sample {
	s.mu.Lock()
	defer s.mu.Unlock()
	return slices.Clone(s.samples)
}

// WriteCSV stores the samples for the run's artifacts, one row each under the
// header t_ms,heap_inuse,rss,goroutines; t_ms counts from the first Start.
func (s *Sampler) WriteCSV(path string) error {
	if err := os.WriteFile(path, s.renderCSV(), 0o644); err != nil { //nolint:gosec // samples, not a secret
		return fmt.Errorf("write samples csv: %w", err)
	}
	return nil
}

// renderCSV formats the samples under the lock, so WriteCSV writes the file
// outside it.
func (s *Sampler) renderCSV() []byte {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]byte, 0, 64*(len(s.samples)+1))
	out = append(out, "t_ms,heap_inuse,rss,goroutines\n"...)
	for _, sm := range s.samples {
		out = fmt.Appendf(out, "%d,%d,%d,%d\n",
			sm.At.Sub(s.start).Milliseconds(), sm.HeapInuse, sm.RSS, sm.Goroutines)
	}
	return out
}

// readRSS reads the process's resident set in bytes from /proc on Linux, and
// is 0 elsewhere.
func readRSS() uint64 {
	raw, err := os.ReadFile("/proc/self/status")
	if err != nil {
		return 0
	}
	return parseVMRSS(string(raw))
}

// parseVMRSS finds the VmRSS line of a /proc status file and returns it in
// bytes, or 0 when the line is missing or malformed.
func parseVMRSS(status string) uint64 {
	for line := range strings.Lines(status) {
		rest, ok := strings.CutPrefix(line, "VmRSS:")
		if !ok {
			continue
		}
		fields := strings.Fields(rest)
		if len(fields) == 0 {
			return 0
		}
		kb, err := strconv.ParseUint(fields[0], 10, 64)
		if err != nil {
			return 0
		}
		return kb << 10
	}
	return 0
}

// ai-generated: the rest of the file (a profile when memory jumps).

// profileJump is how far the heap or the RSS must rise from one sample to
// the next for the sampler to write a heap profile. S7 failed on such jumps,
// and the samples alone cannot say what allocated them.
const profileJump = 6 << 20

// ProfileJumps makes the sampler write a heap profile into dir whenever the
// heap or the RSS rises by more than profileJump from one sample to the
// next: <prefix>-heap-<t_ms>ms.pb.gz, t_ms on the clock of the CSV, each
// told to logf with what writing it cost. Every jump gets one, however many
// came before: the one S7's peak calls for comes late in a client's run. Go's
// heap profile is the allocs profile too: it carries what is in use, as of
// the last GC, and what was allocated since the process began
// (-sample_index=alloc_space), so one file holds both. Call it before Start.
func (s *Sampler) ProfileJumps(dir, prefix string, logf func(format string, args ...any)) {
	if logf == nil {
		logf = func(string, ...any) {}
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.profileDir, s.profilePrefix, s.logf = dir, prefix, logf
}

// ProfilesBetween counts the heap profiles written from the first mark up to
// the second, the second's own left out: a profile is written just after its
// sample, in the process the samples weigh, so one written inside a window
// can leave garbage in a later sample of it, and one at its last mark comes
// after every sample the window holds. It is 0 when a mark is unknown.
func (s *Sampler) ProfilesBetween(fromMark, toMark string) int {
	s.mu.Lock()
	defer s.mu.Unlock()
	from, okFrom := s.marks[fromMark]
	to, okTo := s.marks[toMark]
	if !okFrom || !okTo {
		return 0
	}
	n := 0
	for _, at := range s.profiled {
		if !at.Before(from) && at.Before(to) {
			n++
		}
	}
	return n
}

// jumpProfile is what one sample calls for: nothing (the zero value) or a
// profile at path.
type jumpProfile struct {
	logf           func(format string, args ...any)
	path           string
	before, sample Sample
}

// jumpAt is what the sample at i calls for: a profile when it rose by more
// than profileJump over the one before it in time. The caller holds the lock.
func (s *Sampler) jumpAt(i int) jumpProfile {
	if s.profileDir == "" || i == 0 {
		return jumpProfile{}
	}
	before, sample := s.samples[i-1], s.samples[i]
	if sample.HeapInuse <= before.HeapInuse+profileJump && sample.RSS <= before.RSS+profileJump {
		return jumpProfile{}
	}
	name := fmt.Sprintf("%s-heap-%dms.pb.gz", s.profilePrefix, sample.At.Sub(s.start).Milliseconds())
	return jumpProfile{logf: s.logf, path: filepath.Join(s.profileDir, name), before: before, sample: sample}
}

// profile writes the profile a jump calls for, outside the lock, says so with
// what writing it cost, and notes the sample it was for (ProfilesBetween).
// The write allocates in the process S7 weighs, and its garbage stays in the
// heap in use until the next GC, so the line gives the process's allocation
// over the write and how far the heap in use moved.
func (s *Sampler) profile(j jumpProfile) {
	if j.path == "" {
		return
	}
	name := filepath.Join(filepath.Base(filepath.Dir(j.path)), filepath.Base(j.path))
	var pre, post runtime.MemStats
	runtime.ReadMemStats(&pre)
	err := writeHeapProfile(j.path)
	runtime.ReadMemStats(&post)
	if err != nil {
		j.logf("%s: %v", name, err)
		return
	}
	s.mu.Lock()
	s.profiled = append(s.profiled, j.sample.At)
	s.mu.Unlock()
	j.logf("heap %.1f -> %.1f MiB, rss %.1f -> %.1f MiB from one sample to the next: %s "+
		"(writing it allocated %.1f MiB, heap in use %+.1f MiB)",
		mib(j.before.HeapInuse), mib(j.sample.HeapInuse), mib(j.before.RSS), mib(j.sample.RSS), name,
		mib(post.TotalAlloc-pre.TotalAlloc), (float64(post.HeapInuse)-float64(pre.HeapInuse))/(1<<20))
}

// writeHeapProfile writes the process's heap profile to path.
func writeHeapProfile(path string) error {
	f, err := os.Create(path)
	if err != nil {
		return fmt.Errorf("heap profile: %w", err)
	}
	if err := pprof.Lookup("heap").WriteTo(f, 0); err != nil {
		_ = f.Close()
		return fmt.Errorf("heap profile: %w", err)
	}
	if err := f.Close(); err != nil {
		return fmt.Errorf("heap profile: %w", err)
	}
	return nil
}

// mib is a byte count in MiB, for a log line.
func mib(n uint64) float64 { return float64(n) / (1 << 20) }
