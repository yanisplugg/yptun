// Package videochannel provides a byte transport over a visual video stream.
//
// Payload fragments are rendered into QR codes or tiles, encoded as ordinary
// video frames and decoded back on the far side. Framing, fragment
// acknowledgement and the retransmit loop are the shared ones in
// internal/transport/common; this package owns the visual codec and the
// FPS-paced writer.
package videochannel

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"time"

	"github.com/pion/webrtc/v4"
	"github.com/pion/webrtc/v4/pkg/media"
	"github.com/pion/webrtc/v4/pkg/media/samplebuilder"

	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
	"github.com/openlibrecommunity/olcrtc/internal/transport/common"
)

const (
	defaultMaxPayloadSize = 16 * 1024
	defaultFragmentSize   = 256
	defaultAckTimeout     = 1 * time.Second
	defaultConnectTimeout = 30 * time.Second
	// maxSendAttempts bounds retransmission of the fragments still unacked
	// after one ack budget. The visual path loses individual fragments
	// routinely, and a retry only re-sends what is missing, so the budget is
	// generous.
	maxSendAttempts      = 20
	sampleBuilderMaxLate = 128
	// maxRemoteDecoders caps how many remote tracks get a decoder. Every
	// participant in a shared room publishes one, and each decoder costs two
	// goroutines plus a queue of full grayscale planes.
	maxRemoteDecoders = 8
	// writerBatchSize is how many frames the writer emits per tick. The
	// visual encoder renders one frame per tick, so the ack budget is sized
	// against a batch of one.
	writerBatchSize = 1
)

var (
	// ErrVideoTrackUnsupported is returned when a provider cannot expose video tracks.
	ErrVideoTrackUnsupported = common.ErrVideoTrackUnsupported
	// ErrAckTimeout is returned when the peer does not acknowledge a payload in time.
	ErrAckTimeout = errors.New("videochannel ack timeout")
	// ErrTransportClosed is returned when operations are attempted on a closed transport.
	ErrTransportClosed = errors.New("videochannel transport closed")
)

type streamTransport struct {
	common.Lifecycle

	stream      common.VideoSession
	track       *webrtc.TrackLocalStaticSample
	codec       codecSpec
	encoder     *goEncoder
	encoderMu   sync.Mutex
	decoderMu   sync.Mutex
	decoders    map[*goDecoder]struct{}
	onData      func([]byte)
	queue       *common.OutboundQueue
	sender      *common.Sender
	reassembler *common.Reassembler

	closeCh     chan struct{}
	writerDone  chan struct{}
	closed      atomic.Bool
	writerUp    atomic.Bool
	startWriter sync.Once

	videoW          int
	videoH          int
	videoFPS        int
	videoQRSize     int
	videoQRRecovery string
	videoCodec      string
	videoTileModule int
	videoTileRS     int
	visualOnce      sync.Once
	visual          *visualCodec
	visualErr       error
	remoteRole      byte
	bindingToken    uint32
	shaper          *transport.Shaper
}

// New creates a visual videochannel transport backed by a provider engine.
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

	// Every provider negotiates VP8 outbound; inbound follows whatever the
	// remote announces (see codecSpecForMime).
	codec := vp8CodecSpec()
	track, err := common.NewVideoTrack(codec.capability, "videochannel")
	if err != nil {
		return nil, fmt.Errorf("build video track: %w", err)
	}

	tr := newStreamTransport(stream, track, codec, cfg, opts)

	if err := stream.AddTrack(track); err != nil {
		return nil, fmt.Errorf("attach local video track: %w", err)
	}
	stream.SetTrackHandler(tr.handleRemoteTrack)

	return tr, nil
}

func newStreamTransport(
	stream common.VideoSession,
	track *webrtc.TrackLocalStaticSample,
	codec codecSpec,
	cfg transport.Config,
	opts Options,
) *streamTransport {
	closeCh := make(chan struct{})
	tr := &streamTransport{
		Lifecycle:       common.NewLifecycle(stream),
		stream:          stream,
		track:           track,
		codec:           codec,
		onData:          cfg.OnData,
		queue:           common.NewOutboundQueue(closeCh, ErrTransportClosed),
		reassembler:     common.NewReassembler(256),
		closeCh:         closeCh,
		writerDone:      make(chan struct{}),
		decoders:        make(map[*goDecoder]struct{}),
		videoW:          opts.Width,
		videoH:          opts.Height,
		videoFPS:        opts.FPS,
		videoQRSize:     opts.QRSize,
		videoQRRecovery: opts.QRRecovery,
		videoCodec:      opts.Codec,
		videoTileModule: opts.TileModule,
		videoTileRS:     opts.TileRS,
		remoteRole:      common.RemoteRole(cfg.DeviceID),
		bindingToken:    common.BindingToken(cfg.ChannelID, cfg.RoomURL),
	}

	tr.sender = common.NewSender(common.SenderConfig{
		Role:          common.LocalRole(cfg.DeviceID),
		Binding:       tr.bindingToken,
		FragmentSize:  opts.QRSize,
		MaxAttempts:   maxSendAttempts,
		FrameInterval: tr.frameInterval(),
		BatchSize:     writerBatchSize,
		AckFloor:      defaultAckTimeout,
	}, tr.queue)

	tr.shaper = transport.NewShaper(cfg.Traffic, tr.Features())

	return tr
}

