package link

import (
	"errors"
	"strings"
	"testing"
)

// ai-generated: whole file, cover for the olcrtc:// grammar (release gate).

// TestParseAppFixtures parses the olcbox app's own import fixtures
// (LocationsRepositoryImplTest) and the fleet's link shape, and checks that
// printing each result and parsing it again gives the same Link.
func TestParseAppFixtures(t *testing.T) {
	cases := []struct {
		in   string
		want Link
	}{
		{"olcrtc://wbstream?seichannel@room-01#" + hex64('a') + "%android-01$RU / olc free sub / IPv6",
			Link{Provider: "wbstream", Transport: "seichannel", Room: "room-01", Key: hex64('a'),
				Device: "android-01", Label: "RU / olc free sub / IPv6", VP8FPS: 60, VP8Batch: 64}},
		{"olcrtc://telemost?vp8channel<vp8-fps=30&vp8-batch=32>@https://telemost.yandex.ru/j/1234567890#" + hex64('b'),
			Link{Provider: "telemost", Transport: "vp8channel", Room: "https://telemost.yandex.ru/j/1234567890",
				Key: hex64('b'), VP8FPS: 30, VP8Batch: 32}},
		{"olcrtc://jitsi?datachannel@https://meet.example.org/gate-abc#" + hex64('c') + "$DE",
			Link{Provider: "jitsi", Transport: "datachannel", Room: "https://meet.example.org/gate-abc",
				Key: hex64('c'), Label: "DE", VP8FPS: 60, VP8Batch: 64}},
		{"olcrtc://jazz?datachannel@room-02#" + hex64('b') + "%android-02$DE / backup",
			Link{Provider: "jazz", Transport: "datachannel", Room: "room-02", Key: hex64('b'),
				Device: "android-02", Label: "DE / backup", VP8FPS: 60, VP8Batch: 64}},
		{"olcrtc://jitsi?vp8channel<vp8-fps=25&vp8-batch=1>@https://meet.example.org/olcrtc-x#" + hex64('b'),
			Link{Provider: "jitsi", Transport: "vp8channel", Room: "https://meet.example.org/olcrtc-x",
				Key: hex64('b'), VP8FPS: 25, VP8Batch: 1}},
		// A subscription body may start with a byte order mark.
		{"\uFEFFolcrtc://wbstream?vp8channel@room#" + hex64('d') + "$Fallback",
			Link{Provider: "wbstream", Transport: "vp8channel", Room: "room", Key: hex64('d'),
				Label: "Fallback", VP8FPS: 60, VP8Batch: 64}},
		// Space before the byte order mark is trimmed first, as the app does.
		{" \uFEFFolcrtc://wbstream?vp8channel@room#" + hex64('d') + "$Fallback",
			Link{Provider: "wbstream", Transport: "vp8channel", Room: "room", Key: hex64('d'),
				Label: "Fallback", VP8FPS: 60, VP8Batch: 64}},
		// The fleet's shape: no options, a label with a middle dot.
		{"olcrtc://telemost?vp8channel@https://telemost.yandex.ru/j/1234567890#" + hex64('e') + "$DE · olcRTC\r\n",
			Link{Provider: "telemost", Transport: "vp8channel", Room: "https://telemost.yandex.ru/j/1234567890",
				Key: hex64('e'), Label: "DE · olcRTC", VP8FPS: 60, VP8Batch: 64}},
		// Every field is trimmed; a '%' after the '$' belongs to the label.
		{"  olcrtc:// telemost ? vp8channel @ room-03 # " + hex64('f') + " $ 100% up ",
			Link{Provider: "telemost", Transport: "vp8channel", Room: "room-03", Key: hex64('f'),
				Label: "100% up", VP8FPS: 60, VP8Batch: 64}},
		// Each marker is the first of its kind after the one before it, so a
		// later one is data. Here '?' sits in the transport and the room, '@'
		// in the room, '%' in the device and the label, '#' and '$' in the label.
		{"olcrtc://jitsi?data?channel@https://meet.example.org/a@b?c#" + hex64('a') + "%dev%ice$DE #1 $5 / 100%",
			Link{Provider: "jitsi", Transport: "data?channel", Room: "https://meet.example.org/a@b?c", Key: hex64('a'),
				Device: "dev%ice", Label: "DE #1 $5 / 100%", VP8FPS: 60, VP8Batch: 64}},
		// The search for each marker starts after the one before it, so the same
		// char earlier in the line is data too: here '@' and '#' in the provider,
		// '#' in the transport, '%' and '$' in the room.
		{"olcrtc://jit@si#?vp8#channel@https://meet.example.org/a%20b$c#" + hex64('b'),
			Link{Provider: "jit@si#", Transport: "vp8#channel", Room: "https://meet.example.org/a%20b$c",
				Key: hex64('b'), VP8FPS: 60, VP8Batch: 64}},
	}
	for _, c := range cases {
		got, err := Parse(c.in)
		if err != nil {
			t.Fatalf("Parse(%q) error = %v", c.in, err)
		}
		if got != c.want {
			t.Fatalf("Parse(%q)\n got  %#v\n want %#v", c.in, got, c.want)
		}
		back, err := Parse(got.String())
		if err != nil || back != got {
			t.Fatalf("Parse(%q).String() = %q does not parse back: %v / %#v", c.in, got.String(), err, back)
		}
	}
}

