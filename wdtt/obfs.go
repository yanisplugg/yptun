// SPDX-License-Identifier: MIT
// obfs.go — WebRTC SRTP-like obfuscation for DTLS traffic
// Each UDP packet is wrapped in an RTP header making it indistinguishable
// from a real WebRTC OPUS audio stream to DPI systems.
//
// Packet format:
//   [RTP Header 12 bytes][ChaCha20-Poly1305 payload+tag][Padding 0-N bytes][PadLen 1 byte]
//
// The RTP header fields (SSRC + SeqNum + Timestamp) form the 12-byte AEAD
// nonce, so no separate nonce prefix is needed. The 12-byte header is the
// AEAD's associated data.
//
// Unwrap still recognizes a 24-byte (base + RFC 8285 one-byte-header
// extension) variant on receive by checking the X bit — that longer format
// existed briefly and some deployed servers may still send it — but this
// client always WRITES the plain 12-byte form.

package wdtt

import (
	"crypto/cipher"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"strings"
	"sync"

	"golang.org/x/crypto/chacha20poly1305"
)

var aeadCache sync.Map

const replayWindowSpan = uint64(4096 * 961)
const replayWindowMaxEntries = 8192

type replayWindow struct {
	mu          sync.Mutex
	seen        map[[12]byte]uint64
	ssrc        uint32
	highestTime uint64
	initialized bool
}

func (w *replayWindow) accept(wire []byte) bool {
	if len(wire) < rtpHeaderLenLegacy {
		return false
	}
	ssrc := binary.BigEndian.Uint32(wire[8:12])
	seq := binary.BigEndian.Uint16(wire[2:4])
	ts := binary.BigEndian.Uint32(wire[4:8])
	var nonce [12]byte
	copy(nonce[:], obfsBuildNonce(ssrc, seq, ts))
	w.mu.Lock()
	defer w.mu.Unlock()
	if !w.initialized {
		w.ssrc = ssrc
		w.highestTime = uint64(ts)
		w.seen = make(map[[12]byte]uint64, 4096)
		w.initialized = true
	} else if w.ssrc != ssrc {
		return false
	}
	if _, exists := w.seen[nonce]; exists {
		return false
	}
	base := w.highestTime &^ uint64(0xffffffff)
	extended := base | uint64(ts)
	if extended+(1<<31) < w.highestTime {
		extended += 1 << 32
	} else if extended > w.highestTime+(1<<31) && extended >= 1<<32 {
		extended -= 1 << 32
	}
	if extended+replayWindowSpan < w.highestTime {
		return false
	}
	if extended > w.highestTime {
		w.highestTime = extended
	}
	if len(w.seen) >= replayWindowMaxEntries {
		cutoff := w.highestTime - min(w.highestTime, replayWindowSpan)
		for value, packetTime := range w.seen {
			if packetTime < cutoff {
				delete(w.seen, value)
			}
		}
		if len(w.seen) >= replayWindowMaxEntries {
			return false
		}
	}
	w.seen[nonce] = extended
	return true
}

func getAEAD(key []byte) (cipher.AEAD, error) {
	if len(key) != wrapKeyLen {
		return nil, fmt.Errorf("obfs: key must be %d bytes", wrapKeyLen)
	}
	keyStr := string(key)
	if val, ok := aeadCache.Load(keyStr); ok {
		return val.(cipher.AEAD), nil
	}
	aead, err := chacha20poly1305.New(key)
	if err != nil {
		return nil, err
	}
	aeadCache.Store(keyStr, aead)
	return aead, nil
}

// ─── Configuration ───

// ObfsConfig holds per-session obfuscation parameters.
type ObfsConfig struct {
	SSRC        uint32 // Synchronization Source — random per session
	PayloadType uint8  // RTP payload type (111 = OPUS dynamic)
	PaddingMax  int    // Max random padding bytes appended
}

// NewObfsConfig creates a config with random SSRC and sane defaults.
// mode: "audio" (OPUS-like, PT 111) or "video" (H264-like, PT 96).
func NewObfsConfig(mode string) (*ObfsConfig, error) {
	var buf [4]byte
	if _, err := rand.Read(buf[:]); err != nil {
		return nil, err
	}

	pt := uint8(111)
	pad := 24
	if normalizeObfsMode(mode) == "video" {
		pt = 96
		pad = 60
	}

	return &ObfsConfig{
		SSRC:        binary.BigEndian.Uint32(buf[:]),
		PayloadType: pt,
		PaddingMax:  pad,
	}, nil
}

