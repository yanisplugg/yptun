package salutejazz

import (
	"errors"
	"testing"
	"time"

	"github.com/livekit/protocol/livekit"

	"github.com/openlibrecommunity/olcrtc/internal/relaywin"
)

// ai-generated: the whole file (the relay window's tests on the slow leg,
// olcrtc#49).

// windowBound is the most a leg may hold while the window works: a window,
// the record a sender may start with a byte of room left, and the marks and
// the SFU's stamps on top of what they count.
const windowBound = relayWindow + slowLegRecord + 8<<10

// pairWindow runs the part of the tunnel handshake the window rides on: the
// client confirms the server's identity, a request goes up and the reply
// comes back. What the engine itself puts on the wire at ConfirmPeer travels
// the same ordered lanes ahead of both, so once the reply is in, it has been
// handled at each end. The room has to be up: see waitForRoom.
func pairWindow(t *testing.T, server *Session, serverGot *tally, client *Session, clientGot *tally) {
	t.Helper()
	pairWindowNth(t, 0, server, serverGot, client, clientGot)
}

// pairWindowNth is pairWindow for the nth pairing a test makes. Its hello and
// welcome carry n, so a pairing earlier in the test cannot stand in for them.
func pairWindowNth(t *testing.T, n int, server *Session, serverGot *tally, client *Session, clientGot *tally) {
	t.Helper()
	if err := client.ConfirmPeer(server.localIdentity()); err != nil {
		t.Fatal(err)
	}
	if err := client.SendTo(server.localIdentity(), taggedPayload("hello", n, 64)); err != nil {
		t.Fatal(err)
	}
	waitFor(t, 5*time.Second, "the hello at the server", func() bool {
		_, ok := serverGot.arrival("hello", n)
		return ok
	})
	if err := server.SendTo(client.localIdentity(), taggedPayload("welcome", n, 64)); err != nil {
		t.Fatal(err)
	}
	waitFor(t, 5*time.Second, "the welcome at the client", func() bool {
		_, ok := clientGot.arrival("welcome", n)
		return ok
	})
}

// windowPair is a server and one client, in the room and paired.
func windowPair(t *testing.T, url string, serverSetup ...func(*Session)) (*Session, *tally, *Session, *tally) {
	t.Helper()
	server, serverGot := connectTally(t, url, "server", serverSetup...)
	client, clientGot := connectTally(t, url, "client")
	waitForRoom(t, server, client)
	pairWindow(t, server, serverGot, client, clientGot)
	return server, serverGot, client, clientGot
}

// TestBoundsTheRelayQueue is olcrtc#49 on the fake: a server pushes a 4 MiB
// download to a client whose leg carries 64 kB/s, from the first byte after
// the handshake. The SFU takes everything at once, so without a window the
// leg ends up holding the whole download - over a minute of it, with every
// control ping behind it. With one, it never holds more than a window and a
// record, and the download still moves.
func TestBoundsTheRelayQueue(t *testing.T) {
	url, fake := newFakeConnector(t)
	server, _, client, clientGot := windowPair(t, url)
	to := client.localIdentity()

	const (
		rate  = 64_000
		load  = 4 << 20
		watch = 4 * time.Second
	)
	fake.slowLeg(to, rate)
	writer := startBulk(func(p []byte) error { return server.SendTo(to, p) }, "bulk", load, slowLegRecord)
	t.Cleanup(writer.halt)
	time.Sleep(watch)

	peak := fake.peakQueuedTo(to)
	t.Logf("in %s the leg peaked at %d KiB and delivered %d KiB", watch, peak>>10, clientGot.streamBytes()>>10)
	if peak > windowBound {
		t.Fatalf("the leg to the client held %d KiB at its peak, over the %d KiB a window allows "+
			"(the server had handed the SFU %d KiB in %s)",
			peak>>10, windowBound>>10, writer.sent.Load()*slowLegRecord>>10, watch)
	}
	if delivered, least := clientGot.streamBytes(), int(rate*watch.Seconds())/2; delivered < least {
		t.Fatalf("the client received %d bytes in %s on a %d B/s leg, want at least %d", delivered, watch, rate, least)
	}
	if stray := clientGot.strayLength(); stray >= 0 {
		t.Fatalf("the client was handed a %d byte payload no test sent", stray)
	}
}

// TestSmallFrameLatencyUnderLoad is what the bound buys: a small frame sent
// in the middle of a download - a control ping, a new stream's first bytes -
// waits behind at most a window and a record on the slow leg, plus the moment
// it takes an echo to make room for it.
func TestSmallFrameLatencyUnderLoad(t *testing.T) {
	url, fake := newFakeConnector(t)
	server, _, client, clientGot := windowPair(t, url)
	to := client.localIdentity()

	const rate = 128_000
	fake.slowLeg(to, rate)
	writer := startBulk(func(p []byte) error { return server.SendTo(to, p) }, "bulk", 16<<20, slowLegRecord)
	t.Cleanup(writer.halt)
	waitFor(t, 10*time.Second, "the download to fill the leg", func() bool {
		return fake.queuedTo(to) >= relayWindow/2
	})
	time.Sleep(time.Second)

	budget := time.Duration(relayWindow+slowLegRecord)*time.Second/rate + time.Second
	pingWithin(t, server, clientGot, fake, to, budget, "the download")
}

