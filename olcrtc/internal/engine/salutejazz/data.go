package salutejazz

import (
	"context"
	"fmt"
	"time"

	"github.com/livekit/protocol/livekit"
	"github.com/pion/webrtc/v4"
	"google.golang.org/protobuf/proto"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
	"github.com/openlibrecommunity/olcrtc/internal/logger"
)

// The data plane. Both lanes carry LiveKit data packets: a payload is
// wrapped in a UserPacket, the packet is marshalled and written to a
// publisher channel, and the SFU relays it to the room stamped with this
// participant's identity.
//
// Which channel a lane uses is the finding the engine is built around: the
// SFU drops what is written to the channels it created on the subscriber
// peer connection, so this engine sends on the publisher's channels and
// receives on the subscriber's.

const (
	// datagramPublishTopic marks the lossy lane. It is the topic the other
	// engines here publish datagrams under, so a room can hold a LiveKit
	// peer and a SaluteJazz peer of the same tunnel.
	datagramPublishTopic = "olcrtc.udp"

	// peerPollInterval is how often WaitForPeer looks at the room. The
	// roster and the first frame both arrive on other goroutines.
	peerPollInterval = 50 * time.Millisecond

	// bufferHighWaterMark is how much one publisher channel may hold before
	// the lane it belongs to is over its budget. pion's DataChannel.Send
	// never blocks - it appends to an SCTP pending queue with no bound - so
	// a peer that stops reading turns a writer above into memory here until
	// the process dies. The number is goolom's, which is the one this
	// project has run behind.
	bufferHighWaterMark = 512 * 1024

	// lossyDropLogEvery keeps a lane that is dropping from writing one log
	// line per packet.
	lossyDropLogEvery = 100
)

// The capability interfaces the transport layer type-asserts on. A session
// that carries bytes for the tunnel has to satisfy every one of them: the
// datachannel transport reaches SendTo, SendDatagram and the peer binding
// only through these.
var (
	_ engine.Session             = (*Session)(nil)
	_ engine.PeerSession         = (*Session)(nil)
	_ engine.DatagramSession     = (*Session)(nil)
	_ engine.PeerDatagramSession = (*Session)(nil)
	_ engine.PeerReadySession    = (*Session)(nil)
	_ engine.PeerIdentity        = (*Session)(nil)
	_ engine.PeerResetter        = (*Session)(nil)
	_ engine.PeerRetirer         = (*Session)(nil)
)

// Send publishes one payload on the reliable lane, to the whole room - or, on
// a client whose confirmed server is still in the room, to that server.
func (s *Session) Send(data []byte) error {
	return s.publish(data, "", nil, true)
}

// SendTo publishes one payload on the reliable lane, to a single
// participant. An empty peerID addresses the room, as every engine here
// treats it, and goes where Send does.
func (s *Session) SendTo(peerID string, data []byte) error {
	return s.publish(data, "", destinations(peerID), true)
}

// SendDatagram publishes one payload on the lossy lane, to the whole room -
// or, on a client whose confirmed server is still in the room, to that
// server.
// The lane is ordered and never retransmitted - LiveKit's own _lossy
// settings, which startPublisher creates it with - so a packet that cannot go
// now is worth nothing later: a lane that is not up refuses it, and a lane
// over its budget drops it.
func (s *Session) SendDatagram(data []byte) error {
	return s.publish(data, datagramPublishTopic, nil, false)
}

// SendDatagramTo publishes one payload on the lossy lane, to a single
// participant.
func (s *Session) SendDatagramTo(peerID string, data []byte) error {
	return s.publish(data, datagramPublishTopic, destinations(peerID), false)
}

// destinations is the address list one peer id makes. LiveKit reads an empty
// list as the whole room.
func destinations(peerID string) []string {
	if peerID == "" {
		return nil
	}
	return []string{peerID}
}

