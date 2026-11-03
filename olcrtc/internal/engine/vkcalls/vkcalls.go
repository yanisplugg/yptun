package vkcalls

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/pion/webrtc/v4"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
	"github.com/openlibrecommunity/olcrtc/internal/logger"
)

// Session errors.
var (
	ErrSessionClosed   = errors.New("vkcalls: session closed")
	ErrNoEndpoint      = errors.New("vkcalls: signaling endpoint missing")
	ErrOfferTimeout    = errors.New("vkcalls: no producer offer arrived")
	ErrConnectTimeout  = errors.New("vkcalls: peer connection did not connect")
	ErrSendUnsupported = errors.New("vkcalls: send requires the video transport")
)

const (
	offerWait     = 30 * time.Second
	connectWait   = 30 * time.Second
	commandSeqMod = 1 << 20
	// maxReconnects bounds the supervisor's rejoin attempts before the
	// session ends with the limit reason.
	maxReconnects = 10

	// perfStatInterval is the SDK's statisticsInterval: how often the client
	// reports decoded frames to the SFU.
	perfStatInterval = 5 * time.Second
)

// referenceCapabilities is the allocate-consumer feature report: only what
// this engine implements. The SDK's own mask is a diagnostic reference, not a
// constant to copy (spec 7.4).
func referenceCapabilities() map[string]any {
	return map[string]any{
		"estimatedPerformanceIndex": 0, "audioMix": true, "consumerUpdate": true,
		"producerNotificationDataChannelVersion": 8, "producerCommandDataChannelVersion": 3,
		"consumerScreenDataChannelVersion": 1, "producerScreenDataChannelVersion": 1,
		"asrDataChannelVersion": 1, "animojiDataChannelVersion": 2, "animojiBackendRender": true,
		"onDemandTracks": true, "unifiedPlan": true, "singleSession": true, "videoTracksCount": 36,
		"red": true, "audioShare": true, "fastScreenShare": true, "videoSuspend": false,
		"simulcast": true, "simulcastNativeOrder": true, "consumerFastScreenShare": false,
		"consumerFastScreenShareQualityOnDemand": false, "transparentAudio": false,
	}
}

// Session is an engine.Session over one VK Calls SERVER conversation. Payload
// rides the video track (the videochannel transport on top); the SFU bridges
// no arbitrary data channel, so Send reports unsupported.
type Session struct {
	cfg      engine.Config
	endpoint string
	peerID   string
	iceJSON  string

	video engine.VideoTrackState

	engine.Reconnector

	mu           sync.Mutex
	gen          *generation
	encodings    []webrtc.RTPEncodingParameters // EXPERIMENT: the publish encodings
	closed       atomic.Bool
	reconnecting atomic.Bool
	closeCh      chan struct{}
	closeOnce    sync.Once
	endOnce      sync.Once
}

// generation bundles everything one connection attempt owns, so closing it
// tears the whole attempt down (spec 7.3: state never crosses generations).
type generation struct {
	signal  *signalingClient
	pc      *webrtc.PeerConnection
	command *webrtc.DataChannel
	notify  *webrtc.DataChannel
	shape   *ShapeState
	// view is what Pion's views of this connection's offers settled (#72).
	// ai-generated: this field.
	view *viewMemo

	registryMu sync.Mutex
	registry   Registry
	// participants are the others in the conversation, whose cameras the
	// layout asks for (by string key until the registry names them).
	participants map[string]bool
	commandSeq   int
	sessionID    string
	connected    chan struct{}
	done         chan struct{}
	doneOnce     sync.Once
}

// isDone says whether this attempt was torn down: a failure it raised after
// that says nothing about the session (see reconnectAttempt).

// New builds a VK Calls session from provider credentials.
func New(_ context.Context, cfg engine.Config) (engine.Session, error) {
	if cfg.URL == "" {
		return nil, ErrNoEndpoint
	}
	s := &Session{
		cfg: cfg, endpoint: cfg.URL,
		peerID: cfg.Extra["peer_id_hint"], iceJSON: cfg.Extra["ice_servers"],
		closeCh: make(chan struct{}),
	}
	s.Configure(engine.ReconnectorConfig{
		MaxAttempts: maxReconnects,
		Reconnect:   s.reconnect,
		OnError:     func(err error) { logger.Debugf("vkcalls: reconnect failed: %v", err) },
		OnLimit:     func(string) { s.signalEnded("reconnect limit reached") },
		LimitReason: "reconnect limit reached",
	})
	return s, nil
}

