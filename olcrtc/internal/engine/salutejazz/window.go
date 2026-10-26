package salutejazz

import (
	"encoding/binary"
	"time"

	"github.com/pion/webrtc/v4"
	"google.golang.org/protobuf/proto"

	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/relaywin"
)

// The relay window (olcrtc#49). LiveKit 1.5.3 acknowledges what a sender
// writes at once and queues it toward each receiver without a limit, so on a
// slow SFU-to-receiver leg the queue grows to whatever the receiver's smux
// windows allow - megabytes, tens of seconds of it - with the tunnel's
// control pings waiting at the back until liveness closes a session whose
// bytes are still arriving. The lane's own high-water mark cannot see it: it
// measures the first hop, which the SFU never lets fill.
//
// So every send on the reliable lane counts against a window toward its
// destination (internal/relaywin): a sender may have handed the SFU at most a
// window of bytes for one destination that the destination has not echoed,
// and waits while it has. The window is sized for the destination's leg:
// relayWindow on a leg fast enough, less on a slower one, so that what queues
// ahead of a pong is about relayHorizon of the leg whatever the leg
// (relaywin's Horizon). Which destination a send counts against:
//
//   - a payload addressed to one participant, that participant: the server's
//     reply to a client, keyed by the identity the SFU stamps on the client's
//     packets;
//   - a payload to the room, on a client, the server its ConfirmPeer bound
//     while that server is in the room, which is where the payload then goes
//     (see roomDest);
//   - anything else, nothing: a payload to the room before the handshake has
//     confirmed a server - the hello, and small - or once the server it
//     confirmed has left.
//
// Datagrams count too: the SFU queues them in the same FIFO. They are never
// held - a datagram late is a datagram lost - so one is dropped instead once
// more than a window and datagramSlack are in flight to its destination and
// the window is on, and also while a reliable send to it has been held for a
// probe interval: a datagram flow as fast as the leg would otherwise take the
// room every echo frees, and the byte stream behind it - control pings too -
// would wait until liveness closed the session. A client's datagrams, like
// the rest of what it sends to the room, go to its confirmed server alone, so
// none of them is queued toward anyone else in the room.
//
// The window is on only once the destination has shown it speaks it: its
// first window frame arms it (a client's ConfirmPeer sends one, a mark of
// count 0, ahead of any data, so a server is armed before its first reply
// byte), and so does an echo. A peer of a build before the window never sends
// either, and is sent to as it always was. A server marks nobody who has not
// spoken the window first, so an older client is never handed a frame it
// cannot read; a client marks the server it confirmed from the start, and an
// older server drops the unknown topic as a frame it cannot decrypt.
//
// On the wire a mark or an echo is a UserPacket under windowTopic on the
// reliable lane, addressed to the one participant it is for:
//
//	[version=1][kind: 1 mark, 2 echo][counter, u64 big-endian][advertised window, u32 big-endian]
//
// The counter is the sender's count of bytes handed to the relay for that
// destination when the mark went out, and an echo carries back the counter of
// the mark it answers. The advertised window is reserved for budgeting a
// receiver that several senders fill at once: version 1 writes the window it
// keeps, and a version 1 reader takes the frame whatever it says.
//
// ai-generated: the whole file (olcrtc#49).

