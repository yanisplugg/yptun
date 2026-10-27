package vp8channel

// ai-generated: the whole file (issue #12: the cap a lane keeps after it has
// been dark).

import (
	"testing"
	"time"
)

const second = int64(time.Second)

func TestPaceHalvesTheBusiestSecondAndGrowsBack(t *testing.T) {
	var s pace
	now := 10 * second
	for i := range int64(4) {
		s.wrote(now+i*int64(paceQuarter), 100<<10)
	}
	// Two quieter seconds pass before the lane counts its path dark.
	now += 3 * second
	s.wrote(now, 10<<10)
	if !s.slow(now, s.sentRate(now), 1<<20) {
		t.Fatal("slow() left a loud lane uncapped")
	}
	if want := float64(200 << 10); s.rate != want {
		t.Fatalf("cap = %.0f, want %.0f, half of the busiest second", s.rate, want)
	}

	grow := func(at int64) bool { return s.grow(at, 2*time.Second, 1<<30) }
	if grow(now + 3*second) {
		t.Fatal("grow() raised a cap that held nothing back")
	}
	s.held = true
	if grow(now + second) {
		t.Fatal("grow() raised the cap before the interval passed")
	}
	if !grow(now+3*second) || s.rate != float64(200<<10)*5/4 {
		t.Fatalf("cap after one step = %.0f, want a quarter more", s.rate)
	}
	capped := s.rate
	// Dark while writing well under the cap: not this lane's doing.
	s.wrote(now+4*second, 100<<10)
	if s.slow(now+4*second, s.sentRate(now+4*second), 1<<20) || s.rate != capped {
		t.Fatalf("cap after going dark quiet = %.0f, want it kept at %.0f", s.rate, capped)
	}
	// Dark while keeping to the cap: the cap is not what took the path
	// down, and halving it again only ratchets the lane down.
	s.wrote(now+5*second, int(capped))
	if s.slow(now+5*second, s.sentRate(now+5*second), 1<<20) || s.rate != capped {
		t.Fatalf("cap after going dark at the cap = %.0f, want it kept at %.0f", s.rate, capped)
	}
	// Dark again while writing well over what the cap allows.
	s.wrote(now+6*second, 400<<10)
	if !s.slow(now+6*second, s.sentRate(now+6*second), 1<<20) || s.rate != capped/2 {
		t.Fatalf("cap after going dark over the cap = %.0f, want half of %.0f", s.rate, capped)
	}
	s.held = true
	if !s.grow(now+8*second, 2*time.Second, s.rate) || s.rate != 0 {
		t.Fatalf("cap = %.0f at the lane's full rate, want it lifted", s.rate)
	}
}

func TestPaceLeavesAQuietLaneUncapped(t *testing.T) {
	var s pace
	s.wrote(second, quietRate/2)
	if s.slow(second+1, s.sentRate(second+1), 1<<20) || s.rate != 0 {
		t.Fatalf("a lane writing %d B/s got capped at %.0f", quietRate/2, s.rate)
	}
	// Above quietRate, but under an eighth of what the lane can write.
	s.wrote(second+1, 90<<10)
	if s.slow(second+2, s.sentRate(second+2), 1<<20) || s.rate != 0 {
		t.Fatalf("a lane writing %.0f B/s of 1 MiB/s got capped at %.0f", s.sentRate(second+2), s.rate)
	}
	if got := s.allow(2 * second); got != -1 {
		t.Fatalf("allow() = %d without a cap, want -1", got)
	}
}

func TestPaceCreditFollowsTheRateWithoutBanking(t *testing.T) {
	s := pace{rate: 100 << 10}
	if got := s.allow(10 * int64(time.Millisecond)); got != 1024 {
		t.Fatalf("credit after 10 ms at 100 KiB/s = %d, want 1024", got)
	}
	s.wrote(10*int64(time.Millisecond), 3000)
	if got := s.allow(20 * int64(time.Millisecond)); got != 0 {
		t.Fatalf("credit after overspending = %d, want 0 until repaid", got)
	}
	burst := int(float64(100<<10) * paceBurst.Seconds())
	if got := s.allow(10 * second); got > burst {
		t.Fatalf("credit after a lull = %d, want at most %d", got, burst)
	}
}