// AddVideoTrack records a local track for the next connection.
func (s *Session) AddVideoTrack(track webrtc.TrackLocal) error { //nolint:unparam // the VideoTrackCapable contract
	s.video.StoreVideoTrack(track)
	return nil
}

// SetVideoTrackHandler registers the remote-track callback.
func (s *Session) SetVideoTrackHandler(cb func(*webrtc.TrackRemote, *webrtc.RTPReceiver)) {
	s.video.SetVideoTrackHandler(cb)
}

// Connect joins the conversation and brings the bundled peer connection to
// the connected state with an active media subscription.
func (s *Session) Connect(ctx context.Context) error {
	if s.closed.Load() {
		return ErrSessionClosed
	}

	// A fresh join carries no peer id: the hint names this client only to a
	// retry join (tgt=retry), which the reconnect path issues.
	signal, err := dialSignaling(ctx, s.endpoint, s.cfg.Resolver)
	if err != nil {
		return err
	}
	connection, err := s.waitConnected(ctx, signal)
	if err != nil {
		signal.close()
		return err
	}
	if _, err := signal.command(ctx, "allocate-consumer",
		map[string]any{"capabilities": referenceCapabilities()}); err != nil {
		signal.close()
		return err
	}
	if _, err := signal.command(ctx, "update-media-modifiers", map[string]any{
		"mediaModifiers": map[string]any{"denoise": true, "denoiseAnn": true},
	}); err != nil {
		signal.close()
		return err
	}

	gen := &generation{
		signal:    signal,
		shape:     NewShapeState(),
		registry:  Registry{},
		connected: make(chan struct{}),
		done:      make(chan struct{}),
	}
	offerNote := waitOffer(ctx, signal)
	if offerNote.Description == "" {
		signal.close()
		return ErrOfferTimeout
	}
	gen.sessionID = offerNote.SessionID
	if _, err := signal.command(ctx, "change-media-settings", map[string]any{
		"mediaSettings": map[string]any{"isAudioEnabled": false, "isVideoEnabled": false,
			"isScreenSharingEnabled": false, "isFastScreenSharingEnabled": false,
			"isAudioSharingEnabled": false, "isAnimojiEnabled": true},
	}); err != nil {
		signal.close()
		return err
	}
	if err := s.negotiate(ctx, gen, offerNote.Description); err != nil {
		gen.teardown()
		return err
	}

	s.mu.Lock()
	s.gen = gen
	s.mu.Unlock()
	go gen.perfStatLoop()
	go s.run(gen, connection) //nolint:contextcheck // the session owns its post-connect lifetime
	return nil
}

// waitConnected waits for the connection notification and, when the room
// opened outside SERVER topology, keeps waiting while recruiting the
// signaling-only guests that flip a fresh room - two participants alone
// never switch it, a third simultaneous one does, and SERVER sticks after
// that (see recruitGuests).
func (s *Session) waitConnected(ctx context.Context, signal *signalingClient) (notification, error) {
	connection, err := signal.awaitJoin(ctx)
	if err != nil {
		return notification{}, err
	}
	direct := connection.Conversation == nil || connection.Conversation.Topology != topologyServer
	if !direct {
		return connection, nil
	}
	flipDone := make(chan struct{})
	go s.recruitGuests(ctx, flipDone)
	defer close(flipDone)
	if err := signal.waitServerTopology(ctx, connection); err != nil {
		return notification{}, err
	}
	return connection, nil
}

