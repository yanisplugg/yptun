// Package vp8channel disguises a KCP-based byte transport as a stream of
// valid VP8 keyframes so SFUs that validate bitstream conformance let the
// payload through. The package owns its own KCP framing; the per-message
// fragment/ack machinery used by videochannel/seichannel is unnecessary
// here because KCP already provides ordered, reliable delivery.
//
// The wire layout, the epoch/binding header and the batching format are
// documented in wire.go. Each transport runs two independent KCP planes -
// bulk data and control - so handshake and liveness traffic never queues
// behind a large write.
//
// # Peer restart detection
//
// A client binds to the server epoch authenticated by the encrypted handshake
// and normally keeps it until the provider reconnects. When a frame arrives
// from a different epoch after the bound peer has been silent longer than
// peerRestartGrace, that COULD mean the server restarted and rejoined the SFU -
// but in a shared room it just as easily means an unrelated participant (a
// second olcrtc client) joined or reconnected and the SFU is broadcasting its
// epoch to everyone. Epoch churn alone cannot tell the two apart. A server
// whose provider rebuilds rotates its epoch into every frame it sends from
// then on, its per-peer sessions' too (readdressPeers): one frame left under
// the old epoch keeps the latched peer looking alive.
//
// So a rebuild also requires corroboration: linkUnhealthy, pushed in by the
// client's own control-plane liveness loop through NotifyLinkHealth. A real
// server restart kills that session-specific link almost immediately, so
// genuine restarts still recover fast, while a stranger's epoch with our own
// control plane healthy is ignored and no longer tears down a working provider.
//
// Recovery runs the full provider rebuild (stream.Reconnect), the same path
// control-liveness loss uses, rather than a bare re-handshake: the restarted
// server is a fresh SFU participant, so re-handshaking over the old media
// path only times out. The provider's reconnect callback then rotates our
// epoch, resets the peer latch and drives a fresh handshake. Acting on the
// epoch change recovers in seconds instead of waiting out the relaxed
// control-liveness window (~70s, issue #105). The rebuild fires exactly once
// per restart; the flag clears when the next peer latches.
package vp8channel

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"time"

	"github.com/pion/webrtc/v4"

	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
	"github.com/openlibrecommunity/olcrtc/internal/transport/common"
)

const (
	defaultMaxPayloadSize = 60 * 1024
	defaultConnectTimeout = 60 * time.Second
	// rtpBufSize is the read buffer each track reader holds. pion cannot hand
	// over a packet larger than its own receive MTU of 8192 bytes - ICE reads
	// every candidate with that, and so does the SRTP session above it - so
	// the 64 KB this used to be was 56 KB of dead space per track, on every
	// reader the server keeps per peer and on the two the phone keeps.
	rtpBufSize = 8192
	// outboundQueueSize bounds KCP packets waiting for the paced writer. Sized
	// to a couple of send windows so KCP's flush never blocks (a blocked
	// WriteTo would stall KCP's update loop and delay ACKs); the paced writer
	// keeps it drained so this depth is headroom, not standing latency.
	outboundQueueSize = 1536
	// controlOutboundQueueSize is the queue for the control-plane KCP.
	// Control messages are tiny (ping/pong JSON frames), so a small queue
	// suffices. We keep it separate from bulk data to guarantee forward
	// progress even when the data outbound queue is saturated.
	controlOutboundQueueSize = 2048 // sized for ~20s publisher reconnect window at 20ms tick
	// ackQueueSize bounds the packets that only acknowledge the peer, which
	// the data lane writes ahead of the data, a batch a frame; beyond it they
	// wait with the data. ai-generated: issue #12.
	ackQueueSize     = 256
	inboundQueueSize = 4096
	// The same queues on a host that is killed for using memory. Each slot is
	// a pooled ~1.4 KB packet, so 4096 inbound and 1536 outbound are 8 MB of
	// headroom the server profile can afford and a packet tunnel extension
	// cannot: measured with the phone's own runtime profile against a
	// Telemost room, an Ookla-shaped load (six downloads, then three uploads)
	// took the client from 45 MB to 61 MB of RSS, and the extension that
	// carries it dies at about 48. 1024 slots each way is one KCP window
	// (kcpConstrainedRcvWnd) of packets, which is all the inbound side can
	// hold before KCP's own window stops the sender anyway.
	constrainedInboundQueueSize  = 1024
	constrainedOutboundQueueSize = 1024
	canSendHighWatermark         = 90 // percent
	keepaliveIdlePeriod          = 100 * time.Millisecond
	// forceKeepalivePeriod is how often a bare, fully decodable VP8 keyframe
	// is injected even while bulk data flows, so the SFU decoder never times
	// out and stops forwarding the track.
	forceKeepalivePeriod = 2 * time.Second
	// keyframeRequestGap is the least time between two keyframes written
	// because the relay asked: a tenth of a second answers a request about as
	// fast as its round trip, and a relay that takes none of our keyframes -
	// and so asks without end - gets ten a second, not one a tick.
	keyframeRequestGap = 100 * time.Millisecond
	// ai-generated: publishBurstBytes and this comment (olcrtc#26).
	// publishBurstBytes is what the writer's token bucket holds when the
	// engine's service polices how fast a participant may publish (see
	// engine.PublishRateLimited): two full samples, so both a peer pump and
	// the writer loop can pass one in the same tick, and at the ceilings we
	// meet a tenth of a second of traffic, far too short to look sustained.
	// The rate itself is the service's, not this transport's.
	publishBurstBytes = 2 * defaultMaxPayloadSize
	// defaultPeerRestartGrace is how long the latched peer must be silent
	// before a frame from a different epoch is read as a server restart. The
	// server emits a decodable keepalive every ~2s, so a few missed beats is
	// a confident "the latched peer is gone and a fresh one took its place"
	// signal while staying clear of normal SFU jitter. See issue #105.
	defaultPeerRestartGrace = 6 * time.Second
)