const (
	// windowTopic marks a window frame. It is never the tunnel's: the
	// receive path takes it off before anything is delivered.
	windowTopic = "olcrtc.win"

	// windowVersion is the version this engine writes and the only one it
	// reads. A later version that needs another layout says so here.
	windowVersion byte = 1
	windowMark    byte = 1
	windowEcho    byte = 2
	// windowFrameLen is a version 1 frame, and only a frame of exactly this
	// length is read as one.
	windowFrameLen = 1 + 1 + 8 + 4

	// relayWindow is the most that may be in flight to one destination,
	// where the leg is fast enough to carry it (relayHorizon): 192 KiB. A
	// control ping waits behind up to a window one way and its pong behind
	// up to a window the other, and on the slowest SFU-to-receiver leg the
	// gate has measured, 34 kB/s, two windows and a round trip have to fit
	// in the tunnel's 15 s liveness timeout with a margin: 256 KiB does not.
	// At a 0.35 s round trip it still carries about 3.9 Mbit/s (7/8 of a
	// window a round trip), over the gate's 1.4 and 1.6 Mbit/s floors.
	relayWindow = 192 << 10
	// relayMarkEvery keeps a sender's view of a window within an eighth of
	// it.
	relayMarkEvery = relayWindow / 8
	// relayHorizon sizes each window for its leg (relaywin's Horizon): 3 s
	// of the best rate the leg has shown lately may be in flight, so on a
	// leg slower than the 34 kB/s relayWindow was weighed against - the gate
	// has since measured 26 - a pong waits about that, not a whole window. A
	// leg fast enough to carry relayWindow in that time keeps relayWindow.
	// It has to be longer than what Sber's relay holds a leg's bytes for of
	// its own - echoes on the #49 branch's gate came back 0.3 to 6 s after
	// their marks - or a window measures only itself. relayMinWindow is the
	// least a window shrinks to.
	relayHorizon   = 3 * time.Second
	relayMinWindow = relaywin.DefaultMinWindow
	// relayProbeAfter: a sender held this long since its last mark marks
	// again, which repairs a lost mark or echo, and a destination that has
	// not echoed yet is marked at least this often.
	relayProbeAfter = time.Second
	// relayRetry is how often a held sender looks again, for the probe and
	// the dead check; an echo, a Reset or a Bump wakes it at once anyway.
	relayRetry = 250 * time.Millisecond
	// relayDeadAfter lets a destination go that has held a sender this long
	// without an echo. LiveKit never drops reliable data, so a silent
	// destination is a slow leg or a hole being repaired far more often than
	// a dead one, and liveness closes a dead one long before this: it is a
	// backstop, not a verdict.
	relayDeadAfter = 120 * time.Second
	// datagramSlack is how far past its window a destination may be before
	// a datagram to it is dropped. A TCP pull keeps the window full, so
	// without it UDP to the same destination would never go; with it, a
	// QUIC bulk flow still cannot rebuild the queue the window bounds. A
	// pull is let go at every echo; a reliable send held for a probe
	// interval is not being let go, and datagrams yield to it (relaywin's
	// Over).
	datagramSlack = 64 << 10

	// windowReportEvery is how often a destination's delivery rate and echo
	// round trip go to the debug log, and windowMarksKept how many marks in
	// flight a destination keeps the send time of, to time their echoes.
	windowReportEvery = 5 * time.Second
	windowMarksKept   = 64
)

// defaultRelayTiming is the window's pace in the engine; a test shortens it
// through Session.relayTiming.
func defaultRelayTiming() relaywin.Timing {
	return relaywin.Timing{
		Window:     relayWindow,
		MarkEvery:  relayMarkEvery,
		ProbeAfter: relayProbeAfter,
		DeadAfter:  relayDeadAfter,
		Retry:      relayRetry,
		Horizon:    relayHorizon,
		MinWindow:  relayMinWindow,
	}
}

// windowFrame is a mark or an echo, as the wire carries it.
type windowFrame struct {
	kind    byte
	counter uint64
}

// encode writes the frame in version 1, advertising the window this engine
// keeps.
func (f windowFrame) encode() []byte {
	frame := make([]byte, windowFrameLen)
	frame[0] = windowVersion
	frame[1] = f.kind
	binary.BigEndian.PutUint64(frame[2:10], f.counter)
	binary.BigEndian.PutUint32(frame[10:], relayWindow)
	return frame
}

// parseWindowFrame reads a version 1 frame. Another version, a kind it does
// not know or any other length is not one, and reports false.
func parseWindowFrame(frame []byte) (windowFrame, bool) {
	if len(frame) != windowFrameLen || frame[0] != windowVersion {
		return windowFrame{}, false
	}
	kind := frame[1]
	if kind != windowMark && kind != windowEcho {
		return windowFrame{}, false
	}
	return windowFrame{kind: kind, counter: binary.BigEndian.Uint64(frame[2:10])}, true
}

