package jitsi

import "testing"

// What one receiver can have outstanding is two allowances on top of each
// other: what the sender has handed the bridge and not seen taken off
// (relayWindow) and what may pile up in the sender's own association
// (bridgeBacklogHighWater). The JVB holds about 2 MB per receiver, and what it
// holds is EndpointMessage JSON - base64 of our frames, about four bytes for
// every three - so the two allowances together have to stay under that with
// room for the receiver's own messages.
//
// Written down because the two were chosen in different places for different
// reasons, and the sum is what the bridge actually sees (#15).
func TestWhatOneReceiverCanHaveOutstandingFitsTheBridge(t *testing.T) {
	const (
		jvbPerReceiver = 2 << 20
		base64Overhead = 4.0 / 3.0
		// The receiver's own traffic and the bridge's bookkeeping share that
		// buffer, so filling it exactly is not the goal.
		headroom = 0.75
	)

	outstanding := relayWindow + bridgeBacklogHighWater
	onTheBridge := float64(outstanding) * base64Overhead
	budget := float64(jvbPerReceiver) * headroom

	if onTheBridge > budget {
		t.Fatalf(
			"one receiver may have %d KiB outstanding, %d KiB at the bridge, over the %d KiB budget",
			outstanding/1024, int(onTheBridge)/1024, int(budget)/1024,
		)
	}
}
