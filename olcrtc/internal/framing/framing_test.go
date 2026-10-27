package framing_test

import (
	"bytes"
	"errors"
	"io"
	"strings"
	"testing"

	"github.com/openlibrecommunity/olcrtc/internal/framing"
)

func TestRoundTripJSON(t *testing.T) {
	var buf bytes.Buffer
	type msg struct {
		Type string `json:"type"`
		N    int    `json:"n"`
	}
	in := msg{Type: "ping", N: 7}
	if err := framing.WriteJSON(&buf, in, 1024); err != nil {
		t.Fatalf("write: %v", err)
	}
	body, err := framing.ReadBytes(&buf, 1024)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	want := `{"type":"ping","n":7}`
	if string(body) != want {
		t.Fatalf("body=%q want=%q", body, want)
	}
}

func TestWriteTooLarge(t *testing.T) {
	var buf bytes.Buffer
	err := framing.WriteBytes(&buf, []byte(strings.Repeat("x", 10)), 5)
	if !errors.Is(err, framing.ErrFrameTooLarge) {
		t.Fatalf("want ErrFrameTooLarge, got %v", err)
	}
}

func TestReadTooLarge(t *testing.T) {
	var buf bytes.Buffer
	// Manually craft an oversized header.
	_, _ = buf.Write([]byte{0x00, 0x00, 0x10, 0x00}) // 4096
	_, err := framing.ReadBytes(&buf, 1024)
	if !errors.Is(err, framing.ErrFrameTooLarge) {
		t.Fatalf("want ErrFrameTooLarge, got %v", err)
	}
}

func TestReadTruncated(t *testing.T) {
	var buf bytes.Buffer
	_, _ = buf.Write([]byte{0x00, 0x00, 0x00, 0x04})
	_ = buf.WriteByte(0x41) // only 1 of 4 body bytes
	_, err := framing.ReadBytes(&buf, 1024)
	if err == nil || errors.Is(err, framing.ErrFrameTooLarge) {
		t.Fatalf("want EOF/unexpected, got %v", err)
	}
	if !errors.Is(err, io.ErrUnexpectedEOF) {
		t.Fatalf("want UnexpectedEOF, got %v", err)
	}
}

func TestZeroMaxAllowsAnything(t *testing.T) {
	var buf bytes.Buffer
	big := bytes.Repeat([]byte{0xAA}, 100_000)
	if err := framing.WriteBytes(&buf, big, 0); err != nil {
		t.Fatalf("write: %v", err)
	}
	got, err := framing.ReadBytes(&buf, 0)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if !bytes.Equal(got, big) {
		t.Fatalf("roundtrip mismatch")
	}
}

// writeRecorder keeps every Write it is handed, separately.
type writeRecorder struct {
	writes [][]byte
}

func (w *writeRecorder) Write(p []byte) (int, error) {
	w.writes = append(w.writes, bytes.Clone(p))
	return len(p), nil
}

// One frame is one Write. Below this package every Write becomes its own smux
// frame, record and relay message, and a relay that loses or reorders one of
// them must not be able to separate a length from its body (olcbox#25).
func TestWriteBytesIsOneWrite(t *testing.T) {
	var w writeRecorder
	body := []byte(`{"version":1}`)
	if err := framing.WriteBytes(&w, body, 1024); err != nil {
		t.Fatalf("WriteBytes() error = %v", err)
	}
	if len(w.writes) != 1 {
		t.Fatalf("WriteBytes() made %d writes, want 1", len(w.writes))
	}
	// The body is 13 bytes, so the big-endian length prefix ends in 13.
	want := append([]byte{0, 0, 0, 13}, body...)
	if !bytes.Equal(w.writes[0], want) {
		t.Fatalf("WriteBytes() wrote %x, want %x", w.writes[0], want)
	}
	got, err := framing.ReadBytes(bytes.NewReader(w.writes[0]), 1024)
	if err != nil {
		t.Fatalf("ReadBytes() error = %v", err)
	}
	if !bytes.Equal(got, body) {
		t.Fatalf("ReadBytes() = %q, want %q", got, body)
	}
}

// WriteJSON goes through the same single Write.
func TestWriteJSONIsOneWrite(t *testing.T) {
	var w writeRecorder
	if err := framing.WriteJSON(&w, map[string]int{"n": 1}, 1024); err != nil {
		t.Fatalf("WriteJSON() error = %v", err)
	}
	if len(w.writes) != 1 {
		t.Fatalf("WriteJSON() made %d writes, want 1", len(w.writes))
	}
}
