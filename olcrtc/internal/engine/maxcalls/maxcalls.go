// Package maxcalls is the engine for calls of the MAX messenger (VK): the
// tunnel rides the data channel of a two-party call. The join endpoint's
// WebSocket speaks the ok-calls JSON protocol; the two participants exchange
// their SDP and ICE candidates through its transmit-data relay, which the
// SFU delivers to the other participant, and the media then flows through
// the room's relay candidates, which answer datacenter sources (measured
// 2026-10-18: megabits both ways).
//
// The pair: the participant with the lower uid offers, the other answers.
// The offer is the shape the counterparty's SDK answers - two recvonly video
// transceivers, one recvonly audio and the "datagram" data channel - with
// the candidates stripped and trickled one a frame, as the relay drops a
// message over about 3.5 KB. A room has to be of the DIRECT topology (a
// call created without a camera): a SERVER room carries media through SFU
// legs a pair without a publisher does not drive.
package maxcalls

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/pion/webrtc/v4"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
	"github.com/openlibrecommunity/olcrtc/internal/logger"
)

// ai-generated: the whole file.

// Errors.
var (
	ErrSessionClosed = errors.New("max: session closed")
	ErrNotConnected  = errors.New("max: no data channel is open")
	ErrNoEndpoint    = errors.New("max: no signaling endpoint")
	ErrNoUserID      = errors.New("max: the credentials name no user id")
	// ErrTopology is a room that is not DIRECT: the pair's relay exchange
	// does not run there.
	ErrTopology = errors.New("max: the room is not of the DIRECT topology (create the call without a camera)")
	// ErrNoConnectionFrame is a socket that did not assign a peer id in time.
	ErrNoConnectionFrame = errors.New("max: no connection frame from the SFU")
	// ErrSeatRefused is an accept-call the SFU answered with an error.
	ErrSeatRefused = errors.New("max: the SFU refused the seat")
	// ErrSeatTimeout is an accept-call the SFU did not answer in time.
	ErrSeatTimeout = errors.New("max: no answer to accept-call")
	// ErrOfferTooLarge is an offer the relay would drop.
	ErrOfferTooLarge = errors.New("max: the offer is over the relay's size cap")
)

const (
	topologyDirect = "DIRECT"
	channelLabel   = "datagram"
	// relayCap is the largest SDP a relayed frame may carry: the server
	// answers "Invalid message format" past about 3.5 KB, and the frame
	// around the SDP takes the rest.
	relayCap = 3400
	// seatWait bounds the connection frame and the answer to accept-call.
	seatWait = 15 * time.Second
	// offerRetryWait and offerRetries pace the offers sent again while no
	// answer has come: the relay delivers nothing to a participant whose
	// socket is not up yet, and the next registration push may be the only
	// other trigger. The first offer and its candidates are sent again as
	// they are.
	offerRetryWait = 5 * time.Second
	offerRetries   = 6
	// sendHighWater is how much the data channel may hold before a sender
	// waits. pion's DataChannel.Send never blocks: it queues without bound,
	// so a receiver that stops reading turns a writer into memory (9 GB
	// measured with no gate). It also bounds what is in flight: a megabyte
	// at a 300 ms round trip is 28 Mbit/s, above what the relay carries.
	sendHighWater = 1024 * 1024
	// pendingCandidates bounds the candidates kept for a remote description
	// that has not arrived.
	pendingCandidates = 32
	maxReconnects     = 10
	limitReason       = "reconnect limit reached"
)

// The capability interfaces the transport layer type-asserts on.
var _ engine.Session = (*Session)(nil)

// Session is one participant of a call.
type Session struct {
	engine.Reconnector

	cfg    engine.Config
	api    *webrtc.API
	onData func([]byte)

	mu       sync.Mutex
	endpoint string
	ice      []webrtc.ICEServer
	self     int64
	gen      *generation

	closed       atomic.Bool
	reconnecting atomic.Bool
	closeCh      chan struct{}
	closeOnce    sync.Once
	endOnce      sync.Once
}

