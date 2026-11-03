package salutejazz

import (
	"fmt"
	"strings"
	"time"

	"github.com/pion/interceptor"
	"github.com/pion/webrtc/v4"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/protect"
)

// newWebRTCAPI builds a pion API with IPv4-only ICE and default
// interceptors, over the host's protected networking.
func newWebRTCAPI(resolver protect.Lookup, dtlsProfile engine.DTLSProfile) (*webrtc.API, error) {
	settingEngine := webrtc.SettingEngine{}
	apply, err := engine.NewPionSettings(engine.PionSettingsOptions{
		Resolver:         resolver,
		DTLSProfile:      dtlsProfile,
		LoggerFactory:    logger.NewPionLoggerFactory(),
		IPv4Only:         true,
		ProxyDialer:      true,
		DisableMulticast: true,
	})
	if err != nil {
		return nil, err //nolint:wrapcheck // shared builder already adds protected-net context
	}
	if apply != nil {
		apply(&settingEngine)
	}

	mediaEngine := &webrtc.MediaEngine{}
	if err := mediaEngine.RegisterDefaultCodecs(); err != nil {
		return nil, fmt.Errorf("salutejazz register codecs: %w", err)
	}
	registry := &interceptor.Registry{}
	if err := webrtc.RegisterDefaultInterceptorsWithOptions(
		mediaEngine, registry, engine.DefaultInterceptorOptions()...,
	); err != nil {
		return nil, fmt.Errorf("salutejazz register interceptors: %w", err)
	}
	return webrtc.NewAPI(
		webrtc.WithSettingEngine(settingEngine),
		webrtc.WithMediaEngine(mediaEngine),
		webrtc.WithInterceptorRegistry(registry),
	), nil
}

// newPeerConnection builds one peer connection on the ICE servers rtc:config
// advertised, trickling its candidates under the target it belongs to.
func (s *Session) newPeerConnection(gen *generation, target string) (*webrtc.PeerConnection, error) {
	pc, err := gen.api.NewPeerConnection(webrtc.Configuration{
		ICEServers:   gen.iceServersSnapshot(),
		SDPSemantics: webrtc.SDPSemanticsUnifiedPlan,
	})
	if err != nil {
		return nil, fmt.Errorf("salutejazz new %s peer connection: %w", target, err)
	}
	pc.OnICECandidate(func(candidate *webrtc.ICECandidate) {
		if candidate == nil {
			return
		}
		// A candidate is gathered once the description it belongs to is set,
		// so the fragment this reads is the one this candidate was gathered
		// under.
		if err := s.sendICE(gen, target, localICEUfrag(pc), candidate.ToJSON()); err != nil {
			logger.Debugf("salutejazz: %s candidate: %v", target, err)
		}
	})
	pc.OnConnectionStateChange(func(state webrtc.PeerConnectionState) {
		s.onPeerConnectionState(gen, target, state)
	})
	return pc, nil
}

func (s *Session) onPeerConnectionState(gen *generation, target string, state webrtc.PeerConnectionState) {
	logger.Debugf("salutejazz: %s peer connection %s", target, state)
	connected := state == webrtc.PeerConnectionStateConnected
	if target == targetPublisher {
		gen.pubConnected.Store(connected)
	} else {
		gen.subConnected.Store(connected)
	}
	if state == webrtc.PeerConnectionStateFailed && !s.closed.Load() && gen == s.current() {
		logger.Warnf("salutejazz: %s peer connection failed", target)
		s.queueReconnect()
	}
}

// handleOffer answers the SFU's offer on the subscriber peer connection. The
// SFU creates both data channels on it, and this engine receives on them.
func (s *Session) handleOffer(gen *generation, desc *sdpDescription) error {
	if desc == nil {
		return ErrNoDescription
	}
	pc := gen.subPC.Load()
	if pc == nil {
		var err error
		pc, err = s.newPeerConnection(gen, targetSubscriber)
		if err != nil {
			return err
		}
		pc.OnDataChannel(func(dc *webrtc.DataChannel) { s.wireChannel(gen, dc, false) })
		if !gen.publishPC(targetSubscriber, pc) {
			return ErrSessionClosed
		}
	}
	if err := pc.SetRemoteDescription(webrtc.SessionDescription{
		Type: webrtc.SDPTypeOffer, SDP: desc.SDP,
	}); err != nil {
		return fmt.Errorf("salutejazz subscriber remote description: %w", err)
	}
	gen.drainICE(targetSubscriber)

	answer, err := pc.CreateAnswer(nil)
	if err != nil {
		return fmt.Errorf("salutejazz create answer: %w", err)
	}
	if err := pc.SetLocalDescription(answer); err != nil {
		return fmt.Errorf("salutejazz subscriber local description: %w", err)
	}
	return s.sendMedia(gen, mediaIn{
		Method:      methodAnswer,
		Description: &sdpOut{SDP: answer.SDP, Type: sdpTypeAnswer},
	})
}