// publish writes one data packet to the publisher channel of a lane.
//
// A closed session is over and says so; a generation that is gone, or has
// not negotiated this lane, is a lane that is not there right now, which a
// reconnect may still bring back. Either way nothing is written to a
// generation that has been torn down. The window between that check and the
// write closes itself: teardown closes the peer connections, and pion then
// refuses the write with an error of its own.
//
// A payload to the room from a client goes to the server the client
// confirmed, while that server is still in the room (roomDest).
//
// A lane over its high-water mark is where the two lanes part: the byte
// stream waits for room (awaitSendWindow), because a frame it drops is a
// frame the peer waits for forever, and the datagram lane drops. A datagram
// is dropped as well once its destination's relay window is on and more than
// a window and datagramSlack are in flight to it, or a reliable send to it
// has been held for a probe interval.
//
// The byte stream then waits for its destination's relay window, which the
// lane's mark cannot see: the SFU takes everything at once and queues it
// toward a slow receiver without a limit. What went out is counted against
// the window afterwards, and a mark follows it when one is due (window.go).
// A send that was held while its destination's session ended is refused with
// ErrDestinationEnded, and its frame never reaches the wire. A destination
// the room has reported gone has no window at all (windowKey).
func (s *Session) publish(payload []byte, topic string, dest []string, reliable bool) error {
	if s.closed.Load() {
		return ErrSessionClosed
	}
	gen := s.current()
	if gen == nil || gen.isDone() {
		return ErrNoDataChannel
	}
	dc := publisherChannelOf(gen, reliable)
	if dc == nil || dc.ReadyState() != webrtc.DataChannelStateOpen {
		return ErrNoDataChannel
	}
	dest = gen.roomDest(dest)
	key := gen.windowKey(dest)
	if !reliable {
		if dc.BufferedAmount() > bufferHighWaterMark || (key != "" && gen.win.Over(key, datagramSlack, time.Now())) {
			gen.dropLossy()
			return nil
		}
	} else {
		epoch := gen.relayEpoch(key)
		if err := s.awaitSendWindow(gen, dc); err != nil {
			return err
		}
		if err := s.awaitRelayWindow(gen, key, epoch); err != nil {
			return err
		}
	}
	frame, err := proto.Marshal(dataPacket(payload, topic, dest, reliable))
	if err != nil {
		return fmt.Errorf("salutejazz marshal data packet: %w", err)
	}
	if err := dc.Send(frame); err != nil {
		return fmt.Errorf("salutejazz data channel send: %w", err)
	}
	s.countRelayed(gen, key, len(frame))
	return nil
}

// awaitSendWindow holds the caller while the reliable lane is over its
// budget. pion's Send never blocks, so this is the only back-pressure the
// byte stream has: the caller waits here until the lane's low-water callback
// says the queue has drained back under the mark (wireChannel arms it).
//
// The waiter is taken before the gauge is read. A drain that lands between
// the two closes that very channel, so the wake it sends cannot be missed by
// a caller that was about to wait for it.
//
// A generation that ends while a caller is parked releases it with
// ErrSessionClosed: pion runs the low-water callback only while the channel
// is open, so for a channel that is being closed the wake never comes.
func (s *Session) awaitSendWindow(gen *generation, dc *webrtc.DataChannel) error {
	for {
		window := gen.sendWindow()
		if dc.BufferedAmount() <= bufferHighWaterMark {
			return nil
		}
		select {
		case <-window:
		case <-gen.done:
			return ErrSessionClosed
		case <-s.closeCh:
			return ErrSessionClosed
		}
	}
}

// sendWindow is the channel that is closed the next time the reliable lane
// drains back under its high-water mark.
func (g *generation) sendWindow() <-chan struct{} {
	g.windowMu.Lock()
	defer g.windowMu.Unlock()
	return g.window
}

// openSendWindow releases every sender parked on the reliable lane and arms
// the next wait. pion calls it from its own goroutine, once per crossing.
func (g *generation) openSendWindow() {
	g.windowMu.Lock()
	defer g.windowMu.Unlock()
	close(g.window)
	g.window = make(chan struct{})
}

// dropLossy records one datagram thrown away because the lossy lane, or its
// destination's relay window, was over its budget, and says so every so
// often: a lane that is dropping is worth a line in the log, one per packet
// is not.
func (g *generation) dropLossy() {
	if dropped := g.lossyDrops.Add(1); dropped%lossyDropLogEvery == 1 {
		logger.Debugf("salutejazz: the lossy lane or a relay window is over its budget, %d datagrams dropped", dropped)
	}
}