// generation is everything one join owns: the socket, the peer connection
// and what was negotiated on them. A rejoin is a new generation, so nothing
// of the old one reaches the new.
type generation struct {
	sig  *signaling
	pc   *webrtc.PeerConnection
	self int64
	dc   atomic.Pointer[webrtc.DataChannel]

	// peer is the counterparty's uid once one is known; peerConn its
	// connection id once a description of its has been applied, so a frame
	// of another connection of the same uid reads as a rejoin.
	peer     atomic.Int64
	peerConn atomic.Int64
	offered  atomic.Bool

	mu       sync.Mutex
	gathered []webrtc.ICECandidateInit // ours, for an offer sent again
	pending  []webrtc.ICECandidateInit // theirs, before the remote description

	windowMu sync.Mutex
	window   chan struct{}

	// seated closes on the answer to accept-call, with refusal set when
	// the SFU said no; done closes when the generation is torn down.
	seated     chan struct{}
	seatedOnce sync.Once
	refusal    error
	done       chan struct{}
	doneOnce   sync.Once
}

func newGeneration(sig *signaling, pc *webrtc.PeerConnection, self int64) *generation {
	return &generation{
		sig: sig, pc: pc, self: self,
		window: make(chan struct{}), seated: make(chan struct{}), done: make(chan struct{}),
	}
}

// New builds a session from the provider's credentials: URL is the join
// endpoint, Extra the TURN and STUN servers and our uid.
func New(_ context.Context, cfg engine.Config) (engine.Session, error) {
	if cfg.URL == "" {
		return nil, ErrNoEndpoint
	}
	if uidOf(cfg.Extra) == 0 {
		return nil, ErrNoUserID
	}
	api, err := newAPI(cfg)
	if err != nil {
		return nil, err
	}
	s := &Session{cfg: cfg, api: api, onData: cfg.OnData, closeCh: make(chan struct{})}
	s.adopt(engine.Credentials{URL: cfg.URL, Token: cfg.Token, Extra: cfg.Extra})
	s.Configure(engine.ReconnectorConfig{
		MaxAttempts: maxReconnects,
		Reconnect:   s.reconnect,
		OnError:     func(err error) { logger.Debugf("max: reconnect failed: %v", err) },
		OnLimit:     func(string) { s.signalEnded(limitReason) },
		LimitReason: limitReason,
	})
	return s, nil
}

// adopt makes creds the ones the next join uses.
func (s *Session) adopt(creds engine.Credentials) {
	s.mu.Lock()
	s.endpoint = creds.URL
	s.ice = iceServersOf(creds.Extra)
	s.self = uidOf(creds.Extra)
	s.mu.Unlock()
}

// newAPI is pion set up for the measured offer: VP8 and opus alone, which
// keeps the SDP under the relay cap (the default codec set runs past it),
// and ICE over UDP and TCP, so the room's passive TCP relay candidate is
// dialled when UDP does not get through.
func newAPI(cfg engine.Config) (*webrtc.API, error) {
	media := &webrtc.MediaEngine{}
	if err := media.RegisterCodec(webrtc.RTPCodecParameters{
		RTPCodecCapability: webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeVP8, ClockRate: 90000},
		PayloadType:        96,
	}, webrtc.RTPCodecTypeVideo); err != nil {
		return nil, fmt.Errorf("max: video codec: %w", err)
	}
	if err := media.RegisterCodec(webrtc.RTPCodecParameters{
		RTPCodecCapability: webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeOpus, ClockRate: 48000, Channels: 2},
		PayloadType:        111,
	}, webrtc.RTPCodecTypeAudio); err != nil {
		return nil, fmt.Errorf("max: audio codec: %w", err)
	}
	settings := webrtc.SettingEngine{}
	apply, err := engine.NewPionSettings(engine.PionSettingsOptions{
		Resolver: cfg.Resolver, DTLSProfile: cfg.DTLSProfile, LoggerFactory: logger.NewPionLoggerFactory(),
		IPv4Only: true, DisableMulticast: true,
	})
	if err != nil {
		return nil, err //nolint:wrapcheck // the shared builder already adds context
	}
	if apply != nil {
		apply(&settings)
	}
	settings.SetNetworkTypes([]webrtc.NetworkType{webrtc.NetworkTypeUDP4, webrtc.NetworkTypeTCP4})
	return webrtc.NewAPI(webrtc.WithMediaEngine(media), webrtc.WithSettingEngine(settings)), nil
}

