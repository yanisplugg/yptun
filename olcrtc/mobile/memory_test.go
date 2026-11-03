package mobile

import (
	"math"
	"runtime/debug"
	"testing"
)

func TestSetMemoryLimitRoundTrips(t *testing.T) {
	t.Cleanup(func() { debug.SetMemoryLimit(math.MaxInt64) })

	const want = 30 << 20
	SetMemoryLimit(want)
	if got := MemoryLimit(); got != want {
		t.Fatalf("MemoryLimit() = %d, want %d", got, want)
	}

	// A host that wants no ceiling says so with a non-positive value, rather
	// than having to know the sentinel the runtime uses for "unlimited".
	SetMemoryLimit(0)
	if got := MemoryLimit(); got != 0 {
		t.Fatalf("MemoryLimit() after clearing = %d, want 0", got)
	}
}