// roomDest is where a payload to dest goes. One addressed to someone goes to
// them. One to the room goes, on a client, to the server its ConfirmPeer bound
// while that server is still in the room: the SFU hands a packet to the room
// to every participant in it, so each upload - and whatever backlog a slow leg
// builds of it - would otherwise be queued toward every other client too,
// where no window of this client's counts it (Jitsi's #25, ported). Before the
// handshake has confirmed a server, and once the one it confirmed has left,
// a payload to the room goes to the room: the hello has to reach a server the
// client has not confirmed yet. A server confirms nobody, so what it sends to
// the room goes to the room.
func (g *generation) roomDest(dest []string) []string {
	if len(dest) > 0 {
		return dest
	}
	bound := loadString(&g.confirmed)
	if bound == "" || !g.inRoom(bound) {
		return nil
	}
	return []string{bound}
}

// relayKey is the window a payload to dest counts against: the one
// participant it is addressed to, and none for a payload to the room or to
// several participants. roomDest has already addressed a client's payload to
// its confirmed server where that applies.
func relayKey(dest []string) string {
	if len(dest) == 1 {
		return dest[0]
	}
	return ""
}

// windowKey is relayKey, and no window for a participant the room has
// reported gone. Its server session goes on sending until liveness ends it,
// and a window opened for those sends would never be dropped again - the
// Reset that departure made is its last - so it would stay until the
// generation goes, one for every client that ever left. The SFU drops what is
// addressed to someone who is not there, and a send held when they left was
// released by that Reset.
func (g *generation) windowKey(dest []string) string {
	key := relayKey(dest)
	if key != "" && g.hasLeft(key) {
		return ""
	}
	return key
}

// relayEpoch is the epoch of the window a send to key belongs to, taken
// before the send waits for anything: 0 for a send with no window.
func (g *generation) relayEpoch(key string) uint64 {
	if key == "" {
		return 0
	}
	return g.win.Epoch(key)
}

// awaitRelayWindow holds the caller while the window toward key is full.
//
// The wake is taken before the window is asked, so an echo that lands between
// the two closes the very channel the caller then waits on. A held sender
// also looks again every Retry: the window may want a probe mark by then, or
// have let a silent destination go. A generation that ends releases it with
// ErrSessionClosed, as the lane's own wait does.
//
// epoch is the window's epoch when the send began. A window whose epoch has
// moved on belongs to a session that has ended since - retired, reset, left
// - and the frame held for it is refused rather than sent into whatever
// comes after under the same identity.
//
// Teardown ends the generation before it drops the windows, so a teardown
// that lands while the sender looks can show up as an epoch gone or as a
// window that lets the send go. Either answer is checked against the
// generation first: the session closing is ErrSessionClosed, whatever the
// window said.
//
// A destination the room has reported gone is refused before the wake is
// taken: the room records the departure before its Reset wakes the sender,
// and taking the wake would open a window again for someone who is not
// there, which nothing would ever drop (windowKey).
func (s *Session) awaitRelayWindow(gen *generation, key string, epoch uint64) error {
	if key == "" {
		return nil
	}
	retry := gen.win.Timing().Retry
	for {
		if gen.isDone() || s.closed.Load() {
			return ErrSessionClosed
		}
		if gen.hasLeft(key) {
			return s.closedOr(gen, ErrDestinationEnded)
		}
		wake := gen.win.Wake(key)
		if gen.win.Epoch(key) != epoch {
			return s.closedOr(gen, ErrDestinationEnded)
		}
		ok, probe, counter := gen.win.Room(key, time.Now())
		if probe {
			s.markRelay(gen, key, counter)
		}
		if ok {
			return s.closedOr(gen, nil)
		}
		timer := time.NewTimer(retry)
		select {
		case <-wake:
		case <-timer.C:
		case <-gen.done:
		case <-s.closeCh:
		}
		timer.Stop()
	}
}

