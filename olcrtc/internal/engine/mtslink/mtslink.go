// Package mtslink is the engine for MTS Link rooms: one peer connection to
// the service's SFU, negotiated with a single SDP exchange over HTTP, the
// payload riding the published video track and coming back on the slots the
// SFU fills with the room's other publishers.
//
// What the SFU takes, measured against the live service on 2026-10-11:
//
//   - Video in VP9 or H264, never VP8. The engine publishes VP9 and says so
//     (engine.VideoCodecSelector); the video transport dresses its frames
//     for it.
//   - A track is forwarded from a keyframe the SFU recognises, and again
//     from the next one after a packet it could not repair. It asks for one
//     with RTCP PLI; the engine passes the request on
//     (engine.KeyframeRequestSource).
//   - Nine receive slots, filled with the room's publishers without being
//     asked: a room of ten is one where everybody sees everybody.
//   - Every candidate twice: UDP and passive TCP, both on port 443 of the
//     SFU. The engine offers UDP alone unless the room link asks otherwise
//     ("…?ice=tcp" or "…?ice=both", roomhint): over TCP it dials the SFU's
//     passive candidate itself (pion's active ICE-TCP), the way a browser
//     gets through a network that starves UDP.
//   - No data between guests. The data channel the web client opens carries
//     the SFU's own notes about the slots, one way; this engine leaves it out.
//
// The room is an endless meeting, so it never closes under the engine and a
// rejoin is a new guest in the same room: fresh credentials from the provider,
// a join from scratch.
package mtslink

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	"github.com/pion/interceptor"
	"github.com/pion/rtcp"
	"github.com/pion/webrtc/v4"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/protect"
	"github.com/openlibrecommunity/olcrtc/internal/roomhint"
)

// Session errors.
var (
	ErrSessionClosed   = errors.New("mtslink: session closed")
	ErrNoEndpoint      = errors.New("mtslink: room join URL missing")
	ErrNoVideoTrack    = errors.New("mtslink: nothing to publish: the engine carries a video transport only")
	ErrConnectTimeout  = errors.New("mtslink: peer connection did not connect")
	ErrConnectFailed   = errors.New("mtslink: peer connection failed")
	ErrSendUnsupported = errors.New("mtslink: send requires the video transport")
)

const (
	connectWait   = 30 * time.Second
	maxReconnects = 10
	// receiveSlots is how many video m-lines the offer receives on: the web
	// client's nine, each of which the SFU fills with one publisher.
	receiveSlots = 9
	// leaveWait bounds the call that takes a conference back.
	leaveWait = 5 * time.Second

	videoPayloadType = 98
	rtxPayloadType   = 99
	audioPayloadType = 111
)

// Session is an engine.Session over one MTS Link endless room.
type Session struct {
	cfg engine.Config

	mu sync.Mutex
	// What the provider issued: the room join, and what takes the guest's
	// conference back when a join comes to nothing.
	joinURL      string
	cookies      string
	gateway      string
	conferenceID string
	// ice is the ICE mode the room link asked for (roomhint), empty for UDP.
	ice string
	gen *generation

	video    engine.VideoTrackState
	keyframe atomic.Pointer[func()]

	engine.Reconnector

	closed       atomic.Bool
	reconnecting atomic.Bool
	closeCh      chan struct{}
	closeOnce    sync.Once
	endOnce      sync.Once
}

// generation is everything one join owns, so tearing it down takes the whole
// attempt and nothing crosses attempts.
type generation struct {
	pc *webrtc.PeerConnection
	// connected closes when the peer connection first connects, failed when
	// it fails before it ever did, done when the attempt is torn down.
	connected     chan struct{}
	connectedOnce sync.Once
	failed        chan struct{}
	failedOnce    sync.Once
	done          chan struct{}
	doneOnce      sync.Once
}

func newGeneration(pc *webrtc.PeerConnection) *generation {
	return &generation{
		pc: pc, connected: make(chan struct{}), failed: make(chan struct{}), done: make(chan struct{}),
	}
}

func (gen *generation) isDone() bool {
	select {
	case <-gen.done:
		return true
	default:
		return false
	}
}

func (gen *generation) wasConnected() bool {
	select {
	case <-gen.connected:
		return true
	default:
		return false
	}
}