// negotiate offers the publisher peer connection once the SFU's own
// channels are up, the order the official client was measured in.
func (s *Session) negotiate(gen *generation) {
	select {
	case <-gen.subReady:
	case <-gen.done:
		return
	case <-s.closeCh:
		return
	}
	if err := s.startPublisher(gen); err != nil {
		logger.Warnf("salutejazz: publisher negotiation: %v", err)
		s.reconnectAttempt(gen)
	}
}

// startPublisher offers the peer connection this session sends on. Data
// written to the channels the SFU created is dropped; only a publisher the
// client offers is relayed, and an offer whose one m-line is the data
// channel is accepted without a track.
func (s *Session) startPublisher(gen *generation) error {
	pc, err := s.newPeerConnection(gen, targetPublisher)
	if err != nil {
		return err
	}
	if !gen.publishPC(targetPublisher, pc) {
		return ErrSessionClosed
	}

	ordered := true
	reliable, err := pc.CreateDataChannel(labelReliable, &webrtc.DataChannelInit{Ordered: &ordered})
	if err != nil {
		return fmt.Errorf("salutejazz create %s channel: %w", labelReliable, err)
	}
	s.wireChannel(gen, reliable, true)

	var noRetransmits uint16
	lossy, err := pc.CreateDataChannel(labelLossy, &webrtc.DataChannelInit{
		Ordered: &ordered, MaxRetransmits: &noRetransmits,
	})
	if err != nil {
		return fmt.Errorf("salutejazz create %s channel: %w", labelLossy, err)
	}
	s.wireChannel(gen, lossy, true)

	offer, err := pc.CreateOffer(nil)
	if err != nil {
		return fmt.Errorf("salutejazz create offer: %w", err)
	}
	if err := pc.SetLocalDescription(offer); err != nil {
		return fmt.Errorf("salutejazz publisher local description: %w", err)
	}
	if err := s.sendMedia(gen, mediaIn{
		Method:      methodOffer,
		Description: &sdpOut{SDP: offer.SDP, Type: sdpTypeOffer},
	}); err != nil {
		return err
	}
	return s.awaitPublisherAnswer(gen, pc)
}

func (s *Session) awaitPublisherAnswer(gen *generation, pc *webrtc.PeerConnection) error {
	timer := time.NewTimer(answerTimeout)
	defer timer.Stop()
	select {
	case sdp := <-gen.answer:
		if err := pc.SetRemoteDescription(webrtc.SessionDescription{
			Type: webrtc.SDPTypeAnswer, SDP: sdp,
		}); err != nil {
			return fmt.Errorf("salutejazz publisher remote description: %w", err)
		}
		gen.drainICE(targetPublisher)
		return nil
	case <-gen.done:
		return ErrSessionClosed
	case <-timer.C:
		return ErrNoAnswer
	}
}

// wireChannel publishes one data channel on the session and reports the lane
// as ready once the reliable one opens. This is the seam between negotiation
// and data: the byte stream sends on the publisher's channels, and only the
// subscriber's carry what the SFU relays back, so only those get a receive
// path (see data.go).
func (s *Session) wireChannel(gen *generation, dc *webrtc.DataChannel, publisher bool) {
	label := dc.Label()
	switch {
	case publisher && label == labelReliable:
		gen.pubRel.Store(dc)
		// The wake for a sender parked over the high-water mark. The
		// threshold is the mark itself, so the callback fires exactly as the
		// queue falls back through it, which is when a parked sender may
		// write again. pion keeps both until the channel opens and applies
		// them then, so arming them here is enough.
		dc.SetBufferedAmountLowThreshold(bufferHighWaterMark)
		dc.OnBufferedAmountLow(gen.openSendWindow)
	case publisher && label == labelLossy:
		gen.pubLossy.Store(dc)
	case label == labelReliable:
		gen.subRel.Store(dc)
	case label == labelLossy:
		gen.subLossy.Store(dc)
	default:
		logger.Debugf("salutejazz: unexpected channel %q", label)
		return
	}
	if !publisher {
		s.receiveOn(gen, dc)
	}
	dc.OnOpen(func() {
		logger.Debugf("salutejazz: channel %q open (publisher=%v)", label, publisher)
		if label != labelReliable {
			return
		}
		if publisher {
			engine.CloseSignal(gen.pubReady)
			return
		}
		engine.CloseSignal(gen.subReady)
	})
}

// sendICE trickles one local candidate, one candidate per frame as the web
// client sends them. The server takes an sdpMid even though it sends none,
// and it is given the ICE fragment the candidate was gathered under, as every
// client frame in the capture carries one.
func (s *Session) sendICE(gen *generation, target, ufrag string, candidate webrtc.ICECandidateInit) error {
	mid := "0"
	if candidate.SDPMid != nil && *candidate.SDPMid != "" {
		mid = *candidate.SDPMid
	}
	var index uint16
	if candidate.SDPMLineIndex != nil {
		index = *candidate.SDPMLineIndex
	}
	out := iceCandidate{
		Candidate:     candidate.Candidate,
		SDPMid:        &mid,
		SDPMLineIndex: &index,
		Target:        target,
	}
	if ufrag == "" && candidate.UsernameFragment != nil {
		ufrag = *candidate.UsernameFragment
	}
	if ufrag != "" {
		out.UsernameFragment = &ufrag
	}
	return s.sendMedia(gen, mediaIn{Method: methodICE, Candidates: []iceCandidate{out}})
}