func TestParseRejectsMalformed(t *testing.T) {
	for _, in := range []string{
		"",
		"vless://x",
		"olcrtc://jitsi",
		"olcrtc://jitsi?datachannel",
		"olcrtc://jitsi?datachannel@room",
		"olcrtc://?datachannel@room#" + hex64('a'),
		"olcrtc:// ?datachannel@room#" + hex64('a'),
		"olcrtc://jitsi?@room#" + hex64('a'),
		"olcrtc://jitsi?<vp8-fps=30>@room#" + hex64('a'),
		"olcrtc://jitsi?datachannel@ #" + hex64('a'),
		"olcrtc://jitsi?datachannel@room#",
		"olcrtc://jitsi?datachannel@room#$" + hex64('a'),
		"olcrtc://jitsi?datachannel@room#" + hex64('a')[:63],
		"olcrtc://jitsi?datachannel@room#" + hex64('a') + "0",
		"olcrtc://jitsi?datachannel@room#" + hex64('g'),
		"OLCRTC://jitsi?datachannel@room#" + hex64('a'),
		"olcrtc://crypt1/AAAA",
		// One line only: the app splits a body into lines before it parses,
		// so what follows a line break is another line, never more of this one.
		"olcrtc://wbstream?vp8channel@first#" + hex64('c') + "$First\n" +
			"olcrtc://wbstream?vp8channel@second#" + hex64('d') + "$Second",
		"olcrtc://jitsi?datachannel@room#" + hex64('a') + "\r\n$DE",
		"olcrtc://jitsi?datachannel@room#" + hex64('a') + "\r$DE",
		"olcrtc://jitsi?datachannel@room\n#" + hex64('a'),
	} {
		got, err := Parse(in)
		if !errors.Is(err, ErrMalformed) {
			t.Fatalf("Parse(%q) error = %v, want ErrMalformed", in, err)
		}
		if got != (Link{}) {
			t.Fatalf("Parse(%q) returned %#v with its error", in, got)
		}
	}
}

// TestParseErrorsQuoteNothing: a link carries a room and a key, so no error
// may repeat any part of the line it rejects.
func TestParseErrorsQuoteNothing(t *testing.T) {
	const room = "https://meet.example.org/never-in-an-error"
	badKey := strings.Repeat("a", 63) + "Q"
	for _, in := range []string{
		"olcrtc://jitsi?datachannel@" + room + "#" + badKey + "$never-in-an-error",
		"olcrtc://jitsi?datachannel@" + room,
		"olcrtc://?datachannel@" + room + "#" + hex64('a'),
		"olcrtc://jitsi?datachannel@" + room + "#" + badKey[:40],
		"olcrtc://jitsi?datachannel@" + room + "#" + hex64('a') + "\n$never-in-an-error",
	} {
		_, err := Parse(in)
		if err == nil {
			t.Fatalf("Parse(%q) accepted a malformed link", in)
		}
		if msg := err.Error(); strings.Contains(msg, "never-in-an-error") || strings.Contains(msg, "Q") ||
			strings.Contains(msg, "aaaa") {
			t.Fatalf("Parse error quotes the line: %q", msg)
		}
	}
}

