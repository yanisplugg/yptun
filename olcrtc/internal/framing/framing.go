// Package framing implements the length-prefixed JSON message framing used by
// the olcrtc control and handshake protocols.
//
// Wire format: 4-byte big-endian length followed by that many bytes of body.
// Body interpretation (JSON, protobuf, etc.) is up to the caller; this package
// only deals with byte-level framing.
package framing

import (
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
)

// ErrFrameTooLarge is returned when a frame exceeds the configured max size.
var ErrFrameTooLarge = errors.New("frame too large")

// headerSize is the length prefix: a big-endian uint32.
const headerSize = 4

// WriteJSON marshals msg as JSON and writes it framed.
func WriteJSON(w io.Writer, msg any, maxSize int) error {
	body, err := json.Marshal(msg)
	if err != nil {
		return fmt.Errorf("marshal: %w", err)
	}
	return WriteBytes(w, body, maxSize)
}

// WriteBytes writes body as a single length-prefixed frame, with one Write.
//
// One call rather than a header and then a body, because every layer under
// this one turns each Write into a unit of its own: smux makes it a frame, the
// record layer a record, the relay a message. Written as two, a frame was two
// of each, and a relay that drops or reorders one message leaves the reader
// with a body where a length belongs - `{"ve` read as a four-byte size is
// "frame too large" and the end of the session (olcbox#25). Written as one,
// the length and the body arrive together or not at all.
func WriteBytes(w io.Writer, body []byte, maxSize int) error {
	if maxSize > 0 && len(body) > maxSize {
		return fmt.Errorf("%w: %d > %d", ErrFrameTooLarge, len(body), maxSize)
	}
	frame := make([]byte, headerSize+len(body))
	binary.BigEndian.PutUint32(frame, uint32(len(body))) //nolint:gosec // size bounded by maxSize check
	copy(frame[headerSize:], body)
	if _, err := w.Write(frame); err != nil {
		return fmt.Errorf("write frame: %w", err)
	}
	return nil
}

// ReadBytes reads one length-prefixed frame from r.
func ReadBytes(r io.Reader, maxSize int) ([]byte, error) {
	var hdr [headerSize]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return nil, fmt.Errorf("read hdr: %w", err)
	}
	n := binary.BigEndian.Uint32(hdr[:])
	if maxSize > 0 && n > uint32(maxSize) { //nolint:gosec // maxSize is non-negative
		return nil, fmt.Errorf("%w: %d > %d", ErrFrameTooLarge, n, maxSize)
	}
	buf := make([]byte, n)
	if _, err := io.ReadFull(r, buf); err != nil {
		return nil, fmt.Errorf("read body: %w", err)
	}
	return buf, nil
}