// localICEUfrag is the ICE username fragment one peer connection gathers
// under. pion leaves it out of ICECandidate.ToJSON - that renders the
// candidate line, the mid and the m-line index and nothing else - so it is
// read from the local description, which is the same statement of it that
// went to the SFU in the offer or the answer.
//
// The transport's own GetLocalParameters is the other way to ask, and it is
// not safe here: it reads the ICE transport's gatherer without the lock that
// ICETransport.Start writes it under, and the race detector catches it
// against the transport starting on a remote description. The description is
// read under the peer connection's lock, and a candidate is never gathered
// before it is set.
//
// A peer connection that has none yet, or is being taken apart, answers with
// nothing: a candidate is worth sending without the fragment rather than not
// at all.
func localICEUfrag(pc *webrtc.PeerConnection) string {
	if pc == nil {
		return ""
	}
	desc := pc.LocalDescription()
	if desc == nil {
		return ""
	}
	return ufragFromSDP(desc.SDP)
}

// ufragFromSDP reads the a=ice-ufrag attribute of a description. Both peer
// connections carry one m-line, the data channel's, so there is one fragment
// in the description and the first is it.
func ufragFromSDP(sdp string) string {
	for line := range strings.Lines(sdp) {
		if rest, found := strings.CutPrefix(strings.TrimSpace(line), "a=ice-ufrag:"); found {
			return strings.TrimSpace(rest)
		}
	}
	return ""
}

// addRemoteICE applies the candidates one rtc:ice frame carries.
func (s *Session) addRemoteICE(gen *generation, candidates []iceCandidate) {
	for _, candidate := range candidates {
		target := candidate.Target
		if target == "" {
			target = targetSubscriber
		}
		if err := gen.addICE(target, candidate.toInit()); err != nil {
			logger.Debugf("salutejazz: %s candidate: %v", target, err)
		}
	}
}

// toInit fills in what the server leaves out: its candidates arrive with an
// sdpMLineIndex and no sdpMid, and pion wants at least one of them.
func (c iceCandidate) toInit() webrtc.ICECandidateInit {
	mid := "0"
	if c.SDPMid != nil && *c.SDPMid != "" {
		mid = *c.SDPMid
	}
	var index uint16
	if c.SDPMLineIndex != nil {
		index = *c.SDPMLineIndex
	}
	init := webrtc.ICECandidateInit{Candidate: c.Candidate, SDPMid: &mid, SDPMLineIndex: &index}
	if c.UsernameFragment != nil && *c.UsernameFragment != "" {
		init.UsernameFragment = c.UsernameFragment
	}
	return init
}

// applyICEConfig stores what rtc:config advertised, normalised the way every
// engine here normalises an ICE server list.
func (g *generation) applyICEConfig(config *rtcConfiguration) {
	if config == nil {
		return
	}
	servers := make([]webrtc.ICEServer, 0, len(config.ICEServers))
	for _, srv := range config.ICEServers {
		servers = append(servers, webrtc.ICEServer{
			URLs:           srv.URLs,
			Username:       srv.Username,
			Credential:     srv.Credential,
			CredentialType: webrtc.ICECredentialTypePassword,
		})
	}
	normalised := engine.NormaliseICEServers(servers)
	g.iceMu.Lock()
	g.servers = normalised
	g.iceMu.Unlock()
}

func (g *generation) iceServersSnapshot() []webrtc.ICEServer {
	g.iceMu.Lock()
	defer g.iceMu.Unlock()
	return append([]webrtc.ICEServer(nil), g.servers...)
}

// addICE applies one remote candidate, or queues it when the peer connection
// it belongs to has no remote description yet. Holding iceMu across the
// check and the apply is what keeps a candidate from being queued just after
// the queue was drained.
func (g *generation) addICE(target string, candidate webrtc.ICECandidateInit) error {
	g.iceMu.Lock()
	defer g.iceMu.Unlock()
	pc := g.peerConnection(target)
	if pc == nil || pc.RemoteDescription() == nil {
		g.pending[target] = append(g.pending[target], candidate)
		return nil
	}
	if err := pc.AddICECandidate(candidate); err != nil {
		return fmt.Errorf("salutejazz add candidate: %w", err)
	}
	return nil
}

// drainICE applies what was queued for a target, once its peer connection
// has a remote description.
func (g *generation) drainICE(target string) {
	g.iceMu.Lock()
	defer g.iceMu.Unlock()
	pc := g.peerConnection(target)
	queued := g.pending[target]
	g.pending[target] = nil
	if pc == nil {
		return
	}
	for _, candidate := range queued {
		if err := pc.AddICECandidate(candidate); err != nil {
			logger.Debugf("salutejazz: queued %s candidate: %v", target, err)
		}
	}
}

func (g *generation) peerConnection(target string) *webrtc.PeerConnection {
	if target == targetPublisher {
		return g.pubPC.Load()
	}
	return g.subPC.Load()
}