// negotiate answers one producer offer: the bundled peer connection mirrors
// the offer's codecs, the shaped answer passes the transport gate and
// accept-producer completes the session.
func (s *Session) negotiate(ctx context.Context, gen *generation, offer string) error { //nolint:gocyclo,cyclop
	// Pion negotiates its own view of the offer, one meaning per payload
	// type (#72); the SFU is answered from the real one.
	// ai-generated: this block and the view passed below.
	gen.view = &viewMemo{}
	view, err := pionView(offer, s.publishCodecs(), gen.view)
	if err != nil {
		return err
	}
	api, err := newWebRTCAPI(view, s.cfg, s.cfg.Resolver)
	if err != nil {
		return err
	}
	pc, err := api.NewPeerConnection(webrtc.Configuration{ICEServers: iceServersOf(s.iceJSON)})
	if err != nil {
		return fmt.Errorf("vkcalls: new peer connection: %w", err)
	}
	gen.pc = pc

	// The remote description is set before tracks attach: AddTrack then
	// reuses the offer's publish transceiver instead of opening a fresh one
	// that would pair with a receive-only section (the worker's proven order).
	if err := pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeOffer, SDP: view}); err != nil {
		return fmt.Errorf("vkcalls: set remote description: %w", err)
	}
	// The publish slot carries the video transport's track; its SSRC lands in
	// the native answer and the shaper declares it to the SFU.
	// The service channels are created before the tracks, in the SDK's
	// order (channel ids 0,2,4,… before the media sections' SSRCs).
	if err := s.openControlChannels(gen, pc); err != nil {
		return err
	}
	senders := map[string]*webrtc.RTPSender{}
	s.video.RangeVideoTracks(func(track webrtc.TrackLocal, _ bool) {
		// EXPERIMENT: a track with a RID joins the sender of its track id as
		// one more simulcast encoding.
		if base, ok := senders[track.ID()]; ok && track.RID() != "" {
			if err := base.AddEncoding(track); err != nil {
				logger.Debugf("vkcalls: add encoding %s: %v", track.RID(), err)
			}
			return
		}
		sender, err := pc.AddTrack(track)
		if err != nil {
			logger.Debugf("vkcalls: add track: %v", err)
			return
		}
		senders[track.ID()] = sender
		go drainRTCP(sender)
	})
	s.mu.Lock()
	s.encodings = nil
	s.mu.Unlock()
	for _, sender := range senders {
		for _, enc := range sender.GetParameters().Encodings {
			if enc.RID != "" {
				logger.Debugf("vkcalls: encoding rid=%s ssrc=%d", enc.RID, enc.SSRC)
			}
			s.mu.Lock()
			s.encodings = append(s.encodings, enc)
			s.mu.Unlock()
		}
	}
	pc.OnTrack(func(track *webrtc.TrackRemote, receiver *webrtc.RTPReceiver) {
		logger.Debugf("vkcalls: remote track kind=%s codec=%s ssrc=%d", track.Kind(), track.Codec().MimeType, track.SSRC())
		if handler := s.video.VideoTrackHandler(); handler != nil {
			handler(track, receiver)
		}
	})
	pc.OnConnectionStateChange(func(state webrtc.PeerConnectionState) {
		logger.Debugf("vkcalls: peer connection %s", state)
		switch state {
		case webrtc.PeerConnectionStateConnected:
			select {
			case <-gen.connected:
			default:
				close(gen.connected)
			}
		case webrtc.PeerConnectionStateFailed, webrtc.PeerConnectionStateClosed:
			s.reconnectAttempt(gen)
		case webrtc.PeerConnectionStateNew, webrtc.PeerConnectionStateConnecting,
			webrtc.PeerConnectionStateDisconnected, webrtc.PeerConnectionStateUnknown:
		}
	})

	return s.acceptOffer(ctx, gen, offer)
}

// publishCodecs lists the encoding names of the local video tracks' codecs
// ("vp8"): the payload types Pion must keep in the publish slot's numbering.
//
// ai-generated: this function (#72).
func (s *Session) publishCodecs() []string {
	var out []string
	s.video.RangeVideoTracks(func(track webrtc.TrackLocal, _ bool) {
		if coded, ok := track.(interface {
			Codec() webrtc.RTPCodecCapability
		}); ok {
			out = append(out, strings.ToLower(strings.TrimPrefix(coded.Codec().MimeType, "video/")))
		}
	})
	return out
}