// pingWithin has server send a small frame to the participant named to,
// beside whatever load the test keeps going, and fails the test unless got
// has it within budget.
func pingWithin(t *testing.T, server *Session, got *tally, fake *fakeSFU, to string,
	budget time.Duration, behind string,
) {
	t.Helper()
	sent := time.Now()
	pinged := make(chan error, 1)
	go func() { pinged <- server.SendTo(to, taggedPayload("ping", 0, 64)) }()
	for {
		if at, ok := got.arrival("ping", 0); ok {
			latency := at.Sub(sent)
			if latency > budget {
				t.Fatalf("the ping took %s behind %s, over the %s a window allows", latency, behind, budget)
			}
			t.Logf("the ping took %s behind %s, within %s", latency.Round(time.Millisecond), behind, budget)
			break
		}
		if waited := time.Since(sent); waited > budget+2*time.Second {
			t.Fatalf("the ping had not arrived %s after it was sent behind %s, over the %s a window allows "+
				"(the leg holds %d KiB)", waited.Round(time.Millisecond), behind, budget, fake.queuedTo(to)>>10)
		}
		time.Sleep(10 * time.Millisecond)
	}
	if err := <-pinged; err != nil {
		t.Fatalf("the ping = %v", err)
	}
}

// TestOlderPeerIsSentToAsToday is the compatibility rule, both ways round,
// with a relay that drops every window frame - which is what a peer of a
// build before the window amounts to: nothing is ever echoed, and nothing is
// ever marked back. A client marks its confirmed server anyway (an older
// server reads the mark as a frame it cannot decrypt and moves on); a server
// marks nobody who has not spoken the window first. Neither is ever held: on
// two stalled legs each sends four windows' worth as it always has.
func TestOlderPeerIsSentToAsToday(t *testing.T) {
	url, fake := newFakeConnector(t)
	fake.swallow(windowTopic)
	server, _, client, _ := windowPair(t, url)
	serverID, clientID := server.localIdentity(), client.localIdentity()
	fake.slowLeg(serverID, 0)
	fake.slowLeg(clientID, 0)

	const load = 4 * relayWindow
	up := startBulk(func(p []byte) error { return client.SendTo(serverID, p) }, "up", load, slowLegRecord)
	down := startBulk(func(p []byte) error { return server.SendTo(clientID, p) }, "down", load, slowLegRecord)
	if err := up.finish(t, 10*time.Second, "the upload to a server that never echoes"); err != nil {
		t.Fatalf("the upload = %v", err)
	}
	if err := down.finish(t, 10*time.Second, "the download to a client that never spoke the window"); err != nil {
		t.Fatalf("the download = %v", err)
	}
	// Both writers are done; what they wrote reaches the stalled legs as
	// their own lanes drain into the SFU, all of it.
	for who, id := range map[string]string{"server": serverID, "client": clientID} {
		waitFor(t, 10*time.Second, "the leg to the "+who+" to hold everything sent as today", func() bool {
			return fake.peakQueuedTo(id) >= load
		})
	}
	if marks := fake.framesFrom(clientID, windowTopic); marks == 0 {
		t.Fatal("the client never marked its confirmed server")
	}
	if marks := fake.framesFrom(serverID, windowTopic); marks != 0 {
		t.Fatalf("the server put %d window frames on the wire to a client that never spoke the window", marks)
	}
}

// TestServerArmsOnFirstWindowFrame covers the other half of the rule: the
// first window frame a client sends - the mark its ConfirmPeer puts on the
// wire ahead of any data - is what turns the server's window toward it on,
// before the server's first reply byte and without waiting for an echo.
// Until then the server sends as it always has, and marks nothing.
func TestServerArmsOnFirstWindowFrame(t *testing.T) {
	url, fake := newFakeConnector(t)
	server, _ := connectTally(t, url, "server")
	client, _ := connectTally(t, url, "client")
	waitForRoom(t, server, client)
	serverID, clientID := server.localIdentity(), client.localIdentity()
	fake.slowLeg(clientID, 0)

	before := startBulk(func(p []byte) error { return server.SendTo(clientID, p) }, "before", 2*relayWindow, slowLegRecord)
	if err := before.finish(t, 5*time.Second, "the server before the client spoke the window"); err != nil {
		t.Fatalf("the send before = %v", err)
	}
	if marks := fake.framesFrom(serverID, windowTopic); marks != 0 {
		t.Fatalf("the server marked a client that had not spoken the window %d times", marks)
	}

	// ConfirmPeer alone, with no data behind it: its mark is the frame.
	if err := client.ConfirmPeer(serverID); err != nil {
		t.Fatal(err)
	}
	waitFor(t, 5*time.Second, "the server to hear the client's first window frame", func() bool {
		return server.current().marks(clientID)
	})

	// Two windows are in flight and the window is on now: the first send
	// after the frame is held, and asks the client for an echo.
	after := startBulk(func(p []byte) error { return server.SendTo(clientID, p) }, "after", 1<<20, slowLegRecord)
	t.Cleanup(after.halt)
	if held := after.waitHeld(t, "the server to hold once the client spoke"); held != 0 {
		t.Fatalf("the server sent %d frames past two windows in flight to a client that speaks the window", held)
	}
	if marks := fake.framesFrom(serverID, windowTopic); marks == 0 {
		t.Fatal("the server holds without having marked the client")
	}
	// The leg moves, the mark reaches the client behind what it counts, the
	// echo comes back, and the server goes on.
	fake.slowLeg(clientID, 1_000_000)
	waitFor(t, 10*time.Second, "the server to go on once the client echoed", func() bool {
		return after.sent.Load() > 0
	})
}