// Connect joins the call: the signaling socket, the SFU's connection frame,
// the seat. The negotiation runs from there as the counterparty shows up,
// so a server joins an empty room and waits.
func (s *Session) Connect(ctx context.Context) error {
	if s.closed.Load() {
		return ErrSessionClosed
	}
	s.mu.Lock()
	endpoint, ice, self := s.endpoint, s.ice, s.self
	s.mu.Unlock()

	sig, err := dialSignaling(ctx, endpoint, s.cfg.Resolver)
	if err != nil {
		return err
	}
	welcome, err := sig.awaitConnection(seatWait)
	if err != nil {
		sig.close()
		return err
	}
	if welcome.Conversation.Topology != topologyDirect {
		sig.close()
		return fmt.Errorf("%w: %s", ErrTopology, welcome.Conversation.Topology)
	}
	if len(ice) == 0 {
		ice = iceServersOfParams(welcome.Params)
	}
	pc, err := s.api.NewPeerConnection(webrtc.Configuration{ICEServers: ice})
	if err != nil {
		sig.close()
		return fmt.Errorf("max: peer connection: %w", err)
	}
	gen := newGeneration(sig, pc, self)
	s.wire(gen)
	logger.Infof("max: joined %s as peer %d", shortID(welcome.Conversation.ID), welcome.PeerID.ID)

	if _, err := sig.acceptCall(); err != nil {
		gen.teardown()
		return err
	}
	go s.readLoop(gen)
	if err := gen.awaitSeat(ctx, seatWait); err != nil {
		gen.teardown()
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed.Load() {
		gen.teardown()
		return ErrSessionClosed
	}
	s.gen = gen
	return nil
}

// awaitSeat waits for the SFU's answer to accept-call.
func (g *generation) awaitSeat(ctx context.Context, wait time.Duration) error {
	timer := time.NewTimer(wait)
	defer timer.Stop()
	select {
	case <-g.seated:
		return g.refusal
	case <-g.done:
		return ErrSessionClosed
	case <-ctx.Done():
		return fmt.Errorf("max: seat: %w", ctx.Err())
	case <-timer.C:
		return ErrSeatTimeout
	}
}

// seat records the SFU's answer to accept-call, once.
func (g *generation) seat(refusal error) {
	g.seatedOnce.Do(func() {
		g.refusal = refusal
		close(g.seated)
	})
}

// wire hooks the peer connection to the generation: the counterparty's
// data channel when it offered, our candidates to the relay, the connection
// state to the supervisor.
func (s *Session) wire(gen *generation) {
	gen.pc.OnDataChannel(func(dc *webrtc.DataChannel) {
		if dc.Label() == channelLabel {
			s.adoptChannel(gen, dc)
		}
	})
	gen.pc.OnTrack(func(track *webrtc.TrackRemote, _ *webrtc.RTPReceiver) {
		go drain(track)
	})
	gen.pc.OnICECandidate(func(c *webrtc.ICECandidate) {
		if c == nil {
			return
		}
		init := c.ToJSON()
		gen.mu.Lock()
		gen.gathered = append(gen.gathered, init)
		gen.mu.Unlock()
		if to := gen.peer.Load(); to != 0 {
			if err := gen.sig.relayCandidate(to, init); err != nil {
				logger.Debugf("max: candidate relay: %v", err)
			}
		}
	})
	gen.pc.OnConnectionStateChange(func(state webrtc.PeerConnectionState) {
		logger.Debugf("max: peer connection %s", state)
		switch state {
		case webrtc.PeerConnectionStateFailed:
			// A pair that lost its path, or never found one: a fresh join
			// negotiates again.
			s.reconnectAttempt(gen)
		case webrtc.PeerConnectionStateNew, webrtc.PeerConnectionStateConnecting,
			webrtc.PeerConnectionStateConnected, webrtc.PeerConnectionStateDisconnected,
			webrtc.PeerConnectionStateClosed, webrtc.PeerConnectionStateUnknown:
		}
	})
}

// adoptChannel makes dc the generation's data channel: the tunnel's bytes
// in, the send window out.
func (s *Session) adoptChannel(gen *generation, dc *webrtc.DataChannel) {
	dc.SetBufferedAmountLowThreshold(sendHighWater)
	dc.OnBufferedAmountLow(gen.openSendWindow)
	dc.OnMessage(func(m webrtc.DataChannelMessage) {
		if s.onData != nil {
			s.onData(m.Data)
		}
	})
	dc.OnOpen(func() { logger.Infof("max: data channel open") })
	dc.OnClose(func() { logger.Debugf("max: data channel closed") })
	gen.dc.Store(dc)
}

// drain reads a track nothing consumes, so its packets do not pile up.
func drain(track *webrtc.TrackRemote) {
	buf := make([]byte, 1500)
	for {
		if _, _, err := track.Read(buf); err != nil {
			return
		}
	}
}

// readLoop reads the socket until it ends. A socket that ends under a live
// generation asks for a rejoin; one closed by a teardown does not.
func (s *Session) readLoop(gen *generation) {
	for {
		f, err := gen.sig.read()
		if err != nil {
			if !gen.isDone() && !s.closed.Load() {
				logger.Debugf("%v", err)
				s.reconnectAttempt(gen)
			}
			return
		}
		s.handle(gen, f)
	}
}

// handle acts on one frame.
func (s *Session) handle(gen *generation, f *frame) {
	switch {
	case f.ping:
		if err := gen.sig.pong(); err != nil {
			logger.Debugf("%v", err)
		}
	case f.Type == frameResponse && f.Response == cmdAcceptCall:
		gen.seat(nil)
		// The list names who is in the room already; its sockets are up,
		// so the lower uid may offer at once.
		for _, uid := range f.ParticipantIDs {
			if uid != gen.self {
				s.peerSeen(gen, uid, 0)
				break
			}
		}
	case f.Type == frameError:
		logger.Debugf("max: the SFU answered an error: %s (%s)", f.Error, f.Message)
		gen.seat(fmt.Errorf("%w: %s", ErrSeatRefused, f.Error))
	case f.Type == frameNotification && f.Notification == noteRegisteredPeer:
		if f.ParticipantID != 0 && f.ParticipantID != gen.self {
			s.peerSeen(gen, f.ParticipantID, f.PeerID.ID)
		}
	case f.Type == frameNotification && f.Notification == noteTransmittedData:
		s.handleRelay(gen, f)
	}
}

// peerSeen is a sign that the counterparty's socket is up: the room list at
// our seat, or its registration push. The lower uid offers; a registration
// of another connection of a counterparty already negotiated with is its
// rejoin, and the path to it is gone with its old socket.
func (s *Session) peerSeen(gen *generation, uid, conn int64) {
	if known := gen.peer.Load(); known != 0 && known != uid {
		logger.Debugf("max: participant %d seen beside the pair, ignored", uid)
		return
	}
	gen.peer.Store(uid)
	if negotiated := gen.peerConn.Load(); negotiated != 0 {
		if conn != 0 && conn != negotiated {
			logger.Infof("max: peer %d joined again; rejoining", uid)
			s.reconnectAttempt(gen)
		}
		return
	}
	if gen.self > uid {
		return // the lower uid offers; we answer
	}
	s.offer(gen, uid)
}

// offer sends our offer to the counterparty: made once per generation, with
// the data channel created for it, and sent again as it is while no answer
// has come (see offerRetryWait).
func (s *Session) offer(gen *generation, to int64) {
	if gen.offered.CompareAndSwap(false, true) {
		// The measured shape: two recvonly video transceivers and a
		// recvonly audio one beside the data channel, which is what the
		// counterparty's SDK answers (a sendrecv offer it ignores).
		for _, kind := range []webrtc.RTPCodecType{
			webrtc.RTPCodecTypeVideo, webrtc.RTPCodecTypeVideo, webrtc.RTPCodecTypeAudio,
		} {
			if _, err := gen.pc.AddTransceiverFromKind(kind,
				webrtc.RTPTransceiverInit{Direction: webrtc.RTPTransceiverDirectionRecvonly}); err != nil {
				logger.Warnf("max: %s transceiver: %v", kind, err)
				return
			}
		}
		dc, err := gen.pc.CreateDataChannel(channelLabel, nil)
		if err != nil {
			logger.Warnf("max: data channel: %v", err)
			return
		}
		s.adoptChannel(gen, dc)
		offer, err := gen.pc.CreateOffer(nil)
		if err != nil {
			logger.Warnf("max: offer: %v", err)
			return
		}
		if err := gen.pc.SetLocalDescription(offer); err != nil {
			logger.Warnf("max: local offer: %v", err)
			return
		}
		go s.offerAgain(gen, to)
	}
	if err := s.relayLocal(gen, to); err != nil {
		logger.Warnf("max: offer to %d: %v", to, err)
	}
}

// offerAgain sends the offer again while no answer has come, a bounded
// number of times.
func (s *Session) offerAgain(gen *generation, to int64) {
	timer := time.NewTimer(offerRetryWait)
	defer timer.Stop()
	for range offerRetries {
		select {
		case <-gen.done:
			return
		case <-timer.C:
		}
		if gen.peerConn.Load() != 0 {
			return
		}
		logger.Debugf("max: no answer from %d in %s, offering again", to, offerRetryWait)
		if err := s.relayLocal(gen, to); err != nil {
			logger.Debugf("max: offer to %d: %v", to, err)
		}
		timer.Reset(offerRetryWait)
	}
}

// relayLocal sends the current local description, candidates stripped, and
// then every candidate gathered so far, one a frame.
func (s *Session) relayLocal(gen *generation, to int64) error {
	desc := gen.pc.LocalDescription()
	if desc == nil {
		return ErrNotConnected
	}
	sdp := stripCandidates(desc.SDP)
	if len(sdp) > relayCap {
		return fmt.Errorf("%w: %d bytes", ErrOfferTooLarge, len(sdp))
	}
	if err := gen.sig.relaySDP(to, webrtc.SessionDescription{Type: desc.Type, SDP: sdp}); err != nil {
		return err
	}
	logger.Infof("max: %s relayed to %d (%d bytes SDP)", desc.Type, to, len(sdp))
	gen.mu.Lock()
	gathered := append([]webrtc.ICECandidateInit(nil), gen.gathered...)
	gen.mu.Unlock()
	for _, c := range gathered {
		if err := gen.sig.relayCandidate(to, c); err != nil {
			return err
		}
	}
	return nil
}

// handleRelay acts on a frame the counterparty sent through the relay: its
// offer, its answer or one candidate. The frame names its sender, and the
// SFU can deliver it before the answer to our own accept-call, the one
// other frame that names the room.
func (s *Session) handleRelay(gen *generation, f *frame) {
	var p relayPayload
	if err := json.Unmarshal(f.Data, &p); err != nil {
		logger.Debugf("max: relayed frame: %v", err)
		return
	}
	from, conn := f.ParticipantID, f.PeerID.ID
	switch {
	case from == 0 || from == gen.self:
		logger.Debugf("max: a relayed frame that names no sender, dropped")
	case p.SDP != nil && p.SDP.Type == webrtc.SDPTypeOffer:
		s.answer(gen, from, conn, *p.SDP)
	case p.SDP != nil && p.SDP.Type == webrtc.SDPTypeAnswer:
		s.applyAnswer(gen, from, conn, *p.SDP)
	case p.Candidate != nil:
		gen.addRemoteCandidate(conn, *p.Candidate)
	}
}

// answer answers the counterparty's offer. The same offer again is the
// counterparty not having seen our answer, which goes again; an offer of
// another connection of its is its rejoin.
func (s *Session) answer(gen *generation, from, conn int64, offer webrtc.SessionDescription) {
	if known := gen.peer.Load(); known != 0 && known != from {
		logger.Debugf("max: an offer from %d beside the pair, ignored", from)
		return
	}
	if negotiated := gen.peerConn.Load(); negotiated != 0 {
		if conn == negotiated {
			if err := s.relayLocal(gen, from); err != nil {
				logger.Debugf("max: answer to %d again: %v", from, err)
			}
			return
		}
		logger.Infof("max: peer %d offered from a new connection; rejoining", from)
		s.reconnectAttempt(gen)
		return
	}
	if gen.offered.Load() {
		logger.Warnf("max: an offer from %d while ours is out: both sides offered", from)
		return
	}
	gen.peer.Store(from)
	if err := gen.pc.SetRemoteDescription(offer); err != nil {
		logger.Warnf("max: remote offer: %v", err)
		return
	}
	answer, err := gen.pc.CreateAnswer(nil)
	if err != nil {
		logger.Warnf("max: answer: %v", err)
		return
	}
	if err := gen.pc.SetLocalDescription(answer); err != nil {
		logger.Warnf("max: local answer: %v", err)
		return
	}
	gen.peerConn.Store(conn)
	if err := s.relayLocal(gen, from); err != nil {
		logger.Warnf("max: answer to %d: %v", from, err)
		return
	}
	gen.applyPending()
}

// applyAnswer applies the counterparty's answer to our offer; a second one
// (to an offer sent again) changes nothing.
func (s *Session) applyAnswer(gen *generation, from, conn int64, answer webrtc.SessionDescription) {
	if !gen.offered.Load() || from != gen.peer.Load() || gen.peerConn.Load() != 0 {
		logger.Debugf("max: an answer from %d that answers nothing, ignored", from)
		return
	}
	if err := gen.pc.SetRemoteDescription(answer); err != nil {
		logger.Warnf("max: remote answer: %v", err)
		return
	}
	gen.peerConn.Store(conn)
	logger.Infof("max: answer of %d applied (%d bytes SDP)", from, len(answer.SDP))
	gen.applyPending()
}

// addRemoteCandidate adds one of the counterparty's candidates, or keeps it
// for the remote description still to come; one of another connection than
// the negotiated one is stale.
func (g *generation) addRemoteCandidate(conn int64, c webrtc.ICECandidateInit) {
	if negotiated := g.peerConn.Load(); negotiated != 0 && conn != 0 && conn != negotiated {
		return
	}
	if g.pc.RemoteDescription() == nil {
		g.mu.Lock()
		if len(g.pending) < pendingCandidates {
			g.pending = append(g.pending, c)
		}
		g.mu.Unlock()
		return
	}
	if err := g.pc.AddICECandidate(c); err != nil {
		logger.Debugf("max: remote candidate: %v", err)
	}
}

// applyPending adds the candidates that came before the remote description.
func (g *generation) applyPending() {
	g.mu.Lock()
	pending := g.pending
	g.pending = nil
	g.mu.Unlock()
	for _, c := range pending {
		if err := g.pc.AddICECandidate(c); err != nil {
			logger.Debugf("max: remote candidate: %v", err)
		}
	}
}

// Send writes to the data channel, waiting at the high-water mark for the
// channel to drain.
func (s *Session) Send(data []byte) error {
	gen := s.current()
	if gen == nil {
		return ErrNotConnected
	}
	dc := gen.dc.Load()
	if dc == nil || dc.ReadyState() != webrtc.DataChannelStateOpen {
		return ErrNotConnected
	}
	if err := gen.awaitSendWindow(dc, s.closeCh); err != nil {
		return err
	}
	if err := dc.Send(data); err != nil {
		return fmt.Errorf("max: data channel send: %w", err)
	}
	return nil
}

// awaitSendWindow waits until the channel holds no more than the high-water
// mark. The waiter is taken before the gauge is read, so a drain between the
// two cannot be missed; a generation torn down meanwhile releases the
// caller, as pion fires the low-water callback for an open channel only.
func (g *generation) awaitSendWindow(dc *webrtc.DataChannel, closeCh <-chan struct{}) error {
	for {
		window := g.sendWindow()
		if dc.BufferedAmount() <= sendHighWater {
			return nil
		}
		select {
		case <-window:
		case <-g.done:
			return ErrNotConnected
		case <-closeCh:
			return ErrSessionClosed
		}
	}
}

func (g *generation) sendWindow() <-chan struct{} {
	g.windowMu.Lock()
	defer g.windowMu.Unlock()
	return g.window
}

// openSendWindow releases every waiting sender and arms the next wait. pion
// calls it once per crossing of the threshold.
func (g *generation) openSendWindow() {
	g.windowMu.Lock()
	defer g.windowMu.Unlock()
	close(g.window)
	g.window = make(chan struct{})
}

func (g *generation) isDone() bool {
	select {
	case <-g.done:
		return true
	default:
		return false
	}
}

// teardown ends the generation: the socket, so the read loop returns, and
// the peer connection.
func (g *generation) teardown() {
	g.doneOnce.Do(func() { close(g.done) })
	g.sig.close()
	_ = g.pc.Close()
}

func (s *Session) current() *generation {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.gen
}

// CanSend reports whether the data channel carries data.
func (s *Session) CanSend() bool {
	gen := s.current()
	if gen == nil || gen.isDone() {
		return false
	}
	dc := gen.dc.Load()
	return dc != nil && dc.ReadyState() == webrtc.DataChannelStateOpen
}

// SubscriberCanSend mirrors CanSend: one peer connection carries both ways.
func (s *Session) SubscriberCanSend() bool { return s.CanSend() }

// GetBufferedAmount reports what the data channel holds.
func (s *Session) GetBufferedAmount() uint64 {
	gen := s.current()
	if gen == nil {
		return 0
	}
	if dc := gen.dc.Load(); dc != nil {
		return dc.BufferedAmount()
	}
	return 0
}

// reconnectAttempt asks the supervisor for a rejoin on behalf of one
// generation; one already torn down - by Close, or by a rejoin - asks
// nothing.
func (s *Session) reconnectAttempt(gen *generation) {
	if s.closed.Load() || gen.isDone() {
		return
	}
	s.Request(s.closed.Load(), s.reconnecting.Load())
}

// Reconnect asks the session to rejoin the call. Upper layers call it when a
// liveness probe declares the path dead before the engine has noticed.
func (s *Session) Reconnect(reason string) {
	if s.closed.Load() {
		return
	}
	logger.Debugf("max: reconnect requested: %s", reason)
	s.Request(s.closed.Load(), s.reconnecting.Load())
}

// reconnect tears the current join down and joins again, with fresh
// credentials when the provider gives them (a new join of the same session
// key: the endpoint's token is the join's).
func (s *Session) reconnect(ctx context.Context) error {
	if s.closed.Load() {
		return ErrSessionClosed
	}
	s.reconnecting.Store(true)
	defer s.reconnecting.Store(false)

	s.mu.Lock()
	gen := s.gen
	s.gen = nil
	s.mu.Unlock()
	if gen != nil {
		gen.teardown()
	}
	if s.cfg.Refresh != nil {
		creds, err := s.cfg.Refresh(ctx)
		if err != nil {
			return fmt.Errorf("refresh credentials: %w", err)
		}
		s.adopt(creds)
	}
	if err := s.Connect(ctx); err != nil {
		return err
	}
	s.NotifyReconnect()
	return nil
}

// WatchConnection services the session's reconnect requests until ctx ends
// or the session closes.
func (s *Session) WatchConnection(ctx context.Context) {
	s.Watch(ctx, s.closeCh)
}

// signalEnded ends the session for good: the verdict is reported once, and
// everything waiting on the session stops with it.
func (s *Session) signalEnded(reason string) {
	s.endOnce.Do(func() { s.SignalEnded(reason) })
	s.closed.Store(true)
	s.closeOnce.Do(func() { close(s.closeCh) })
}

// Close leaves the call. A closed session refuses every later reconnect
// request and stops the supervisor.
func (s *Session) Close() error {
	s.closed.Store(true)
	s.mu.Lock()
	gen := s.gen
	s.gen = nil
	s.mu.Unlock()
	if gen != nil {
		gen.teardown()
	}
	s.closeOnce.Do(func() { close(s.closeCh) })
	return nil
}

// iceServersOf reads the TURN and STUN servers the provider put in Extra.
func iceServersOf(extra map[string]string) []webrtc.ICEServer {
	var servers []webrtc.ICEServer
	if urls := listOf(extra["turnUrl"]); len(urls) > 0 {
		servers = append(servers, webrtc.ICEServer{
			URLs: urls, Username: extra["turnUsername"], Credential: extra["turnCredential"],
		})
	}
	if urls := listOf(extra["stunUrl"]); len(urls) > 0 {
		servers = append(servers, webrtc.ICEServer{URLs: urls})
	}
	return servers
}

// iceServersOfParams reads the servers the SFU's connection frame names.
func iceServersOfParams(params conversationParams) []webrtc.ICEServer {
	var servers []webrtc.ICEServer
	if len(params.TURN.URLs) > 0 {
		servers = append(servers, webrtc.ICEServer{
			URLs: params.TURN.URLs, Username: params.TURN.Username, Credential: params.TURN.Credential,
		})
	}
	if len(params.STUN.URLs) > 0 {
		servers = append(servers, webrtc.ICEServer{URLs: params.STUN.URLs})
	}
	return servers
}

// listOf splits a comma-separated list, blanks dropped.
func listOf(v string) []string {
	var out []string
	for _, item := range strings.Split(v, ",") {
		if item = strings.TrimSpace(item); item != "" {
			out = append(out, item)
		}
	}
	return out
}

// uidOf reads our uid from the credentials; 0 when they name none.
func uidOf(extra map[string]string) int64 {
	uid, err := strconv.ParseInt(extra["userId"], 10, 64)
	if err != nil || uid <= 0 {
		return 0
	}
	return uid
}

// stripCandidates takes the candidate lines out of an SDP: they trickle one
// a frame, under the relay cap.
func stripCandidates(sdp string) string {
	lines := strings.Split(sdp, "\r\n")
	kept := lines[:0]
	for _, line := range lines {
		if !strings.HasPrefix(line, "a=candidate:") {
			kept = append(kept, line)
		}
	}
	return strings.Join(kept, "\r\n")
}

// shortID shortens a conversation id for the log.
func shortID(id string) string {
	if len(id) > 8 {
		return id[:8]
	}
	return id
}