// Connect starts the transport connection.
func (p *streamTransport) Connect(ctx context.Context) error {
	connectCtx, cancel := context.WithTimeout(ctx, defaultConnectTimeout)
	defer cancel()

	encoder := newGoEncoder(p.videoW, p.videoH, p.videoFPS)

	if err := p.stream.Connect(connectCtx); err != nil {
		encoder.Close()
		return fmt.Errorf("connect stream: %w", err)
	}

	p.encoderMu.Lock()
	if p.closed.Load() {
		p.encoderMu.Unlock()
		encoder.Close()
		return ErrTransportClosed
	}
	if p.encoder != nil {
		p.encoder.Close()
	}
	p.encoder = encoder
	p.encoderMu.Unlock()

	p.startWriter.Do(func() {
		p.writerUp.Store(true)
		go p.writerLoop()
	})

	return nil
}

// Send transmits data through the transport with per-fragment retransmits.
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

// frameInterval is the writer's tick period. FPS is defaulted by
// Options.withDefaults, so this only guards hand-built transports in tests.
func (p *streamTransport) frameInterval() time.Duration {
	fps := p.videoFPS
	if fps <= 0 {
		fps = defaultFPS
	}
	return time.Second / time.Duration(fps)
}

// perAttemptAckTimeout returns how long one send attempt waits for acks of a
// fragments-sized payload. The writer emits one frame per tick, so the shared
// batch-aware budget is evaluated at a batch of one.
func perAttemptAckTimeout(fragments, fps int) time.Duration {
	if fps <= 0 {
		fps = 25
	}
	return common.PerAttemptAckTimeout(
		fragments, writerBatchSize, time.Second/time.Duration(fps), defaultAckTimeout)
}

// Close terminates the transport.
func (p *streamTransport) Close() error {
	if p.closed.CompareAndSwap(false, true) {
		close(p.closeCh)

		p.encoderMu.Lock()
		if p.encoder != nil {
			p.encoder.Close()
		}
		p.encoderMu.Unlock()

		p.decoderMu.Lock()
		for decoder := range p.decoders {
			decoder.Close()
		}
		p.decoders = nil
		p.decoderMu.Unlock()

		if p.writerUp.Load() {
			<-p.writerDone
		}
		if err := p.stream.Close(); err != nil {
			return fmt.Errorf("close stream: %w", err)
		}
	}
	return nil
}

// SetReconnectCallback registers reconnect handling.
func (p *streamTransport) SetReconnectCallback(cb func()) {
	p.stream.SetReconnectCallback(cb)
}

// CanSend reports whether transport is ready for sending.
func (p *streamTransport) CanSend() bool {
	return !p.closed.Load() && p.stream.CanSend()
}

// Features describes the current videochannel transport semantics.
func (p *streamTransport) Features() transport.Features {
	maxPayload := defaultMaxPayloadSize
	if p.videoQRSize*64 > maxPayload {
		maxPayload = p.videoQRSize * 64
	}
	return p.shaper.Features(transport.Features{MaxPayloadSize: maxPayload})
}

func (p *streamTransport) writeIdleFrame(enc *goEncoder, frameDuration time.Duration) {
	rawFrame, err := p.renderFrame(nil)
	if err != nil {
		logger.Debugf("videochannel render idle error: %v", err)
		return
	}
	// The Go encoder copies grayscale input into its own pad buffer before
	// returning, so the transport's immutable idle frame remains reusable.
	sample, err := enc.EncodeFrame(rawFrame)
	if err != nil {
		logger.Warnf("videochannel encoder idle error: %v", err)
		return
	}

	_ = p.track.WriteSample(media.Sample{Data: sample, Duration: frameDuration})
}

func (p *streamTransport) writePayloadFrame(enc *goEncoder, payload []byte, frameDuration time.Duration) {
	rawFrame, err := p.renderFrame(payload)
	if err != nil {
		logger.Debugf("videochannel render error: %v", err)
		return
	}

	sample, err := enc.EncodeFrame(rawFrame)
	if err != nil {
		logger.Warnf("videochannel encoder error: %v", err)
		return
	}

	_ = p.track.WriteSample(media.Sample{Data: sample, Duration: frameDuration})
}