// TestEchoFromAThirdIdentityMovesNothing is who an echo counts from: the
// destination the window is kept for, named by the SFU's identity stamp. An
// echo of the right count from anyone else, or from a sender the SFU names
// only by its sid, moves nothing; nor does an echo of a count from before a
// Reset, which is past the new window's start. None of it reaches the
// callbacks.
func TestEchoFromAThirdIdentityMovesNothing(t *testing.T) {
	sess, in := newCallbackSession(t)
	gen := newGeneration(nil)
	storeString(&gen.identity, "self")
	now := time.Now()
	held := func() bool {
		ok, _, _ := gen.win.Room("server", now)
		return !ok
	}
	echo := func(counter uint64) []byte { return windowFrame{kind: windowEcho, counter: counter}.encode() }

	gen.win.Sent("server", relayWindow, now)
	// The server's first window frame arms the window: a whole one is in
	// flight, so the sender holds.
	sess.handleDataPacket(gen, userPacket(t, "server", windowTopic, echo(0)))
	if !held() {
		t.Fatal("a window in flight to a destination that speaks the window does not hold")
	}

	sess.handleDataPacket(gen, userPacket(t, "third", windowTopic, echo(relayWindow)))
	sess.handleDataPacket(gen, sidWindowPacket(t, "PA_server", echo(relayWindow)))
	if !held() {
		t.Fatal("an echo from someone other than the destination moved its window")
	}
	if gen.marks("PA_server") {
		t.Fatal("a sender the SFU named only by its sid was taken for a peer that speaks the window")
	}
	sess.handleDataPacket(gen, userPacket(t, "server", windowTopic, echo(relayWindow)))
	if held() {
		t.Fatal("the destination's own echo of everything in flight left the sender held")
	}

	// A Reset starts the window over, past every count before it.
	gen.win.Reset("server")
	gen.win.Sent("server", relayWindow, now)
	gen.win.Arm("server")
	sess.handleDataPacket(gen, userPacket(t, "server", windowTopic, echo(relayWindow)))
	if !held() {
		t.Fatal("an echo from before a Reset moved the window after it")
	}
	sess.handleDataPacket(gen, userPacket(t, "server", windowTopic, echo(2*relayWindow)))
	if held() {
		t.Fatal("an echo of the new window's count left the sender held")
	}

	select {
	case got := <-in:
		t.Fatalf("a window frame reached the %s callback as a %d byte payload", got.lane, len(got.payload))
	default:
	}
}

// sidWindowPacket is a window frame from a sender the SFU names only by its
// sid.
func sidWindowPacket(t *testing.T, sid string, frame []byte) []byte {
	t.Helper()
	topic := windowTopic
	return marshalPacket(t, &livekit.DataPacket{Value: &livekit.DataPacket_User{
		User: &livekit.UserPacket{Payload: frame, ParticipantSid: sid, Topic: &topic},
	}})
}

// TestGenerationTeardownReleasesWithErrSessionClosed is the way out of a
// window that never opens: the attempt ends. The held sender is told the
// session is closed, as a sender held on the lane's mark is, and is not left
// waiting for an echo that cannot come.
func TestGenerationTeardownReleasesWithErrSessionClosed(t *testing.T) {
	url, fake := newFakeConnector(t)
	server, _, client, _ := windowPair(t, url)
	to := client.localIdentity()
	fake.slowLeg(to, 0)

	writer := startBulk(func(p []byte) error { return server.SendTo(to, p) }, "bulk", 4<<20, slowLegRecord)
	writer.waitHeld(t, "the server to hold on a full window")
	go server.teardown(server.current())
	if err := writer.finish(t, 5*time.Second, "the held sender after teardown"); !errors.Is(err, ErrSessionClosed) {
		t.Fatalf("the held send = %v, want %v", err, ErrSessionClosed)
	}
}

// TestATeardownMidLookIsSessionClosed is the same way out, landing while the
// held sender is looking at its window rather than parked on it: teardown
// ends the generation and then drops its windows, so a sender that looked
// at the generation just before may find its epoch gone. That is still the
// session closing, not its destination's session ending, and says so. The
// sender here looks again every nanosecond, and each trial lands the
// teardown somewhere in its loop.
func TestATeardownMidLookIsSessionClosed(t *testing.T) {
	const trials = 500
	for trial := range trials {
		s := &Session{closeCh: make(chan struct{})}
		gen := newGeneration(nil)
		gen.win = relaywin.New(relaywin.Timing{Window: 1, Retry: time.Nanosecond})
		gen.win.Arm("peer")
		gen.win.Sent("peer", 1, time.Now())
		epoch := gen.win.Epoch("peer")

		held := make(chan error, 1)
		go func() { held <- s.awaitRelayWindow(gen, "peer", epoch) }()
		time.Sleep(time.Duration(trial%10) * time.Microsecond)
		s.teardown(gen)
		select {
		case err := <-held:
			if !errors.Is(err, ErrSessionClosed) {
				t.Fatalf("trial %d: the held send = %v, want %v", trial, err, ErrSessionClosed)
			}
		case <-time.After(5 * time.Second):
			t.Fatalf("trial %d: the held send outlived its generation", trial)
		}
	}
}