// dataPacket builds the packet the SFU relays. The service runs LiveKit
// 1.5.3, which reads the kind and the destinations off the fields that
// later versions deprecated; the current fields are filled in too, so the
// same frame reaches a newer server unchanged.
func dataPacket(payload []byte, topic string, dest []string, reliable bool) *livekit.DataPacket {
	user := &livekit.UserPacket{
		Payload:               payload,
		DestinationIdentities: dest,
	}
	if topic != "" {
		user.Topic = proto.String(topic)
	}
	kind := livekit.DataPacket_LOSSY
	if reliable {
		kind = livekit.DataPacket_RELIABLE
	}
	return &livekit.DataPacket{
		Kind:                  kind,
		DestinationIdentities: dest,
		Value:                 &livekit.DataPacket_User{User: user},
	}
}

// receiveOn attaches the receive path to one channel of the subscriber peer
// connection. Only those carry inbound traffic: what this session publishes
// goes out on the publisher's channels and never comes back on them.
func (s *Session) receiveOn(gen *generation, dc *webrtc.DataChannel) {
	dc.OnMessage(func(msg webrtc.DataChannelMessage) {
		s.handleDataPacket(gen, msg.Data)
	})
}

// handleDataPacket routes one relayed packet by topic: a window frame is the
// relay window's and goes no further, the datagram topic feeds the lossy
// lane, everything else the byte stream. A packet that arrives on a
// generation that has been torn down belongs to a connection this session has
// already replaced, and is dropped.
func (s *Session) handleDataPacket(gen *generation, frame []byte) {
	if s.closed.Load() || gen.isDone() {
		return
	}
	var packet livekit.DataPacket
	if err := proto.Unmarshal(frame, &packet); err != nil {
		logger.Debugf("salutejazz: data packet: %v", err)
		return
	}
	user := packet.GetUser()
	if user == nil {
		// Speaker updates, transcriptions, chat and the rest of what the
		// room puts on these channels. None of it is ours.
		return
	}
	sender, byIdentity := senderIdentity(&packet)
	if sender != "" && sender == gen.localIdentity() {
		// Our own payload, relayed back to us. Nothing in the room should
		// send it, but a tunnel that reads its own bytes answers its own
		// handshake, so a packet stamped with this session's identity is
		// dropped wherever it came from.
		return
	}
	if byIdentity {
		// Only an identity belongs in the roster. The sid fallback below
		// names the same participant under an id no packet can be addressed
		// to, and a roster entry under it would hand WaitForPeer and every
		// caller of the peer list a participant they cannot reach.
		gen.notePeer(sender)
	}
	switch user.GetTopic() {
	case windowTopic:
		s.handleWindowFrame(gen, sender, byIdentity, user.GetPayload())
	case datagramPublishTopic:
		s.deliver(sender, user.GetPayload(), s.onPeerDatagram, s.onDatagram)
	default:
		s.deliver(sender, user.GetPayload(), s.onPeerData, s.onData)
	}
}

// deliver hands one payload to the lane's callbacks: the per-peer one when
// the room named a sender and the upper layer routes by peer, the plain one
// otherwise. Payload bytes are never logged.
func (s *Session) deliver(sender string, payload []byte, toPeer func(string, []byte), plain func([]byte)) {
	switch {
	case toPeer != nil && sender != "":
		toPeer(sender, payload)
	case plain != nil:
		plain(payload)
	}
}

// senderIdentity is who sent one data packet, and whether the room named
// them by identity. LiveKit 1.5.3, which the service runs, stamps the user
// packet and falls back to the sid for a participant it has no identity for;
// later versions stamp the packet around it instead. The sid names the same
// participant, so it is reported as the sender, but it is not an identity
// and the roster does not take it.
func senderIdentity(packet *livekit.DataPacket) (string, bool) {
	user := packet.GetUser()
	if identity := user.GetParticipantIdentity(); identity != "" { //nolint:staticcheck // 1.5.3 wire
		return identity, true
	}
	if sid := user.GetParticipantSid(); sid != "" { //nolint:staticcheck // 1.5.3 wire
		return sid, false
	}
	identity := packet.GetParticipantIdentity()
	return identity, identity != ""
}

// CanSend reports whether the reliable lane is open and under its budget. A
// lane over the mark says no, because a Send issued there would park until it
// drains, and the layer above would rather hold its bytes than hand them to a
// queue that is not moving.
func (s *Session) CanSend() bool {
	return !s.closed.Load() && laneReady(s.publisherChannel(true))
}

