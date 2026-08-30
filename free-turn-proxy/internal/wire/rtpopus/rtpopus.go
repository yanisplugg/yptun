// SPDX-License-Identifier: MIT

// Package rtpopus реализует профиль обфускации с мимикрией под поток RTP/Opus (RFC 3550).
package rtpopus

import (
	"crypto/cipher"
	"crypto/rand"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"sync/atomic"

	"golang.org/x/crypto/chacha20poly1305"
)

const (
	KeyLen     = 32
	rtpHdrLen  = 12
	nonceLen   = 12
	tagLen     = 16
	headerLen  = rtpHdrLen + nonceLen
	HeaderLen  = headerLen
	Overhead   = headerLen + tagLen
	rtpVersion = 0x80
	rtpPT      = 0x6F
	tsStep     = 960
)

func MaxWire(payloadLen int) int { return Overhead + payloadLen }

// State инкапсулирует AEAD-шифр на основе общего ключа.
type State struct {
	aead cipher.AEAD
}

func NewState(key []byte) (*State, error) {
	if len(key) != KeyLen {
		return nil, fmt.Errorf("rtpopus:key must be %d bytes (got %d)", KeyLen, len(key))
	}
	aead, err := chacha20poly1305.New(key)
	if err != nil {
		return nil, fmt.Errorf("rtpopus:aead init: %w", err)
	}
	return &State{aead: aead}, nil
}

// Conn хранит состояние RTP-сессии и счётчики пакетов.
type Conn struct {
	state     *State
	sessionID [4]byte
	ssrc      [4]byte
	counter   atomic.Uint64
	seq       atomic.Uint32
	timestamp atomic.Uint32
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
		return nil, errors.New("rtpopus:nil state")
	}
	c := &Conn{state: state}

	var rnd [16]byte
	if _, err := rand.Read(rnd[:]); err != nil {
		return nil, fmt.Errorf("rtpopus:rand init: %w", err)
	}
	copy(c.sessionID[:], rnd[0:4])
	copy(c.ssrc[:], rnd[4:8])
	if isServer {
		c.sessionID[0] |= 0x80
		c.ssrc[0] |= 0x80
	} else {
		c.sessionID[0] &^= 0x80
		c.ssrc[0] &^= 0x80
	}
	c.seq.Store(uint32(binary.BigEndian.Uint16(rnd[8:10])))
	c.timestamp.Store(binary.BigEndian.Uint32(rnd[10:14]))

	var cb [8]byte
	if _, err := rand.Read(cb[:]); err != nil {
		return nil, fmt.Errorf("rtpopus:counter rand: %w", err)
	}
	c.counter.Store(binary.BigEndian.Uint64(cb[:]))
	return c, nil
}

func (*Conn) HeaderLen() int    { return headerLen }
func (*Conn) Overhead() int     { return Overhead }
func (*Conn) MaxWire(n int) int { return Overhead + n }

// WrapInto шифрует payload и записывает RTP-пакет в dst.
func (c *Conn) WrapInto(dst, payload []byte) (int, error) {
	if len(dst) < Overhead+len(payload) {
		return 0, errors.New("rtpopus:dst buffer too small")
	}
	copy(dst[headerLen:], payload)
	return c.WrapInPlace(dst, len(payload))
}

// WrapInPlace шифрует plaintext на месте в переданном буфере.
func (c *Conn) WrapInPlace(buf []byte, plainLen int) (int, error) {
	wireLen := Overhead + plainLen
	if len(buf) < wireLen {
		return 0, errors.New("rtpopus:dst buffer too small")
	}

	buf[0] = rtpVersion
	buf[1] = rtpPT
	seq := uint16(c.seq.Add(1) - 1) //nolint:gosec // RTP sequence number is intentionally mod 2^16
	binary.BigEndian.PutUint16(buf[2:4], seq)
	ts := c.timestamp.Add(tsStep) - tsStep
	binary.BigEndian.PutUint32(buf[4:8], ts)
	copy(buf[8:12], c.ssrc[:])

	noncePos := rtpHdrLen
	copy(buf[noncePos:noncePos+4], c.sessionID[:])
	ctr := c.counter.Add(1) - 1
	binary.BigEndian.PutUint64(buf[noncePos+4:noncePos+nonceLen], ctr)

	nonce := buf[noncePos : noncePos+nonceLen]
	aad := buf[:headerLen]
	ctPos := headerLen
	c.state.aead.Seal(buf[ctPos:ctPos], nonce, buf[ctPos:ctPos+plainLen], aad)

	return wireLen, nil
}

func (c *Conn) Unwrap(wire, dst []byte) (int, error) {
	plain, err := c.UnwrapInPlace(wire)
	if err != nil {
		return 0, err
	}
	if len(plain) > len(dst) {
		return 0, errors.New("rtpopus:dst buffer too small")
	}
	copy(dst[:len(plain)], plain)
	return len(plain), nil
}

// UnwrapInPlace расшифровывает RTP-пакет на месте в переданном буфере wire.
func (c *Conn) UnwrapInPlace(wire []byte) ([]byte, error) {
	if len(wire) < Overhead {
		return nil, errors.New("rtpopus:packet too short")
	}
	nonce := wire[rtpHdrLen : rtpHdrLen+nonceLen]
	aad := wire[:headerLen]
	ct := wire[headerLen:]

	plain, err := c.state.aead.Open(ct[:0], nonce, ct, aad)
	if err != nil {
		return nil, fmt.Errorf("rtpopus:AEAD open: %w", err)
	}
	return plain, nil
}

func GenKeyHex() (string, error) {
	key := make([]byte, KeyLen)
	if _, err := rand.Read(key); err != nil {
		return "", fmt.Errorf("rtpopus:key gen: %w", err)
	}
	return hex.EncodeToString(key), nil
}

// DecodeKey декодирует ключ обфускации из шестнадцатеричной строки.
func DecodeKey(enabled bool, raw string) ([]byte, error) {
	if !enabled {
		return nil, nil
	}
	if raw == "" {
		return nil, errors.New("-obf-profile != none requires -obf-key")
	}
	key, err := hex.DecodeString(raw)
	if err != nil {
		return nil, fmt.Errorf("-obf-key invalid hex: %w", err)
	}
	if len(key) != KeyLen {
		return nil, fmt.Errorf("-obf-key must decode to %d bytes (got %d)", KeyLen, len(key))
	}
	return key, nil
}
