package jitsi

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"fmt"
	"strconv"
	"time"

	pioninterceptor "github.com/pion/interceptor"
	"github.com/pion/interceptor/pkg/nack"
	"github.com/pion/interceptor/pkg/twcc"
	"github.com/pion/webrtc/v4"
	"github.com/zarazaex69/j"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/protect"
	"github.com/openlibrecommunity/olcrtc/internal/testhooks"
)

// waitForJingle waits for Jicofo's session-initiate after a peer joins.
func (s *Session) waitForJingle() {
	jSess := s.jSess.Load()
	if jSess == nil {
		return
	}

	stanza, err := jSess.Conn.WaitJingle(s.runCtx)
	if err != nil {
		if s.closed.Load() || s.runCtx.Err() != nil {
			return
		}
		logger.Warnf("jitsi: wait jingle failed: %v", err)
		s.requestReconnect("wait jingle failed: " + err.Error())
		return
	}
	_ = stanza // completeJingleSetup reads the cached stanza through j.Session.

	if err := s.completeJingleSetup(s.runCtx, jSess); err != nil {
		if !s.closed.Load() {
			logger.Warnf("jitsi: jingle setup failed: %v", err)
			s.requestReconnect("jingle setup failed")
		}
	}
}

func (s *Session) completeJingleSetup(ctx context.Context, jSess *j.Session) error {
	logger.Infof("jitsi: session-initiate received; colibri-ws=%s", jSess.ColibriWS)

	needBridge := s.onData != nil || s.onPeerData != nil
	wantVideo := s.shouldRequestVideo()
	sctpBridge := (needBridge || wantVideo) && jSess.ColibriWS == ""
	// The receiver constraints JVB needs before it forwards any video now
	// go with every bridge this session opens; see openBridge.

	// ai-generated: this call. A test build (olcrtc_testhooks) may hold either
	// bridge back here for the gate's late-server scenario; empty otherwise.
	testhooks.BeforeBridgeOpen()

	if (needBridge || wantVideo) && !sctpBridge {
		if err := s.openBridgeWS(ctx, jSess); err != nil {
			return err
		}
	}
	if s.shouldNegotiatePC(needBridge) {
		if err := s.negotiatePC(ctx, jSess, sctpBridge); err != nil {
			return err
		}
	}
	if sctpBridge {
		if err := s.openBridgeSCTP(ctx, jSess); err != nil {
			return err
		}
	}

	s.goLaunch(s.recvLoop)
	s.goLaunch(func() { s.announceEpoch(needBridge) })
	return nil
}

func (s *Session) announceEpoch(needBridge bool) {
	if !needBridge {
		return
	}
	const (
		interval = 200 * time.Millisecond
		attempts = 25
	)
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for range attempts {
		if s.closed.Load() || s.peerEpoch.Load() != 0 {
			return
		}
		if err := s.Send(nil); err != nil {
			logger.Debugf("jitsi: epoch announce failed: %v", err)
		}
		select {
		case <-s.done:
			return
		case <-ticker.C:
		}
	}
}

func (s *Session) shouldNegotiatePC(needBridge bool) bool {
	return needBridge || s.shouldRequestVideo()
}

func (s *Session) shouldRequestVideo() bool {
	return s.WantsVideo()
}

// newSettingEngine builds the pion settings shared with the other engines.
func newSettingEngine(resolver protect.Lookup, dtlsProfile engine.DTLSProfile) (webrtc.SettingEngine, error) {
	settings := webrtc.SettingEngine{}
	apply, err := engine.NewPionSettings(engine.PionSettingsOptions{
		Resolver:         resolver,
		DTLSProfile:      dtlsProfile,
		LoggerFactory:    logger.NewPionLoggerFactory(),
		IPv4Only:         true,
		DisableMulticast: true,
	})
	if err != nil {
		return settings, err //nolint:wrapcheck // shared builder adds protected-net context
	}
	if apply != nil {
		apply(&settings)
	}
	return settings, nil
}