// DatagramCanSend reports whether the lossy lane is open and under its
// budget. It negotiates alongside the reliable one and can lag it by a
// moment. Over the mark it says no, and the datagram writer above polls it:
// this is what stops the packet before the lane has to drop it.
func (s *Session) DatagramCanSend() bool {
	return !s.closed.Load() && laneReady(s.publisherChannel(false))
}

// SubscriberCanSend reports whether the subscriber PC is connected. Unlike
// CanSend it does not wait for the publisher PC, which negotiates second.
func (s *Session) SubscriberCanSend() bool {
	return !s.closed.Load() && s.subscriberConnected()
}

// GetBufferedAmount is what both publisher channels still hold.
func (s *Session) GetBufferedAmount() uint64 {
	var buffered uint64
	for _, dc := range []*webrtc.DataChannel{s.publisherChannel(true), s.publisherChannel(false)} {
		if dc != nil {
			buffered += dc.BufferedAmount()
		}
	}
	return buffered
}

// publisherChannel returns the live publisher channel of one lane. A
// generation that is gone has no lane, whatever state its channels are still
// in: teardown ends the generation before pion closes them, and in that
// window publish already refuses. Everything that reports on a lane reads it
// through here, so the predicates and the write agree.
func (s *Session) publisherChannel(reliable bool) *webrtc.DataChannel {
	gen := s.current()
	if gen == nil || gen.isDone() {
		return nil
	}
	return publisherChannelOf(gen, reliable)
}

func publisherChannelOf(gen *generation, reliable bool) *webrtc.DataChannel {
	if reliable {
		return gen.pubRel.Load()
	}
	return gen.pubLossy.Load()
}

func laneOpen(dc *webrtc.DataChannel) bool {
	return dc != nil && dc.ReadyState() == webrtc.DataChannelStateOpen
}

// laneReady is what a lane has to be for the layer above to write on it: open,
// and holding less than one high-water mark of data it has not managed to
// send.
func laneReady(dc *webrtc.DataChannel) bool {
	return laneOpen(dc) && dc.BufferedAmount() <= bufferHighWaterMark
}

// LocalPeerID is the identity this session is addressed by, which is the
// participantId its join-response named. The SFU issues a fresh one per
// connection, so it changes under a rejoin and is empty until the connector
// has answered a join.
func (s *Session) LocalPeerID() string { return s.localIdentity() }

// ConfirmPeer binds this session to the one remote identity the tunnel
// handshake authenticated.
//
// The binding belongs to the connection attempt that is live when it is
// made, and a rejoin does not carry it over: the SFU hands every connection
// fresh participant ids, so an identity confirmed under the previous attempt
// names nobody under the next one. The upper layer runs its handshake again
// after a reconnect and confirms again. What PeerSeen asks about is kept
// apart from the binding and outlives it: the server the last handshake
// confirmed.
//
// The confirmed server is also where this client's payloads to the room go
// while it is in the room, and what its relay window is kept toward; the
// server's window toward this client has to be on before the server's first
// reply byte. So the binding puts a mark of count 0 on the wire here, ahead
// of any data: the server arms its window on it, and a server of a build
// before the window drops it as a frame it cannot decrypt.
// A binding that moves to another identity ends the window toward the old
// one, and a send held on it with it.
func (s *Session) ConfirmPeer(peerID string) error {
	if peerID == "" {
		return fmt.Errorf("%w: empty identity", engine.ErrInvalidPeerID)
	}
	gen := s.current()
	if gen == nil || gen.isDone() {
		return ErrSessionClosed
	}
	if old := gen.confirmed.Swap(&peerID); old != nil && *old != peerID {
		gen.forgetRelayPeer(*old)
	}
	s.server.Store(&peerID)
	s.sendWindowFrame(gen, peerID, windowMark, 0)
	return nil
}

// ResetPeer drops the confirmed binding, after an upper-layer handshake
// failure. The room roster is left alone: who is in the room is the
// connector's statement, not this session's. The relay window toward the
// server that was bound goes with the binding, and a send held on it is
// refused: the session it belonged to is over.
func (s *Session) ResetPeer() {
	gen := s.current()
	if gen == nil {
		return
	}
	if old := gen.confirmed.Swap(nil); old != nil {
		gen.forgetRelayPeer(*old)
	}
}