// TestWindowFramesNeverReachOnData covers the receive side of the topic:
// marks and echoes are the window's, and nothing under the topic reaches the
// tunnel - not a frame it answers, not one it cannot read, not one from a
// sender it does not act on. Through the fake, a download and an upload at
// once leave both ends with exactly the bytes that were sent, while window
// frames flowed both ways.
func TestWindowFramesNeverReachOnData(t *testing.T) {
	sess, in := newCallbackSession(t)
	gen := newGeneration(nil)
	storeString(&gen.identity, "self")
	for name, packet := range map[string][]byte{
		"a mark":             userPacket(t, "peer", windowTopic, windowFrame{kind: windowMark, counter: 7}.encode()),
		"an echo":            userPacket(t, "peer", windowTopic, windowFrame{kind: windowEcho, counter: 7}.encode()),
		"another version":    userPacket(t, "peer", windowTopic, append([]byte{2}, make([]byte, 13)...)),
		"a short frame":      userPacket(t, "peer", windowTopic, []byte{1, 1}),
		"a frame from a sid": sidWindowPacket(t, "PA_peer", windowFrame{kind: windowMark}.encode()),
	} {
		sess.handleDataPacket(gen, packet)
		select {
		case got := <-in:
			t.Fatalf("%s reached the %s callback as a %d byte payload", name, got.lane, len(got.payload))
		default:
		}
	}

	url, fake := newFakeConnector(t)
	server, serverGot, client, clientGot := windowPair(t, url)
	serverID, clientID := server.localIdentity(), client.localIdentity()
	fake.slowLeg(serverID, 400_000)
	fake.slowLeg(clientID, 400_000)
	const load = 3 * relayWindow
	up := startBulk(func(p []byte) error { return client.SendTo(serverID, p) }, "up", load, slowLegRecord)
	down := startBulk(func(p []byte) error { return server.SendTo(clientID, p) }, "down", load, slowLegRecord)
	for _, w := range []*bulkWriter{up, down} {
		if err := w.finish(t, 20*time.Second, "a load both ways"); err != nil {
			t.Fatalf("a load both ways = %v", err)
		}
	}
	for who, got := range map[string]*tally{"server": serverGot, "client": clientGot} {
		waitFor(t, 10*time.Second, "the "+who+" to receive the load", func() bool {
			return got.streamBytes() >= load+64
		})
		if stray := got.strayLength(); stray >= 0 {
			t.Fatalf("the %s was handed a %d byte payload no test sent", who, stray)
		}
		if bytes := got.streamBytes(); bytes != load+64 {
			t.Fatalf("the %s received %d bytes, want the %d sent", who, bytes, load+64)
		}
	}
	for who, id := range map[string]string{"server": serverID, "client": clientID} {
		if fake.framesFrom(id, windowTopic) == 0 {
			t.Fatalf("the %s put no window frame on the wire", who)
		}
	}
}

// TestDeadAfterBackstop is the window giving up on a destination that holds
// a sender for DeadAfter without a single echo: the window turns off and the
// sender goes on as it would without one. Liveness, not the window, is what
// decides a peer is gone; this only keeps a window from holding forever. The
// engine's DeadAfter is two minutes, so the test shortens it.
func TestDeadAfterBackstop(t *testing.T) {
	const deadAfter = 1500 * time.Millisecond
	url, fake := newFakeConnector(t)
	server, _, client, _ := windowPair(t, url, func(s *Session) {
		s.relayTiming = relaywin.Timing{
			Window: relayWindow, MarkEvery: relayMarkEvery,
			ProbeAfter: 200 * time.Millisecond, DeadAfter: deadAfter, Retry: 50 * time.Millisecond,
		}
	})
	to := client.localIdentity()
	fake.slowLeg(to, 0)

	writer := startBulk(func(p []byte) error { return server.SendTo(to, p) }, "bulk", 1<<20, slowLegRecord)
	writer.waitHeld(t, "the server to hold on a leg that never delivers")
	if err := writer.finish(t, deadAfter+5*time.Second, "the server once DeadAfter had passed"); err != nil {
		t.Fatalf("the send after DeadAfter = %v", err)
	}
	held := time.Duration(writer.longest.Load())
	if held < deadAfter*9/10 || held > deadAfter+time.Second {
		t.Fatalf("the server was held %s, want about the %s DeadAfter", held, deadAfter)
	}
}