var (
	// ErrVideoTrackUnsupported is returned when a provider cannot expose video tracks.
	ErrVideoTrackUnsupported = common.ErrVideoTrackUnsupported
	// ErrTransportClosed is returned when operations are attempted on a closed transport.
	ErrTransportClosed = errors.New("vp8channel transport closed")
)

// videoSession is the provider contract vp8channel needs. The control plane
// must be able to send as soon as the subscriber PC is up, which is what the
// subscriber-aware extension adds on top of the shared video session.
type videoSession = common.SubscriberVideoSession

type streamTransport struct {
	common.Lifecycle

	stream videoSession
	track  *webrtc.TrackLocalStaticSample
	// carrier is the codec the frames ride as; nil is the transport's own VP8
	// (carrier.go). dressBuf is the sample a carrier that re-heads its frames
	// builds, reused under writeMu.
	carrier  *carrier
	dressBuf []byte
	// keyframeWanted is a relay's request for a keyframe, answered by the
	// writer on its next tick (requestKeyframe).
	keyframeWanted atomic.Bool
	// writeMu serializes all track.WriteSample calls. pion's WriteSample is
	// not safe for concurrent use (see writeSampleLocked); the server writes
	// bulk data from per-peer pumps while writerLoop writes control frames
	// and keepalives, so both paths must funnel through this lock.
	writeMu sync.Mutex
	// sampleWriter, when set, replaces the real track.WriteSample call.
	// Tests inject a writer here to observe the exact byte stream that
	// reaches the track and to assert that writeSampleLocked serializes
	// concurrent callers. It must consume data before returning, matching
	// TrackLocalStaticSample.WriteSample. Always invoked under writeMu.
	sampleWriter   func([]byte) bool
	onData         func([]byte)
	onPeerData     func(peerID string, data []byte)
	onDatagram     func([]byte)
	onPeerDatagram func(peerID string, data []byte)
	// serverMode records which side of the link this is. The multi-peer
	// (server) side keeps one session per remote epoch; the single-peer
	// (client) side latches onto exactly one. Routing decisions read this
	// instead of re-deriving the mode from a callback being nil.
	serverMode bool

	// data carries bulk traffic, control carries handshake and liveness.
	// Both are plain KCP planes with independent epochs and queues.
	data    *kcpPlane
	control *kcpPlane
	// datagram is the lossy lane: whole frames the writer sends as they are,
	// bypassing KCP, after control and before bulk data (see datagram.go).
	datagram chan []byte

	// onControlData / onPeerControlData are swapped in by the upper layer at
	// any time and read on every received control frame, so they are atomic
	// rather than mutex-guarded.
	onControlData     atomic.Pointer[func([]byte)]
	onPeerControlData atomic.Pointer[func(peerID string, data []byte)]

	closeCh    chan struct{}
	writerDone chan struct{}
	closed     atomic.Bool
	writerUp   atomic.Bool
	writerOnce sync.Once

	frameInterval time.Duration
	batchSize     int

	// localEpoch is stamped into every outgoing VP8 frame. Explicit
	// upper-layer resets rotate it so the peer can reset its KCP state too.
	// Peer-triggered resets keep it stable to avoid reset ping-pong.
	bindingToken uint32
	epochMu      sync.RWMutex
	localEpoch   uint32
	peerEpoch    atomic.Uint32

	// lastPeerFrameNano stamps the wall-clock time of the most recent frame
	// from the latched peer epoch, peerRestarting guards the provider rebuild
	// from firing more than once per restart, and peerRestartGrace is the
	// silence the watchdog demands. linkUnhealthy is the corroborating signal
	// pushed in by the control-plane liveness loop. See "Peer restart
	// detection" in the package doc.
	lastPeerFrameNano atomic.Int64
	peerRestarting    atomic.Bool
	peerRestartGrace  time.Duration
	linkUnhealthy     atomic.Bool

	// blackoutAfter, probeEvery and growEvery tune when a data lane counts
	// its direction as dark, how often it probes it then, and how fast a
	// capped lane grows back (lane.go). Zero means the default; tests
	// shorten them. ai-generated: issue #12.
	blackoutAfter time.Duration
	probeEvery    time.Duration
	growEvery     time.Duration

	peerConfirmed atomic.Bool
	// peerSeen records that any foreign epoch sent a well-formed frame with
	// our binding token since the last plane restart (transport.PeerObserver).
	peerSeen atomic.Bool

	// shaper applies the optional traffic policy to the bulk data path only;
	// the control plane must stay unpaced.
	shaper *transport.Shaper

	// limiter holds the whole track under the rate the engine's service
	// tolerates from one publisher, and is nil when it polices none. Every
	// write charges it; the bulk writers ask it first.
	// ai-generated: this field (olcrtc#26).
	limiter *common.PublishLimiter
	// sendWindow is the KCP send window every plane of this transport runs
	// with: a round trip of the ceiling when there is one, the relay's own
	// appetite when there is not. ai-generated: this field (olcrtc#26).
	sendWindow int

	// peers holds one session per remote epoch in server mode. Each session
	// owns an isolated bulk KCP plus, once the peer starts handshaking, its
	// own control KCP. Idle sessions are reclaimed by sweepPeers; without
	// that a client rotating its epoch on every reconnect would strand
	// goroutines and queues for the lifetime of the process.
	peers peerTable
}