// closedOr is ErrSessionClosed once the generation or the session has ended,
// and err otherwise.
func (s *Session) closedOr(gen *generation, err error) error {
	if gen.isDone() || s.closed.Load() {
		return ErrSessionClosed
	}
	return err
}

// countRelayed counts n bytes handed to the relay for key and, when a mark is
// due, sends it: on the caller's goroutine, straight after the bytes it
// counts, so the ordered lane keeps it behind them.
func (s *Session) countRelayed(gen *generation, key string, n int) {
	if key == "" {
		return
	}
	if due, counter := gen.win.Sent(key, n, time.Now()); due {
		s.markRelay(gen, key, counter)
	}
}

// markRelay sends key a mark of counter, if key is someone this session
// marks: the server it confirmed, or a peer that has spoken the window. A
// peer of an older build is never handed a frame it cannot read.
func (s *Session) markRelay(gen *generation, key string, counter uint64) {
	if !gen.marks(key) {
		return
	}
	gen.noteMark(key, counter, time.Now())
	s.sendWindowFrame(gen, key, windowMark, counter)
}

// sendWindowFrame puts a mark or an echo on the reliable lane, to the one
// participant it is for. It waits for nothing - not the lane's mark, not the
// window a mark exists to move - and what cannot go is lost: a lost mark
// costs a probe, a lost echo is covered by the next one.
func (s *Session) sendWindowFrame(gen *generation, to string, kind byte, counter uint64) {
	dc := gen.pubRel.Load()
	if dc == nil || dc.ReadyState() != webrtc.DataChannelStateOpen {
		return
	}
	frame := windowFrame{kind: kind, counter: counter}.encode()
	packet, err := proto.Marshal(dataPacket(frame, windowTopic, []string{to}, true))
	if err != nil {
		return
	}
	_ = dc.Send(packet)
}

// handleWindowFrame takes a mark or an echo off the receive path. It acts
// only on a sender the SFU names by identity - the only name a window is
// kept under - that has not left the room, and only on a frame it can read;
// whatever it does, the frame is not the tunnel's and goes no further. A
// participant that has left can still be heard from, the last of its frames
// on a slow leg, but its window has gone with it: nothing arms it again and
// no echo is sent to someone who is not there.
//
// The first frame from a peer arms the window toward it. A mark is answered
// with an echo of its count: packets arrive in order on this channel and each
// is handed up before the next, so by now every frame the peer sent before
// the mark has been. An echo moves the window toward its sender, and only
// that one: relaywin takes it only past the last echo and within what was
// sent.
func (s *Session) handleWindowFrame(gen *generation, from string, byIdentity bool, payload []byte) {
	if !byIdentity || gen.hasLeft(from) {
		return
	}
	frame, ok := parseWindowFrame(payload)
	if !ok {
		return
	}
	if gen.noteSpeaker(from) {
		gen.win.Arm(from)
	}
	switch frame.kind {
	case windowMark:
		s.sendWindowFrame(gen, from, windowEcho, frame.counter)
	case windowEcho:
		now := time.Now()
		if moved, turnedOn := gen.win.ApplyEchoAt(from, frame.counter, now); moved {
			gen.noteEcho(from, frame.counter, turnedOn, now)
		}
	}
}

// RetirePeer is the server's word that its session on peerID has ended
// (engine.PeerRetirer). A send held toward the peer is refused. The window's
// counts are kept: the old session's bytes are still queued at the SFU, and
// the peer, still in the room, may start a fresh session behind them.
func (s *Session) RetirePeer(peerID string) {
	if gen := s.current(); gen != nil {
		gen.win.Bump(peerID)
	}
}

// forgetRelayPeer ends the window toward identity and everything this attempt
// knew of it: the participant left, or a client's binding to it was dropped
// or moved. A send held on the window is refused, and a window kept for
// identity later starts over, off until it speaks the window again.
func (g *generation) forgetRelayPeer(identity string) {
	g.win.Reset(identity)
	g.relayMu.Lock()
	defer g.relayMu.Unlock()
	delete(g.relayPeers, identity)
}

