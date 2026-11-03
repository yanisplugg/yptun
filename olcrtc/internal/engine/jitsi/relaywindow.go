package jitsi

import (
	"bytes"
	"encoding/binary"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/logger"
)

// ai-generated: the whole file (an end-to-end window over the JVB relay,
// olcrtc#15).
//
// The JVB relays an EndpointMessage from one endpoint to another without ever
// pushing back on the sender: it reads the sender's association as fast as
// frames arrive (its advertised window stays at 4.5 MB) and queues them for
// the receiver in its SCTP send buffer toward that receiver, about 2 MB
// (dcsctp's default). A message that does not fit is dropped. Measured on a
// live bridge: a receiver that stopped reading for 8 s lost 224 consecutive
// 12 KiB frames once ~2 MB were queued for it, while the sender's own backlog
// never rose above its high-water mark. A dropped message is a lost smux
// frame, and nothing above repairs one: the stream it belonged to ends short.
//
// Nothing above bounded that queue either. bridgeBacklogHighWater bounds what
// waits in the sender's own association, and smux's windows are per stream: a
// phone advertises 512 KiB a stream, so six downloads may have 3 MiB in
// flight, 4 MB of base64 at the JVB. When the receiver's leg slows (loss on
// its downlink, a retransmission timeout) the excess over 2 MB is dropped,
// frames of every stream at once. That is how six parallel pulls through a
// GitHub runner all ended short while one pull never did.
//
// So a sender bounds what it has handed the bridge for one destination and
// the destination has not yet taken off the relay: relayWindow bytes. It
// follows its frames with a mark that carries how many bytes it has sent to
// that destination, and the destination echoes the mark as soon as its
// receive loop reaches it. The channel is ordered end to end, so an echo
// means every frame before the mark has left the relay and been handed up;
// what is in flight is what was sent less the last mark echoed. The count is
// the sender's own, so a frame lost anyway cannot skew it: the next echo
// covers it.
//
// Marks and echoes travel under their own magic, which a build without the
// window drops unread. The window therefore only holds a sender back once an
// echo has come from that destination: an older peer is sent to as before.
//
// An echo moves a window only when it can answer one of that window's marks:
// it comes from the endpoint the window is kept for, under the epoch its
// frames go to, with a count past the last echo and within what was sent.
// Anyone in the room can learn both epochs, but not send under another
// endpoint's name: the JVB fills in the sender. Counts never start over: a
// window opened after a reset or a reap starts at the session's count, past
// every mark sent before, so a late echo of one is out of its range.

const (
	// relayWindow is 512 KiB of frames, ~700 KB of EndpointMessage JSON at
	// the JVB (base64 and the wrapper), well under the ~2 MB it holds for
	// one receiver. It still covers the ~5.5 Mbit/s a relay carries over a
	// round trip of more than a second, so on a path that works it costs no
	// throughput.
	//
	// It was 1 MiB, chosen against the bridge's own buffer alone. What that
	// left out is the path: with bridgeBacklogHighWater on top, up to ~1.5 MB
	// of payload could be outstanding, which is a fraction of a second on a
	// clean leg and seconds of queue on a lossy one - and seconds of queue is
	// where SCTP's retransmission backoff takes over from anything we decide
	// (#15: 115k T3-rtx and a control round trip of 1.3-2.5 s from a GitHub
	// runner, where the same cell passes from a datacentre).
	relayWindow = 512 << 10
	// relayMarkEvery keeps the sender's view within an eighth of the window
	// of the truth, for one ~100-byte message a mark.
	relayMarkEvery = relayWindow / 8
	// relayProbeAfter: a sender held this long since its last mark marks
	// again, which is what repairs a lost mark or echo. Until a destination
	// has echoed, its frames are marked at least this often too, so a peer
	// that speaks the window turns it on within a second of traffic.
	relayProbeAfter = time.Second
	// relayDeadAfter: a destination that has held the sender this long
	// without echoing is gone (liveness tears a session down in 15 s), and
	// its window is turned off so what waits for it drains as it used to.
	relayDeadAfter = 30 * time.Second
	// relayRetry is how often a sender its window holds looks again, for
	// probes and the dead check; an echo or a reset wakes it at once anyway.
	relayRetry = relayProbeAfter / 4

	windowMark byte = 1
	windowEcho byte = 2
	// windowFrameLen: magic, kind, sender epoch, receiver epoch, counter.
	windowFrameLen = 4 + 1 + 4 + 4 + 8
)

var bridgeWindowMagic = [4]byte{'O', 'L', 'W', '1'} //nolint:gochecknoglobals // wire protocol constant

