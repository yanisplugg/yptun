package mobile

import (
	"fmt"
	"math"
	"runtime"
	"runtime/debug"
	"strconv"
)

// SetMemoryLimit gives the Go runtime a soft memory ceiling, in bytes.
//
// A host with a hard limit needs this. An iOS packet tunnel provider is given
// roughly 50 MB for everything and is killed for exceeding it without being
// told, and the ceiling is reached the way Go reaches ceilings: not by
// climbing to it, but in one step, when the heap doubles between collections.
// A trace from a phone showed 35.1 MB one sample and 46.0 MB the next, 250 ms
// apart, and then nothing.
//
// The limit is what the runtime collects against rather than a cap it
// enforces: as the heap approaches it the collector runs more often, spending
// CPU to avoid the step. Go will not spend more than half the CPU doing it, so
// a limit set too low costs throughput rather than deadlocking.
//
// It governs the whole runtime, which on iOS is the one shared by olcRTC,
// sing-box and Xray inside Cores.xcframework — the right scope, since they
// share the process that gets killed. Pass a non-positive value to remove a
// limit that was set.
func SetMemoryLimit(bytes int64) {
	if bytes <= 0 {
		debug.SetMemoryLimit(math.MaxInt64)
		return
	}
	debug.SetMemoryLimit(bytes)
}

// MemoryLimit reports the soft ceiling in force, or 0 when there is none.
func MemoryLimit() int64 {
	current := debug.SetMemoryLimit(-1)
	if current == math.MaxInt64 {
		return 0
	}
	return current
}

// MemoryStats reports what the Go runtime is holding, as one compact line.
//
// The footprint the system kills a process for is not the Go heap: it also
// counts stacks, the allocator's spans, and everything the process holds that
// Go never allocated. A trace that carries only the footprint therefore cannot
// say whether a limit is working, whether a climb is Go's at all, or whether a
// death happened with the heap nowhere near its ceiling — which is exactly the
// question left standing after 1.0.411, where the extension died at 39 MB of a
// 50 MB allowance with 11 MB to spare.
//
// So the split is reported rather than inferred:
//
//	heap    what is live plus not yet collected, against the limit in force
//	sys     everything the runtime has taken from the OS, the part of the
//	        footprint Go is answerable for
//	rel     of that, what has been handed back and is no longer resident
//	stacks  goroutine stacks, which a connection-per-stream load grows
//	gc      collections so far; a number racing upward means the limit binds
//
// ReadMemStats stops the world. It is cheap at this rate — tens of
// microseconds, four times a second — but it is not free, so it belongs in a
// debugging path and not in the data path.
func MemoryStats() string {
	var m runtime.MemStats
	runtime.ReadMemStats(&m)

	mb := func(v uint64) float64 { return float64(v) / (1 << 20) }

	limit := "none"
	if l := MemoryLimit(); l > 0 {
		limit = strconv.FormatFloat(float64(l)/(1<<20), 'f', 1, 64)
	}

	return fmt.Sprintf(
		"heap %.1f/%s MB  sys %.1f MB  rel %.1f MB  stacks %.1f MB  gc %d  goroutines %d",
		mb(m.HeapAlloc), limit,
		mb(m.Sys),
		mb(m.HeapReleased),
		mb(m.StackSys),
		m.NumGC,
		runtime.NumGoroutine(),
	)
}

// FreeOSMemory returns as much memory to the operating system as the runtime
// can, immediately.
//
// For a host that is killed on its footprint rather than its heap, the
// difference matters: memory Go has collected but not released is still
// resident and still counted. The runtime returns it on its own schedule,
// which is the right trade for a server and the wrong one for a process the
// system is about to choose as its victim.
//
// So this exists for one caller: a memory-pressure notification. iOS sends one
// before it starts killing, and a packet tunnel provider sits low enough in
// the jetsam band to be an early choice even when it is nowhere near its own
// allowance. Answering the warning by handing back everything held in reserve
// is the cheapest thing that can be done at that moment.
//
// It forces a stop-the-world collection, so it belongs on that event and
// nowhere else. Calling it on a timer would spend the throughput it is meant
// to protect.
func FreeOSMemory() {
	debug.FreeOSMemory()
}
