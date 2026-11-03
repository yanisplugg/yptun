// Package seichannel provides a byte transport over H264 SEI messages.
//
// Payload fragments ride SEI NAL units inside otherwise ordinary H264 access
// units, so an SFU that only inspects the video bitstream forwards them
// untouched. Framing, fragment acknowledgement and the retransmit loop are
// the shared ones in internal/transport/common; this package owns the H264
// provider and the FPS-paced writer.
package seichannel

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"time"

	"github.com/pion/webrtc/v4"
	"github.com/pion/webrtc/v4/pkg/media"

	"github.com/openlibrecommunity/olcrtc/internal/hostprofile"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
	"github.com/openlibrecommunity/olcrtc/internal/transport/common"
)

const (
	defaultFragmentSize   = 900
	defaultAckTimeout     = 3 * time.Second
	defaultFPS            = 30
	defaultBatchSize      = 64
	defaultConnectTimeout = 30 * time.Second
	// maxSendAttempts bounds retransmission of the fragments still unacked
	// after one ack budget. It stays at four: the budget already scales with
	// the message's drain time, so four rounds is a long wait, and a Send
	// that fails is retried by the layer above.
	maxSendAttempts = 4
	// fragmentsPerMessage is how many fragments the payload cap allows one
	// message, and so one smux frame, to need.
	fragmentsPerMessage = 8
	// h264FmtpLine is the H264 profile both the local track and its
	// binding announce: constrained baseline, non-interleaved.
	h264FmtpLine = "level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f"
	// helloEvery is how often the writer beacons while it has other things
	// to write. A hello carries what this side can do (see
	// common.FeatureOrdered), so a peer that starts sending the moment it
	// is ready still learns it within a second.
	//
	// ai-generated: this constant.
	helloEvery = time.Second
)

var (
	// ErrVideoTrackUnsupported is returned when a provider cannot expose video tracks.
	ErrVideoTrackUnsupported = common.ErrVideoTrackUnsupported
	// ErrAckTimeout is returned when the peer does not acknowledge a payload in time.
	ErrAckTimeout = errors.New("seichannel ack timeout")
	// ErrTransportClosed is returned when operations are attempted on a closed transport.
	ErrTransportClosed = errors.New("seichannel transport closed")
)

type streamTransport struct {
	common.Lifecycle

	stream      common.VideoSession
	track       *webrtc.TrackLocalStaticSample
	onData      func([]byte)
	queue       *common.OutboundQueue
	sender      *common.Sender
	reassembler *common.Reassembler
	// window and ordered are the two halves of the ordered path: several
	// messages in flight out, delivery in the sender's order in. A peer
	// whose hello does not announce the feature gets the sender above.
	//
	// ai-generated: these two fields, peerOrdered, hello and deliverMu.
	window    *common.Window
	ordered   *common.Ordered
	hello     []byte
	deliverMu sync.Mutex

	closeCh     chan struct{}
	writerDone  chan struct{}
	closed      atomic.Bool
	writerUp    atomic.Bool
	peerReady   atomic.Bool
	peerOrdered atomic.Bool
	startWriter sync.Once

	fragmentSize  int
	frameInterval time.Duration
	batchSize     int
	remoteRole    byte
	bindingToken  uint32
	shaper        *transport.Shaper
}

// New creates a seichannel transport backed by a provider.
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

	track, err := common.NewVideoTrack(webrtc.RTPCodecCapability{
		MimeType:    webrtc.MimeTypeH264,
		ClockRate:   90000,
		Channels:    0,
		SDPFmtpLine: h264FmtpLine,
	}, "seichannel")
	if err != nil {
		return nil, fmt.Errorf("build video track: %w", err)
	}

	tr := newStreamTransport(stream, track, cfg, opts)

	if err := stream.AddTrack(track); err != nil {
		return nil, fmt.Errorf("attach local video track: %w", err)
	}
	stream.SetTrackHandler(tr.handleRemoteTrack)

	return tr, nil
}