// windowFrame is a mark or an echo. The epochs route it like a data frame;
// counter is the sender's byte count at the mark, which an echo returns.
type windowFrame struct {
	kind          byte
	senderEpoch   uint32
	receiverEpoch uint32
	counter       uint64
}

func (f windowFrame) encode() []byte {
	b := make([]byte, windowFrameLen)
	copy(b, bridgeWindowMagic[:])
	b[4] = f.kind
	binary.BigEndian.PutUint32(b[5:9], f.senderEpoch)
	binary.BigEndian.PutUint32(b[9:13], f.receiverEpoch)
	binary.BigEndian.PutUint64(b[13:], f.counter)
	return b
}

func parseWindowFrame(payload []byte) (windowFrame, bool) {
	if len(payload) != windowFrameLen || !bytes.Equal(payload[:4], bridgeWindowMagic[:]) {
		return windowFrame{}, false
	}
	return windowFrame{
		kind:          payload[4],
		senderEpoch:   binary.BigEndian.Uint32(payload[5:9]),
		receiverEpoch: binary.BigEndian.Uint32(payload[9:13]),
		counter:       binary.BigEndian.Uint64(payload[13:]),
	}, true
}

// relayState is the window toward one destination.
type relayState struct {
	sent      uint64    // bytes handed to the bridge for the destination
	marked    uint64    // sent, as of the last mark
	echoed    uint64    // the highest mark the destination has echoed
	active    bool      // an echo came: the destination speaks the window
	markedAt  time.Time // when the last mark went out
	heldSince time.Time // when the window filled, reset by every echo
}

// relayTiming shortens the window's timers in tests; its zero fields take
// the constants.
type relayTiming struct {
	probe time.Duration
	dead  time.Duration
	retry time.Duration
}

func (t relayTiming) probeAfter() time.Duration {
	if t.probe > 0 {
		return t.probe
	}
	return relayProbeAfter
}

func (t relayTiming) deadAfter() time.Duration {
	if t.dead > 0 {
		return t.dead
	}
	return relayDeadAfter
}

func (t relayTiming) retryAfter() time.Duration {
	if t.retry > 0 {
		return t.retry
	}
	return relayRetry
}

// relayKey names the window a frame to peerID counts against: that peer's
// in peer mode, the one peer's otherwise.
func (s *Session) relayKey(peerID string) string {
	if s.onPeerData == nil {
		return ""
	}
	return peerID
}

// relayRoom reports whether a frame to peerID may go now. A held window that
// is due a probe hands back the count to mark, with probe true.
func (s *Session) relayRoom(peerID string, now time.Time) (bool, uint64, bool) {
	s.relayMu.Lock()
	defer s.relayMu.Unlock()
	st := s.relayWin[s.relayKey(peerID)]
	if st == nil || !st.active || st.sent-st.echoed < relayWindow {
		if st != nil {
			st.heldSince = time.Time{}
		}
		return true, 0, false
	}
	if st.heldSince.IsZero() {
		st.heldSince = now
	}
	if now.Sub(st.heldSince) >= s.relayTiming.deadAfter() {
		st.active = false
		logger.Infof("jitsi bridge: no echo from %q for %s with %d bytes in flight - window off",
			peerID, now.Sub(st.heldSince).Round(time.Second), st.sent-st.echoed)
		return true, 0, false
	}
	if now.Sub(st.markedAt) >= s.relayTiming.probeAfter() {
		st.marked, st.markedAt = st.sent, now
		return false, st.sent, true
	}
	return false, 0, false
}

// relayReady is relayRoom that sends the probe it asks for.
func (s *Session) relayReady(peerID string) bool {
	ok, counter, probe := s.relayRoom(peerID, time.Now())
	if probe {
		s.sendWindowFrame(peerID, windowMark, s.peerEpochFor(peerID), counter)
	}
	return ok
}

// waitRelayRoom holds the sender while the window toward peerID is full. It
// looks again when an echo or a reset wakes it, and once a retry interval
// otherwise, for the probe and the dead check. Returns false when the session
// closes or a reconnect makes the frame stale meanwhile.
func (s *Session) waitRelayRoom(peerID string, frame []byte) bool {
	for !s.relayReady(peerID) {
		select {
		case <-s.done:
			return false
		case <-s.relayWake:
		case <-time.After(s.relayTiming.retryAfter()):
		}
		if !s.outboundFrameCurrent(frame) {
			return false
		}
	}
	return true
}