// TestParseOptionsFollowTheApp: options sit between the first '<' and the
// last '>', keys are case-insensitive, vp8-fps wins over fps and vp8-batch
// over batch, a value that is not an int32 is skipped, and the result is
// clamped into the app's bounds (fps 1..120, batch 1..64).
func TestParseOptionsFollowTheApp(t *testing.T) {
	cases := []struct {
		token, transport string
		fps, batch       int
	}{
		{"vp8channel", "vp8channel", 60, 64},
		{"vp8channel<>", "vp8channel", 60, 64},
		{"seichannel<fps=30&batch=32&frag=900&ack-ms=2000>", "seichannel", 30, 32},
		{"seichannel<fps=30&batch=8>>", "seichannel", 30, 64}, // the last '>' ends it: "8>" is no int
		{"vp8channel<vp8-fps=20&fps=30>", "vp8channel", 20, 64},
		{"vp8channel<fps=30&vp8-fps=20&batch=8&vp8-batch=16>", "vp8channel", 20, 16},
		{"vp8channel <VP8-FPS=25& vp8-batch = 8 >", "vp8channel", 25, 8},
		{"vp8channel<vp8-fps=500&vp8-batch=500>", "vp8channel", 120, 64},
		{"vp8channel<vp8-fps=0&vp8-batch=-3>", "vp8channel", 1, 1},
		{"vp8channel<vp8-fps=4294967296&fps=30&vp8-batch=x&=7&batch>", "vp8channel", 30, 64},
		{"vp8channel<vp8-fps=30", "vp8channel<vp8-fps=30", 60, 64},
	}
	for _, c := range cases {
		in := "olcrtc://telemost?" + c.token + "@room#" + hex64('a')
		got, err := Parse(in)
		if err != nil {
			t.Fatalf("Parse(%q) error = %v", in, err)
		}
		if got.Transport != c.transport || got.VP8FPS != c.fps || got.VP8Batch != c.batch {
			t.Fatalf("Parse(%q) = %q %d/%d, want %q %d/%d",
				in, got.Transport, got.VP8FPS, got.VP8Batch, c.transport, c.fps, c.batch)
		}
		back, err := Parse(got.String())
		if err != nil || back != got {
			t.Fatalf("Parse(%q).String() = %q does not parse back: %v / %#v", in, got.String(), err, back)
		}
	}
}

func TestStringRoundTrip(t *testing.T) {
	l := Link{Provider: "telemost", Transport: "vp8channel", Room: "https://telemost.yandex.ru/j/1",
		Key: hex64('d'), Label: "DE", VP8FPS: 60, VP8Batch: 64}
	if got, want := l.String(), "olcrtc://telemost?vp8channel@https://telemost.yandex.ru/j/1#"+hex64('d')+"$DE"; got != want {
		t.Fatalf("String() = %q, want %q", got, want)
	}
	back, err := Parse(l.String())
	if err != nil || back != l {
		t.Fatalf("round trip: %v / %#v", err, back)
	}

	l = Link{Provider: "wbstream", Transport: "vp8channel", Room: "room-01", Key: hex64('e'),
		Device: "android-01", VP8FPS: 30, VP8Batch: 64}
	want := "olcrtc://wbstream?vp8channel<vp8-fps=30&vp8-batch=64>@room-01#" + hex64('e') + "%android-01"
	if got := l.String(); got != want {
		t.Fatalf("String() = %q, want %q", got, want)
	}
	back, err = Parse(l.String())
	if err != nil || back != l {
		t.Fatalf("round trip: %v / %#v", err, back)
	}
}

func hex64(c byte) string {
	b := make([]byte, 64)
	for i := range b {
		b[i] = c
	}
	return string(b)
}