func (gen *generation) teardown() {
	gen.doneOnce.Do(func() { close(gen.done) })
	_ = gen.pc.Close()
}

// New builds an MTS Link session from provider credentials: URL is the room
// join, Token the guest's cookies, Extra the gateway and the conference they
// take back.
func New(_ context.Context, cfg engine.Config) (engine.Session, error) {
	if cfg.URL == "" {
		return nil, ErrNoEndpoint
	}
	s := &Session{cfg: cfg, closeCh: make(chan struct{})}
	s.adopt(engine.Credentials{URL: cfg.URL, Token: cfg.Token, Extra: cfg.Extra})
	s.Configure(engine.ReconnectorConfig{
		MaxAttempts: maxReconnects,
		Reconnect:   s.reconnect,
		OnError:     func(err error) { logger.Debugf("mtslink: reconnect failed: %v", err) },
		OnLimit:     func(string) { s.signalEnded("reconnect limit reached") },
		LimitReason: "reconnect limit reached",
	})
	return s, nil
}

// adopt makes creds the ones the next join uses.
func (s *Session) adopt(creds engine.Credentials) {
	s.mu.Lock()
	s.joinURL, s.cookies = creds.URL, creds.Token
	s.gateway, s.conferenceID = creds.Extra["gateway"], creds.Extra["conferenceId"]
	s.ice = creds.Extra["ice"]
	s.mu.Unlock()
}

// VideoCodec implements engine.VideoCodecSelector: the SFU answers a
// published track with VP9 and H264 only.
func (s *Session) VideoCodec() string { return webrtc.MimeTypeVP9 }

// SetKeyframeRequestHandler implements engine.KeyframeRequestSource.
func (s *Session) SetKeyframeRequestHandler(cb func()) {
	if cb == nil {
		s.keyframe.Store(nil)
		return
	}
	s.keyframe.Store(&cb)
}

// AddVideoTrack records the local track the next join publishes.
func (s *Session) AddVideoTrack(track webrtc.TrackLocal) error { //nolint:unparam // the VideoTrackCapable contract
	s.video.StoreVideoTrack(track)
	return nil
}

// SetVideoTrackHandler registers the callback the room's video arrives on:
// one track per receive slot, each carrying whichever publisher the SFU put
// there.
func (s *Session) SetVideoTrackHandler(cb func(*webrtc.TrackRemote, *webrtc.RTPReceiver)) {
	s.video.SetVideoTrackHandler(cb)
}

func (s *Session) httpClient() *http.Client {
	resolver := s.cfg.Resolver
	if resolver == nil {
		resolver = protect.NewResolver(s.cfg.DNSServer)
	}
	return protect.NewHTTPClient(resolver)
}

