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

	"github.com/pion/rtp/codecs"
	"github.com/pion/webrtc/v4"
	"github.com/pion/webrtc/v4/pkg/media"
	"github.com/pion/webrtc/v4/pkg/media/samplebuilder"

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
	maxSendAttempts      = 4
	sampleBuilderMaxLate = 128
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

	closeCh     chan struct{}
	writerDone  chan struct{}
	closed      atomic.Bool
	writerUp    atomic.Bool
	peerReady   atomic.Bool
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
		SDPFmtpLine: "level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f",
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

	tr.sender = common.NewSender(common.SenderConfig{
		Role:          common.LocalRole(cfg.DeviceID),
		Binding:       tr.bindingToken,
		FragmentSize:  opts.FragmentSize,
		MaxAttempts:   maxSendAttempts,
		FrameInterval: tr.frameInterval,
		BatchSize:     opts.BatchSize,
		AckFloor:      time.Duration(opts.AckTimeoutMS) * time.Millisecond,
	}, tr.queue)

	tr.shaper = transport.NewShaper(cfg.Traffic, tr.Features())

	return tr
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

	err := p.sender.Send(data)
	switch {
	case err == nil:
		return nil
	case errors.Is(err, common.ErrAckTimeout):
		return ErrAckTimeout
	default:
		return fmt.Errorf("send fragments: %w", err)
	}
}

// Close terminates the transport.
func (p *streamTransport) Close() error {
	if p.closed.CompareAndSwap(false, true) {
		close(p.closeCh)
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
	p.reassembler.Reset()
}

// CanSend reports whether transport is ready for sending.
func (p *streamTransport) CanSend() bool {
	return !p.closed.Load() && p.peerReady.Load() && p.stream.CanSend()
}

// Features describes the current seichannel transport semantics.
func (p *streamTransport) Features() transport.Features {
	return p.shaper.Features(transport.Features{
		MaxPayloadSize: p.fragmentSize * 8,
	})
}

func (p *streamTransport) writerLoop() {
	defer close(p.writerDone)

	ticker := time.NewTicker(p.frameInterval)
	defer ticker.Stop()

	idle := buildVideoAccessUnit(p.sender.Hello())
	var scratch []byte

	for {
		select {
		case <-p.closeCh:
			return
		case <-ticker.C:
			var ok bool
			scratch, ok = p.writeBatch(idle, scratch)
			if !ok {
				return
			}
		}
	}
}

func (p *streamTransport) writeBatch(idle, scratch []byte) ([]byte, bool) {
	for i := range p.batchSize {
		payload, ok := p.queue.Next()
		if !ok {
			return scratch, false
		}
		if payload == nil {
			if i > 0 {
				return scratch, true
			}
			_ = p.track.WriteSample(media.Sample{Data: idle, Duration: p.frameInterval})
			return scratch, true
		}
		// Pion's H264 payloader copies every NAL into RTP-owned storage before
		// WriteSample returns, so this writer-owned access unit can be reused.
		scratch = buildVideoAccessUnitInto(scratch[:0], payload)
		_ = p.track.WriteSample(media.Sample{
			Data:     scratch,
			Duration: p.frameInterval,
		})
	}
	return scratch, true
}

func (p *streamTransport) handleRemoteTrack(track *webrtc.TrackRemote, _ *webrtc.RTPReceiver) {
	go func() {
		sb := samplebuilder.New(sampleBuilderMaxLate, &codecs.H264Packet{}, track.Codec().ClockRate)

		popSamples := func() {
			for sample := sb.Pop(); sample != nil; sample = sb.Pop() {
				p.handleSample(sample.Data)
			}
		}

		for {
			packet, _, err := track.ReadRTP()
			if err != nil {
				sb.Flush()
				popSamples()
				return
			}

			sb.Push(packet)
			popSamples()
		}
	}()
}

func (p *streamTransport) handleSample(sample []byte) {
	// The track reader flushes the sample builder when the track ends, which
	// is exactly what Close causes: without this the application receives
	// data after Close has already returned.
	if p.closed.Load() {
		return
	}
	payloads := extractVideoPayloads(sample)
	for _, payload := range payloads {
		frame, err := common.DecodeFrame(payload)
		if err != nil {
			continue
		}
		if !p.acceptFrame(frame) {
			continue
		}

		p.peerReady.Store(true)

		switch frame.Type {
		case common.FrameTypeHello:
			// Presence only; readiness is already recorded above.
		case common.FrameTypeAck:
			p.resolveAck(frame.Seq, frame.CRC, frame.FragIdx)
		case common.FrameTypeData:
			p.handleInboundFrame(frame)
		}
	}
}

func (p *streamTransport) handleInboundFrame(frame common.Frame) {
	common.DeliverFragment(p.reassembler, frame, p.onData, p.sendAck)
}

func (p *streamTransport) sendAck(seq, crc uint32, fragIdx uint16) {
	p.sender.Ack(seq, crc, fragIdx)
}

func (p *streamTransport) resolveAck(seq, crc uint32, fragIdx uint16) {
	p.sender.Resolve(seq, crc, fragIdx)
}

// acceptFrame reports whether an inbound frame is addressed to this side:
// sent by the peer role we expect and carrying our session binding.
func (p *streamTransport) acceptFrame(frame common.Frame) bool {
	return frame.AcceptedBy(p.remoteRole, p.bindingToken)
}
