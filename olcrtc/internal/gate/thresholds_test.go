package gate

import "testing"

// ai-generated: whole file, unit cover for the thresholds a pair is judged
// by: its target's, with a provider's own throughput floors where the
// provider's relay carries less.

func TestThresholdsForKeepTheTargetsOwnForAProviderWithoutFloors(t *testing.T) {
	for _, p := range []string{"jitsi", "telemost", "wbstream"} {
		if got := Local.For(p); got != Local {
			t.Fatalf("Local.For(%s) = %+v, want Local", p, got)
		}
		if got := Link.For(p); got != Link {
			t.Fatalf("Link.For(%s) = %+v, want Link", p, got)
		}
	}
}

func TestThresholdsForSaluteJazzLowerTheThroughputFloorsAlone(t *testing.T) {
	floors, ok := providerFloors["salutejazz"]
	if !ok {
		t.Fatal("salutejazz has no floors of its own")
	}
	if floors.Down >= Local.ThroughputDownBps || floors.Up >= Local.ThroughputUpBps {
		t.Fatalf("salutejazz floors %+v do not lower Local's", floors)
	}
	sj := Local.For("salutejazz")
	if sj.ThroughputDownBps != floors.Down || sj.ThroughputUpBps != floors.Up {
		t.Fatalf("Local.For(salutejazz) floors = %v down, %v up, want %+v",
			sj.ThroughputDownBps, sj.ThroughputUpBps, floors)
	}
	sj.ThroughputDownBps, sj.ThroughputUpBps = Local.ThroughputDownBps, Local.ThroughputUpBps
	if sj != Local {
		t.Fatalf("Local.For(salutejazz) changed more than the floors: %+v", sj)
	}
	// A provider's floor never raises a target's own: the link target's are
	// its own measurement.
	link := Link.For("salutejazz")
	if link.ThroughputDownBps != min(Link.ThroughputDownBps, floors.Down) ||
		link.ThroughputUpBps != min(Link.ThroughputUpBps, floors.Up) {
		t.Fatalf("Link.For(salutejazz) = %v down, %v up, want the lower of each",
			link.ThroughputDownBps, link.ThroughputUpBps)
	}
}