// New creates a vp8channel transport backed by a provider engine.
func New(ctx context.Context, cfg transport.Config) (transport.Transport, error) {
	opts, err := optionsFrom(cfg)
	if err != nil {
		return nil, err
	}

	// Payloads ride the video track, so the engine stays in pure-video mode:
	// no data callbacks, otherwise it would gate readiness on a bridge this
	// transport never uses and deliver provider bytes behind our back.
	engineCfg := cfg
	engineCfg.OnData = nil
	engineCfg.OnPeerData = nil

	session, err := engineCfg.OpenEngine(ctx)
	if err != nil {
		return nil, err
	}

	stream, err := common.NewEngineVideoSession(session)
	if err != nil {
		return nil, fmt.Errorf("open video session: %w", err)
	}

	// The relay behind the engine decides what the frames ride as: VP8 unless
	// the engine names another codec.
	ride, err := carrierFor(stream.VideoCodec())
	if err != nil {
		_ = stream.Close()
		return nil, err
	}

	track, err := common.NewVideoTrack(ride.capability, "vp8channel", ride.trackOptions...)
	if err != nil {
		return nil, fmt.Errorf("build video track: %w", err)
	}

	tr := newStreamTransport(stream, track, cfg, opts)
	tr.carrier = ride
	if ride.answersKeyframeRequests() {
		stream.SetKeyframeRequestHandler(tr.requestKeyframe)
	}

	if err := stream.AddTrack(track); err != nil {
		return nil, fmt.Errorf("attach local video track: %w", err)
	}
	stream.SetTrackHandler(tr.handleRemoteTrack)

	return tr, nil
}

