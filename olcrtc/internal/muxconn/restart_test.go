package muxconn

import (
	"bytes"
	"io"
	"testing"
)

// ai-generated: the whole file (a peer that starts its smux session over,
// olcrtc#49).

// A client that gives a handshake up and tries again runs the retry over a
// new smux session, under the relay identity the server keys its peer
// sessions on, so the new session's frames reach the conn of the old one.
// The new session opens its first stream again, for the retried hello: that
// record is the new session's, and it is handed back rather than read into
// the old one, where the retry's hello landed on the old session's control
// stream.
func TestARecordOfTheSessionAfterIsHandedBack(t *testing.T) {
	clientKeys, serverKeys := newTestKeyPair(t)
	seal := func(record []byte) []byte {
		t.Helper()
		sealed, err := clientKeys.Seal(record, []byte(dataRecordAAD))
		if err != nil {
			t.Fatal(err)
		}
		return sealed
	}
	link := &stubLink{canSend: true}
	carried := NewPeer(link, serverKeys, "peer")

	// The session the conn carries opens two streams and writes on both.
	for _, record := range [][]byte{
		append(smuxFrame(smuxCmdSYN, 3, nil), smuxFrame(smuxCmdPSH, 3, []byte("hello"))...),
		smuxFrame(smuxCmdSYN, 5, nil),
		smuxFrame(smuxCmdPSH, 5, []byte("more")),
	} {
		if back := carried.PushSession(seal(record)); back != nil {
			t.Fatalf("a record of the session the conn carries was handed back: % x", back)
		}
	}

	// The next session opens its first stream again.
	restart := append(smuxFrame(smuxCmdSYN, 3, nil), smuxFrame(smuxCmdPSH, 3, []byte("again"))...)
	back := carried.PushSession(seal(restart))
	if !bytes.Equal(back, restart) {
		t.Fatalf("PushSession(the next session's first record) = % x, want it handed back as % x", back, restart)
	}
	if got := carried.PayloadBytes(); got != uint64(len("hello")+len("more")) {
		t.Fatalf("payload read into the old session = %d bytes, want only its own %d", got, len("hello")+len("more"))
	}

	// The conn built for the new session takes the record as it was opened,
	// and goes on from there: its later streams are its own, and its first
	// opened again would be the session after it.
	next := NewPeer(link, serverKeys, "peer")
	next.PushOpened(back)
	if again := next.PushSession(seal(smuxFrame(smuxCmdSYN, 5, nil))); again != nil {
		t.Fatalf("the new session's second stream was handed back: % x", again)
	}
	want := append(append([]byte(nil), restart...), smuxFrame(smuxCmdSYN, 5, nil)...)
	got := make([]byte, len(want))
	if _, err := io.ReadFull(next, got); err != nil {
		t.Fatalf("ReadFull() error = %v", err)
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("the new session read % x, want % x", got, want)
	}
	if again := next.PushSession(seal(smuxFrame(smuxCmdSYN, 3, nil))); again == nil {
		t.Fatal("the stream the handed-back record opened did not count as opened on the new conn")
	}
}

// smux hands a stream its id under a lock it lets go before the SYN is
// written, and its shaper then writes round robin across streams, so the SYNs
// of streams a client opens at once reach the wire in any order: every SOCKS
// connection opens its tunnel on its own goroutine, and the requests parked
// on a session that comes up all open together. A SYN below one the conn has
// seen is still the session's own. Only the session's first stream, which a
// client opens alone for its hello before any other can exist, opened again
// is the session after it.
func TestStreamsOpenedOutOfOrderStayInTheirSession(t *testing.T) {
	clientKeys, serverKeys := newTestKeyPair(t)
	seal := func(record []byte) []byte {
		t.Helper()
		sealed, err := clientKeys.Seal(record, []byte(dataRecordAAD))
		if err != nil {
			t.Fatal(err)
		}
		return sealed
	}
	carried := NewPeer(&stubLink{canSend: true}, serverKeys, "peer")
	for _, record := range [][]byte{
		append(smuxFrame(smuxCmdSYN, 3, nil), smuxFrame(smuxCmdPSH, 3, []byte("hello"))...),
		smuxFrame(smuxCmdSYN, 7, nil),
		smuxFrame(smuxCmdSYN, 5, nil),
		append(smuxFrame(smuxCmdSYN, 11, nil), smuxFrame(smuxCmdSYN, 9, nil)...),
	} {
		if back := carried.PushSession(seal(record)); back != nil {
			t.Fatalf("a stream the session opened out of order was handed back: % x", back)
		}
	}
	if back := carried.PushSession(seal(smuxFrame(smuxCmdSYN, 3, nil))); back == nil {
		t.Fatal("the session's first stream opened again was not handed back")
	}
}
