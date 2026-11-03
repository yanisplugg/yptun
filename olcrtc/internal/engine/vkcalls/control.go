package vkcalls

import (
	"encoding/binary"
	"errors"
	"fmt"
	"math"
	"sort"
)

// The producer control route is a small MessagePack subset over the
// producerCommand data channel, per the SDK bundle: fixint/fixstr/fixarray,
// the int16 marker for larger ints, bin8 and nil. The registry notification
// uses the same encoding.
var (
	ErrRegistryShort  = errors.New("vkcalls: producerNotification shorter than its header")
	ErrRegistryShape  = errors.New("vkcalls: producerNotification registry is not a one-entry map")
	ErrLayoutNoStream = errors.New("vkcalls: layout update without any registry stream")
	ErrLayoutKeyLong  = errors.New("vkcalls: registry stream key exceeds the fixstr bound")
	ErrLayoutTooMany  = errors.New("vkcalls: layout update exceeds the array16 bound")
	// ErrNotRegistry marks an expected non-registry notification frame.
	ErrNotRegistry = errors.New("vkcalls: frame is not a registry update")
)

// registryKind is the leading byte of kind-1 producerNotification frames: a
// per-consumer map {"<participantId>:sCAMERA" → compactId}.
const registryKind = 1

// Registry maps SFU stream descriptions to the compact ids this consumer
// must address them by in update-display-layout.
type Registry map[string]uint8

// ParseRegistry decodes a kind-1 producerNotification frame. Any other kind
// returns an empty registry and no error: frames of other kinds are expected
// and carry no addressing data.
// ParseRegistry decodes a kind-1 producerNotification frame. Frames of other
// kinds are expected on this channel and carry no addressing data; they yield
// ErrNotRegistry so callers skip them uniformly.
func ParseRegistry(frame []byte) (Registry, error) {
	if len(frame) == 0 {
		return nil, ErrRegistryShort
	}
	if frame[0] != registryKind {
		return nil, ErrNotRegistry
	}
	if len(frame) < 4 {
		return nil, ErrRegistryShort
	}
	var count, pos int
	switch {
	case frame[1]&0xF0 == 0x80: // fixmap
		count, pos = int(frame[1]&0x0F), 2
	case frame[1] == 0xDE: // map16
		count, pos = int(binary.BigEndian.Uint16(frame[2:4])), 4
	default:
		return nil, ErrRegistryShape
	}
	out := make(Registry, count)
	for range count {
		key, next, err := readRegistryKey(frame, pos)
		if err != nil {
			return nil, err
		}
		id, after, err := readRegistryID(frame, next)
		if err != nil {
			return nil, err
		}
		out[key], pos = id, after
	}
	return out, nil
}

// readRegistryKey reads the fixstr stream description at pos.
func readRegistryKey(frame []byte, pos int) (string, int, error) {
	if pos >= len(frame) {
		return "", 0, ErrRegistryShort
	}
	if frame[pos]&0xE0 != 0xA0 {
		return "", 0, ErrRegistryShape
	}
	end := pos + 1 + int(frame[pos]&0x1F)
	if end > len(frame) {
		return "", 0, ErrRegistryShort
	}
	return string(frame[pos+1 : end]), end, nil
}

// readRegistryID reads the compact id at pos: a positive fixint, or the
// uint8 form the encoder picks from 128 on.
func readRegistryID(frame []byte, pos int) (uint8, int, error) {
	switch {
	case pos >= len(frame):
		return 0, 0, ErrRegistryShort
	case frame[pos] < 0x80:
		return frame[pos], pos + 1, nil
	case frame[pos] == 0xCC && pos+1 < len(frame):
		return frame[pos+1], pos + 2, nil
	case frame[pos] == 0xCC:
		return 0, 0, ErrRegistryShort
	default:
		return 0, 0, ErrRegistryShape
	}
}

// LayoutEntry is one stream subscription: the tile priority and the size the
// consumer displays it at, which the SFU uses to pick a simulcast layer.
// ByCompact selects the compact-id addressing the SFU honours (the SDK's
// form); the fixstr key form is kept for diagnostics.
type LayoutEntry struct {
	Key       string
	Compact   uint8
	ByCompact bool
	Priority  int
	Width     int
	Height    int
}

