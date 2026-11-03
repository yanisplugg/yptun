package vp8channel

import "testing"

// pion reads every ICE candidate and every SRTP session with an 8192-byte
// buffer, so no RTP packet larger than that ever reaches a track reader; a
// read buffer beyond it is dead space per track, and the server keeps one
// reader per peer. Anything smaller would truncate a packet pion did deliver.
func TestTrackReadBufferMatchesWhatPionCanDeliver(t *testing.T) {
	const pionReceiveMTU = 8192
	if rtpBufSize != pionReceiveMTU {
		t.Fatalf("rtpBufSize = %d, want pion's receive MTU %d", rtpBufSize, pionReceiveMTU)
	}
}