// TestBulkBothWaysAsymmetricLegs documents how the two directions of a
// tunnel are coupled. A mark travels behind the data it counts and its echo
// behind the other direction's data, so the upload's window moves only as
// fast as its echoes come down the slow leg: here the upload has a leg four
// times the download's and still runs at about the download's pace. What the
// window guarantees holds all the same: neither leg holds more than a window,
// and both directions move.
func TestBulkBothWaysAsymmetricLegs(t *testing.T) {
	url, fake := newFakeConnector(t)
	server, serverGot, client, clientGot := windowPair(t, url)
	serverID, clientID := server.localIdentity(), client.localIdentity()

	const (
		downRate = 64_000
		upRate   = 256_000
		watch    = 6 * time.Second
	)
	fake.slowLeg(clientID, downRate)
	fake.slowLeg(serverID, upRate)
	down := startBulk(func(p []byte) error { return server.SendTo(clientID, p) }, "down", 16<<20, slowLegRecord)
	up := startBulk(func(p []byte) error { return client.SendTo(serverID, p) }, "up", 16<<20, slowLegRecord)
	t.Cleanup(down.halt)
	t.Cleanup(up.halt)
	time.Sleep(watch)

	for who, id := range map[string]string{"server": serverID, "client": clientID} {
		if peak := fake.peakQueuedTo(id); peak > windowBound {
			t.Fatalf("the leg to the %s held %d KiB, over the %d KiB a window allows", who, peak>>10, windowBound>>10)
		}
	}
	downloaded, uploaded := clientGot.streamBytes(), serverGot.streamBytes()
	t.Logf("in %s: download %.0f kB/s on a %d kB/s leg, upload %.0f kB/s on a %d kB/s leg",
		watch, float64(downloaded)/watch.Seconds()/1000, downRate/1000,
		float64(uploaded)/watch.Seconds()/1000, upRate/1000)
	if least := int(downRate*watch.Seconds()) / 2; downloaded < least {
		t.Fatalf("the download moved %d bytes, want at least %d", downloaded, least)
	}
	if uploaded < relayWindow {
		t.Fatalf("the upload moved %d bytes, not even its first window", uploaded)
	}
	if most := int(upRate*watch.Seconds()) / 2; uploaded > most {
		t.Fatalf("the upload moved %d bytes, over half its leg: its echoes no longer wait behind the download "+
			"- update this test's account of the coupling", uploaded)
	}
}

// TestFanInTwoUploaders is two clients uploading to one server at once. Each
// keeps its own window toward the server, so the server's leg holds up to one
// window per uploader: the advertised window in every frame is reserved for
// budgeting this, and version 1 does not. Both uploads move.
func TestFanInTwoUploaders(t *testing.T) {
	url, fake := newFakeConnector(t)
	server, serverGot := connectTally(t, url, "server")
	first, firstGot := connectTally(t, url, "first")
	second, secondGot := connectTally(t, url, "second")
	waitForRoom(t, server, first, second)
	pairWindow(t, server, serverGot, first, firstGot)
	pairWindow(t, server, serverGot, second, secondGot)
	serverID := server.localIdentity()

	const (
		rate  = 128_000
		watch = 5 * time.Second
	)
	fake.slowLeg(serverID, rate)
	one := startBulk(func(p []byte) error { return first.SendTo(serverID, p) }, "one", 16<<20, slowLegRecord)
	two := startBulk(func(p []byte) error { return second.SendTo(serverID, p) }, "two", 16<<20, slowLegRecord)
	t.Cleanup(one.halt)
	t.Cleanup(two.halt)
	time.Sleep(watch)

	if peak := fake.peakQueuedTo(serverID); peak > 2*windowBound {
		t.Fatalf("the leg to the server held %d KiB for two uploaders, over the %d KiB two windows allow",
			peak>>10, 2*windowBound>>10)
	}
	var fromOne, fromTwo int
	for seq := range int(one.sent.Load()) {
		if _, ok := serverGot.arrival("one", seq); ok {
			fromOne += slowLegRecord
		}
	}
	for seq := range int(two.sent.Load()) {
		if _, ok := serverGot.arrival("two", seq); ok {
			fromTwo += slowLegRecord
		}
	}
	total := fromOne + fromTwo
	t.Logf("in %s: %d KiB from the first uploader, %d KiB from the second, the leg peaked at %d KiB",
		watch, fromOne>>10, fromTwo>>10, fake.peakQueuedTo(serverID)>>10)
	if least := int(rate*watch.Seconds()) / 2; total < least {
		t.Fatalf("the server received %d bytes from both, want at least %d", total, least)
	}
	if fromOne < total/4 || fromTwo < total/4 {
		t.Fatalf("one uploader starved the other: %d and %d bytes", fromOne, fromTwo)
	}
}