// newConferenceAPI builds the conference PeerConnection API.
//
// ai-generated: the media engine and the interceptor registry.
func newConferenceAPI(resolver protect.Lookup, dtlsProfile engine.DTLSProfile) (*webrtc.API, error) {
	settings, err := newSettingEngine(resolver, dtlsProfile)
	if err != nil {
		return nil, err
	}
	media := &webrtc.MediaEngine{}
	if err = media.RegisterDefaultCodecs(); err != nil {
		return nil, fmt.Errorf("register codecs: %w", err)
	}
	registry, err := newConferenceInterceptors(media)
	if err != nil {
		return nil, err
	}
	return webrtc.NewAPI(
		webrtc.WithSettingEngine(settings),
		webrtc.WithMediaEngine(media),
		webrtc.WithInterceptorRegistry(registry),
	), nil
}

// newConferenceInterceptors is what the conference PeerConnection runs. pion's
// default set stays off: the report ones probe the DTLS transport on a tick
// and flood the log until DTLS is up.
//
// The transport-cc feedback generator is the one JVB cannot do without. Its
// bandwidth estimator for an endpoint runs on that feedback: without any it
// takes the time since its first packet as the round trip, cuts the estimate
// by a fifth every second from 3 s on to its 30 kbps floor, and stops
// forwarding the endpoint any video above that, so a tunnel direction goes
// dark mid-transfer (issue #12). The generator starts on the first tagged
// packet, which cannot arrive before DTLS.
//
// The NACK responder sends again what the bridge says it did not get. On a
// meet.ffmuc.net bridge that lost 5-10% of what each endpoint sent it, JVB
// asked and asked again: 4127 requests in a 75 s run, none of them answered,
// and the frames those packets belonged to never reassembled at the peer
// (issue #14). With the responder the same bridge asked 410 times in 32 s,
// every ask settled by the packet coming back.
//
// ai-generated: the whole function.
func newConferenceInterceptors(media *webrtc.MediaEngine) (*pioninterceptor.Registry, error) {
	registry := &pioninterceptor.Registry{}
	responder, err := nack.NewResponderInterceptor(engine.NackResponderOptions()...)
	if err != nil {
		return nil, fmt.Errorf("nack responder: %w", err)
	}
	registry.Add(responder)
	if err = webrtc.ConfigureTWCCSenderWithOptions(media, registry,
		twcc.WithLoggerFactory(logger.NewPionLoggerFactory())); err != nil {
		return nil, fmt.Errorf("transport-cc feedback: %w", err)
	}
	return registry, nil
}

// negotiatePC applies Jicofo's offer in ordered stages. Trickle draining starts
// before Accept so source-add cannot lose a race with the first RTP packet.
//
//nolint:contextcheck // peer connection lifetime follows runCtx, not negotiation timeout
func (s *Session) negotiatePC(ctx context.Context, jSess *j.Session, sctpBridge bool) error {
	pc, err := s.newConferencePeerConnection(jSess)
	if err != nil {
		return err
	}

	hasLocalTracks, keepaliveTrack, err := s.configureConferenceTracks(pc)
	if err != nil {
		_ = pc.Close()
		return err
	}
	s.installPeerConnectionHandlers(pc)
	if err := s.preparePeerConnection(ctx, jSess, pc, sctpBridge, hasLocalTracks); err != nil {
		_ = pc.Close()
		return err
	}

	pcCtx := s.installPeerConnectionState(pc)
	if keepaliveTrack != nil {
		s.goLaunch(func() { s.rtpKeepalive(pcCtx, keepaliveTrack) })
	}
	return nil
}

func (s *Session) newConferencePeerConnection(jSess *j.Session) (*webrtc.PeerConnection, error) {
	api, err := newConferenceAPI(s.resolver, s.dtlsProfile)
	if err != nil {
		return nil, err
	}
	pcConfig := jSess.IceConfig()
	pcConfig.ICEServers = engine.NormaliseICEServers(pcConfig.ICEServers)
	pcConfig.SDPSemantics = webrtc.SDPSemanticsPlanB
	pc, err := api.NewPeerConnection(pcConfig)
	if err != nil {
		return nil, fmt.Errorf("new pc: %w", err)
	}
	if _, err := pc.AddTransceiverFromKind(
		webrtc.RTPCodecTypeAudio,
		webrtc.RTPTransceiverInit{Direction: webrtc.RTPTransceiverDirectionRecvonly},
	); err != nil {
		_ = pc.Close()
		return nil, fmt.Errorf("add audio recvonly: %w", err)
	}
	return pc, nil
}

