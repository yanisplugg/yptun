package jitsi

import (
	"bytes"
	"context"
	"encoding/base64"
	"fmt"
	"time"

	"github.com/zarazaex69/j"

	"github.com/openlibrecommunity/olcrtc/internal/logger"
)

const (
	// bridgeMaxMessageSize stays below JVB's practical 16 KiB websocket limit.
	bridgeMaxMessageSize = 16 * 1024
	bridgeOpenTimeout    = 30 * time.Second
	// sendLoop must not wait through a full reconnect because it is the only
	// consumer of both bounded queues. Old-epoch frames are stale anyway.
	jSessionWaitTimeout = 2 * time.Second

	// colibriClassEndpointMessage is the JVB bridge-channel message class
	// used for opaque raw payloads (see endpointMessage and decodeRaw).
	colibriClassEndpointMessage = "EndpointMessage"

	// bridgeBacklogHighWater bounds what may pile up below sendLoop on its
	// way out: pion's SCTP association when the bridge is the JVB's data
	// channel, the j library's 1024-message queue when it is the colibri
	// websocket. Neither pushes back on its own - DataChannel.SendText never
	// blocks, and the websocket queue is 16 MB deep - so the bounded queues
	// above this point were bounding nothing: the sender's memory was the
	// only limit. Measured on a ~5 Mbit/s SCTP bridge, six parallel
	// downloads put 85 MB into the server process in eight seconds, two
	// minutes of queue that every later frame - connect acks, pongs - sat
	// behind; each connect failed at its deadline and liveness tore the
	// session down (olcbox#23). The gauge counts bytes in flight as well as
	// bytes pending, and in flight is a congestion window's worth, so the
	// mark has to sit above what a fast path keeps in the air or it would
	// cap throughput: 512 KB, the same figure the goolom engine uses. That
	// is thirty-two full messages, under a second at 5 Mbit/s, and many poll
	// intervals at any rate a relay carries.
	// 512 KB until #15: see relayWindow, which this sits on top of.
	bridgeBacklogHighWater = 256 * 1024
	bridgeBacklogPoll      = 5 * time.Millisecond
)

var bridgeMagic = [4]byte{'O', 'L', 'R', '1'} //nolint:gochecknoglobals // wire protocol constant

func (s *Session) openBridgeWS(ctx context.Context, jSess *j.Session) error {
	return s.openBridge(ctx, jSess, "", "colibri-ws", jSess.OpenBridge)
}

func (s *Session) openBridgeSCTP(ctx context.Context, jSess *j.Session) error {
	return s.openBridge(ctx, jSess, " sctp", "sctp", jSess.WaitBridgeSCTP)
}

func (s *Session) openBridge(
	ctx context.Context,
	jSess *j.Session,
	errorSuffix string,
	transport string,
	open func(context.Context) error,
) error {
	bctx, bcancel := context.WithTimeout(ctx, bridgeOpenTimeout)
	err := open(bctx)
	bcancel()
	if err != nil {
		return fmt.Errorf("open bridge%s: %w", errorSuffix, err)
	}
	s.peerEndpoint.Store(nil)
	s.peerVideoSSRC.Store(0)
	s.markBridgeReady()
	s.requestPeerVideo(ctx, jSess)
	logger.Infof("jitsi: bridge open %s (endpoints=%v)", transport, endpointList(jSess))
	return nil
}

// endpointList is the room's other occupants, for the log, and nothing at
// all for a session with no connection behind it.
//
// ai-generated: this helper.
func endpointList(jSess *j.Session) []string {
	if jSess == nil || jSess.Conn == nil {
		return nil
	}
	return jSess.Endpoints()
}

// videoMaxHeight is the height the receiver constraints ask for. The
// transports carry bytes, not pictures, so any layer will do; what matters
// is that a constraint is there at all.
const videoMaxHeight = 720

// requestPeerVideo establishes the receiver constraints on the bridge that
// was just opened. JVB forwards a participant's video only to an endpoint
// that asked for it, and the ask goes over the bridge, so every bridge needs
// it: the first one and the one a rejoin opens. Without it a rejoin came up
// with a live conference, a live bridge and no video at all, and the tunnel
// never carried a byte again (#9).
//
// ai-generated: this method and its call in openBridge.
func (s *Session) requestPeerVideo(ctx context.Context, jSess *j.Session) {
	if !s.shouldRequestVideo() {
		return
	}
	ask := s.askVideo
	if ask == nil {
		ask = func(ctx context.Context, sess *j.Session) error {
			return sess.RequestVideo(ctx, videoMaxHeight)
		}
	}
	if err := ask(ctx, jSess); err != nil {
		logger.Debugf("jitsi: request video: %v", err)
	}
}