// openControlChannels creates the two service data channels the engine
// speaks: producerCommand carries the layout subscription, and
// producerNotification delivers the stream registry.
func (s *Session) openControlChannels(gen *generation, pc *webrtc.PeerConnection) error {
	ordered := true
	notify, err := pc.CreateDataChannel("producerNotification", &webrtc.DataChannelInit{Ordered: &ordered})
	if err != nil {
		return fmt.Errorf("vkcalls: producer notification channel: %w", err)
	}
	gen.notify = notify
	command, err := pc.CreateDataChannel("producerCommand", &webrtc.DataChannelInit{Ordered: &ordered})
	if err != nil {
		return fmt.Errorf("vkcalls: producer command channel: %w", err)
	}
	gen.command = command
	// The participants known at connect, and a registry that arrived first,
	// are subscribed as soon as the channel can carry the layout.
	command.OnOpen(func() {
		// The SDK configures its encodings right after accept-producer
		// (sync({force:true})): without this the SFU accepts the stream but
		// never forwards it, and the command is fire-and-forget, so the
		// mistake is silent. The engine publishes one layer: l at the
		// transport's small frame. Bitrate is written in kbit/s.
		{
			gen.registryMu.Lock()
			gen.commandSeq++
			seq := gen.commandSeq
			gen.registryMu.Unlock()
			w, h, fps, kbps := layerProfile()
			frame := encodeChangeSimulcast(seq, "l", w, h, fps, kbps*1000)
			logger.Debugf("vkcalls: change-simulcast %x: %v", frame, command.Send(frame))
		}
		gen.subscribeAll()
	})
	command.OnMessage(func(message webrtc.DataChannelMessage) {
		logger.Debugf("vkcalls: command reply %x", message.Data)
	})

	notify.OnMessage(func(message webrtc.DataChannelMessage) {
		s.onServiceFrame(gen, message.Data)
	})
	return nil
}

// acceptOffer shapes the answer for the current offer and completes the
// accept-producer handshake.
func (s *Session) acceptOffer(ctx context.Context, gen *generation, offer string) error {
	native, err := gen.pc.CreateAnswer(nil)
	if err != nil {
		return fmt.Errorf("vkcalls: create answer: %w", err)
	}
	if localErr := gen.pc.SetLocalDescription(native); localErr != nil {
		return fmt.Errorf("vkcalls: set local description: %w", localErr)
	}
	shaped, err := ShapeAnswer(offer, native.SDP, gen.shape)
	if err != nil {
		return err
	}
	if _, err := gen.signal.command(ctx, "accept-producer", acceptPayload(offer, shaped, gen.sessionID)); err != nil {
		return err
	}
	select {
	case <-gen.connected:
		return nil
	case <-time.After(connectWait):
		return ErrConnectTimeout
	case <-ctx.Done():
		return fmt.Errorf("vkcalls: connect wait: %w", ctx.Err())
	}
}

// acceptPayload builds the accept-producer command body: the shaped answer,
// the session the offer belonged to and the offer's SSRC attributions.
func acceptPayload(offer, shaped, sessionID string) map[string]any {
	ssrcs := remoteSSRCs(offer)
	ids := make([]any, 0, len(ssrcs))
	for _, ssrc := range ssrcs {
		ids = append(ids, ssrc)
	}
	return map[string]any{fieldDescription: shaped, "sessionId": sessionID, "ssrcs": ids}
}

// waitOffer returns the first producer-updated notification, or an empty one
// on timeout.
func waitOffer(ctx context.Context, signal *signalingClient) notification {
	timer := time.NewTimer(offerWait)
	defer timer.Stop()
	for {
		select {
		case note := <-signal.Notifications():
			if note.Notification == "producer-updated" && note.Description != "" {
				return note
			}
		case <-timer.C:
			return notification{}
		case <-signal.Closed():
			return notification{}
		case <-ctx.Done():
			return notification{}
		}
	}
}

