package mobile

import (
	"strings"
	"testing"
)

func TestMemoryStatsReportsTheLimitInForce(t *testing.T) {
	t.Cleanup(func() { SetMemoryLimit(0) })

	SetMemoryLimit(0)
	if got := MemoryStats(); !strings.Contains(got, "heap ") || !strings.Contains(got, "/none MB") {
		t.Fatalf("without a limit the line should say none, got %q", got)
	}

	SetMemoryLimit(28 * 1024 * 1024)
	got := MemoryStats()
	if !strings.Contains(got, "/28.0 MB") {
		t.Fatalf("the line should carry the limit in force, got %q", got)
	}
	for _, field := range []string{"sys ", "rel ", "stacks ", "gc ", "goroutines "} {
		if !strings.Contains(got, field) {
			t.Fatalf("missing %q in %q", field, got)
		}
	}
}