func newStreamTransport(
	stream videoSession,
	track *webrtc.TrackLocalStaticSample,
	cfg transport.Config,
	opts Options,
) *streamTransport {
	// ai-generated: limiter and sendWindow (olcrtc#26). The ceiling comes
	// from the engine, so a service that polices publishers paces this
	// transport and one that does not leaves it alone.
	limiter := common.NewPublishLimiter(common.PublishRateLimit(stream), publishBurstBytes)
	tr := &streamTransport{
		Lifecycle:        common.NewLifecycle(stream),
		stream:           stream,
		track:            track,
		onData:           cfg.OnData,
		onPeerData:       cfg.OnPeerData,
		onDatagram:       cfg.OnDatagram,
		onPeerDatagram:   cfg.OnPeerDatagram,
		serverMode:       cfg.OnPeerData != nil,
		datagram:         make(chan []byte, datagramQueueSize),
		closeCh:          make(chan struct{}),
		writerDone:       make(chan struct{}),
		frameInterval:    time.Second / time.Duration(opts.FPS),
		batchSize:        opts.BatchSize,
		bindingToken:     channelBindingToken(cfg),
		limiter:          limiter,
		sendWindow:       kcpSendWindow(limiter != nil),
		localEpoch:       randomEpoch(),
		peerRestartGrace: defaultPeerRestartGrace,
	}

	tr.data = newDataPlane(outboundQueueSizeFor(), func(data []byte) {
		if tr.onData != nil {
			tr.onData(data)
		}
	})
	tr.control = newKCPPlane(controlOutboundQueueSize, tr.deliverControlData)
	// ai-generated: both planes run with this transport's window (olcrtc#26).
	tr.data.sndWnd, tr.control.sndWnd = tr.sendWindow, tr.sendWindow

	tr.shaper = transport.NewShaper(cfg.Traffic, tr.Features())

	return tr
}

// Connect brings up the provider, both KCP planes and the paced writer.
func (p *streamTransport) Connect(ctx context.Context) error {
	connectCtx, cancel := context.WithTimeout(ctx, defaultConnectTimeout)
	defer cancel()

	if err := p.stream.Connect(connectCtx); err != nil {
		return fmt.Errorf("connect stream: %w", err)
	}

	// Start both planes eagerly so Send/CanSend work immediately after
	// Connect. Without this the handshake round-trip that runs right after
	// would deadlock: muxconn.Write spins on CanSend (which checks for a live
	// KCP) while KCP only started on the first incoming peer frame.
	switch started, err := p.data.start(p.epochHeader()); {
	case err != nil:
		logger.Infof("vp8channel: startKCP failed: %v", err)
	case started:
		logger.Infof("vp8channel: KCP started localEpoch=0x%08x", p.localEpochValue())
	}

	switch started, err := p.control.start(p.controlEpochHeader()); {
	case err != nil:
		logger.Infof("vp8channel: startControlKCP failed: %v", err)
	case started:
		logger.Infof("vp8channel: control KCP started epoch=0x%08x", p.controlEpochValue())
	}

	p.writerOnce.Do(func() {
		p.writerUp.Store(true)
		go p.writerLoop()

		// Only the multi-peer (server) side accumulates per-peer sessions, so
		// only that side needs the reaper.
		if p.serverMode {
			go p.sweepPeers()
		}
	})

	return nil
}

// Send transmits data on the bulk data plane.
func (p *streamTransport) Send(data []byte) error {
	return p.shaper.Send(p.send, data)
}

func (p *streamTransport) send(data []byte) error {
	return p.sendVia(p.data.get(), data)
}

// SendTo transmits data to a specific peer identified by its epoch hex string.
func (p *streamTransport) SendTo(peerID string, data []byte) error {
	return p.shaper.Send(func(payload []byte) error {
		return p.sendToPeer(peerID, payload, p.peerDataFor)
	}, data)
}

// sendVia is the shape every send path shares: refuse once the transport is
// closed or the target KCP session is gone, otherwise hand the message over.
func (p *streamTransport) sendVia(rt *kcpRuntime, data []byte) error {
	if p.closed.Load() || rt == nil {
		return ErrTransportClosed
	}

	return rt.send(data)
}

// sendToPeer resolves peerID and sends through the runtime pick returns.
func (p *streamTransport) sendToPeer(peerID string, data []byte, pick func(uint32) *kcpRuntime) error {
	if p.closed.Load() {
		return ErrTransportClosed
	}

	epoch, err := parsePeerID(peerID)
	if err != nil {
		return fmt.Errorf("vp8channel: invalid peerID %q: %w", peerID, err)
	}

	return p.sendVia(pick(epoch), data)
}

// peerDataFor returns the bulk KCP of a known peer, without creating one.
// ai-generated: lookup instead of get; a data send does not claim the epoch.
func (p *streamTransport) peerDataFor(epoch uint32) *kcpRuntime {
	sess := p.peers.lookup(epoch)
	if sess == nil {
		return nil
	}

	return sess.data
}

// SupportsPeerRouting reports whether this transport can address individual peers.
func (p *streamTransport) SupportsPeerRouting() bool {
	return p.serverMode
}

// LocalPeerID returns the local data epoch in the transport routing format.
func (p *streamTransport) LocalPeerID() string {
	return formatPeerID(p.localEpochValue())
}