// run owns the session after a successful connect: renegotiations and the
// signaling socket's lifetime.
func (s *Session) run(gen *generation, connection notification) {
	others := participantIDs(connection, gen.signal.Self())
	logger.Debugf("vkcalls: connected as <%s>, %d other participants", idTag(gen.signal.Self()), len(others))
	for _, id := range others {
		gen.noteParticipant(id)
	}
	gen.subscribeAll()
	for {
		select {
		case note := <-gen.signal.Notifications():
			switch note.Notification {
			case "participant-joined", "media-settings-changed":
				// A camera may appear with either: ask for it by key.
				if id, err := decimalID(note.ParticipantID); err == nil && id != gen.signal.Self() && gen.noteParticipant(id) {
					gen.subscribeAll()
				}
			case "producer-updated":
				if note.Description == "" || note.SessionID == gen.sessionID {
					continue
				}
				gen.sessionID = note.SessionID
				if err := s.reanswer(gen, note.Description); err != nil {
					logger.Debugf("vkcalls: renegotiation: %v", err)
					s.reconnectAttempt(gen)
				}
			case "hungup":
				if id, err := decimalID(note.ParticipantID); err == nil {
					gen.removeStream(id)
				}
			}
		case <-gen.signal.Closed():
			// The supervisor owns the verdict: a rejoin is asked for, and the
			// session only ends through its limit or the caller's policy.
			s.reconnectAttempt(gen)
			return
		}
	}
}

// reanswer handles a renegotiation offer on the established transport: same
// shaping and accept handshake, no second connect wait.
func (s *Session) reanswer(gen *generation, offer string) error {
	if gen.pc == nil {
		return ErrSessionClosed
	}
	offer = strings.ReplaceAll(offer, "\r\n", "\n")
	// ai-generated: this block, Pion's view of the renegotiation offer (#72).
	view, err := pionView(offer, s.publishCodecs(), gen.view)
	if err != nil {
		return err
	}
	remote := webrtc.SessionDescription{Type: webrtc.SDPTypeOffer, SDP: view}
	if srdErr := gen.pc.SetRemoteDescription(remote); srdErr != nil {
		return fmt.Errorf("vkcalls: renegotiation remote: %w", srdErr)
	}
	native, err := gen.pc.CreateAnswer(nil)
	if err != nil {
		return fmt.Errorf("vkcalls: renegotiation answer: %w", err)
	}
	if localErr := gen.pc.SetLocalDescription(native); localErr != nil {
		return fmt.Errorf("vkcalls: renegotiation local: %w", localErr)
	}
	shaped, err := ShapeAnswer(offer, native.SDP, gen.shape)
	if err != nil {
		return err
	}
	_, err = gen.signal.command(context.Background(), "accept-producer",
		acceptPayload(offer, shaped, gen.sessionID))
	return err
}

// onServiceFrame folds producerNotification registry frames into the stream
// registry and re-subscribes to everything the SFU offers us.
func (s *Session) onServiceFrame(gen *generation, data []byte) {
	if len(data) > 0 && data[0] != registryKind && data[0] != 6 && len(data) <= 48 {
		logger.Debugf("vkcalls: service frame kind=%d hex %x", data[0], data)
	}
	entries, err := ParseRegistry(data)
	if errors.Is(err, ErrNotRegistry) {
		return
	}
	if err != nil {
		logger.Debugf("vkcalls: registry frame: %v", err)
		return
	}
	if len(entries) == 0 {
		return
	}
	gen.registryMu.Lock()
	for key, id := range entries {
		gen.registry[key] = id
		logger.Debugf("vkcalls: registry %s -> %d", streamKind(key), id)
	}
	gen.registryMu.Unlock()
	gen.subscribeAll()
}

// removeStream drops a hung-up participant's stream from the subscription.
// Registry keys are typed with a participant-type prefix ("u" for USER)
// before the numeric id.
func (gen *generation) removeStream(participant string) {
	prefixes := []string{"u" + participant + ":", participant + ":"}
	gen.registryMu.Lock()
	delete(gen.participants, participant)
	delete(gen.registry, "u"+participant)
	for key := range gen.registry {
		for _, prefix := range prefixes {
			if len(key) > len(prefix) && key[:len(prefix)] == prefix {
				delete(gen.registry, key)
			}
		}
	}
	gen.registryMu.Unlock()
}

// nextSeq takes the next producerCommand sequence number; every command on
// that channel shares one counter, as in the SDK.
func (gen *generation) nextSeq() int {
	gen.registryMu.Lock()
	defer gen.registryMu.Unlock()
	gen.commandSeq++
	return gen.commandSeq % commandSeqMod
}