// TestHeldSendAcrossEpochNeverSends is the epoch guard. A send held on a
// window belongs to the session that was live when it began; when that
// session ends while it waits - the server retires the peer, the client
// drops its binding or binds another server - the send returns an error and
// its frame never reaches the wire: the other end may already be answering a
// fresh handshake under the same identity, and an old session's frame in the
// middle of it corrupts that stream.
//
// What the SFU holds outlives the session, so a retirement keeps the window's
// counts - the next send is held too, until the old backlog drains - while a
// binding that is dropped or moved starts the window over.
func TestHeldSendAcrossEpochNeverSends(t *testing.T) {
	for _, tc := range []struct {
		name      string
		upload    bool
		end       func(server, client *Session)
		heldAfter bool
	}{
		{"the server retires the client", false, func(server, client *Session) {
			server.RetirePeer(client.localIdentity())
		}, true},
		{"the client drops its binding", true, func(_, client *Session) {
			client.ResetPeer()
		}, false},
		{"the client binds another server", true, func(_, client *Session) {
			if err := client.ConfirmPeer("fakepart-elsewhere"); err != nil {
				panic(err)
			}
		}, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			url, fake := newFakeConnector(t)
			server, serverGot, client, clientGot := windowPair(t, url)
			sender, to, got := server, client.localIdentity(), clientGot
			if tc.upload {
				sender, to, got = client, server.localIdentity(), serverGot
			}
			send := func(p []byte) error { return sender.SendTo(to, p) }
			fake.slowLeg(to, 0)

			writer := startBulk(send, "bulk", 4<<20, slowLegRecord)
			held := int(writer.waitHeld(t, "the sender to hold on a full window"))
			tc.end(server, client)
			if err := writer.finish(t, 2*time.Second, "the held send once its session ended"); !errors.Is(err, ErrDestinationEnded) {
				t.Fatalf("the held send = %v, want %v", err, ErrDestinationEnded)
			}

			after := make(chan error, 1)
			go func() { after <- send(taggedPayload("after", 0, 64)) }()
			if tc.heldAfter {
				select {
				case err := <-after:
					t.Fatalf("the send after a retirement went at once (%v): the old backlog is still queued", err)
				case <-time.After(slowLegSettle):
				}
			}
			fake.slowLeg(to, 2_000_000)
			select {
			case err := <-after:
				if err != nil {
					t.Fatalf("the send after the session ended = %v", err)
				}
			case <-time.After(10 * time.Second):
				t.Fatal("the send after the session ended never went")
			}
			waitFor(t, 10*time.Second, "everything sent before the change, and the send after it", func() bool {
				_, last := got.arrival("bulk", held-1)
				_, fresh := got.arrival("after", 0)
				return last && fresh
			})
			time.Sleep(slowLegSettle)
			if _, ok := got.arrival("bulk", held); ok {
				t.Fatal("the frame held when its session ended reached the other end")
			}
		})
	}
}

// TestParticipantLeftReleasesParkedPublish is the destination leaving the
// room while a send to it is held: the connector's participant-left, or a
// roster update that reports it disconnected. Either releases the held send
// with the error of a session that ended, and a window kept for someone who
// is not there any more holds nothing after.
func TestParticipantLeftReleasesParkedPublish(t *testing.T) {
	for _, tc := range []struct {
		name  string
		leave func(server, client *Session)
	}{
		{"the connector reports it gone", func(server, client *Session) {
			server.handleEnvelope(server.current(), envIn{
				Event: eventParticipantLeft, ParticipantID: client.localIdentity(),
			})
		}},
		{"the roster reports it disconnected", func(_, client *Session) {
			_ = client.Close()
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			url, fake := newFakeConnector(t)
			server, _, client, _ := windowPair(t, url)
			to := client.localIdentity()
			fake.slowLeg(to, 0)

			writer := startBulk(func(p []byte) error { return server.SendTo(to, p) }, "bulk", 4<<20, slowLegRecord)
			writer.waitHeld(t, "the server to hold on a full window")
			tc.leave(server, client)
			if err := writer.finish(t, 5*time.Second, "the held send once its destination left"); !errors.Is(err, ErrDestinationEnded) {
				t.Fatalf("the held send = %v, want %v", err, ErrDestinationEnded)
			}
			done := make(chan error, 1)
			go func() { done <- server.SendTo(to, taggedPayload("after", 0, slowLegRecord)) }()
			select {
			case <-done:
			case <-time.After(2 * time.Second):
				t.Fatal("a send to a participant that has left was held")
			}
		})
	}
}

// TestAPeerThatLeftKeepsNoWindow is a server that stays joined while its
// clients come and go. The room reports a client gone with a send to it held
// on a full window, and the server's session for that client goes on sending
// until liveness ends it - pings, keepalives, stream data, datagrams - and is
// then retired. None of it opens a window toward the client again: the SFU
// drops what is addressed to someone who is not there, and a window opened
// for them would stay, with its wake channel, until the generation goes - one
// for every client that ever left.
func TestAPeerThatLeftKeepsNoWindow(t *testing.T) {
	for _, tc := range []struct {
		name  string
		leave func(server, client *Session)
	}{
		{"the connector reports it gone", func(server, client *Session) {
			server.handleEnvelope(server.current(), envIn{
				Event: eventParticipantLeft, ParticipantID: client.localIdentity(),
			})
		}},
		{"the roster reports it disconnected", func(_, client *Session) {
			_ = client.Close()
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			url, fake := newFakeConnector(t)
			server, _, client, _ := windowPair(t, url)
			to := client.localIdentity()
			fake.slowLeg(to, 0)

			writer := startBulk(func(p []byte) error { return server.SendTo(to, p) }, "bulk", 4<<20, slowLegRecord)
			writer.waitHeld(t, "the server to hold on a full window")
			tc.leave(server, client)
			if err := writer.finish(t, 5*time.Second, "the held send once its destination left"); !errors.Is(err, ErrDestinationEnded) {
				t.Fatalf("the held send = %v, want %v", err, ErrDestinationEnded)
			}
			for i := range 3 {
				if err := server.SendTo(to, taggedPayload("after", i, 64)); err != nil {
					t.Fatalf("a send to a participant that has left = %v", err)
				}
				if err := server.SendDatagramTo(to, taggedPayload("datagram", i, 64)); err != nil {
					t.Fatalf("a datagram to a participant that has left = %v", err)
				}
			}
			server.RetirePeer(to)
			if n := server.current().win.Len(); n != 0 {
				t.Fatalf("the server keeps %d relay windows once its only client has left, want 0", n)
			}
		})
	}
}