// relaySent counts a frame that went on the bridge toward peerID and marks
// the window when a mark is due: every relayMarkEvery bytes, and once a probe
// interval while the destination has not echoed yet.
func (s *Session) relaySent(peerID string, n int) {
	now := time.Now()
	s.relayMu.Lock()
	key := s.relayKey(peerID)
	st := s.relayWin[key]
	if st == nil {
		// A new window starts at the session's count, which no mark sent
		// before it is past: no echo of one can move this window.
		st = &relayState{sent: s.relayCount, marked: s.relayCount, echoed: s.relayCount}
		s.relayWin[key] = st
	}
	add := uint64(n) //nolint:gosec // n is a frame length
	st.sent += add
	s.relayCount += add
	due := st.sent-st.marked >= relayMarkEvery || (!st.active && now.Sub(st.markedAt) >= s.relayTiming.probeAfter())
	if due {
		st.marked, st.markedAt = st.sent, now
	}
	counter := st.sent
	s.relayMu.Unlock()
	if due {
		s.sendWindowFrame(peerID, windowMark, s.peerEpochFor(peerID), counter)
	}
}

// sendWindowFrame puts a mark or an echo on the bridge without waiting: the
// receive loop sends echoes, and a lost one costs a probe, not a stall. A
// mark to a peer whose epoch is not known yet is not sent at all: only a mark
// addressed to its epoch is echoed.
func (s *Session) sendWindowFrame(to string, kind byte, receiverEpoch uint32, counter uint64) {
	if receiverEpoch == 0 {
		return
	}
	frame := windowFrame{kind: kind, senderEpoch: s.localEpoch.Load(), receiverEpoch: receiverEpoch, counter: counter}
	var err error
	if s.sendHook != nil {
		err = s.sendHook(to, frame.encode())
	} else if jSess := s.jSess.Load(); jSess != nil {
		err = trySendEndpointRaw(jSess, to, frame.encode())
	}
	if err != nil && !s.closed.Load() {
		logger.Debugf("jitsi bridge window frame: %v", err)
	}
}

// handleWindowFrame takes a mark or an echo off the receive path and reports
// whether payload was one, so the caller does not read it as data. Only what
// is addressed to this session's epoch counts.
func (s *Session) handleWindowFrame(from string, payload []byte) bool {
	f, ok := parseWindowFrame(payload)
	if !ok {
		return false
	}
	local := s.localEpoch.Load()
	if f.receiverEpoch != local || f.senderEpoch == 0 || f.senderEpoch == local {
		return true
	}
	switch f.kind {
	case windowMark:
		// Every frame the peer sent before the mark has been handed up by
		// now: this loop delivers in order and onData returns first.
		s.sendWindowFrame(from, windowEcho, f.senderEpoch, f.counter)
	case windowEcho:
		s.applyEcho(from, f)
	}
	return true
}

// applyEcho moves the window toward from on and wakes a sender it held. An
// echo counts only from the endpoint and the epoch this session sends that
// peer's frames to, and only for a count past the last echo and within what
// was sent: one from before a reset or a reap, or behind a later echo, moves
// nothing.
func (s *Session) applyEcho(from string, f windowFrame) {
	if f.senderEpoch != s.echoerEpoch(from) {
		return
	}
	s.relayMu.Lock()
	st := s.relayWin[s.relayKey(from)]
	moved := st != nil && f.counter > st.echoed && f.counter <= st.sent
	turnedOn := false
	if moved {
		turnedOn, st.active = !st.active, true
		st.echoed = f.counter
		st.heldSince = time.Time{}
	}
	s.relayMu.Unlock()
	if !moved {
		return
	}
	if turnedOn {
		logger.Debugf("jitsi bridge: %q echoes marks - relay window on", from)
	}
	s.wakeRelaySenders()
}

// echoerEpoch is the epoch an echo from endpoint from must carry: the one
// that peer's frames come under in peer mode. Otherwise it is the confirmed
// peer's, and only from the endpoint latched for that peer: an echo from
// anyone else carries no epoch that counts.
func (s *Session) echoerEpoch(from string) uint32 {
	if s.onPeerData == nil {
		if ep := s.peerEndpoint.Load(); ep == nil || *ep != from {
			return 0
		}
		return s.peerEpoch.Load()
	}
	s.peerEpochMu.Lock()
	defer s.peerEpochMu.Unlock()
	return s.peerEpochs[from]
}

// wakeRelaySenders wakes whatever a window may be holding: sendLoop for the
// peer queues, and a sender waiting in waitRelayRoom.
func (s *Session) wakeRelaySenders() {
	s.wakePeerSender()
	select {
	case s.relayWake <- struct{}{}:
	default:
	}
}

// resetRelayWindows forgets every window: this session or its peer starts
// over, and a count from before means nothing to the frames after. The
// session's count goes on, see relaySent. A sender held by a window that is
// gone now is woken.
func (s *Session) resetRelayWindows() {
	s.relayMu.Lock()
	clear(s.relayWin)
	s.relayMu.Unlock()
	s.wakeRelaySenders()
}