// perfStatLoop reports the consumer's decoded frames every five seconds, the
// SDK's statisticsInterval: the SFU's consumer-leg liveness depends on these
// reports (spike tun-rr-01: without them the forward stalls within a minute).
func (gen *generation) perfStatLoop() {
	ticker := time.NewTicker(perfStatInterval)
	defer ticker.Stop()
	for {
		select {
		case <-gen.done:
			return
		case <-ticker.C:
		}
		if gen.pc == nil || gen.command == nil || gen.command.ReadyState() != webrtc.DataChannelStateOpen {
			continue
		}
		// Pion never populates FramesDecoded (nothing in the stack decodes),
		// so the honest progression signal is received video packets — the
		// report exists to prove the consumer consumes, and packet counts do.
		var received uint32
		for _, entry := range gen.pc.GetStats() {
			inbound, ok := entry.(webrtc.InboundRTPStreamStats)
			if !ok || inbound.Kind != kindVideo {
				continue
			}
			received += inbound.PacketsReceived
		}
		frame := encodePerfStatReport(gen.nextSeq(), received, received)
		if err := gen.command.Send(frame); err != nil {
			logger.Debugf("vkcalls: perf stat: %v", err)
		}
	}
}

// subscribeAll sends the update-display-layout producer command for every
// registry stream. The SFU forwards exactly what the layout lists; without
// it nothing flows (spike hybrid-03…11).
func (gen *generation) subscribeAll() {
	gen.registryMu.Lock()
	entries := layoutEntries(gen.registry, gen.participantList())
	gen.commandSeq++
	sequence := gen.commandSeq % commandSeqMod
	gen.registryMu.Unlock()
	if len(entries) == 0 {
		return
	}
	frame, err := EncodeLayout(sequence, entries)
	if err != nil {
		logger.Debugf("vkcalls: layout encode: %v", err)
		return
	}
	if gen.command == nil || gen.command.ReadyState() != webrtc.DataChannelStateOpen {
		return
	}
	if err := gen.command.Send(frame); err != nil {
		logger.Debugf("vkcalls: layout send: %v", err)
		return
	}
	logger.Debugf("vkcalls: layout %d sent for %d streams", sequence, len(entries))
}

// noteParticipant records another participant and says whether it is new.
func (gen *generation) noteParticipant(id string) bool {
	gen.registryMu.Lock()
	defer gen.registryMu.Unlock()
	if gen.participants[id] {
		return false
	}
	if gen.participants == nil {
		gen.participants = map[string]bool{}
	}
	gen.participants[id] = true
	return true
}

// participantList is the participants noted so far; the caller holds
// registryMu.
func (gen *generation) participantList() []string {
	out := make([]string, 0, len(gen.participants))
	for id := range gen.participants {
		out = append(out, id)
	}
	return out
}

// reconnectAttempt asks the session's supervisor for a rejoin on behalf of
// one connection attempt. A failure raised by an attempt already torn down
// says nothing about the session - Close, a superseding attempt or a giving-up
// Connect did it deliberately - so it asks nothing. The supervisor coalesces
// repeats and consults the caller's own policy (SetShouldReconnect).
func (s *Session) reconnectAttempt(gen *generation) {
	if s.closed.Load() || gen.isDone() {
		return
	}
	s.Request(s.closed.Load(), s.reconnecting.Load())
}

func (gen *generation) isDone() bool {
	select {
	case <-gen.done:
		return true
	default:
		return false
	}
}

func (gen *generation) stop() {
	if gen.done == nil {
		return
	}
	gen.doneOnce.Do(func() { close(gen.done) })
}

func (gen *generation) teardown() {
	gen.stop()
	if gen.pc != nil {
		_ = gen.pc.Close()
	}
	if gen.signal != nil {
		gen.signal.close()
	}
}

// Reconnect asks the session to rejoin the room. Upper layers call it when a
// liveness probe declares the path dead before the engine has noticed; the
// supervisor serializes it with the failure-driven rejoins.
func (s *Session) Reconnect(reason string) {
	if s.closed.Load() {
		return
	}
	logger.Debugf("vkcalls: reconnect requested: %s", reason)
	s.Request(s.closed.Load(), s.reconnecting.Load())
}

