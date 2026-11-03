package vkcalls

import (
	"errors"
	"strings"
	"testing"
)

// fixtureOffer mirrors the live SFU offer structure with sanitized values:
// an audio-mix sendonly section, the application section, two publish slots
// (audio and video recvonly) and one video recv section.
const fixtureOffer = "v=0\r\n" +
	"o=- 1790552099500 1790552100511 IN IP4 127.0.0.1\r\n" +
	"s=-\r\nt=0 0\r\n" +
	"a=group:BUNDLE 0 1 2 3 5\r\n" +
	"a=ice-lite\r\n" +
	"a=msid-semantic: WMS *\r\n" +
	"m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n" +
	"c=IN IP4 0.0.0.0\r\n" +
	"a=rtcp:9 IN IP4 0.0.0.0\r\n" +
	"a=candidate:1 1 udp 1 192.0.2.1 43210 typ host\r\n" +
	"a=ice-ufrag:QcK1avCrgDjn21p\r\n" +
	"a=ice-pwd:GZU7Kw3Sq1YSJxrABCtRTGhms\r\n" +
	"a=fingerprint:sha-256 32:16:0D:10:FC:A1:F1:04:43:F5:22:01:9B:2E:53:38\r\n" +
	"a=setup:actpass\r\n" +
	"a=mid:0\r\n" +
	"a=extmap:4 http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01\r\n" +
	"a=extmap:1 urn:ietf:params:rtp-hdrext:ssrc-audio-level\r\n" +
	"a=sendonly\r\n" +
	"a=rtcp-mux\r\n" +
	"a=rtpmap:111 opus/48000/2\r\n" +
	"a=rtcp-fb:111 transport-cc\r\n" +
	"a=fmtp:111 useinbandfec=1;minptime=10\r\n" +
	"a=ssrc:2463708138 cname:audio-mix\r\n" +
	"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n" +
	"c=IN IP4 0.0.0.0\r\n" +
	"a=ice-ufrag:QcK1avCrgDjn21p\r\n" +
	"a=ice-pwd:GZU7Kw3Sq1YSJxrABCtRTGhms\r\n" +
	"a=mid:1\r\n" +
	"a=sendrecv\r\n" +
	"m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n" +
	"c=IN IP4 0.0.0.0\r\n" +
	"a=ice-ufrag:QcK1avCrgDjn21p\r\n" +
	"a=mid:2\r\n" +
	"a=extmap:1 urn:ietf:params:rtp-hdrext:ssrc-audio-level\r\n" +
	"a=recvonly\r\n" +
	"a=rtcp-mux\r\n" +
	"a=rtpmap:111 opus/48000/2\r\n" +
	"a=fmtp:111 useinbandfec=1;minptime=10;maxaveragebitrate=64000\r\n" +
	"m=video 9 UDP/TLS/RTP/SAVPF 102 103 100 101\r\n" +
	"c=IN IP4 0.0.0.0\r\n" +
	"a=ice-ufrag:QcK1avCrgDjn21p\r\n" +
	"a=mid:3\r\n" +
	"a=extmap:4 http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01\r\n" +
	"a=extmap:3 http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time\r\n" +
	"a=recvonly\r\n" +
	"a=rtcp-mux\r\n" +
	"a=rtpmap:102 VP9/90000\r\n" +
	"a=rtcp-fb:102 transport-cc\r\n" +
	"a=rtpmap:103 rtx/90000\r\n" +
	"a=fmtp:103 apt=102\r\n" +
	"a=rtpmap:100 VP8/90000\r\n" +
	"a=rtcp-fb:100 nack\r\n" +
	"a=rtcp-fb:100 nack pli\r\n" +
	"a=rid:l recv\r\n" +
	"a=simulcast:recv l\r\n" +
	"m=video 9 UDP/TLS/RTP/SAVPF 100 101\r\n" +
	"c=IN IP4 0.0.0.0\r\n" +
	"a=ice-ufrag:QcK1avCrgDjn21p\r\n" +
	"a=mid:5\r\n" +
	"a=extmap:4 http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01\r\n" +
	"a=extmap:3 http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time\r\n" +
	"a=sendonly\r\n" +
	"a=rtcp-mux\r\n" +
	"a=rtpmap:100 VP8/90000\r\n" +
	"a=rtcp-fb:100 nack\r\n" +
	"a=ssrc:2463708139 cname:pat\r\n" +
	"a=ssrc:2463708139 msid:pat video-pat\r\n"

