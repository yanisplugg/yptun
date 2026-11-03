package salutejazz

import (
	"bytes"
	"math"
	"testing"
)

// ai-generated: the whole file (the relay window's wire, olcrtc#49).

// TestAWindowFrameIsItsFourteenBytes pins what both ends of a tunnel have to
// agree on, byte for byte: the topic, then the version, the kind, the count
// big-endian and the advertised window big-endian, which version 1 fills with
// the window it keeps.
func TestAWindowFrameIsItsFourteenBytes(t *testing.T) {
	if windowTopic != "olcrtc.win" {
		t.Fatalf("window frames go under %q, the other end reads %q", windowTopic, "olcrtc.win")
	}
	got := windowFrame{kind: windowMark, counter: 0x0102030405060708}.encode()
	want := []byte{
		1,                      // version
		1,                      // mark
		1, 2, 3, 4, 5, 6, 7, 8, // counter
		0x00, 0x03, 0x00, 0x00, // advertised: 192 KiB
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("mark = % x, want % x", got, want)
	}
	if echo := (windowFrame{kind: windowEcho, counter: 1}).encode(); echo[1] != 2 || len(echo) != len(want) {
		t.Fatalf("echo = % x, want kind 2 in %d bytes", echo, len(want))
	}
	if relayWindow != 192<<10 {
		t.Fatalf("the advertised window is %d, the layout above says 192 KiB", relayWindow)
	}
}

// TestAWindowFrameRoundTrips reads back what encode writes, for both kinds
// and the counts at either end of the range. The advertised window is
// reserved for fan-in budgeting: a version 1 reader takes a frame whatever
// it says and does nothing with it.
func TestAWindowFrameRoundTrips(t *testing.T) {
	for _, kind := range []byte{windowMark, windowEcho} {
		for _, counter := range []uint64{0, 1, relayWindow, math.MaxUint64} {
			sent := windowFrame{kind: kind, counter: counter}
			wire := sent.encode()
			got, ok := parseWindowFrame(wire)
			if !ok || got != sent {
				t.Fatalf("kind %d counter %d: read back %+v, %v", kind, counter, got, ok)
			}
			other := bytes.Clone(wire)
			copy(other[10:], []byte{0xff, 0xff, 0xff, 0xff})
			if got, ok := parseWindowFrame(other); !ok || got != sent {
				t.Fatalf("kind %d counter %d: another advertised window read as %+v, %v", kind, counter, got, ok)
			}
		}
	}
}

// TestWhatIsNotAVersionOneFrameIsNotRead covers the frames a reader refuses:
// another version, a kind it does not know, and any other length. A frame
// under the window's topic is never the tunnel's either way; refusing it only
// means the window does nothing with it.
func TestWhatIsNotAVersionOneFrameIsNotRead(t *testing.T) {
	good := windowFrame{kind: windowEcho, counter: 42}.encode()
	with := func(at int, value byte) []byte {
		frame := bytes.Clone(good)
		frame[at] = value
		return frame
	}
	for name, frame := range map[string][]byte{
		"version 0":    with(0, 0),
		"version 2":    with(0, 2),
		"kind 0":       with(1, 0),
		"kind 3":       with(1, 3),
		"one short":    good[:len(good)-1],
		"one long":     append(bytes.Clone(good), 0),
		"empty":        nil,
		"version only": {1},
	} {
		if got, ok := parseWindowFrame(frame); ok {
			t.Fatalf("%s: read as %+v", name, got)
		}
	}
}