// peerLatchFresh is how long the latched endpoint is trusted as a
// destination after the last frame from it. The control plane speaks every
// control.DefaultInterval (10 s), so three intervals of silence means
// something is wrong with the peer, not with the clock - and the old
// behaviour, a frame the bridge hands to the whole room, is the safe thing
// to fall back to while the session sorts itself out.
const peerLatchFresh = 30 * time.Second

// broadcastTarget is where a frame that belongs to no peer queue goes.
//
// On the server it is the room: the server has many clients and its
// broadcasts are for all of them. On a client it is the one endpoint it has
// latched - the server's - because the bridge hands a frame addressed to ""
// to every endpoint in the room, so each client's upload also lands in every
// other client's receive queue on the bridge, where it is dropped after
// taking up room in a buffer of about 2 MB (#25). The server never latches
// an endpoint (it takes the peer path instead), so it is unaffected.
//
// ai-generated: the whole function.
func (s *Session) broadcastTarget() string {
	ep := s.peerEndpoint.Load()
	if ep == nil {
		return ""
	}
	if seen := s.peerEndpointSeen.Load(); seen == 0 ||
		time.Since(time.Unix(0, seen)) > peerLatchFresh {
		return ""
	}
	return *ep
}

// Send queues a broadcast bridge frame, waiting for room in the queue.
func (s *Session) Send(data []byte) error {
	if s.closed.Load() {
		return ErrSessionClosed
	}
	if !s.bridgeReady.Load() {
		return ErrBridgeNotReady
	}
	framed, err := s.encodeBridgeFrame(data, "")
	if err != nil {
		return err
	}
	return s.enqueueBridgeFrame(framed)
}

// SendTo queues a bridge frame for a specific Jitsi endpoint.
func (s *Session) SendTo(peerID string, data []byte) error {
	if peerID == "" {
		return s.Send(data)
	}
	if s.closed.Load() {
		return ErrSessionClosed
	}
	if !s.bridgeReady.Load() {
		return ErrBridgeNotReady
	}
	framed, err := s.encodeBridgeFrame(data, peerID)
	if err != nil {
		return err
	}
	return s.enqueuePeerBridgeFrame(peerID, framed)
}

// enqueueBridgeFrame queues a broadcast frame. A full queue is back-pressure
// for smux, not a failure: the writer slows down, the session stays up, and
// a control frame behind it waits at most one queue's worth.
func (s *Session) enqueueBridgeFrame(framed []byte) error {
	if len(framed) > bridgeMaxMessageSize {
		return ErrSendTooLarge
	}
	select {
	case s.sendQueue <- framed:
		return nil
	case <-s.done:
		return ErrSessionClosed
	}
}

// enqueuePeerBridgeFrame queues a frame on the peer's own queue and wakes
// the sender.
func (s *Session) enqueuePeerBridgeFrame(peerID string, framed []byte) error {
	if len(framed) > bridgeMaxMessageSize {
		return ErrSendTooLarge
	}
	pq := s.peerQueueFor(peerID)
	defer s.releasePeerQueue(pq)
	select {
	case pq.ch <- framed:
		s.wakePeerSender()
		return nil
	case <-s.done:
		return ErrSessionClosed
	}
}

// peerQueueFor returns the peer's queue with a reference held; the caller
// releases it once its frame is in (or the session is gone).
func (s *Session) peerQueueFor(peerID string) *peerQueue {
	s.peerQueueMu.Lock()
	defer s.peerQueueMu.Unlock()
	pq := s.peerQueues[peerID]
	if pq == nil {
		pq = &peerQueue{ch: make(chan []byte, defaultSendQueueSize)}
		s.peerQueues[peerID] = pq
	}
	pq.refs++
	pq.lastUsed = time.Now()
	return pq
}

func (s *Session) releasePeerQueue(pq *peerQueue) {
	s.peerQueueMu.Lock()
	pq.refs--
	s.peerQueueMu.Unlock()
}

func (s *Session) wakePeerSender() {
	select {
	case s.peerWake <- struct{}{}:
	default:
	}
}

func (s *Session) sendLoop() {
	// ai-generated: the retry timer, for peers the relay window holds.
	var retry <-chan time.Time
	for {
		select {
		case <-s.done:
			return
		case data, ok := <-s.sendQueue:
			if !ok {
				return
			}
			s.sendBridgeFrame(s.broadcastTarget(), data)
		case <-s.peerWake:
			retry = s.drainPeerQueuesRetry()
		case <-retry:
			retry = s.drainPeerQueuesRetry()
		}
	}
}

