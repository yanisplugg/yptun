package vp8channel

import (
	"testing"

	runtimecfg "github.com/openlibrecommunity/olcrtc/internal/runtime"
)

// The packet queues between the track reader, KCP and the paced writer used
// to keep their server size on a phone: 4096 inbound and 1536 outbound slots
// of ~1.4 KB each, 8 MB of headroom in a process allowed about 50. Measured
// with the phone's runtime profile against a Telemost room, an Ookla-shaped
// load took the client from 45 MB to 61 MB of RSS, and the extension that
// carries it dies at about 48. The constrained profile now sizes them to one
// KCP window.
func TestConstrainedProfileShrinksThePacketQueues(t *testing.T) {
	t.Cleanup(runtimecfg.ResetBufferProfileForTest)
	runtimecfg.ResetBufferProfileForTest()
	if in, out := inboundQueueSizeFor(), outboundQueueSizeFor(); in != inboundQueueSize || out != outboundQueueSize {
		t.Fatalf("server profile queues = %d/%d, want %d/%d", in, out, inboundQueueSize, outboundQueueSize)
	}
	runtimecfg.UseConstrainedBuffers()
	in, out := inboundQueueSizeFor(), outboundQueueSizeFor()
	if in >= inboundQueueSize || out >= outboundQueueSize {
		t.Fatalf("constrained queues = %d/%d are not smaller than %d/%d", in, out, inboundQueueSize, outboundQueueSize)
	}
	// One receive window of packets is all the inbound side can usefully hold:
	// past that KCP's own window has already stopped the sender.
	if rcv := kcpRcvWindow(); in < rcv {
		t.Fatalf("constrained inbound queue %d holds less than the KCP receive window %d", in, rcv)
	}
}