// fixtureNative mirrors the fields the shaper takes from Pion's answer.
const fixtureNative = "v=0\r\n" +
	"o=- 123 2 IN IP4 127.0.0.1\r\n" +
	"s=-\r\nt=0 0\r\n" +
	"a=group:BUNDLE 0 1 2 3 5\r\n" +
	"m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n" +
	"a=ice-ufrag:KdfaZFqRLOnUhmUW\r\n" +
	"a=ice-pwd:WTugXtAYkOEfiYMfOWDCKNNQNhVqAkoc\r\n" +
	"a=fingerprint:sha-256 BC:92:07:E6:13:23:65:25:C2:FE:85:A0:9D:EE:46:CA\r\n" +
	"a=mid:0\r\n" +
	"a=recvonly\r\n" +
	"m=video 9 UDP/TLS/RTP/SAVPF 100\r\n" +
	"a=ice-ufrag:KdfaZFqRLOnUhmUW\r\n" +
	"a=ice-pwd:WTugXtAYkOEfiYMfOWDCKNNQNhVqAkoc\r\n" +
	"a=fingerprint:sha-256 BC:92:07:E6:13:23:65:25:C2:FE:85:A0:9D:EE:46:CA\r\n" +
	"a=mid:3\r\n" +
	"a=sendonly\r\n" +
	"a=msid:proofkit video\r\n" +
	"a=ssrc:3842537998 cname:proofkit\r\n"

func TestShapeAnswerStructure(t *testing.T) {
	state := NewShapeState()
	shaped, err := ShapeAnswer(fixtureOffer, fixtureNative, state)
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(shaped, "\r\n")

	// Session header: fully generated, BUNDLE echoed from the offer.
	if lines[0] != "v=0" || !strings.HasPrefix(lines[1], "o=- ") || lines[4] != "a=group:BUNDLE 0 1 2 3 5" {
		t.Fatalf("session header %v", lines[:6])
	}
	if lines[5] != "a=msid-semantic: WMS proofkit" {
		t.Fatalf("msid-semantic %q", lines[5])
	}

	// The application section keeps the reference shape: no direction, no rtcp.
	app := sectionOf(t, lines, "m=application 9 UDP/DTLS/SCTP webrtc-datachannel")
	if app[1] != "c=IN IP4 0.0.0.0" || app[2] != "a=ice-ufrag:KdfaZFqRLOnUhmUW" {
		t.Fatalf("application head %v", app[:3])
	}
	if strings.Join(app, "\n") != strings.Join([]string{
		"m=application 9 UDP/DTLS/SCTP webrtc-datachannel",
		"c=IN IP4 0.0.0.0",
		"a=ice-ufrag:KdfaZFqRLOnUhmUW",
		"a=ice-pwd:WTugXtAYkOEfiYMfOWDCKNNQNhVqAkoc",
		"a=ice-options:trickle",
		"a=fingerprint:sha-256 BC:92:07:E6:13:23:65:25:C2:FE:85:A0:9D:EE:46:CA",
		"a=setup:active",
		"a=mid:1",
		"a=sctp-port:5000",
	}, "\n") {
		t.Fatalf("application section %v", app)
	}

	// A recv section answers recvonly with the offered codec vocabulary, no
	// SSRC attributions, no rid/simulcast from the offer.
	recv := sectionOf(t, lines, "m=video 9 UDP/TLS/RTP/SAVPF 100 101")
	wantRecv := strings.Join([]string{
		"m=video 9 UDP/TLS/RTP/SAVPF 100 101",
		"c=IN IP4 0.0.0.0",
		"a=rtcp:9 IN IP4 0.0.0.0",
		"a=ice-ufrag:KdfaZFqRLOnUhmUW",
		"a=ice-pwd:WTugXtAYkOEfiYMfOWDCKNNQNhVqAkoc",
		"a=ice-options:trickle",
		"a=fingerprint:sha-256 BC:92:07:E6:13:23:65:25:C2:FE:85:A0:9D:EE:46:CA",
		"a=setup:active",
		"a=mid:5",
		"a=extmap:4 http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01",
		"a=extmap:3 http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time",
		"a=recvonly",
		"a=rtcp-mux",
		"a=rtpmap:100 VP8/90000",
		"a=rtcp-fb:100 nack",
	}, "\n")
	if strings.Join(recv, "\n") != wantRecv {
		t.Fatalf("recv section:\n%s", strings.Join(recv, "\n"))
	}

	// The video publish slot takes the browser's simulcast declaration:
	// three rid constraints, one simulcast line, no SSRC attribution — the
	// SFU binds the stream by the rid RTP header (spike rid-onelayer).
	pub := sectionOf(t, lines, "m=video 9 UDP/TLS/RTP/SAVPF 102 103 100 101")
	if !contains(pub, "a=sendonly") || !contains(pub, "a=msid:proofkit video") {
		t.Fatalf("publish section %v", pub)
	}
	for _, want := range []string{
		"a=rid:l send max-width=320;max-height=180;max-fps=15;max-br=180000",
		"a=rid:m send max-width=640;max-height=360;max-fps=20;max-br=500000",
		"a=rid:h send max-width=1280;max-height=720;max-fps=20;max-br=1200000",
		"a=simulcast:send l;m;h",
	} {
		if !contains(pub, want) {
			t.Fatalf("publish section missing %q: %v", want, pub)
		}
	}
	for _, line := range pub {
		if strings.HasPrefix(line, "a=ssrc:") {
			t.Fatalf("ssrc attribution leaked into the video publish slot: %q", line)
		}
	}

	// The audio publish slot gets a stable synthetic SSRC and our msid with a
	// slot-scoped track id. Both audio sections share one m= text, so the
	// publish one is identified by its mid.
	audioPub := sectionByMid(t, lines, "a=mid:2")
	if !contains(audioPub, "a=sendonly") {
		t.Fatalf("audio publish section %v", audioPub)
	}
	if !contains(audioPub, "a=msid:proofkit 2-audio") {
		t.Fatalf("audio publish msid not slot-scoped: %v", audioPub)
	}
	audioSSRC := lineOf(audioPub, "a=ssrc:")
	if audioSSRC == "" {
		t.Fatal("audio publish slot without SSRC")
	}
	again, err := ShapeAnswer(fixtureOffer, fixtureNative, state)
	if err != nil {
		t.Fatal(err)
	}
	if !contains(strings.Split(again, "\r\n"), audioSSRC) {
		t.Fatal("synthetic audio SSRC is not stable across re-answers")
	}

	// Offer candidates and ssrc attributions never leak into the answer.
	for _, line := range lines {
		if strings.HasPrefix(line, "a=candidate:") {
			t.Fatalf("candidate leaked: %q", line)
		}
		if line == "a=ssrc:2463708138 cname:audio-mix" {
			t.Fatal("offer ssrc leaked into a recv answer")
		}
	}
}