// WaitForPeer blocks until someone else is in the room: the roster has named
// a participant, a data packet has arrived from one, or the handshake has
// confirmed one.
func (s *Session) WaitForPeer(ctx context.Context) error {
	ticker := time.NewTicker(peerPollInterval)
	defer ticker.Stop()
	for {
		if s.hasPeer() {
			return nil
		}
		select {
		case <-ctx.Done():
			return fmt.Errorf("salutejazz wait for peer: %w", ctx.Err())
		case <-s.closeCh:
			return ErrSessionClosed
		case <-ticker.C:
		}
	}
}

// hasPeer reports whether the live connection attempt knows of anyone else.
func (s *Session) hasPeer() bool {
	gen := s.current()
	if gen == nil {
		return false
	}
	return loadString(&gen.confirmed) != "" || gen.hasRemote()
}

// PeerSeen implements transport.PeerObserver: the server the last handshake
// this session completed confirmed is in the room, by the roster of the
// attempt that is live now. The client asks when a handshake has gone
// unanswered, to tell a server that is there and silent - its hello queued at
// the SFU behind a dead session's backlog - from an empty room.
//
// Nobody else counts. A room whose server has been retired can still hold a
// client of another device, and taking that for a peer had a client retry a
// dead room instead of failing over to the next. The binding cannot answer
// either: the client drops it before every handshake it retries, and a rejoin
// starts without one. The identity can, across both: the SFU hands out a new
// one only to a connection that joins again, so a server that stayed is in
// the rejoined attempt's roster under the name it was confirmed by.
func (s *Session) PeerSeen() bool {
	server := loadString(&s.server)
	gen := s.current()
	if server == "" || gen == nil || gen.isDone() {
		return false
	}
	return gen.inRoom(server)
}

// hasRemote reports whether anyone else is in the room, without building the
// list: WaitForPeer asks on a poll.
func (g *generation) hasRemote() bool {
	g.peersMu.RLock()
	defer g.peersMu.RUnlock()
	return len(g.peers) > 0
}

// inRoom reports whether the roster names identity. Every payload a confirmed
// client sends to the room asks it, so it reads the map under the read lock
// and builds nothing.
func (g *generation) inRoom(identity string) bool {
	g.peersMu.RLock()
	defer g.peersMu.RUnlock()
	_, ok := g.peers[identity]
	return ok
}

// hasLeft reports whether the room has reported identity gone during this
// attempt.
func (g *generation) hasLeft(identity string) bool {
	g.peersMu.RLock()
	defer g.peersMu.RUnlock()
	_, gone := g.left[identity]
	return gone
}

// dropPeer takes one participant out of the roster, on the connector's word
// that it has left, for the rest of the attempt.
func (g *generation) dropPeer(identity string) {
	g.peersMu.Lock()
	defer g.peersMu.Unlock()
	g.departLocked(identity)
}

// departLocked takes identity out of the roster for good. Called with peersMu
// held for writing.
func (g *generation) departLocked(identity string) {
	delete(g.peers, identity)
	g.left[identity] = struct{}{}
}

// notePeer records a sender the roster has not named yet, so the first
// packet from a participant counts as that participant appearing. The
// roster update that follows fills in its sid, and the one that reports it
// gone removes it again. A participant the room has reported gone is not
// recorded again: its last frames can arrive long after it left.
//
// Every relayed packet comes through here, and after the first one from a
// participant there is nothing left to record: that case takes the read lock
// and leaves, so a session carrying traffic does not serialise its packets
// behind a map it is not changing. So does a packet from one that has left.
func (g *generation) notePeer(identity string) {
	if identity == "" || identity == g.localIdentity() {
		return
	}
	g.peersMu.RLock()
	_, known := g.peers[identity]
	_, gone := g.left[identity]
	g.peersMu.RUnlock()
	if known || gone {
		return
	}
	g.peersMu.Lock()
	defer g.peersMu.Unlock()
	// Between the two locks the roster may have learned this identity, with
	// the sid an update carries, or lost it; the check is made again rather
	// than overwriting the one or bringing back the other.
	_, known = g.peers[identity]
	_, gone = g.left[identity]
	if !known && !gone {
		g.peers[identity] = ""
	}
}