// drainPeerQueuesRetry drains the peer queues and, when the relay window held
// some back, says when to look again: probes and the dead check run on this
// side, so a held peer needs a visit even when nothing wakes the loop.
//
// ai-generated: added for the relay window (olcrtc#15).
func (s *Session) drainPeerQueuesRetry() <-chan time.Time {
	if !s.drainPeerQueues() {
		return nil
	}
	return time.After(s.relayTiming.retryAfter())
}

// drainPeerQueues sends one frame per peer per pass, round-robin, until
// every peer queue is empty or held by its relay window, serving the
// broadcast queue between peers so a busy room does not starve it. A peer
// the window holds keeps its frames and is skipped, so one stalled receiver
// does not hold the others. Queues nobody has touched for peerQueueIdle and
// nobody holds are dropped at the end. Reports whether a window held any.
func (s *Session) drainPeerQueues() bool {
	for {
		s.peerQueueMu.Lock()
		peers := make([]string, 0, len(s.peerQueues))
		queues := make([]*peerQueue, 0, len(s.peerQueues))
		for id, pq := range s.peerQueues {
			peers = append(peers, id)
			queues = append(queues, pq)
		}
		s.peerQueueMu.Unlock()

		progressed, held := false, false
		for i, pq := range queues {
			select {
			case <-s.done:
				return false
			default:
			}
			// ai-generated: the relay window check (olcrtc#15).
			if len(pq.ch) > 0 && !s.relayReady(peers[i]) {
				held = true
			} else {
				select {
				case data := <-pq.ch:
					s.sendBridgeFrame(peers[i], data)
					progressed = true
				default:
				}
			}
			select {
			case data := <-s.sendQueue:
				s.sendBridgeFrame(s.broadcastTarget(), data)
			default:
			}
		}
		if !progressed {
			s.reapPeerQueues()
			return held
		}
	}
}

func (s *Session) reapPeerQueues() {
	cutoff := time.Now().Add(-peerQueueIdle)
	s.peerQueueMu.Lock()
	defer s.peerQueueMu.Unlock()
	for id, pq := range s.peerQueues {
		if pq.refs == 0 && len(pq.ch) == 0 && pq.lastUsed.Before(cutoff) {
			delete(s.peerQueues, id)
			// ai-generated: the peer's relay window goes with its queue.
			s.relayMu.Lock()
			delete(s.relayWin, id)
			s.relayMu.Unlock()
		}
	}
}

func (s *Session) sendBridgeFrame(to string, data []byte) {
	if !s.outboundFrameCurrent(data) {
		return
	}
	send := s.bridgeSender()
	if send == nil {
		return
	}
	// ai-generated: the relay window wait and count (olcrtc#15). Only a
	// single-peer session waits for its window here. drainPeerQueues let a
	// peer's frame through already; an echo that has turned the window on
	// since must not hold the one loop that serves every other peer. A
	// broadcast in peer mode has no window to wait for: an echo moves only
	// the window of the endpoint it came from.
	if !s.waitBridgeRoom() || (s.onPeerData == nil && !s.waitRelayRoom(to, data)) || !s.outboundFrameCurrent(data) {
		return
	}
	if err := send(to, data); err != nil {
		if s.closed.Load() {
			return
		}
		logger.Debugf("jitsi bridge send: %v", err)
		return
	}
	s.relaySent(to, len(data))
}

// bridgeSender returns what puts a frame on the bridge: the live session's,
// once there is one (waiting for a reconnect as sendLoop may), or the test
// hook.
//
// ai-generated: the seam the relay tests drive two sessions through.
func (s *Session) bridgeSender() func(to string, frame []byte) error {
	if s.sendHook != nil {
		return s.sendHook
	}
	jSess := s.waitJSession()
	if jSess == nil {
		return nil
	}
	return func(to string, frame []byte) error { return sendEndpointRaw(jSess, to, frame) }
}

// bridgeBacklog reports the bytes queued below sendLoop: the SCTP
// association's unsent data plus whatever the websocket bridge still holds.
// Both gauges read zero before the bridge exists, which is the right answer -
// there is nothing to wait behind yet.
func (s *Session) bridgeBacklog() int {
	backlog := 0
	s.pcMu.Lock()
	pc := s.pc
	s.pcMu.Unlock()
	if pc != nil {
		if sctp := pc.SCTP(); sctp != nil {
			backlog += sctp.BufferedAmount()
		}
	}
	if jSess := s.jSess.Load(); jSess != nil {
		backlog += jSess.BridgeSendQueueDepth() * bridgeMaxMessageSize
	}
	return backlog
}