func TestShapeAnswerVersionBumps(t *testing.T) {
	state := NewShapeState()
	first, err := ShapeAnswer(fixtureOffer, fixtureNative, state)
	if err != nil {
		t.Fatal(err)
	}
	second, err := ShapeAnswer(fixtureOffer, fixtureNative, state)
	if err != nil {
		t.Fatal(err)
	}
	v1 := strings.Fields(strings.Split(first, "\r\n")[1])
	v2 := strings.Fields(strings.Split(second, "\r\n")[1])
	if v1[2] == v2[2] {
		t.Fatalf("o= version did not bump: %q vs %q", v1, v2)
	}
	if v1[1] != v2[1] {
		t.Fatalf("o= session id changed across answers: %q vs %q", v1[1], v2[1])
	}
}

func TestShapeAnswerErrors(t *testing.T) {
	if _, err := ShapeAnswer(fixtureOffer, "v=0", NewShapeState()); !errors.Is(err, ErrShapeNoCrypto) {
		t.Fatalf("want ErrShapeNoCrypto, got %v", err)
	}
	noPublish := strings.ReplaceAll(fixtureNative, "a=ssrc:3842537998 cname:proofkit\r\n", "")
	if _, err := ShapeAnswer(fixtureOffer, noPublish, NewShapeState()); !errors.Is(err, ErrShapeNoPublish) {
		t.Fatalf("want ErrShapeNoPublish, got %v", err)
	}
	noBundle := strings.ReplaceAll(fixtureOffer, "a=group:BUNDLE 0 1 2 3 5\r\n", "")
	if _, err := ShapeAnswer(noBundle, fixtureNative, NewShapeState()); !errors.Is(err, ErrShapeBundle) {
		t.Fatalf("want ErrShapeBundle, got %v", err)
	}
}

func TestRemoteSSRCs(t *testing.T) {
	got := remoteSSRCs(fixtureOffer)
	want := []string{"2463708138", "2463708139"}
	if len(got) != len(want) {
		t.Fatalf("ssrcs %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("ssrc[%d]=%s, want %s", i, got[i], want[i])
		}
	}
}

func sectionOf(t *testing.T, lines []string, mLine string) []string {
	t.Helper()
	var out []string
	for _, line := range lines {
		if strings.HasPrefix(line, "m=") {
			if line == mLine {
				out = append(out, line)
			} else if out != nil {
				break
			}
			continue
		}
		if out != nil {
			out = append(out, line)
		}
	}
	if out == nil {
		t.Fatalf("section %q not found", mLine)
	}
	return out
}

func sectionByMid(t *testing.T, lines []string, mid string) []string {
	t.Helper()
	var current []string
	var found []string
	flush := func() {
		if len(current) > 0 && contains(current, mid) && found == nil {
			found = current
		}
	}
	for _, line := range lines {
		if strings.HasPrefix(line, "m=") {
			flush()
			current = []string{line}
			continue
		}
		if current != nil {
			current = append(current, line)
		}
	}
	flush()
	if found == nil {
		t.Fatalf("section with %q not found", mid)
	}
	return found
}

func contains(lines []string, want string) bool {
	for _, line := range lines {
		if line == want {
			return true
		}
	}
	return false
}

func lineOf(lines []string, prefix string) string {
	for _, line := range lines {
		if strings.HasPrefix(line, prefix) {
			return line
		}
	}
	return ""
}