func newStreamTransport(
	stream common.VideoSession,
	track *webrtc.TrackLocalStaticSample,
	cfg transport.Config,
	opts Options,
) *streamTransport {
	closeCh := make(chan struct{})
	tr := &streamTransport{
		Lifecycle:     common.NewLifecycle(stream),
		stream:        stream,
		track:         track,
		onData:        cfg.OnData,
		queue:         common.NewOutboundQueue(closeCh, ErrTransportClosed),
		reassembler:   common.NewReassembler(256),
		closeCh:       closeCh,
		writerDone:    make(chan struct{}),
		fragmentSize:  opts.FragmentSize,
		frameInterval: time.Second / time.Duration(opts.FPS),
		batchSize:     opts.BatchSize,
		remoteRole:    common.RemoteRole(cfg.DeviceID),
		bindingToken:  common.BindingToken(cfg.ChannelID, cfg.RoomURL),
	}

	role := common.LocalRole(cfg.DeviceID)
	tr.sender = common.NewSender(common.SenderConfig{
		Role:          role,
		Binding:       tr.bindingToken,
		FragmentSize:  opts.FragmentSize,
		MaxAttempts:   maxSendAttempts,
		FrameInterval: tr.frameInterval,
		BatchSize:     opts.BatchSize,
		AckFloor:      time.Duration(opts.AckTimeoutMS) * time.Millisecond,
	}, tr.queue)
	// ai-generated: the ordered path and the hello that announces it.
	size := orderedBounds()
	tr.window = common.NewWindow(common.WindowConfig{
		Role:         role,
		Binding:      tr.bindingToken,
		FragmentSize: opts.FragmentSize,
		Messages:     size.messages,
		Bytes:        size.inFlight,
	}, tr.sender.Seq())
	tr.ordered = common.NewOrdered(size.ahead, size.held)
	tr.hello = common.EncodeHelloFeatures(role, tr.bindingToken, common.FeatureOrdered)

	tr.shaper = transport.NewShaper(cfg.Traffic, tr.Features())

	return tr
}

// orderedSize bounds the ordered path: how many messages and bytes stay in
// flight, and how many and how much this side holds for a peer that is
// behind. Zero takes the package default.
//
// ai-generated: this type and orderedBounds.
type orderedSize struct{ messages, inFlight, ahead, held int }

// orderedBounds sizes the ordered path for this host: a phone's packet
// tunnel is killed for growing rather than swapped, so it keeps less in
// flight and holds less for a peer that is behind.
func orderedBounds() orderedSize {
	if hostprofile.BuffersAreConstrained() {
		return orderedSize{messages: 24, inFlight: 192 << 10, ahead: 64, held: 384 << 10}
	}
	return orderedSize{}
}

// Connect starts the transport connection.
func (p *streamTransport) Connect(ctx context.Context) error {
	connectCtx, cancel := context.WithTimeout(ctx, defaultConnectTimeout)
	defer cancel()

	if err := p.stream.Connect(connectCtx); err != nil {
		return fmt.Errorf("connect stream: %w", err)
	}

	p.startWriter.Do(func() {
		p.writerUp.Store(true)
		go p.writerLoop()
	})

	return nil
}

// Send transmits data through the transport.
func (p *streamTransport) Send(data []byte) error {
	return p.shaper.Send(p.send, data)
}

func (p *streamTransport) send(data []byte) error {
	if p.closed.Load() {
		return ErrTransportClosed
	}

	// ai-generated: the choice of path. A peer that announced ordered
	// delivery takes the window, which keeps several messages in flight;
	// any other peer takes one message at a time, all it can reassemble in
	// order.
	send := p.sender.Send
	if p.peerOrdered.Load() && p.window != nil {
		send = p.window.Send
	}
	err := send(data)
	switch {
	case err == nil:
		return nil
	case errors.Is(err, common.ErrAckTimeout):
		return ErrAckTimeout
	case errors.Is(err, common.ErrWindowClosed):
		return ErrTransportClosed
	default:
		return fmt.Errorf("send fragments: %w", err)
	}
}