func (s *Session) configureConferenceTracks(
	pc *webrtc.PeerConnection,
) (bool, *webrtc.TrackLocalStaticSample, error) {
	hasLocalTracks, err := s.addVideoTransceivers(pc)
	if err != nil {
		return false, nil, err
	}
	keepaliveTrack, err := s.setupVideoMLine(pc, hasLocalTracks)
	if err != nil {
		return false, nil, err
	}
	return hasLocalTracks, keepaliveTrack, nil
}

func (s *Session) installPeerConnectionHandlers(pc *webrtc.PeerConnection) {
	pc.OnTrack(s.handleRemoteTrack)
	pc.OnConnectionStateChange(s.handlePeerConnectionState)
}

func (s *Session) preparePeerConnection(
	ctx context.Context,
	jSess *j.Session,
	pc *webrtc.PeerConnection,
	sctpBridge bool,
	hasLocalTracks bool,
) error {
	neg := jSess.Negotiator()
	neg.PC = pc
	// ai-generated: this call. Every path to a PeerConnection (first join,
	// rejoin, full reconnect) answers the session-initiate cached here.
	s.noteSources(neg.JingleStanza, true)
	neg.OnIceConnectionStateChange = func(state webrtc.ICEConnectionState) {
		logger.Debugf("jitsi ICE state: %s", state)
	}

	trickleCtx, trickleCancel := context.WithCancel(ctx)
	s.setTrickleCancel(trickleCancel)
	stanzas := jSess.LowLevel().Stanzas()
	s.goLaunch(func() { s.trickleDrainLoop(trickleCtx, pc, neg, stanzas) })

	if sctpBridge {
		if err := jSess.PrepareBridgeSCTP(pc); err != nil {
			return fmt.Errorf("prepare bridge sctp: %w", err)
		}
	}
	if err := neg.Accept(ctx); err != nil {
		return fmt.Errorf("session-accept: %w", err)
	}
	if hasLocalTracks {
		if err := neg.SendSourceAddFromSDP(pc.LocalDescription().SDP); err != nil {
			logger.Debugf("jitsi: source-add (initial): %v", err)
		}
	}
	return nil
}

func (s *Session) installPeerConnectionState(pc *webrtc.PeerConnection) context.Context {
	s.pcMu.Lock()
	s.pc = pc
	if s.pcCancel != nil {
		s.pcCancel()
	}
	s.pcCtx, s.pcCancel = context.WithCancel(s.runCtx)
	pcCtx := s.pcCtx
	s.pcMu.Unlock()
	return pcCtx
}

func (s *Session) addVideoTransceivers(pc *webrtc.PeerConnection) (bool, error) {
	var addErr error
	hasLocalTracks := s.RangeVideoTracks(func(track webrtc.TrackLocal, wantsRemote bool) {
		if addErr != nil {
			return
		}
		direction := webrtc.RTPTransceiverDirectionSendonly
		if wantsRemote {
			direction = webrtc.RTPTransceiverDirectionSendrecv
		}
		transceiver, err := pc.AddTransceiverFromTrack(track, webrtc.RTPTransceiverInit{Direction: direction})
		if err != nil {
			addErr = fmt.Errorf("add track: %w", err)
			return
		}
		s.drainSenderRTCP(transceiver.Sender())
	})
	return hasLocalTracks, addErr
}

func (s *Session) setupVideoMLine(
	pc *webrtc.PeerConnection,
	hasLocalTracks bool,
) (*webrtc.TrackLocalStaticSample, error) {
	if !hasLocalTracks {
		return s.addVideoOrKeepaliveTrack(pc)
	}
	return nil, nil //nolint:nilnil // no keepalive needed with local tracks
}

