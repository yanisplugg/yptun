package vp8channel

import (
	"crypto/rand"
	"encoding/binary"
	"hash/crc32"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/transport"
	"github.com/openlibrecommunity/olcrtc/internal/transport/common"
)

// Epoch header construction and parsing. These are pure functions over the
// wire layout documented in wire.go; the transport only adds thin accessors
// that supply its current epoch.

func buildEpochHeader(token, src uint32) [epochHdrLen]byte {
	return buildEpochHeaderTo(token, src, 0)
}

// buildEpochHeaderTo builds a frame header addressed to a specific destination
// epoch. dst==0 means broadcast (every participant accepts it).
func buildEpochHeaderTo(token, src, dst uint32) [epochHdrLen]byte {
	var hdr [epochHdrLen]byte
	copy(hdr[:], vp8Keepalive)
	binary.BigEndian.PutUint32(hdr[tokenOff:srcOff], token)
	binary.BigEndian.PutUint32(hdr[srcOff:dstOff], src)
	binary.BigEndian.PutUint32(hdr[dstOff:crcOff], dst)
	binary.BigEndian.PutUint32(hdr[crcOff:epochHdrLen], epochCRC(token, src, dst))
	return hdr
}

func epochCRC(token, src, dst uint32) uint32 {
	var buf [12]byte
	binary.BigEndian.PutUint32(buf[0:4], token)
	binary.BigEndian.PutUint32(buf[4:8], src)
	binary.BigEndian.PutUint32(buf[8:12], dst)
	return crc32.ChecksumIEEE(buf[:])
}

// parseEpochHeader returns (token, src, dst, ok). ok is false when the frame is
// too short or the CRC does not validate.
func parseEpochHeader(frame []byte) (uint32, uint32, uint32, bool) {
	if len(frame) < epochHdrLen {
		return 0, 0, 0, false
	}
	token := binary.BigEndian.Uint32(frame[tokenOff:srcOff])
	src := binary.BigEndian.Uint32(frame[srcOff:dstOff])
	dst := binary.BigEndian.Uint32(frame[dstOff:crcOff])
	gotCRC := binary.BigEndian.Uint32(frame[crcOff:epochHdrLen])
	return token, src, dst, gotCRC == epochCRC(token, src, dst)
}

// bindingToken derives the session token stamped into every frame. It is the
// shared derivation so all transports isolate concurrent sessions the same way.
func bindingToken(clientID string) uint32 {
	return common.BindingToken(clientID, "")
}

// channelBindingToken derives a per-session token so multiple olcrtc pairs
// in the same SFU room (e.g. concurrent e2e runs or real multi-tenant usage)
// do not accept each other's VP8/KCP frames.
func channelBindingToken(cfg transport.Config) uint32 {
	return common.BindingToken(cfg.ChannelID, cfg.RoomURL)
}

func randomEpoch() uint32 {
	var b [4]byte
	if _, err := rand.Read(b[:]); err != nil {
		// rand.Read on Linux essentially never fails; fall back to a
		// time-derived value rather than panic.
		//nolint:gosec // G115: bounded conversion verified by surrounding logic
		e := uint32(time.Now().UnixNano()) & ^controlEpochFlag
		if e == 0 {
			e = 1
		}
		return e
	}
	// Mask off the high bit: data epochs must not collide with control epochs.
	e := binary.BigEndian.Uint32(b[:]) & ^controlEpochFlag
	if e == 0 {
		e = 1
	}
	return e
}

// epochHeader returns the frame header used to tag every KCP packet sent in
// the current local session.
func (p *streamTransport) epochHeader() [epochHdrLen]byte {
	return buildEpochHeader(p.bindingToken, p.localEpochValue())
}

// controlEpochValue derives the control-plane epoch live from the current
// data epoch. Control epoch = localEpoch | controlEpochFlag. The high bit
// is set so the receiver can distinguish control frames from bulk data frames
// on the same RTP stream, and the server can correlate a client's data and
// control planes by arithmetic (controlEpoch &^ controlEpochFlag == dataEpoch).
// This must stay live (not latched) so that data epoch rotations on reconnect
// are visible to the server; with a latched control epoch the server could no
// longer correlate a new data epoch to the same client's control stream.
func (p *streamTransport) controlEpochValue() uint32 {
	return p.localEpochValue() | controlEpochFlag
}

// controlEpochHeader builds the epoch header for the control-plane track.
func (p *streamTransport) controlEpochHeader() [epochHdrLen]byte {
	return buildEpochHeader(p.bindingToken, p.controlEpochValue())
}

// rotateEpochHeader picks a fresh local epoch and returns its header.
func (p *streamTransport) rotateEpochHeader() [epochHdrLen]byte {
	p.epochMu.Lock()
	for {
		next := randomEpoch()
		if next != p.localEpoch {
			p.localEpoch = next
			break
		}
	}
	epoch := p.localEpoch
	p.epochMu.Unlock()
	return buildEpochHeader(p.bindingToken, epoch)
}

func (p *streamTransport) localEpochValue() uint32 {
	p.epochMu.RLock()
	defer p.epochMu.RUnlock()
	return p.localEpoch
}