// Close terminates the transport.
func (p *streamTransport) Close() error {
	if p.closed.CompareAndSwap(false, true) {
		close(p.closeCh)
		if p.window != nil {
			p.window.Close()
		}
		if p.writerUp.Load() {
			<-p.writerDone
		}
		if err := p.stream.Close(); err != nil {
			return fmt.Errorf("close stream: %w", err)
		}
	}
	return nil
}

// SetReconnectCallback registers reconnect handling. The peer latch and the
// reassembly state both describe a session the reconnect just replaced, so
// they are cleared before the upper layer runs.
func (p *streamTransport) SetReconnectCallback(cb func()) {
	p.stream.SetReconnectCallback(func() {
		p.resetPeerState()
		if cb != nil {
			cb()
		}
	})
}

// PeerResetter is satisfied so the liveness layer can drop peer state without
// rebuilding the provider connection.
var _ transport.PeerResetter = (*streamTransport)(nil)

// ResetPeer forgets the current peer. Without it the readiness latch, which
// only ever moved to true, kept reporting a peer that had already left: every
// send was accepted and then quietly burned its whole retry budget.
func (p *streamTransport) ResetPeer() {
	p.resetPeerState()
}

func (p *streamTransport) resetPeerState() {
	p.peerReady.Store(false)
	// ai-generated: the ordered path's state. The peer has forgotten this
	// side's sequence numbers, so the window starts a new stream and the
	// receiver waits to be told where the next one picks up.
	p.peerOrdered.Store(false)
	p.reassembler.Reset()
	if p.ordered != nil {
		p.ordered.Reset()
	}
	if p.window != nil {
		p.window.Reset()
	}
}

// CanSend reports whether transport is ready for sending.
func (p *streamTransport) CanSend() bool {
	return !p.closed.Load() && p.peerReady.Load() && p.stream.CanSend()
}

// Features describes the current seichannel transport semantics.
func (p *streamTransport) Features() transport.Features {
	return p.shaper.Features(transport.Features{
		MaxPayloadSize: p.fragmentSize * fragmentsPerMessage,
	})
}

// writerLoop writes one access unit per tick: the acknowledgements and
// frames waiting, then whatever the window lets out, then a hello when
// there was nothing else or the last one is a second old.
//
// ai-generated: the batching and the window drain; it used to write one
// access unit per frame and at most a batch of them a tick.
func (p *streamTransport) writerLoop() {
	defer close(p.writerDone)

	ticker := time.NewTicker(p.frameInterval)
	defer ticker.Stop()

	var (
		au        []byte
		payloads  [][]byte
		lastHello time.Time
	)

	for {
		select {
		case <-p.closeCh:
			return
		case now := <-ticker.C:
			var ok bool
			payloads, ok = p.collect(payloads[:0])
			if !ok {
				return
			}
			if len(payloads) == 0 || now.Sub(lastHello) >= helloEvery {
				payloads, lastHello = append(payloads, p.helloFrame()), now
			}
			au = buildVideoAccessUnitInto(au[:0], payloads)
			_ = p.track.WriteSample(media.Sample{Data: au, Duration: p.frameInterval})
		}
	}
}

// collect takes this tick's frames: the queue's acknowledgements and
// one-at-a-time fragments first, then the window's. The second result is
// false once the transport is closing.
func (p *streamTransport) collect(payloads [][]byte) ([][]byte, bool) {
	for len(payloads) < p.batchSize {
		frame, open := p.queue.Next()
		if !open {
			return payloads, false
		}
		if frame == nil {
			break
		}
		payloads = append(payloads, frame)
	}
	if p.window == nil || len(payloads) >= p.batchSize {
		return payloads, true
	}
	if !p.peerOrdered.Load() && p.window.InFlight() == 0 {
		return payloads, true
	}
	p.window.Drain(p.batchSize-len(payloads), func(frame []byte) bool {
		payloads = append(payloads, frame)
		return true
	})
	return payloads, true
}