// newAPI is pion set up as the endpoint the SFU expects: VP9 with its
// retransmission stream and opus, the feedback a browser offers, and the
// default interceptors - reports, NACK both ways and transport-wide
// congestion feedback, which is how a lost packet is repaired before the SFU
// gives the frame up.
func (s *Session) newAPI() (*webrtc.API, error) {
	media := &webrtc.MediaEngine{}
	feedback := []webrtc.RTCPFeedback{
		{Type: "nack"}, {Type: "nack", Parameter: "pli"}, {Type: "ccm", Parameter: "fir"},
		{Type: "goog-remb"}, {Type: "transport-cc"},
	}
	for _, c := range []struct {
		params webrtc.RTPCodecParameters
		kind   webrtc.RTPCodecType
	}{
		{webrtc.RTPCodecParameters{
			RTPCodecCapability: webrtc.RTPCodecCapability{
				MimeType: webrtc.MimeTypeOpus, ClockRate: 48000, Channels: 2, SDPFmtpLine: "minptime=10;useinbandfec=1",
			},
			PayloadType: audioPayloadType,
		}, webrtc.RTPCodecTypeAudio},
		{webrtc.RTPCodecParameters{
			RTPCodecCapability: webrtc.RTPCodecCapability{
				MimeType: webrtc.MimeTypeVP9, ClockRate: 90000, SDPFmtpLine: "profile-id=0", RTCPFeedback: feedback,
			},
			PayloadType: videoPayloadType,
		}, webrtc.RTPCodecTypeVideo},
		{webrtc.RTPCodecParameters{
			RTPCodecCapability: webrtc.RTPCodecCapability{
				MimeType: webrtc.MimeTypeRTX, ClockRate: 90000, SDPFmtpLine: fmt.Sprintf("apt=%d", videoPayloadType),
			},
			PayloadType: rtxPayloadType,
		}, webrtc.RTPCodecTypeVideo},
	} {
		if err := media.RegisterCodec(c.params, c.kind); err != nil {
			return nil, fmt.Errorf("mtslink: register codec: %w", err)
		}
	}

	settings := webrtc.SettingEngine{}
	apply, err := engine.NewPionSettings(engine.PionSettingsOptions{
		Resolver:         s.cfg.Resolver,
		DTLSProfile:      s.cfg.DTLSProfile,
		LoggerFactory:    logger.NewPionLoggerFactory(),
		IPv4Only:         true,
		DisableMulticast: true,
	})
	if err != nil {
		return nil, err //nolint:wrapcheck // the shared builder already adds context
	}
	if apply != nil {
		apply(&settings)
	}
	s.mu.Lock()
	ice := s.ice
	s.mu.Unlock()
	if types := iceNetworkTypes(ice); types != nil {
		logger.Infof("mtslink: ICE over %s, as the room link or this side asks", ice)
		settings.SetNetworkTypes(types)
	}
	registry := &interceptor.Registry{}
	if err := webrtc.RegisterDefaultInterceptorsWithOptions(
		media, registry, engine.DefaultInterceptorOptions()...,
	); err != nil {
		return nil, fmt.Errorf("mtslink: interceptors: %w", err)
	}
	return webrtc.NewAPI(
		webrtc.WithMediaEngine(media),
		webrtc.WithSettingEngine(settings),
		webrtc.WithInterceptorRegistry(registry),
	), nil
}

// iceNetworkTypes is what an ICE mode from the room link (roomhint) asks of
// pion, nil for the shared settings' UDP. With TCP among them pion dials the
// SFU's passive TCP candidate itself; the SFU then answers with that one alone
// when the offer has no UDP.
//
// ai-generated: this function.
func iceNetworkTypes(mode string) []webrtc.NetworkType {
	switch mode {
	case roomhint.ICETCP:
		return []webrtc.NetworkType{webrtc.NetworkTypeTCP4}
	case roomhint.ICEBoth:
		return []webrtc.NetworkType{webrtc.NetworkTypeUDP4, webrtc.NetworkTypeTCP4}
	default:
		return nil
	}
}

// buildPeerConnection lays the offer out in the web client's order, less what
// the SFU does not ask for: the published camera, a microphone line that
// stays silent, the one line the SFU plays the room's audio on - it refuses
// an offer with two ("exceeded maximal audio players value 1") - and the
// receive slots. It returns the audio player, whose mid the join names.
func (s *Session) buildPeerConnection() (*webrtc.PeerConnection, *webrtc.RTPTransceiver, error) {
	api, err := s.newAPI()
	if err != nil {
		return nil, nil, err
	}
	pc, err := api.NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		return nil, nil, fmt.Errorf("mtslink: new peer connection: %w", err)
	}

	var addErr error
	s.video.RangeVideoTracks(func(track webrtc.TrackLocal, _ bool) {
		sender, trackErr := pc.AddTrack(track)
		if trackErr != nil {
			addErr = trackErr
			return
		}
		go s.readRTCP(sender)
	})
	if addErr != nil {
		_ = pc.Close()
		return nil, nil, fmt.Errorf("mtslink: add the published track: %w", addErr)
	}

	add := func(kind webrtc.RTPCodecType, direction webrtc.RTPTransceiverDirection) (*webrtc.RTPTransceiver, error) {
		return pc.AddTransceiverFromKind(kind, webrtc.RTPTransceiverInit{Direction: direction})
	}
	if _, err = add(webrtc.RTPCodecTypeAudio, webrtc.RTPTransceiverDirectionSendrecv); err != nil {
		_ = pc.Close()
		return nil, nil, fmt.Errorf("mtslink: microphone line: %w", err)
	}
	player, err := add(webrtc.RTPCodecTypeAudio, webrtc.RTPTransceiverDirectionRecvonly)
	if err != nil {
		_ = pc.Close()
		return nil, nil, fmt.Errorf("mtslink: audio player line: %w", err)
	}
	for range receiveSlots {
		if _, err = add(webrtc.RTPCodecTypeVideo, webrtc.RTPTransceiverDirectionRecvonly); err != nil {
			_ = pc.Close()
			return nil, nil, fmt.Errorf("mtslink: receive slot: %w", err)
		}
	}
	return pc, player, nil
}