func (s *Session) addVideoOrKeepaliveTrack(
	pc *webrtc.PeerConnection,
) (*webrtc.TrackLocalStaticSample, error) {
	if s.wantsVideoReceive() {
		if _, err := pc.AddTransceiverFromKind(
			webrtc.RTPCodecTypeVideo,
			webrtc.RTPTransceiverInit{Direction: webrtc.RTPTransceiverDirectionRecvonly},
		); err != nil {
			return nil, fmt.Errorf("add video recvonly: %w", err)
		}
		return nil, nil //nolint:nilnil // nil signals no keepalive track
	}
	keepaliveTrack, err := newKeepaliveTrack()
	if err != nil {
		return nil, fmt.Errorf("create keepalive track: %w", err)
	}
	sender, err := pc.AddTrack(keepaliveTrack)
	if err != nil {
		return nil, fmt.Errorf("add keepalive track: %w", err)
	}
	s.drainSenderRTCP(sender)
	return keepaliveTrack, nil
}

// drainSenderRTCP reads and discards the RTCP the bridge sends about one of
// our tracks. The read is what drives the interceptor chain: without it the
// NACK responder never sees a retransmission request.
//
// ai-generated: the whole function (olcrtc#14).
func (s *Session) drainSenderRTCP(sender *webrtc.RTPSender) {
	if sender == nil {
		return
	}
	s.goLaunch(func() { engine.DrainRTCP(sender) })
}

func (s *Session) wantsVideoReceive() bool {
	return s.VideoTrackHandler() != nil
}

func newKeepaliveTrack() (*webrtc.TrackLocalStaticSample, error) {
	track, err := webrtc.NewTrackLocalStaticSample(
		webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeVP8, ClockRate: 90000},
		"jitsi-ka-"+randomTrackSuffix(),
		"olcrtc-ka-"+randomTrackSuffix(),
	)
	if err != nil {
		return nil, fmt.Errorf("new keepalive track: %w", err)
	}
	return track, nil
}

func randomTrackSuffix() string {
	var b [6]byte
	if _, err := rand.Read(b[:]); err != nil {
		return strconv.FormatInt(time.Now().UnixNano(), 10)
	}
	return base64.RawURLEncoding.EncodeToString(b[:])
}

func drainTrack(track *webrtc.TrackRemote) {
	buf := make([]byte, 1500)
	for {
		if _, _, err := track.Read(buf); err != nil {
			return
		}
	}
}

// handleRemoteTrack hands the peer's video to the carrier and drains every
// other track the bridge forwards. Draining is not only about the receiver
// buffer: the transport-cc generator records what is read off a track, so a
// track nobody reads is reported to JVB as lost end to end, and with the
// audio track alone unread half of what the bridge sent came back marked
// lost.
//
// ai-generated: draining the tracks with no reader (issue #12 review).
func (s *Session) handleRemoteTrack(track *webrtc.TrackRemote, recv *webrtc.RTPReceiver) {
	cb := s.VideoTrackHandler()
	if track.Kind() != webrtc.RTPCodecTypeVideo || cb == nil {
		go drainTrack(track)
		return
	}
	ssrc := uint32(track.SSRC())
	// ai-generated: the bridge check and the latch. The bridge's bandwidth
	// probes arrive on a video source of its own, sometimes ahead of the
	// peer's first packet: latching that one would drain the peer's
	// stream. The latch follows the peer that rejoins under a new source.
	if s.isBridgeSSRC(ssrc) || !s.latchPeerVideo(ssrc) {
		go drainTrack(track)
		return
	}
	cb(track, recv)
}

func (s *Session) handlePeerConnectionState(state webrtc.PeerConnectionState) {
	logger.Debugf("jitsi pc state: %s", state.String())
	if state == webrtc.PeerConnectionStateFailed && !s.closed.Load() {
		s.requestReconnect("jitsi peer connection failed")
	}
}

// teardownPC cancels PC-bound goroutines before closing the connection.
func (s *Session) teardownPC() {
	s.pcMu.Lock()
	oldPC := s.pc
	s.pc = nil
	pcCancel := s.pcCancel
	s.pcCancel = nil
	s.pcCtx = nil
	trickleCancel := s.trickleCancel
	s.trickleCancel = nil
	s.pcMu.Unlock()
	if pcCancel != nil {
		pcCancel()
	}
	if trickleCancel != nil {
		trickleCancel()
	}
	if oldPC != nil {
		_ = oldPC.Close()
	}
}

func (s *Session) setTrickleCancel(cancel context.CancelFunc) {
	s.pcMu.Lock()
	prev := s.trickleCancel
	s.trickleCancel = cancel
	s.pcMu.Unlock()
	if prev != nil {
		prev()
	}
}