// helloFrame is this side's beacon, which announces what it can do.
func (p *streamTransport) helloFrame() []byte {
	if p.hello != nil {
		return p.hello
	}
	return p.sender.Hello()
}

// handleRemoteTrack reads the peer's track and hands every SEI payload to
// the frame path as its packet arrives.
//
// ai-generated: the per-packet read; it used to run a sample builder.
func (p *streamTransport) handleRemoteTrack(track *webrtc.TrackRemote, _ *webrtc.RTPReceiver) {
	go func() {
		var (
			reader   packetReader
			payloads [][]byte
		)
		for {
			packet, _, err := track.ReadRTP()
			if err != nil {
				return
			}
			payloads = reader.payloads(packet, payloads[:0])
			for _, payload := range payloads {
				p.handlePayload(payload)
			}
		}
	}()
}

// handleSample takes a whole access unit. Only the tests that build one by
// hand use it now; the track reader goes packet by packet.
func (p *streamTransport) handleSample(sample []byte) {
	for _, payload := range extractVideoPayloads(sample) {
		p.handlePayload(payload)
	}
}

// handlePayload decodes one SEI payload and routes the frame it carries.
func (p *streamTransport) handlePayload(payload []byte) {
	// The track reader outlives Close by as long as it takes the peer's
	// track to end, which is exactly what Close causes: without this the
	// application receives data after Close has already returned.
	if p.closed.Load() {
		return
	}
	frame, err := common.DecodeFrame(payload)
	if err != nil || !p.acceptFrame(frame) {
		return
	}

	p.peerReady.Store(true)

	switch frame.Type {
	case common.FrameTypeHello:
		// ai-generated: the peer's features. Only a peer that says it
		// delivers stream frames in order gets them.
		p.peerOrdered.Store(frame.Features&common.FeatureOrdered != 0)
	case common.FrameTypeAck:
		p.resolveAck(frame.Seq, frame.CRC, frame.FragIdx)
	case common.FrameTypeData:
		p.handleInboundFrame(frame)
	case common.FrameTypeStream:
		p.handleStreamFrame(frame)
	}
}

// handleStreamFrame takes one fragment of the ordered stream: it is
// acknowledged as soon as it is stored, and the messages it completes are
// delivered in the order the peer queued them.
//
// ai-generated: this method.
func (p *streamTransport) handleStreamFrame(frame common.Frame) {
	if p.ordered == nil {
		return
	}
	p.deliverMu.Lock()
	defer p.deliverMu.Unlock()
	ack, ready := p.ordered.Push(frame)
	if ack {
		p.sendAck(frame.Seq, frame.CRC, frame.FragIdx)
	}
	for _, message := range ready {
		if p.onData != nil {
			p.onData(message)
		}
	}
}

func (p *streamTransport) handleInboundFrame(frame common.Frame) {
	common.DeliverFragment(p.reassembler, frame, p.onData, p.sendAck)
}

func (p *streamTransport) sendAck(seq, crc uint32, fragIdx uint16) {
	p.sender.Ack(seq, crc, fragIdx)
}

// resolveAck marks one acknowledged fragment. Both senders draw sequence
// numbers from the same counter, so exactly one of them holds this one.
func (p *streamTransport) resolveAck(seq, crc uint32, fragIdx uint16) {
	if p.window != nil && p.window.Ack(seq, crc, fragIdx) {
		return
	}
	p.sender.Resolve(seq, crc, fragIdx)
}

// acceptFrame reports whether an inbound frame is addressed to this side:
// sent by the peer role we expect and carrying our session binding.
func (p *streamTransport) acceptFrame(frame common.Frame) bool {
	return frame.AcceptedBy(p.remoteRole, p.bindingToken)
}