// TestDatagramsCountAndDropOnlyPastWPlusD is the lossy lane under the window
// (objection 2 of the challenge). A datagram is queued at the SFU like any
// other packet, so it counts against its destination's window. It is never
// held - a datagram late is a datagram lost - but once more than a window
// and datagramSlack are in flight to a destination whose window is on, it is
// dropped: a QUIC bulk flow can then no longer rebuild the queue the window
// bounds, and the slack still lets UDP through beside a TCP pull that keeps
// the window full. A destination whose window is off - an older build, which
// never echoes - never has a datagram dropped by this rule. The window is the
// destination's own, sized for its leg (relaywin's Horizon); here the leg
// has not moved, so it is the size a window starts at.
func TestDatagramsCountAndDropOnlyPastWPlusD(t *testing.T) {
	const size = 1024
	budget := relayWindow + datagramSlack

	t.Run("a window that is on", func(t *testing.T) {
		url, fake := newFakeConnector(t)
		server, _, client, clientGot := windowPair(t, url)
		to := client.localIdentity()
		fake.slowLeg(to, 0)
		gen := server.current()
		onBudget := int(gen.win.Size(to)) + datagramSlack //nolint:gosec // a window is at most relayWindow, which fits

		went := 0
		for gen.lossyDrops.Load() == 0 && went < 2*onBudget/size {
			if err := server.SendDatagramTo(to, taggedPayload("dgram", went, size)); err != nil {
				t.Fatal(err)
			}
			went++
		}
		went-- // the one that was dropped
		if least, most := onBudget/(size+128), onBudget/size+1; went < least || went > most {
			t.Fatalf("the first datagram was dropped after %d went, want between %d and %d for %d KiB",
				went, least, most, onBudget>>10)
		}
		for seq := range 8 {
			if err := server.SendDatagramTo(to, taggedPayload("over", seq, size)); err != nil {
				t.Fatal(err)
			}
		}
		if dropped := gen.lossyDrops.Load(); dropped != 9 {
			t.Fatalf("%d datagrams were dropped past the window and its slack, want every one of the 9", dropped)
		}
		// The datagrams filled the window for the byte stream too.
		held := make(chan error, 1)
		go func() { held <- server.SendTo(to, taggedPayload("held", 0, 64)) }()
		select {
		case err := <-held:
			t.Fatalf("a reliable send went past a window of datagrams in flight (%v)", err)
		case <-time.After(slowLegSettle):
		}

		// The leg moves: what went arrives, the echoes open the window, and
		// datagrams go again.
		fake.slowLeg(to, 2_000_000)
		waitFor(t, 10*time.Second, "the datagrams that went, and the held send", func() bool {
			_, heldIn := clientGot.arrival("held", 0)
			return clientGot.datagramBytes() == went*size && heldIn
		})
		if err := <-held; err != nil {
			t.Fatalf("the held send = %v", err)
		}
		if err := server.SendDatagramTo(to, taggedPayload("again", 0, size)); err != nil {
			t.Fatal(err)
		}
		waitFor(t, 5*time.Second, "a datagram once the window moved", func() bool {
			_, ok := clientGot.arrival("again", 0)
			return ok
		})
		if dropped := gen.lossyDrops.Load(); dropped != 9 {
			t.Fatalf("%d datagrams dropped in all, want the 9 sent past the onBudget", dropped)
		}
	})

	t.Run("a window that is off", func(t *testing.T) {
		url, fake := newFakeConnector(t)
		fake.swallow(windowTopic)
		server, _, client, _ := windowPair(t, url)
		to := client.localIdentity()
		fake.slowLeg(to, 0)
		for seq := range 2 * budget / size {
			if err := server.SendDatagramTo(to, taggedPayload("dgram", seq, size)); err != nil {
				t.Fatal(err)
			}
		}
		if dropped := server.current().lossyDrops.Load(); dropped != 0 {
			t.Fatalf("%d datagrams to a peer that never echoes were dropped", dropped)
		}
	})
}