// reconnect tears the current attempt down and joins again. The old attempt
// goes whole - signaling and the bundled peer connection - so the SFU never
// carries two guests of ours; then the provider's fresh credentials, then a
// join from scratch (spec 7.3: state never crosses generations).
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
		s.mu.Lock()
		s.endpoint = creds.URL
		s.peerID = creds.Extra["peer_id_hint"]
		s.iceJSON = creds.Extra["ice_servers"]
		s.mu.Unlock()
	}
	if err := s.Connect(ctx); err != nil {
		return err
	}
	// The transport re-handshakes the tunnel over the rebuilt carrier when
	// it hears this, the shared contract's successful-reconnect notice.
	s.NotifyReconnect()
	return nil
}

// Encodings (EXPERIMENT) lists the publish sender's encodings.
func (s *Session) Encodings() []webrtc.RTPEncodingParameters {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]webrtc.RTPEncodingParameters(nil), s.encodings...)
}

// Send is unsupported: the SFU bridges only its known service channels, so
// payload rides the video transport.
func (s *Session) Send([]byte) error { return ErrSendUnsupported }

// CanSend reports whether the bundled peer connection is connected.
func (s *Session) CanSend() bool {
	s.mu.Lock()
	gen := s.gen
	s.mu.Unlock()
	if gen == nil || gen.pc == nil {
		return false
	}
	return gen.pc.ConnectionState() == webrtc.PeerConnectionStateConnected
}

// SubscriberCanSend mirrors CanSend: one bundled peer connection.
func (s *Session) SubscriberCanSend() bool { return s.CanSend() }

// GetBufferedAmount reports the producer command channel's buffered amount.
func (s *Session) GetBufferedAmount() uint64 {
	s.mu.Lock()
	gen := s.gen
	s.mu.Unlock()
	if gen == nil || gen.command == nil {
		return 0
	}
	return gen.command.BufferedAmount()
}

// WatchConnection services the session's reconnect requests until ctx ends
// or the session closes.
func (s *Session) WatchConnection(ctx context.Context) {
	s.Watch(ctx, s.closeCh)
}

// end reports the session's terminal verdict once, through the shared
// Reconnector's callback slot.
func (s *Session) end(reason string) {
	s.endOnce.Do(func() { s.SignalEnded(reason) })
}

// signalEnded ends the session for good: the verdict is about the room, so
// no rejoin is asked for, and everything waiting on the session's done
// channel - the reconnect supervisor included - stops with it.
func (s *Session) signalEnded(reason string) {
	s.end(reason)
	s.closed.Store(true)
	s.closeOnce.Do(func() { close(s.closeCh) })
}

// Close leaves the conversation and frees the peer connection. A closed
// session refuses every later reconnect request and stops the supervisor.
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

// drainRTCP reads a sender's RTCP until the sender closes, so the RTCP
// reaches the interceptors.
func drainRTCP(sender *webrtc.RTPSender) {
	buf := make([]byte, 1500)
	for {
		if _, _, err := sender.Read(buf); err != nil {
			return
		}
	}
}

// streamKind is a registry key with its participant id replaced by a tag.
func streamKind(key string) string {
	head, suffix, _ := strings.Cut(key, ":")
	prefix := strings.TrimRight(head, "0123456789")
	tag := idTag(strings.TrimPrefix(head, prefix))
	if suffix == "" {
		return prefix + "<" + tag + ">"
	}
	return prefix + "<" + tag + ">:" + suffix
}

// idTag tells participant ids apart in a debug log without printing them.
func idTag(id string) string {
	sum := sha256.Sum256([]byte(id))
	return hex.EncodeToString(sum[:2])
}

// iceServersOf decodes the provider's ice_servers JSON document; an empty or
// invalid document yields an empty list — the SFU's own candidates arrive in
// the offer.
func iceServersOf(raw string) []webrtc.ICEServer {
	type serverJSON struct {
		URLs       []string `json:"urls"`
		Username   string   `json:"username"`
		Credential string   `json:"credential"`
	}
	var servers []serverJSON
	if err := json.Unmarshal([]byte(raw), &servers); err != nil {
		return nil
	}
	out := make([]webrtc.ICEServer, 0, len(servers))
	for _, s := range servers {
		if len(s.URLs) == 0 {
			continue
		}
		ice := webrtc.ICEServer{URLs: s.URLs, Username: s.Username, Credential: s.Credential}
		out = append(out, ice)
	}
	return out
}