// waitBridgeRoom holds the sender until the backlog below it is under the
// high-water mark, so that back-pressure reaches smux instead of memory.
// Returns false when the session closes meanwhile. A link that never drains
// is not this function's concern: the queues above fill, smux writes stall,
// and liveness tears the session down, which is what unblocks this loop.
func (s *Session) waitBridgeRoom() bool {
	gauge := s.backlogGauge
	if gauge == nil {
		gauge = s.bridgeBacklog
	}
	var held time.Time
	peak := 0
	for {
		backlog := gauge()
		if backlog <= bridgeBacklogHighWater {
			if !held.IsZero() && time.Since(held) >= time.Second {
				logger.Debugf("jitsi bridge: sender held %v behind a %d-byte backlog",
					time.Since(held).Round(time.Millisecond), peak)
			}
			return true
		}
		if held.IsZero() {
			held = time.Now()
		}
		peak = max(peak, backlog)
		select {
		case <-s.done:
			return false
		case <-time.After(bridgeBacklogPoll):
		}
	}
}

// endpointMessage mirrors the wire shape of a Jitsi Videobridge EndpointMessage.
// Field order matters: some Jackson versions on the bridge side drop the
// payload field entirely if it appears before "to" (see
// https://github.com/jitsi/jitsi-videobridge/pull/2424), so this is declared
// and marshalled as a struct (not a map) to force colibriClass, to,
// msgPayload in that exact order regardless of Go's map-key sorting.
type endpointMessage struct {
	ColibriClass string             `json:"colibriClass"` //nolint:tagliatelle // JVB wire protocol uses camelCase
	To           string             `json:"to"`           //nolint:tagliatelle // JVB wire protocol uses camelCase
	MsgPayload   endpointRawPayload `json:"msgPayload"`   //nolint:tagliatelle // JVB wire protocol uses camelCase
}

type endpointRawPayload struct {
	Raw string `json:"raw"`
}

// sendEndpointRaw sends opaque bytes as msgPayload.raw instead of the
// nonstandard top-level "raw" field used by the underlying j library's
// BridgeSendRaw. Per the JVB EndpointMessage docs, the payload belongs under
// msgPayload; some bridge builds silently drop the frame otherwise. See
// olcrtc#143.
func sendEndpointRaw(jSess *j.Session, to string, data []byte) error {
	br := jSess.Bridge()
	if br == nil {
		return ErrBridgeNotReady
	}
	if err := br.SendJSON(newEndpointMessage(to, data)); err != nil {
		return fmt.Errorf("send endpoint message: %w", err)
	}
	return nil
}

// trySendEndpointRaw is sendEndpointRaw that never waits: a websocket bridge
// with a full queue drops the message instead.
//
// ai-generated: added for the relay window's marks and echoes (olcrtc#15).
func trySendEndpointRaw(jSess *j.Session, to string, data []byte) error {
	br := jSess.Bridge()
	if br == nil {
		return ErrBridgeNotReady
	}
	if err := br.TrySendJSON(newEndpointMessage(to, data)); err != nil {
		return fmt.Errorf("try send endpoint message: %w", err)
	}
	return nil
}

// newEndpointMessage wraps data as the JVB relays it to endpoint to.
//
// ai-generated: split out of sendEndpointRaw so that trySendEndpointRaw
// sends the same shape (olcrtc#15).
func newEndpointMessage(to string, data []byte) endpointMessage {
	return endpointMessage{
		ColibriClass: colibriClassEndpointMessage,
		To:           to,
		MsgPayload: endpointRawPayload{
			Raw: base64.StdEncoding.EncodeToString(data),
		},
	}
}

// setJSession installs a session and republishes the readiness signal used by
// sendLoop. Passing nil rearms the signal for the next reconnect.
func (s *Session) setJSession(sess *j.Session) *j.Session {
	old := s.jSess.Swap(sess)

	s.jSessMu.Lock()
	defer s.jSessMu.Unlock()
	if s.jSessReady == nil {
		s.jSessReady = make(chan struct{})
	}
	if sess == nil {
		select {
		case <-s.jSessReady:
			s.jSessReady = make(chan struct{})
		default:
		}
		return old
	}
	select {
	case <-s.jSessReady:
	default:
		close(s.jSessReady)
	}
	return old
}

func (s *Session) waitJSession() *j.Session {
	if s.closed.Load() {
		return nil
	}
	if jSess := s.jSess.Load(); jSess != nil {
		return jSess
	}

	s.jSessMu.Lock()
	if s.jSessReady == nil {
		s.jSessReady = make(chan struct{})
	}
	ready := s.jSessReady
	s.jSessMu.Unlock()

	timer := time.NewTimer(jSessionWaitTimeout)
	defer timer.Stop()
	select {
	case <-ready:
		return s.jSess.Load()
	case <-s.done:
		return nil
	case <-timer.C:
		return nil
	}
}