func normalizeObfsMode(mode string) string {
	if strings.EqualFold(strings.TrimSpace(mode), "video") {
		return "video"
	}
	return "audio"
}

// ─── Per-direction state (sequence + timestamp counters) ───

// ObfsState tracks monotonically increasing RTP sequence number and timestamp using a 48-bit packet counter.
type ObfsState struct {
	mu      sync.Mutex
	initSeq uint16
	initTs  uint32
	count   uint64
}

// NewObfsState creates a state with random initial seq/ts and count=0.
func NewObfsState() (*ObfsState, error) {
	var buf [6]byte
	if _, err := rand.Read(buf[:]); err != nil {
		return nil, err
	}
	return &ObfsState{
		initSeq: binary.BigEndian.Uint16(buf[0:2]),
		initTs:  binary.BigEndian.Uint32(buf[2:6]),
		count:   0,
	}, nil
}

// ─── Nonce derivation ───

// obfsBuildNonce deterministically builds a 12-byte AEAD nonce from RTP fields.
//
//	[SSRC 4B][SeqNum 2B][0x00 0x00][Timestamp 4B]
func obfsBuildNonce(ssrc uint32, seq uint16, ts uint32) []byte {
	n := make([]byte, 12)
	binary.BigEndian.PutUint32(n[0:4], ssrc)
	binary.BigEndian.PutUint16(n[4:6], seq)
	// n[6], n[7] = 0x00 — zero padding for unique nonce space
	binary.BigEndian.PutUint32(n[8:12], ts)
	return n
}

// rtpHeaderLenFull is the base 12-byte RTP header plus a one-byte-header RTP
// extension (RFC 8285) carrying abs-send-time (3 bytes) and
// transport-wide-cc (2 bytes), padded to a 4-byte boundary — the same shape
// real WebRTC clients (and VK calls) send on essentially every packet.
// rtpHeaderLenLegacy is the bare 12-byte header (no extension), for
// compatibility with servers running before this extension was added.
const (
	rtpHeaderLenFull   = 24
	rtpHeaderLenLegacy = 12
)

// ─── Wrap (encrypt + add RTP header) ───

// obfsWrapPacket wraps a plaintext payload into an RTP-like packet with authenticated encryption.
// The output looks like:
//
//	[V=2,P=1,X=0,CC=0 | PT | SeqNum | Timestamp | SSRC | encrypted_payload | padding | padLen]
func obfsWrapPacket(key, payload []byte, cfg *ObfsConfig, state *ObfsState) ([]byte, error) {
	if len(key) != wrapKeyLen {
		return nil, fmt.Errorf("obfs: key must be %d bytes (got %d)", wrapKeyLen, len(key))
	}
	if len(payload) == 0 {
		return nil, errors.New("obfs: empty payload")
	}

	state.mu.Lock()
	c := state.count
	state.count++
	state.mu.Unlock()

	seq := state.initSeq + uint16(c)
	ts := state.initTs + uint32(c)*960 + uint32(c>>16)

	// Build nonce from RTP fields
	nonce := obfsBuildNonce(cfg.SSRC, seq, ts)

	// Determine padding
	padRand := 0
	if cfg.PaddingMax > 0 {
		var rndBuf [1]byte
		if _, err := rand.Read(rndBuf[:]); err != nil {
			return nil, fmt.Errorf("obfs: padding random: %w", err)
		}
		padRand = int(rndBuf[0]) % cfg.PaddingMax
	}
	padTotal := padRand + 1 // +1 for the length byte itself

	headerLen := rtpHeaderLenLegacy

	// Allocate output: header + payload + AEAD tag + padTotal
	outLen := headerLen + len(payload) + chacha20poly1305.Overhead + padTotal
	out := make([]byte, outLen)

	// RTP Header (12 bytes, no extension).
	// Byte 0 bit layout: V(2) P(1) X(1) CC(4) — masks 0xC0/0x20/0x10/0x0F.
	out[0] = 0x80 | 0x20 // V=2, P=1 (padding present), X=0 (no extension)
	out[1] = cfg.PayloadType & 0x7F
	binary.BigEndian.PutUint16(out[2:4], seq)
	binary.BigEndian.PutUint32(out[4:8], ts)
	binary.BigEndian.PutUint32(out[8:12], cfg.SSRC)

	aead, err := getAEAD(key)
	if err != nil {
		return nil, fmt.Errorf("obfs: cipher init: %w", err)
	}
	sealed := aead.Seal(out[headerLen:headerLen], nonce, payload, out[:headerLen])

	// Random padding bytes
	padStart := headerLen + len(sealed)
	if padRand > 0 {
		if _, err := rand.Read(out[padStart : padStart+padRand]); err != nil {
			return nil, fmt.Errorf("obfs: padding bytes: %w", err)
		}
	}

	// Last byte = total padding count (RFC 3550 §5.1)
	out[outLen-1] = byte(padTotal)

	return out, nil
}