// readRTCP reads what the SFU says about the published track until the sender
// closes - which also feeds the interceptors - and passes its keyframe
// requests on.
func (s *Session) readRTCP(sender *webrtc.RTPSender) {
	for {
		packets, _, err := sender.ReadRTCP()
		if err != nil {
			return
		}
		for _, packet := range packets {
			switch packet.(type) {
			case *rtcp.PictureLossIndication, *rtcp.FullIntraRequest:
				if cb := s.keyframe.Load(); cb != nil {
					(*cb)()
				}
			}
		}
	}
}

// Connect joins the room: the offer, the SFU's answer, the pin, and the wait
// for the peer connection to settle.
func (s *Session) Connect(ctx context.Context) error {
	if s.closed.Load() {
		return ErrSessionClosed
	}
	if !s.video.HasVideoTracks() {
		return ErrNoVideoTrack
	}
	client := s.httpClient()
	defer client.CloseIdleConnections()

	pc, player, err := s.buildPeerConnection()
	if err != nil {
		return err
	}
	gen := newGeneration(pc)
	s.watch(gen)

	s.mu.Lock()
	joinURL := s.joinURL
	s.mu.Unlock()

	if err = s.negotiate(ctx, gen, client, joinURL, player); err == nil {
		err = s.awaitConnected(ctx, gen)
	}
	if err != nil {
		gen.teardown()
		s.leave(ctx, client)
		return err
	}
	return nil
}

// watch wires a generation's peer connection to the session.
func (s *Session) watch(gen *generation) {
	gen.pc.OnTrack(func(track *webrtc.TrackRemote, receiver *webrtc.RTPReceiver) {
		logger.Debugf("mtslink: remote track kind=%s codec=%s", track.Kind(), track.Codec().MimeType)
		if handler := s.video.VideoTrackHandler(); handler != nil && track.Kind() == webrtc.RTPCodecTypeVideo {
			handler(track, receiver)
			return
		}
		go drain(track)
	})
	gen.pc.OnConnectionStateChange(func(state webrtc.PeerConnectionState) {
		logger.Debugf("mtslink: peer connection %s", state)
		switch state {
		case webrtc.PeerConnectionStateConnected:
			gen.connectedOnce.Do(func() { close(gen.connected) })
		case webrtc.PeerConnectionStateFailed, webrtc.PeerConnectionStateClosed:
			if !gen.wasConnected() {
				// A join that never connected is Connect's to report; the
				// caller, or the supervisor running it, decides what next.
				gen.failedOnce.Do(func() { close(gen.failed) })
				return
			}
			s.reconnectAttempt(gen)
		case webrtc.PeerConnectionStateNew, webrtc.PeerConnectionStateConnecting,
			webrtc.PeerConnectionStateDisconnected, webrtc.PeerConnectionStateUnknown:
		}
	})
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

// negotiate is the SDP exchange. The offer goes out as soon as it is made,
// without waiting for candidates, as the web client's does: the answer
// carries the SFU's, and ours reach it as peer-reflexive ones.
func (s *Session) negotiate(
	ctx context.Context, gen *generation, client *http.Client, joinURL string, player *webrtc.RTPTransceiver,
) error {
	offer, err := gen.pc.CreateOffer(nil)
	if err != nil {
		return fmt.Errorf("mtslink: offer: %w", err)
	}
	if err = gen.pc.SetLocalDescription(offer); err != nil {
		return fmt.Errorf("mtslink: local description: %w", err)
	}
	target, err := offerURL(joinURL, player.Mid())
	if err != nil {
		return err
	}
	answer, err := joinOffer(ctx, client, target, offer.SDP)
	if err != nil {
		return err
	}
	peer := peerOf(answer)
	if peer == "" {
		return ErrNoPeerID
	}
	if err = gen.pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeAnswer, SDP: answer}); err != nil {
		return fmt.Errorf("mtslink: remote description: %w", err)
	}
	if pinErr := pinPeer(ctx, client, target, peer); pinErr != nil {
		logger.Debugf("mtslink: %v", pinErr)
	}
	return nil
}

