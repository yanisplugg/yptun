// SPDX-License-Identifier: MIT

// Package rtpopus2 реализует wire-профиль обфускации с RTP-заголовком и one-byte extensions (RFC 8285).
package rtpopus2

import (
	"crypto/cipher"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"sync/atomic"

	"golang.org/x/crypto/chacha20poly1305"
)

const (
	KeyLen     = 32
	rtpHdrLen  = 12
	rtpExtLen  = 12
	nonceLen   = 12
	tagLen     = 16
	headerLen  = rtpHdrLen + rtpExtLen + nonceLen
	overhead   = headerLen + tagLen
	rtpVersion = 0x90
	rtpPT      = 0x6F
	rtpMarker  = 0x80
	tsStep     = 960

	extAudioLevelHdr = 0x10
	extTransportHdr  = 0x21
)

func MaxWire(payloadLen int) int { return overhead + payloadLen }

// State хранит AEAD-экземпляр общего ключа.
type State struct {
	aead cipher.AEAD
}

func NewState(key []byte) (*State, error) {
	if len(key) != KeyLen {
		return nil, fmt.Errorf("rtpopus2:key must be %d bytes (got %d)", KeyLen, len(key))
	}
	aead, err := chacha20poly1305.New(key)
	if err != nil {
		return nil, fmt.Errorf("rtpopus2:aead init: %w", err)
	}
	return &State{aead: aead}, nil
}

type Conn struct {
	state     *State
	sessionID [4]byte
	ssrc      [4]byte
	counter   atomic.Uint64
	seq       atomic.Uint32
	timestamp atomic.Uint32
	tcc       atomic.Uint32
	firstPkt  atomic.Bool
}

func NewConn(key []byte, isServer bool) (*Conn, error) {
	s, err := NewState(key)
	if err != nil {
		return nil, err
	}
	return NewConnFromState(s, isServer)
}

func NewConnFromState(state *State, isServer bool) (*Conn, error) {
	if state == nil {
		return nil, errors.New("rtpopus2:nil state")
	}
	c := &Conn{state: state}

	var rnd [16]byte
	if _, err := rand.Read(rnd[:]); err != nil {
		return nil, fmt.Errorf("rtpopus2:rand init: %w", err)
	}
	copy(c.sessionID[:], rnd[0:4])
	copy(c.ssrc[:], rnd[4:8])
	if isServer {
		c.sessionID[0] |= 0x80
	} else {
		c.sessionID[0] &^= 0x80
	}
	c.seq.Store(uint32(binary.BigEndian.Uint16(rnd[8:10])))
	c.timestamp.Store(binary.BigEndian.Uint32(rnd[10:14]))
	c.tcc.Store(uint32(binary.BigEndian.Uint16(rnd[14:16])))

	var cb [8]byte
	if _, err := rand.Read(cb[:]); err != nil {
		return nil, fmt.Errorf("rtpopus2:counter rand: %w", err)
	}
	c.counter.Store(binary.BigEndian.Uint64(cb[:]))
	return c, nil
}

func (*Conn) HeaderLen() int    { return headerLen }
func (*Conn) Overhead() int     { return overhead }
func (*Conn) MaxWire(n int) int { return overhead + n }

// WrapInto кодирует payload в dst.
func (c *Conn) WrapInto(dst, payload []byte) (int, error) {
	if len(dst) < overhead+len(payload) {
		return 0, errors.New("rtpopus2:dst buffer too small")
	}
	copy(dst[headerLen:], payload)
	return c.WrapInPlace(dst, len(payload))
}

// WrapInPlace кодирует plaintext из buf[HeaderLen:HeaderLen+plainLen] на месте.
func (c *Conn) WrapInPlace(buf []byte, plainLen int) (int, error) {
	wireLen := overhead + plainLen
	if len(buf) < wireLen {
		return 0, errors.New("rtpopus2:dst buffer too small")
	}

	buf[0] = rtpVersion
	pt := byte(rtpPT)
	if c.firstPkt.CompareAndSwap(false, true) {
		pt |= rtpMarker
	}
	buf[1] = pt
	seq := uint16(c.seq.Add(1) - 1) //nolint:gosec // RTP sequence mod 2^16
	binary.BigEndian.PutUint16(buf[2:4], seq)
	ts := c.timestamp.Add(tsStep) - tsStep
	binary.BigEndian.PutUint32(buf[4:8], ts)
	copy(buf[8:12], c.ssrc[:])

	buf[12] = 0xBE
	buf[13] = 0xDE
	binary.BigEndian.PutUint16(buf[14:16], 2)
	buf[16] = extAudioLevelHdr
	buf[17] = 0x80 | byte(seq&0x3F) //nolint:gosec // V=1, level варьируется
	buf[18] = extTransportHdr
	tcc := uint16(c.tcc.Add(1) - 1) //nolint:gosec // transport-cc seq mod 2^16
	binary.BigEndian.PutUint16(buf[19:21], tcc)
	buf[21], buf[22], buf[23] = 0, 0, 0

	copy(buf[24:28], c.sessionID[:])
	ctr := c.counter.Add(1) - 1
	binary.BigEndian.PutUint64(buf[28:headerLen], ctr)

	nonce := buf[24:headerLen]
	aad := buf[:headerLen]
	c.state.aead.Seal(buf[headerLen:headerLen], nonce, buf[headerLen:headerLen+plainLen], aad)
	return wireLen, nil
}

func (c *Conn) Unwrap(wire, dst []byte) (int, error) {
	plain, err := c.UnwrapInPlace(wire)
	if err != nil {
		return 0, err
	}
	if len(plain) > len(dst) {
		return 0, errors.New("rtpopus2:dst buffer too small")
	}
	copy(dst[:len(plain)], plain)
	return len(plain), nil
}

// UnwrapInPlace декодирует пакет на месте в переданном срезе wire.
func (c *Conn) UnwrapInPlace(wire []byte) ([]byte, error) {
	if len(wire) < overhead {
		return nil, errors.New("rtpopus2:packet too short")
	}
	nonce := wire[rtpHdrLen+rtpExtLen : headerLen]
	aad := wire[:headerLen]
	ct := wire[headerLen:]

	plain, err := c.state.aead.Open(ct[:0], nonce, ct, aad)
	if err != nil {
		return nil, fmt.Errorf("rtpopus2:AEAD open: %w", err)
	}
	return plain, nil
}