// ConfirmPeer binds the single-peer data plane to an authenticated remote epoch.
func (p *streamTransport) ConfirmPeer(peerID string) error {
	epoch, err := parsePeerID(peerID)
	if err != nil {
		return fmt.Errorf("vp8channel: confirm peer: %w", err)
	}
	if epoch == 0 || epoch&controlEpochFlag != 0 {
		return fmt.Errorf("vp8channel: %w: data epoch 0x%08x", transport.ErrInvalidPeerID, epoch)
	}

	if rt := p.data.get(); rt != nil {
		rt.setHeader(buildEpochHeaderTo(p.bindingToken, p.localEpochValue(), epoch))
	}
	p.peerEpoch.Store(epoch)
	p.lastPeerFrameNano.Store(time.Now().UnixNano())
	p.peerRestarting.Store(false)
	p.peerConfirmed.Store(true)
	logger.Infof("vp8channel: authenticated peer epoch=0x%08x", epoch)
	return nil
}

// Close tears down both planes, every peer session and the provider.
func (p *streamTransport) Close() error {
	if p.closed.CompareAndSwap(false, true) {
		close(p.closeCh)

		p.data.close()
		p.control.close()
		p.peers.closeAll()
		p.drainDatagramQueue()

		if p.writerUp.Load() {
			<-p.writerDone
		}
		if err := p.stream.Close(); err != nil {
			return fmt.Errorf("close stream: %w", err)
		}
	}
	return nil
}

// PeerResetter is satisfied so upper layers can restart the KCP state machine.
var _ transport.PeerResetter = (*streamTransport)(nil)

// ResetPeer drops queued KCP traffic and starts a fresh KCP state machine while
// keeping the provider connection alive. The client/server liveness layer calls
// this before rebuilding smux so replacement handshakes are not parsed behind
// stale bytes from streams that were active when the old session died.
func (p *streamTransport) ResetPeer() {
	p.restartPlanes()
}

// restartPlanes rotates the data epoch and restarts both KCP planes on it.
// controlEpochValue() derives live from the new data epoch, so the control
// header follows automatically and the peer re-correlates the two by
// arithmetic. On the server the per-peer sessions go on under the new epoch
// too (see readdressPeers).
func (p *streamTransport) restartPlanes() {
	p.peerConfirmed.Store(false)
	p.peerSeen.Store(false)
	p.peerEpoch.Store(0)
	p.data.restart(p.rotateEpochHeader())
	p.control.restart(p.controlEpochHeader())
	p.readdressPeers()
}

// PeerSeen implements transport.PeerObserver: a frame with our binding token
// from a foreign epoch has arrived since the last plane restart.
func (p *streamTransport) PeerSeen() bool { return p.peerSeen.Load() }

// NotifyLinkHealth implements transport.LinkHealthObserver. The client wires
// its control-plane liveness loop to this so the peer-restart watchdog has
// corroborating evidence before it fires; see the package doc.
func (p *streamTransport) NotifyLinkHealth(unhealthy bool) {
	p.linkUnhealthy.Store(unhealthy)
}

// SetReconnectCallback registers reconnect handling. A provider reconnect
// rotates our epoch and restarts both KCP planes before the upper layer runs.
func (p *streamTransport) SetReconnectCallback(cb func()) {
	p.stream.SetReconnectCallback(func() {
		p.restartPlanes()
		if cb != nil {
			cb()
		}
	})
}

// WaitForPeer blocks until the remote peer has been authenticated by the
// encrypted handshake, or ctx is cancelled.
// Implements transport.PeerReadyTransport.
func (p *streamTransport) WaitForPeer(ctx context.Context) error {
	const pollInterval = 50 * time.Millisecond
	for {
		if p.peerConfirmed.Load() {
			return nil
		}
		select {
		case <-ctx.Done():
			return fmt.Errorf("wait for peer: %w", ctx.Err())
		case <-time.After(pollInterval):
		}
	}
}

// ready is the shape every CanSend variant shares: the transport is open, the
// KCP session exists and the provider accepts writes.
func (p *streamTransport) ready(rt *kcpRuntime, providerReady func() bool) bool {
	return !p.closed.Load() && rt != nil && providerReady()
}

// CanSend reports whether the bulk data plane is ready and its queue has room.
func (p *streamTransport) CanSend() bool {
	return p.ready(p.data.get(), p.stream.CanSend) &&
		len(p.data.out) < cap(p.data.out)*canSendHighWatermark/100
}

// Features describes the current vp8channel transport semantics.
func (p *streamTransport) Features() transport.Features {
	return p.shaper.Features(transport.Features{MaxPayloadSize: defaultMaxPayloadSize, Datagram: true})
}
