package gate

import (
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/openlibrecommunity/olcrtc/internal/link"
)

// ai-generated: whole file, unit cover for the link target. The room and the
// key are made up.

func TestLinkTargetIsOnePairFromTheLink(t *testing.T) {
	l := link.Link{Provider: "telemost", Transport: "vp8channel", Room: "https://telemost.yandex.ru/j/1",
		Key: strings.Repeat("ab", 32), VP8FPS: 60, VP8Batch: 64}
	load := LoadURLs{Small: "https://proofkit.org/gate/kb", Big: "https://proofkit.org/gate/10mb.bin",
		Sink: "https://speed.cloudflare.com/__up", BigBytes: 10 << 20}
	lt := NewLinkTarget(l, load, "8.8.8.8:53")
	if got := lt.Pairs(); len(got) != 1 || got[0] != (Pair{"telemost", "vp8channel"}) {
		t.Fatalf("Pairs = %v", got)
	}
	ep, stop, err := lt.Open(context.Background(), Pair{"telemost", "vp8channel"}, t.TempDir(), OpenOptions{})
	if err != nil {
		t.Fatal(err)
	}
	stop()
	want := Endpoint{Provider: "telemost", Transport: "vp8channel", Room: l.Room, Key: l.Key, DNS: "8.8.8.8:53",
		VP8FPS: 60, VP8Batch: 64}
	if ep != want {
		t.Fatalf("endpoint = %+v", ep)
	}
	_, _, err = lt.Open(context.Background(), Pair{"jitsi", "datachannel"}, t.TempDir(), OpenOptions{})
	if !errors.Is(err, ErrPairNotCarried) {
		t.Fatalf("a pair the link does not carry must be refused: %v", err)
	}
	if strings.Contains(err.Error(), l.Room) || strings.Contains(err.Error(), l.Key) {
		t.Fatalf("the refusal quotes the link: %v", err)
	}
	if lt.Load() != load || lt.Platform() != "engine-linux" || lt.Name() != "link" {
		t.Fatal("names/load")
	}
}