func (p *streamTransport) writerLoop() {
	defer close(p.writerDone)
	defer func() {
		p.encoderMu.Lock()
		defer p.encoderMu.Unlock()
		if p.encoder != nil {
			p.encoder.Close()
		}
	}()

	frameDuration := p.frameInterval()
	ticker := time.NewTicker(frameDuration)
	defer ticker.Stop()

	for {
		select {
		case <-p.closeCh:
			return
		case <-ticker.C:
			payload, ok := p.queue.Next()
			if !ok {
				return
			}

			p.encoderMu.Lock()
			enc := p.encoder
			p.encoderMu.Unlock()

			if enc == nil {
				continue
			}

			if payload == nil {
				p.writeIdleFrame(enc, frameDuration)
			} else {
				p.writePayloadFrame(enc, payload, frameDuration)
			}
		}
	}
}

func (p *streamTransport) renderFrame(payload []byte) ([]byte, error) {
	visual, err := p.getVisualCodec()
	if err != nil {
		return nil, err
	}
	return visual.render(payload)
}

func (p *streamTransport) getVisualCodec() (*visualCodec, error) {
	p.visualOnce.Do(func() {
		p.visual, p.visualErr = newVisualCodec(
			p.videoW, p.videoH,
			p.videoCodec, p.videoQRRecovery,
			p.videoTileModule, p.videoTileRS,
		)
	})
	return p.visual, p.visualErr
}

func (p *streamTransport) extractFrame(frame []byte) ([]byte, error) {
	visual, err := p.getVisualCodec()
	if err != nil {
		return nil, err
	}
	return visual.extract(frame)
}

func (p *streamTransport) popDecoderFrames(decoder *goDecoder) {
	defer func() {
		p.decoderMu.Lock()
		if p.decoders != nil {
			delete(p.decoders, decoder)
		}
		p.decoderMu.Unlock()
		decoder.Close()
	}()

	for {
		select {
		case <-p.closeCh:
			return
		default:
		}

		frame, err := decoder.PopFrame()
		if err != nil {
			if !errors.Is(err, ErrTransportClosed) && !p.closed.Load() {
				logger.Warnf("videochannel decoder pop error: %v", err)
			}
			return
		}
		p.handleFrame(frame)
	}
}

func (p *streamTransport) readDecoderInput(track *webrtc.TrackRemote, decoder *goDecoder, codec codecSpec) {
	sb := samplebuilder.New(sampleBuilderMaxLate, codec.depacketizer(), track.Codec().ClockRate)
	for {
		select {
		case <-p.closeCh:
			return
		default:
		}

		packet, _, err := track.ReadRTP()
		if err != nil {
			sb.Flush()
			return
		}

		sb.Push(packet)
		for sample := sb.Pop(); sample != nil; sample = sb.Pop() {
			if err := decoder.PushSample(sample.Data); err != nil {
				if !p.closed.Load() {
					logger.Warnf("videochannel decoder push error: %v", err)
				}
				return
			}
		}
	}
}

func (p *streamTransport) handleRemoteTrack(track *webrtc.TrackRemote, _ *webrtc.RTPReceiver) {
	codec, ok := codecSpecForMime(track.Codec().MimeType)
	if !ok {
		logger.Warnf("videochannel unsupported remote codec: %s", track.Codec().MimeType)
		return
	}

	decoder := newGoDecoder()

	p.decoderMu.Lock()
	if p.closed.Load() || p.decoders == nil || len(p.decoders) >= maxRemoteDecoders {
		full := len(p.decoders) >= maxRemoteDecoders
		p.decoderMu.Unlock()
		decoder.Close()
		if full {
			logger.Warnf("videochannel: %d decoders already running, ignoring remote track", maxRemoteDecoders)
		}
		return
	}
	p.decoders[decoder] = struct{}{}
	p.decoderMu.Unlock()

	go p.popDecoderFrames(decoder)
	go p.readDecoderInput(track, decoder, codec)
}

func (p *streamTransport) handleFrame(frame []byte) {
	payload, err := p.extractFrame(frame)
	if err != nil || len(payload) == 0 {
		if err != nil {
			logger.Debugf("videochannel extract visual payload error: %v", err)
		}
		return
	}

	decoded, err := common.DecodeFrame(payload)
	if err != nil {
		logger.Debugf("videochannel decode transport frame error: %v", err)
		return
	}
	if !p.acceptFrame(decoded) {
		return
	}

	switch decoded.Type {
	case common.FrameTypeAck:
		p.resolveAck(decoded.Seq, decoded.CRC, decoded.FragIdx)
	case common.FrameTypeData:
		p.handleInboundFrame(decoded)
	case common.FrameTypeHello:
		// videochannel has no idle beacon; nothing to do.
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