// ─── Unwrap (strip RTP header + decrypt) ───

// obfsUnwrapPacket strips the RTP header+extension, removes padding, and
// decrypts the payload. Returns number of plaintext bytes written to dst.
func obfsUnwrapPacket(key, wire, dst []byte) (int, error) {
	if len(key) != wrapKeyLen {
		return 0, fmt.Errorf("obfs: key must be %d bytes (got %d)", wrapKeyLen, len(key))
	}
	if len(wire) < rtpHeaderLenLegacy+1 { // minimum: bare 12-byte header + at least 1 byte
		return 0, errors.New("obfs: packet too short")
	}

	// Validate RTP version
	if (wire[0] >> 6) != 2 {
		return 0, errors.New("obfs: not RTP v2")
	}

	// Header length is determined by the X bit (extension present) of the
	// INCOMING packet, not by our own LegacyHeader config — this lets a
	// single client transparently talk to both old (12-byte, no extension)
	// and new (24-byte, with extension) servers without needing to know in
	// advance which one it's receiving from.
	headerLen := rtpHeaderLenLegacy
	if wire[0]&0x10 != 0 { // X bit
		headerLen = rtpHeaderLenFull
	}
	if len(wire) < headerLen+1 {
		return 0, errors.New("obfs: packet too short for declared extension")
	}

	// Extract RTP fields for nonce
	seq := binary.BigEndian.Uint16(wire[2:4])
	ts := binary.BigEndian.Uint32(wire[4:8])
	ssrc := binary.BigEndian.Uint32(wire[8:12])

	// Handle padding (P bit)
	payloadEnd := len(wire)
	if wire[0]&0x20 != 0 {
		padLen := int(wire[len(wire)-1])
		if padLen == 0 || padLen > payloadEnd-headerLen {
			return 0, fmt.Errorf("obfs: invalid padding length %d", padLen)
		}
		payloadEnd -= padLen
	}

	ciphertextLen := payloadEnd - headerLen
	if ciphertextLen <= chacha20poly1305.Overhead {
		return 0, errors.New("obfs: no payload after stripping header/padding")
	}
	if ciphertextLen-chacha20poly1305.Overhead > len(dst) {
		return 0, errors.New("obfs: dst buffer too small")
	}

	// Build nonce and decrypt
	nonce := obfsBuildNonce(ssrc, seq, ts)
	aead, err := getAEAD(key)
	if err != nil {
		return 0, fmt.Errorf("obfs: cipher init: %w", err)
	}
	plain, err := aead.Open(dst[:0], nonce, wire[headerLen:payloadEnd], wire[:headerLen])
	if err != nil {
		return 0, fmt.Errorf("obfs: auth: %w", err)
	}

	return len(plain), nil
}

// ─── Detection ───

// obfsIsRTPPacket checks if a raw UDP packet looks like our obfuscated RTP.
// Used by the server and client to reject non-obfuscated packets.
func obfsIsRTPPacket(wire []byte) bool {
	if len(wire) < rtpHeaderLenLegacy+1 {
		return false
	}
	// RTP version must be 2
	if (wire[0] >> 6) != 2 {
		return false
	}
	// Our payload types: 111 (audio) or 96 (video)
	pt := wire[1] & 0x7F
	return pt == 111 || pt == 96
}