// TestAConfirmedClientsDatagramsGoToItsServer is where a client's datagram
// goes: to the room until its handshake has confirmed a server, and to that
// server alone after, where it counts against the server's window. Another
// participant in the room - a second client - has it queued toward it no
// more.
func TestAConfirmedClientsDatagramsGoToItsServer(t *testing.T) {
	url, _ := newFakeConnector(t)
	server, serverGot := connectTally(t, url, "server")
	client, clientGot := connectTally(t, url, "client")
	other, otherGot := connectTally(t, url, "other")
	waitForRoom(t, server, client, other)

	if err := client.SendDatagram(taggedPayload("before", 0, 64)); err != nil {
		t.Fatal(err)
	}
	waitFor(t, 5*time.Second, "the room-wide datagram at both others", func() bool {
		_, atServer := serverGot.arrival("before", 0)
		_, atOther := otherGot.arrival("before", 0)
		return atServer && atOther
	})

	pairWindow(t, server, serverGot, client, clientGot)
	if err := client.SendDatagram(taggedPayload("after", 0, 64)); err != nil {
		t.Fatal(err)
	}
	if err := client.SendDatagram(taggedPayload("after", 1, 64)); err != nil {
		t.Fatal(err)
	}
	waitFor(t, 5*time.Second, "the datagrams at the confirmed server", func() bool {
		_, first := serverGot.arrival("after", 0)
		_, second := serverGot.arrival("after", 1)
		return first && second
	})
	if err := client.SendDatagram(taggedPayload("after", 2, 64)); err != nil {
		t.Fatal(err)
	}
	waitFor(t, 5*time.Second, "the last datagram at the server", func() bool {
		_, ok := serverGot.arrival("after", 2)
		return ok
	})
	// Room-wide, each would have reached the other participant about when
	// it reached the server.
	time.Sleep(slowLegSettle)
	for seq := range 3 {
		if _, ok := otherGot.arrival("after", seq); ok {
			t.Fatalf("datagram %d of a confirmed client reached another participant", seq)
		}
	}
}

// TestUDPUnderConcurrentPull is what datagramSlack is for: a TCP pull keeps
// the window toward a client full, and UDP to the same client still flows
// beside it, each datagram waiting at the SFU behind no more than the window
// and the slack.
func TestUDPUnderConcurrentPull(t *testing.T) {
	url, fake := newFakeConnector(t)
	server, _, client, clientGot := windowPair(t, url)
	to := client.localIdentity()

	const (
		rate      = 128_000
		datagrams = 60
		every     = 50 * time.Millisecond
		size      = 512
	)
	fake.slowLeg(to, rate)
	pull := startBulk(func(p []byte) error { return server.SendTo(to, p) }, "bulk", 16<<20, slowLegRecord)
	t.Cleanup(pull.halt)
	waitFor(t, 10*time.Second, "the pull to fill the leg", func() bool {
		return fake.queuedTo(to) >= relayWindow/2
	})
	time.Sleep(time.Second)
	pulled := clientGot.streamBytes()

	sentAt := make([]time.Time, datagrams)
	for seq := range datagrams {
		sentAt[seq] = time.Now()
		if err := server.SendDatagramTo(to, taggedPayload("udp", seq, size)); err != nil {
			t.Fatal(err)
		}
		time.Sleep(every)
	}
	late := time.Duration(relayWindow+datagramSlack)*time.Second/rate + time.Second
	time.Sleep(late)

	arrived := 0
	for seq := range datagrams {
		at, ok := clientGot.arrival("udp", seq)
		if !ok {
			continue
		}
		arrived++
		if took := at.Sub(sentAt[seq]); took > late {
			t.Fatalf("datagram %d took %s beside the pull, over the %s the window and its slack allow", seq, took, late)
		}
	}
	if arrived < datagrams*9/10 {
		t.Fatalf("%d of %d datagrams arrived beside the pull, %d dropped", arrived, datagrams, server.current().lossyDrops.Load())
	}
	if moved := clientGot.streamBytes() - pulled; moved < int(rate*(float64(datagrams)*every.Seconds()))/2 {
		t.Fatalf("the pull moved %d bytes beside the datagrams", moved)
	}
}

// TestAReliableSendGoesBesideADatagramFlood is the other side of the slack. A
// datagram flow faster than the leg keeps between a window and a window and
// the slack in flight to its destination, and takes the room each echo frees
// before a reliable sender held on that window looks again; each of those
// echoes starts the dead clock over too, so the byte stream - control pings
// with it - would wait until liveness closed the session, the #49 close
// brought about by the lossy lane. Once a reliable send has been held for a
// probe interval, datagrams to its destination yield to it: it goes when the
// leg has drained under a window, and arrives behind at most what the flow
// had queued.
func TestAReliableSendGoesBesideADatagramFlood(t *testing.T) {
	url, fake := newFakeConnector(t)
	server, _, client, clientGot := windowPair(t, url)
	to := client.localIdentity()

	const (
		rate  = 64_000
		size  = 1024
		every = 4 * time.Millisecond // four times the leg's rate
	)
	fake.slowLeg(to, rate)
	stop, flooded := make(chan struct{}), make(chan error, 1)
	go func() {
		tick := time.NewTicker(every)
		defer tick.Stop()
		for seq := 0; ; seq++ {
			select {
			case <-stop:
				flooded <- nil
				return
			case <-tick.C:
			}
			if err := server.SendDatagramTo(to, taggedPayload("flood", seq, size)); err != nil {
				flooded <- err
				return
			}
		}
	}()
	defer func() {
		close(stop)
		if err := <-flooded; err != nil {
			t.Errorf("the flood = %v", err)
		}
	}()
	gen := server.current()
	waitFor(t, 10*time.Second, "the flood to fill the window and its slack", func() bool {
		return gen.lossyDrops.Load() > 0
	})

	budget := time.Duration(relayWindow+datagramSlack)*time.Second/rate + relayProbeAfter + time.Second
	pingWithin(t, server, clientGot, fake, to, budget, "the datagram flood")
}
