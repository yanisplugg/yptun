package vp8channel

import (
	"strconv"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/control"
)

// A client that vanishes mid-download - network gone, app killed - keeps its
// server session until liveness ends it, tens of seconds later. Everything
// the server retransmits for it in the meantime goes into the one track every
// other client in the room downloads through, so the flood is paid for by
// people who are still there (#24).
//
// The measurement is the point of this test: it runs the same download twice,
// once with the lane's blackout window as it ships and once with it pushed
// out of reach, and compares what the server wrote into the room after the
// client stopped answering.
func TestAVanishedClientStopsCostingTheRoomBandwidth(t *testing.T) {
	if testing.Short() {
		t.Skip("runs a real server and client session with a download in flight")
	}

	flooded := measureAfterAClientVanishes(t, time.Minute)
	held := measureAfterAClientVanishes(t, 0)
	t.Logf("server wrote %d KiB in the window without the hold, %d KiB with it",
		flooded/1024, held/1024)

	// Without the hold this is megabytes a second into the track every other
	// client downloads through; if it is not, the experiment did not
	// reproduce the flood and proves nothing about the hold.
	if flooded < 2*1024*1024 {
		t.Fatalf("the flood did not reproduce: %d KiB in the window", flooded/1024)
	}
	// With it, what is left is the probe: one held-back push per probeEvery,
	// plus the pump's keyframes.
	if held > 512*1024 {
		t.Fatalf("a vanished client still cost the room %d KiB in two seconds", held/1024)
	}
}

// measureAfterAClientVanishes returns the bytes the server wrote into the room
// during a two-second window that begins a second after the lane's blackout
// window would have closed. blackout of zero leaves the shipped default.
func measureAfterAClientVanishes(t *testing.T, blackout time.Duration) int64 {
	t.Helper()
	name, room := newMemRoom(t)
	// Liveness must not end the session inside the window: this is about the
	// time before that, which is what #24 is about.
	startRoomServer(t.Context(), t, name, room,
		control.Config{Interval: time.Second, Timeout: 30 * time.Second, Failures: 8})
	echo := startEcho(t)

	srv := waitForServer(t, room)
	if blackout != 0 {
		srv.blackoutAfter = blackout
	}

	const clients = 4
	stops := make([]func(), 0, clients)
	t.Cleanup(func() {
		for _, stop := range stops {
			stop()
		}
	})
	for i := range clients {
		addr, stop := startRoomClient(t.Context(), t, name, "vanishing-"+strconv.Itoa(i))
		stops = append(stops, stop)
		go func() { _ = echoThrough(addr, echo, 16*1024*1024) }()
	}

	server := waitForSending(t, room)
	members := waitForClientMembers(t, room, clients)

	// The clients are gone: they receive nothing, so they answer nothing.
	for _, member := range members {
		member.deaf.Store(true)
	}
	// The same moment in both arms: a second past the shipped blackout
	// window, while a download that nobody is reading is still in flight.
	time.Sleep(defaultBlackoutAfter + time.Second)

	before := server.bytesOut.Load()
	time.Sleep(2 * time.Second)
	written := server.bytesOut.Load() - before
	t.Logf("blackout %s: %d KiB in the window", cmpOr(blackout, defaultBlackoutAfter), written/1024)
	return written
}

func cmpOr(d, fallback time.Duration) time.Duration {
	if d != 0 {
		return d
	}
	return fallback
}

func waitForServer(t *testing.T, room *memRoom) *streamTransport {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if srv := room.server.Load(); srv != nil {
			return srv
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("the room never had a server")
	return nil
}

// waitForSending returns the server's member once it is actually pushing the
// download: measuring before that measures nothing.
func waitForSending(t *testing.T, room *memRoom) *memMember {
	t.Helper()
	deadline := time.Now().Add(30 * time.Second)
	for time.Now().Before(deadline) {
		if m := room.serverMember(); m != nil && m.bytesOut.Load() > 64*1024 {
			return m
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("the server never got the download going")
	return nil
}

func waitForClientMembers(t *testing.T, room *memRoom, want int) []*memMember {
	t.Helper()
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		if clients := room.clients(); len(clients) == want {
			return clients
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("the room never had %d clients", want)
	return nil
}