// recvLoop consumes the bridge channel. Only one instance may run at a time:
// Connect, completeJingleSetup and finishReconnect each start one, and two
// loops racing on the same channel split frames between them and hand them to
// onData concurrently and out of order - which the record layer's replay
// window then rejects as junk. A later loop waits here until the previous one
// has seen its channel close.
func (s *Session) recvLoop() {
	s.recvMu.Lock()
	defer s.recvMu.Unlock()

	gen := s.bridgeGen.Load()
	jSess := s.jSess.Load()
	if jSess == nil || (s.onData == nil && s.onPeerData == nil) || !s.bridgeReady.Load() {
		logger.Debugf("jitsi: recvLoop early exit jSess=%v onData=%v onPeerData=%v bridgeReady=%v",
			jSess != nil, s.onData != nil, s.onPeerData != nil, s.bridgeReady.Load())
		return
	}
	msgs := jSess.BridgeMessages()
	if msgs == nil {
		logger.Debugf("jitsi: recvLoop: BridgeMessages() returned nil, exiting")
		return
	}
	logger.Debugf("jitsi: recvLoop started")
	for {
		select {
		case <-s.done:
			return
		case msg, ok := <-msgs:
			if !s.deliverBridgeMessageGen(gen, msg, ok) {
				return
			}
		}
	}
}

func (s *Session) deliverBridgeMessage(msg j.BridgeMessage, ok bool) bool {
	return s.deliverBridgeMessageGen(s.bridgeGen.Load(), msg, ok)
}

func (s *Session) deliverBridgeMessageGen(gen uint64, msg j.BridgeMessage, ok bool) bool {
	if !ok {
		if !s.closed.Load() {
			s.requestReconnectGen(gen, "jitsi bridge closed")
		}
		return false
	}
	raw := decodeRaw(msg)
	// ai-generated: marks and echoes of the relay window (olcrtc#15).
	if s.handleWindowFrame(msg.From, raw) {
		return true
	}
	payload, valid := bridgePayload(raw)
	if !valid {
		return true
	}
	if s.onPeerData != nil && msg.From != "" {
		return s.deliverPeerBridgePayload(msg.From, payload)
	}
	data, accepted := s.acceptEpochFrame(payload)
	if !accepted {
		return true
	}
	if !s.requireTargetedPeer || s.peerEpoch.Load() != 0 {
		s.latchPeerEndpoint(msg.From)
	}
	if len(data) == 0 {
		return true
	}
	s.onData(data)
	return true
}

// bridgePayload returns a decoded payload that carries the data magic.
//
// ai-generated: it takes the payload deliverBridgeMessageGen decoded once
// for the relay window's frames, not the message (olcrtc#15).
func bridgePayload(payload []byte) ([]byte, bool) {
	if payload == nil {
		return nil, false
	}
	if len(payload) < len(bridgeMagic) || !bytes.Equal(payload[:len(bridgeMagic)], bridgeMagic[:]) {
		return nil, false
	}
	return payload, true
}

func (s *Session) deliverPeerBridgePayload(from string, payload []byte) bool {
	data, ok := s.acceptPeerEpochFrame(from, payload)
	if !ok || len(data) == 0 {
		return true
	}
	s.onPeerData(from, data)
	return true
}

// decodeRaw extracts the base64 payload from an EndpointMessage. It accepts
// both the standard msgPayload.raw shape (as sent by sendEndpointRaw) and the
// legacy top-level "raw" field (as sent by older olcrtc builds, or by peers
// still on the j library's BridgeSendRaw), for backward compatibility. See
// olcrtc#143.
func decodeRaw(m j.BridgeMessage) []byte {
	if m.Class != colibriClassEndpointMessage {
		return nil
	}
	enc, ok := rawFieldFrom(m.Fields)
	if !ok {
		return nil
	}
	out, err := base64.StdEncoding.DecodeString(enc)
	if err != nil {
		return nil
	}
	return out
}

func rawFieldFrom(fields map[string]any) (string, bool) {
	if payload, ok := fields["msgPayload"].(map[string]any); ok {
		if raw, ok := payload["raw"].(string); ok {
			return raw, true
		}
	}
	raw, ok := fields["raw"].(string)
	return raw, ok
}

func (s *Session) markBridgeReady() {
	s.bridgeGen.Add(1)
	s.bridgeReady.Store(true)
}
