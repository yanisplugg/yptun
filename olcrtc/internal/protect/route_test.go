package protect

import "testing"

// ProbeRoute asks the host protector, not the network: the iOS pin refuses
// every socket while no physical interface exists, and that refusal is the
// only signal the engine has that dialing is pointless right now.
func TestProbeRouteFollowsProtector(t *testing.T) {
	restoreProtector(t)

	SetProtector(nil)
	if !ProbeRoute() {
		t.Fatal("ProbeRoute() = false without a protector, want true")
	}

	var asked int
	SetProtector(func(int) bool { asked++; return false })
	if ProbeRoute() {
		t.Fatal("ProbeRoute() = true while the protector refuses every socket")
	}
	if asked == 0 {
		t.Fatal("ProbeRoute() never asked the protector")
	}

	SetProtector(func(int) bool { return true })
	if !ProbeRoute() {
		t.Fatal("ProbeRoute() = false while the protector accepts sockets")
	}
}
