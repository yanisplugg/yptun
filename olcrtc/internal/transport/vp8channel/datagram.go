package vp8channel

import (
	"bytes"
	"encoding/binary"
	"errors"
	"fmt"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/transport"
)

// Lossy datagrams ride the same VP8 track as KCP, tagged so the receiver
// hands them to the datagram callback instead of the KCP plane. A single
// datagram is OLUD + payload; the writer packs several bound for the same
// destination into OLUB + (uint16 length, datagram)*.
//
// ai-generated: datagram lane, ported from the fork onto the split writer.
const datagramQueueSize = 4096

var (
	datagramMagic      = [4]byte{'O', 'L', 'U', 'D'} //nolint:gochecknoglobals // wire marker
	datagramBatchMagic = [4]byte{'O', 'L', 'U', 'B'} //nolint:gochecknoglobals // wire marker
)

// ErrDatagramQueueFull is returned when a lossy datagram cannot be queued.
var ErrDatagramQueueFull = errors.New("vp8channel datagram queue full")

// SendDatagram implements transport.DatagramTransport: on the client the
// datagram goes to the latched peer, before that as a broadcast.
func (p *streamTransport) SendDatagram(data []byte) error {
	return p.enqueueDatagram(p.peerEpoch.Load(), data)
}

// SendDatagramTo implements transport.PeerDatagramTransport.
func (p *streamTransport) SendDatagramTo(peerID string, data []byte) error {
	epoch, err := parsePeerID(peerID)
	if err != nil {
		return fmt.Errorf("vp8channel: invalid peerID %q: %w", peerID, err)
	}
	return p.enqueueDatagram(epoch, data)
}

// DatagramCanSend reports whether the lossy queue has room. Datagrams never
// touch KCP, so unlike CanSend this needs no live KCP runtime: an open
// transport whose provider accepts writes is enough.
func (p *streamTransport) DatagramCanSend() bool {
	return !p.closed.Load() && p.stream.CanSend() &&
		len(p.datagram) < cap(p.datagram)*canSendHighWatermark/100
}

func (p *streamTransport) enqueueDatagram(dst uint32, data []byte) error {
	if p.closed.Load() {
		return ErrTransportClosed
	}
	if len(data)+epochHdrLen+len(datagramMagic) > defaultMaxPayloadSize {
		return transport.ErrTrafficPayloadTooLarge
	}
	hdr := buildEpochHeaderTo(p.bindingToken, p.localEpochValue(), dst)
	frame := make([]byte, 0, epochHdrLen+len(datagramMagic)+len(data))
	frame = append(frame, hdr[:]...)
	frame = append(frame, datagramMagic[:]...)
	frame = append(frame, data...)
	select {
	case p.datagram <- frame:
		return nil
	default:
		return ErrDatagramQueueFull
	}
}

// drainDatagramQueue drops whatever the writer has not sent; called on Close.
func (p *streamTransport) drainDatagramQueue() {
	for {
		select {
		case <-p.datagram:
		default:
			return
		}
	}
}

func splitDatagramPayload(payload []byte) ([]byte, bool) {
	if len(payload) < len(datagramMagic) || !bytes.Equal(payload[:len(datagramMagic)], datagramMagic[:]) {
		return nil, false
	}
	return payload[len(datagramMagic):], true
}

func splitDatagramBatchPayload(payload []byte) ([][]byte, bool) {
	if len(payload) < len(datagramBatchMagic) || !bytes.Equal(payload[:len(datagramBatchMagic)], datagramBatchMagic[:]) {
		return nil, false
	}
	rest := payload[len(datagramBatchMagic):]
	packets := make([][]byte, 0, 4)
	for len(rest) > 0 {
		if len(rest) < 2 {
			return nil, false
		}
		size := int(binary.BigEndian.Uint16(rest[:2]))
		rest = rest[2:]
		if size == 0 || len(rest) < size {
			return nil, false
		}
		packets = append(packets, rest[:size])
		rest = rest[size:]
	}
	return packets, true
}

// handleDatagramFrame delivers one datagram to the side's callback. On the
// server every foreign epoch is a peer; on the client only the latched one,
// and a datagram never drives peer-restart detection - the reliable plane
// owns that.
func (p *streamTransport) handleDatagramFrame(src uint32, payload []byte) {
	if p.serverMode {
		if p.onPeerDatagram != nil {
			p.onPeerDatagram(formatPeerID(src), payload)
		}
		return
	}
	if !p.peerConfirmed.Load() || src != p.peerEpoch.Load() {
		return
	}
	p.lastPeerFrameNano.Store(time.Now().UnixNano())
	if p.onDatagram != nil {
		p.onDatagram(payload)
	}
}

func (p *streamTransport) handleDatagramBatchFrame(src uint32, packets [][]byte) {
	for _, packet := range packets {
		if payload, ok := splitDatagramPayload(packet); ok {
			p.handleDatagramFrame(src, payload)
		}
	}
}

func sameEpochHeader(a, b []byte) bool {
	return len(a) >= epochHdrLen && len(b) >= epochHdrLen && bytes.Equal(a[:epochHdrLen], b[:epochHdrLen])
}