// awaitConnected waits for the peer connection to settle and makes the
// generation the session's once it has.
func (s *Session) awaitConnected(ctx context.Context, gen *generation) error {
	timer := time.NewTimer(connectWait)
	defer timer.Stop()
	select {
	case <-gen.connected:
	case <-gen.failed:
		return ErrConnectFailed
	case <-gen.done:
		return ErrSessionClosed
	case <-ctx.Done():
		return fmt.Errorf("mtslink: connect wait: %w", ctx.Err())
	case <-timer.C:
		return ErrConnectTimeout
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed.Load() {
		return ErrSessionClosed
	}
	s.gen = gen
	return nil
}

// leave takes back the conference of a join that came to nothing. The
// service drops the conference of a publisher that was on the SFU and left
// by itself; one whose publisher never arrived stays in the room's list.
//
// The join's own context may be the reason it failed, so the call outlives
// it, for leaveWait at most.
func (s *Session) leave(ctx context.Context, client *http.Client) {
	s.mu.Lock()
	gateway, conference, cookies := s.gateway, s.conferenceID, s.cookies
	s.mu.Unlock()
	ctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), leaveWait)
	defer cancel()
	if err := takeBack(ctx, client, gateway, conference, cookies); err != nil {
		logger.Debugf("mtslink: %v", err)
	}
}

// reconnectAttempt asks the session's supervisor for a rejoin on behalf of
// one join. A failure raised by a join already torn down says nothing about
// the session - Close or a superseding join did it deliberately - so it asks
// nothing.
func (s *Session) reconnectAttempt(gen *generation) {
	if s.closed.Load() || gen.isDone() {
		return
	}
	s.Request(s.closed.Load(), s.reconnecting.Load())
}

// Reconnect asks the session to rejoin the room. Upper layers call it when a
// liveness probe declares the path dead before the engine has noticed; the
// supervisor serializes it with the failure-driven rejoins.
func (s *Session) Reconnect(reason string) {
	if s.closed.Load() {
		return
	}
	logger.Debugf("mtslink: reconnect requested: %s", reason)
	s.Request(s.closed.Load(), s.reconnecting.Load())
}

// reconnect tears the current join down and joins again as a new guest: the
// old peer goes first, so the room never holds two of ours, then the
// provider's fresh credentials, then a join from scratch.
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
	// The transport shakes hands again over the rebuilt carrier when it
	// hears this, the shared contract's successful-reconnect notice.
	s.NotifyReconnect()
	return nil
}

// Send is unsupported: the room relays no data between guests, so the payload
// rides the video transport.
func (s *Session) Send([]byte) error { return ErrSendUnsupported }

// CanSend reports whether the peer connection is connected.
func (s *Session) CanSend() bool {
	s.mu.Lock()
	gen := s.gen
	s.mu.Unlock()
	return gen != nil && !gen.isDone() && gen.pc.ConnectionState() == webrtc.PeerConnectionStateConnected
}

// SubscriberCanSend mirrors CanSend: one peer connection carries both ways.
func (s *Session) SubscriberCanSend() bool { return s.CanSend() }

// GetBufferedAmount is the video transport's to answer, not a data channel's.
func (s *Session) GetBufferedAmount() uint64 { return 0 }

// WatchConnection services the session's reconnect requests until ctx ends
// or the session closes.
func (s *Session) WatchConnection(ctx context.Context) {
	s.Watch(ctx, s.closeCh)
}

// signalEnded ends the session for good: the verdict is reported once,
// through the shared Reconnector's callback, and everything waiting on the
// session - the reconnect supervisor included - stops with it.
func (s *Session) signalEnded(reason string) {
	s.endOnce.Do(func() { s.SignalEnded(reason) })
	s.closed.Store(true)
	s.closeOnce.Do(func() { close(s.closeCh) })
}

// Close leaves the room and frees the peer connection. A closed session
// refuses every later reconnect request and stops the supervisor.
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
