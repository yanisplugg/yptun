package vp8channel

import (
	"bytes"
	"encoding/binary"
)

// vp8Keepalive is a minimal valid VP8 keyframe. It heads every frame we emit
// so an SFU that validates the bitstream keeps forwarding the track.
var vp8Keepalive = []byte{ //nolint:gochecknoglobals // package-level state intentional
	0x30, 0x01, 0x00, 0x9d, 0x01, 0x2a, 0x10, 0x00,
	0x10, 0x00, 0x00, 0x47, 0x08, 0x85, 0x85, 0x88,
	0x99, 0x84, 0x88, 0xfc,
}

// KCP data frames are disguised as valid VP8 frames so Telemost SFU lets them
// through. The SFU validates the VP8 bitstream and drops frames that don't
// look like real VP8 - so we prepend the keepalive keyframe and append our
// header + payload after it. Wire layout:
//
//	[0..20]    = vp8Keepalive (valid VP8 keyframe, passes SFU inspection)
//	[20..24]   = binding token derived from client-id (big-endian uint32)
//	[24..28]   = sender's session epoch (src, big-endian uint32)
//	[28..32]   = destination epoch (dst, big-endian uint32; 0 = broadcast)
//	[32..36]   = CRC32(token || src || dst)
//	[36..]     = raw KCP packet bytes
//
// The dst field lets the server address downlink to one specific client even
// though the SFU forwards every frame to every participant: a receiver drops
// any frame whose dst is non-zero and not its own epoch. dst==0 is a broadcast
// used before the sender has learned the receiver's epoch (CLIENT_HELLO and
// the server's pre-latch frames). This mirrors the src+dst scheme the jitsi
// engine already uses (internal/engine/jitsi).
const (
	tokenOff    = 20
	srcOff      = 24
	dstOff      = 28
	crcOff      = 32
	epochHdrLen = 36
	// controlEpochFlag marks an epoch as belonging to the control-plane
	// KCP session. The high bit of the epoch uint32 is reserved for this
	// purpose; data-plane epochs are generated with the high bit clear.
	controlEpochFlag uint32 = 0x80000000
)

// kcpBatchMagic prefixes a VP8 sample that carries several length-prefixed
// KCP packets instead of a single one.
var kcpBatchMagic = [4]byte{'O', 'L', 'K', 'B'} //nolint:gochecknoglobals // wire marker

func deliverKCPPayload(rt *kcpRuntime, payload []byte) {
	if rt == nil || len(payload) == 0 {
		return
	}
	splitKCPPayload(payload, rt.deliver)
}

func splitKCPPayload(payload []byte, deliver func([]byte)) {
	if len(payload) < len(kcpBatchMagic) ||
		!bytes.Equal(payload[:len(kcpBatchMagic)], kcpBatchMagic[:]) {
		deliver(payload)
		return
	}

	rest := payload[len(kcpBatchMagic):]
	for len(rest) > 0 {
		if len(rest) < 2 {
			return
		}
		size := int(binary.BigEndian.Uint16(rest[:2]))
		rest = rest[2:]
		if size == 0 || len(rest) < size {
			return
		}
		deliver(rest[:size])
		rest = rest[size:]
	}
}