// relayPeer is what the window's wire knows of one peer: whether it speaks
// the window, and what the debug log reports of it. The log names a peer by
// the order this attempt met it in, never by its identity.
type relayPeer struct {
	ordinal int
	speaks  bool
	// marks is the send time of each mark still in flight, oldest first,
	// which an echo is timed against.
	marks []markStamp
	rtt   time.Duration
	// since, echoed and delivered measure the delivery rate: the bytes
	// echoed since since, counted from echoed.
	since     time.Time
	echoed    uint64
	delivered uint64
}

type markStamp struct {
	counter uint64
	at      time.Time
}

// relayPeerLocked is identity's entry, made on first use. Called with
// relayMu held.
func (g *generation) relayPeerLocked(identity string) *relayPeer {
	peer := g.relayPeers[identity]
	if peer == nil {
		g.relaySeq++
		peer = &relayPeer{ordinal: g.relaySeq}
		g.relayPeers[identity] = peer
	}
	return peer
}

// noteSpeaker records that identity has sent a window frame, and reports
// whether this is the first.
func (g *generation) noteSpeaker(identity string) bool {
	g.relayMu.Lock()
	defer g.relayMu.Unlock()
	peer := g.relayPeerLocked(identity)
	if peer.speaks {
		return false
	}
	peer.speaks = true
	logger.Debugf("salutejazz: peer %d speaks the relay window", peer.ordinal)
	return true
}

// marks reports whether this session marks key: the server it confirmed, or
// a peer that has spoken the window.
func (g *generation) marks(key string) bool {
	if key == loadString(&g.confirmed) {
		return true
	}
	g.relayMu.Lock()
	defer g.relayMu.Unlock()
	peer := g.relayPeers[key]
	return peer != nil && peer.speaks
}

// noteMark keeps the send time of a mark to key, for the echo that answers
// it. Only so many are kept: a destination that never echoes does not grow
// the list.
func (g *generation) noteMark(key string, counter uint64, now time.Time) {
	g.relayMu.Lock()
	defer g.relayMu.Unlock()
	peer := g.relayPeerLocked(key)
	if len(peer.marks) >= windowMarksKept {
		peer.marks = peer.marks[1:]
	}
	peer.marks = append(peer.marks, markStamp{counter: counter, at: now})
}

// noteEcho takes an echo that moved key's window to counter into what the
// debug log reports: the round trip of the mark it answers and, every
// windowReportEvery, the rate the destination has taken bytes off the relay
// at and the size the window has taken for it. That rate is the leg's, which
// is what a red gate cell is judged by.
func (g *generation) noteEcho(key string, counter uint64, turnedOn bool, now time.Time) {
	size := g.win.Size(key)
	g.relayMu.Lock()
	defer g.relayMu.Unlock()
	peer := g.relayPeerLocked(key)
	if turnedOn {
		logger.Debugf("salutejazz: peer %d echoes marks - relay window on", peer.ordinal)
	}
	answered := 0
	for answered < len(peer.marks) && peer.marks[answered].counter <= counter {
		if peer.marks[answered].counter == counter {
			peer.rtt = now.Sub(peer.marks[answered].at)
		}
		answered++
	}
	peer.marks = peer.marks[answered:]
	if peer.since.IsZero() {
		peer.since, peer.echoed = now, counter
		return
	}
	peer.delivered += counter - peer.echoed
	peer.echoed = counter
	if elapsed := now.Sub(peer.since); elapsed >= windowReportEvery {
		logger.Debugf("salutejazz: relay window to peer %d: %.1f kB/s delivered, echo rtt %s, window %d KiB",
			peer.ordinal, float64(peer.delivered)/elapsed.Seconds()/1000, peer.rtt.Round(time.Millisecond), size>>10)
		peer.since, peer.delivered = now, 0
	}
}
