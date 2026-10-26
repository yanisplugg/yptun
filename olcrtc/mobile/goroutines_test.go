package mobile

import (
	"fmt"
	"strconv"
	"strings"
	"sync"
	"testing"
)

// blockedForSummary parks until released, so the profile has goroutines whose
// first frame of ours is this function.
func blockedForSummary(release <-chan struct{}, started *sync.WaitGroup) {
	started.Done()
	<-release
}

func TestGoroutineSummaryNamesWhereGoroutinesWait(t *testing.T) {
	const parked = 7
	release := make(chan struct{})
	var started sync.WaitGroup
	started.Add(parked)
	for range parked {
		go blockedForSummary(release, &started)
	}
	started.Wait()
	t.Cleanup(func() { close(release) })

	got := GoroutineSummary()
	if strings.Contains(got, "\n") {
		t.Fatalf("the summary must be one line, got %q", got)
	}
	if !strings.HasPrefix(got, "goroutines ") || !strings.Contains(got, ":") {
		t.Fatalf("the summary should open with the total, got %q", got)
	}
	if !strings.Contains(got, "mobile.blockedForSummary "+strconv.Itoa(parked)) {
		t.Fatalf("the parked goroutines should be named and counted, got %q", got)
	}
}

func TestSummarizeGoroutineProfileLabelsByTheFirstFrameOfOurs(t *testing.T) {
	profile := "goroutine profile: total 9\n" +
		group(3, "runtime.gopark", "internal/poll.runtime_pollWait",
			"github.com/openlibrecommunity/olcrtc/internal/tunnelcore.copyOneWay") +
		group(2, "runtime.gopark", "github.com/pion/ice/v4.(*Agent).connectivityChecks") +
		group(2, "runtime.gopark", "sync.runtime_Semacquire",
			"github.com/openlibrecommunity/olcrtc/internal/client.(*Client).tunnel") +
		group(1, "runtime.gopark", "net/http.(*persistConn).readLoop") +
		group(1, "runtime.gopark")

	want := "goroutines 9: tunnelcore.copyOneWay 3, client.(*Client).tunnel 2, " +
		"pion/ice/v4.(*Agent).connectivityChecks 2, net/http.(*persistConn).readLoop 1, runtime 1"
	if got := summarizeGoroutineProfile(profile); got != want {
		t.Fatalf("summarizeGoroutineProfile()\n got %q\nwant %q", got, want)
	}
}

func TestSummarizeGoroutineProfileFoldsTheTailIntoOther(t *testing.T) {
	// Eight places, counts 8 down to 1: six are named, the two smallest fold.
	groups := make([]string, 0, 8)
	for i := 8; i >= 1; i-- {
		groups = append(groups, group(i, "runtime.gopark",
			fmt.Sprintf("github.com/openlibrecommunity/olcrtc/internal/client.place%d", i)))
	}
	got := summarizeGoroutineProfile("goroutine profile: total 36\n" + strings.Join(groups, ""))
	if !strings.HasSuffix(got, "client.place3 3, other 3") {
		t.Fatalf("the two smallest groups should fold into other, got %q", got)
	}
	if strings.Count(got, ",") != 6 {
		t.Fatalf("six named groups and other, got %q", got)
	}
}

// group renders one debug=1 profile group with the frames innermost first.
func group(count int, frames ...string) string {
	pcs := make([]string, 0, len(frames))
	lines := make([]string, 0, len(frames))
	for i, frame := range frames {
		pcs = append(pcs, fmt.Sprintf(" 0x%x", 0x1000+i))
		lines = append(lines, fmt.Sprintf("#\t0x%x\t%s+0x%x\t/src/file.go:%d", 0x1000+i, frame, 0x10+i, i+1))
	}
	return strconv.Itoa(count) + " @" + strings.Join(pcs, "") + "\n" + strings.Join(lines, "\n") + "\n\n"
}