// EncodeLayout builds the update-display-layout producer-command frame:
// [cmdType=0, 0, sequence, nil, [bin(layout)…], nil]. Each layout blob is the
// stream key (compact int, or fixstr), variant 0, then priority, width,
// height and fit (nil when unset) — the field order the SDK serializer
// writes.
func EncodeLayout(sequence int, entries []LayoutEntry) ([]byte, error) {
	if len(entries) == 0 {
		return nil, ErrLayoutNoStream
	}
	if len(entries) > math.MaxUint16 {
		return nil, fmt.Errorf("%w: %d entries", ErrLayoutTooMany, len(entries))
	}
	out := appendMPInt(nil, 0)
	out = appendMPInt(out, 0)
	out = appendMPInt(out, sequence)
	out = append(out, 0xC0)
	if len(entries) < 16 {
		out = append(out, 0x90|byte(len(entries))) //nolint:gosec // bounded to 15 entries here
	} else {
		out = append(out, 0xDC, byte(len(entries)>>8), byte(len(entries))) //nolint:gosec // bounded to uint16 above
	}
	for _, e := range entries {
		blob := make([]byte, 0, 32)
		switch {
		case e.ByCompact:
			blob = append(blob, e.Compact)
		case e.Key != "":
			if len(e.Key) >= 32 {
				return nil, fmt.Errorf("%w: %d bytes", ErrLayoutKeyLong, len(e.Key))
			}
			blob = append(blob, 0xA0|byte(len(e.Key))) //nolint:gosec // bounded to 31 bytes above
			blob = append(blob, e.Key...)
		default:
			return nil, ErrLayoutNoStream
		}
		blob = append(blob, 0x00) // variant 0: set the tile layout
		blob = appendMPInt(blob, e.Priority)
		if e.Width > 0 && e.Height > 0 {
			blob = appendMPInt(blob, e.Width)
			blob = appendMPInt(blob, e.Height)
		} else {
			blob = append(blob, 0xC0, 0xC0)
		}
		blob = append(blob, 0x00) // fit=cv
		if len(blob) >= 256 {
			return nil, ErrLayoutKeyLong
		}
		out = append(out, 0xC4, byte(len(blob))) //nolint:gosec // bounded to 255 bytes above
		out = append(out, blob...)
	}
	return append(out, 0xC0), nil
}

// appendMPInt writes a small non-negative int in the wire's compact form:
// fixint below 128, otherwise the int16 marker the SDK's int encoder picks.
func appendMPInt(dst []byte, value int) []byte {
	switch {
	case value >= 0 && value < 128:
		return append(dst, byte(value))
	case value >= math.MinInt16 && value <= math.MaxInt16:
		var buf [2]byte
		binary.BigEndian.PutUint16(buf[:], uint16(int16(value))) //nolint:gosec // two's-complement bit pattern by design
		return append(append(dst, 0xD1), buf[:]...)
	default:
		var buf [4]byte
		binary.BigEndian.PutUint32(buf[:], uint32(value)) //nolint:gosec // two's-complement bit pattern by design
		return append(append(dst, 0xD2), buf[:]...)
	}
}

// sortedStreams returns the registry keys in a stable order so repeated
// layout commands encode identically.
func (r Registry) sortedStreams() []string {
	keys := make([]string, 0, len(r))
	for key := range r {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	return keys
}

// cameraKey is the stream description of a participant's camera.
func cameraKey(participant string) string { return "u" + participant + ":sCAMERA" }

// layoutEntries is the whole subscription: every registry stream by its
// compact id, then the camera of every participant the registry does not
// name yet by its string key. The SFU answers a string key with a registry
// entry for it: without asking, a participant who joins later is never
// named and never forwarded (live, 2026-09-28).
func layoutEntries(r Registry, participants []string) []LayoutEntry {
	entries, _ := r.LayoutFor() // an empty registry adds nothing
	asked := make([]string, 0, len(participants))
	for _, participant := range participants {
		if _, known := r[cameraKey(participant)]; !known {
			asked = append(asked, cameraKey(participant))
		}
	}
	sort.Strings(asked)
	for _, key := range asked {
		entries = append(entries, LayoutEntry{Key: key, Priority: 1, Width: 640, Height: 360})
	}
	return entries
}

// LayoutFor builds the subscription of every registry stream.
func (r Registry) LayoutFor() ([]LayoutEntry, error) {
	streams := r.sortedStreams()
	if len(streams) == 0 {
		return nil, ErrLayoutNoStream
	}
	entries := make([]LayoutEntry, 0, len(streams))
	for _, key := range streams {
		entry := LayoutEntry{Key: key, Compact: r[key], ByCompact: true, Priority: 1, Width: 640, Height: 360}
		entries = append(entries, entry)
	}
	return entries, nil
}
