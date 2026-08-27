package videochannel

import (
	"errors"
	"fmt"
	"sync"
	"sync/atomic"

	"codeberg.org/rape4me/kc/vp8"
)

// goEncoder is a pure Go VP8 encoder.
type goEncoder struct {
	enc       *vp8.Encoder
	width     int
	height    int
	frameSize int
	closed    atomic.Bool
	mu        sync.Mutex
}

func newGoEncoder(width, height, _ int) *goEncoder {
	enc := vp8.NewEncoder(width, height, 63)
	enc.SetKeyInterval(1)
	return &goEncoder{
		enc:       enc,
		width:     width,
		height:    height,
		frameSize: width * height,
	}
}

func (e *goEncoder) EncodeFrame(frame []byte) ([]byte, error) {
	if e.closed.Load() {
		return nil, ErrTransportClosed
	}
	if len(frame) != e.frameSize {
		return nil, fmt.Errorf("%w: got %d expected %d", ErrUnexpectedFrameSize, len(frame), e.frameSize)
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	encoded, err := e.enc.Encode(frame)
	if err != nil {
		return nil, fmt.Errorf("vp8 encode: %w", err)
	}
	return encoded, nil
}

// Close stops the encoder. Further EncodeFrame calls fail.
func (e *goEncoder) Close() {
	e.closed.Store(true)
}

// goDecoder is a pure Go VP8 decoder.
// decoderQueueDepth bounds how many decoded frames wait for the extractor.
// Each one is a full grayscale plane the vp8 decoder allocates fresh - 2 MB at
// 1080p - and one decoder exists per remote track, so the queue depth is the
// dominant memory cost of this transport. It is deep enough to absorb an
// extraction that runs long without stalling the reader, and no deeper.
const decoderQueueDepth = 8

type goDecoder struct {
	dec       *vp8.Decoder
	frames    chan []byte
	closed    atomic.Bool
	closeOnce sync.Once
	closeCh   chan struct{}
}

func newGoDecoder() *goDecoder {
	return &goDecoder{
		dec:     vp8.NewDecoder(),
		frames:  make(chan []byte, decoderQueueDepth),
		closeCh: make(chan struct{}),
	}
}

func (d *goDecoder) PushSample(sample []byte) error {
	if d.closed.Load() {
		return ErrTransportClosed
	}
	frame, err := d.dec.Decode(sample)
	if err != nil {
		if errors.Is(err, vp8.ErrNoReference) {
			return nil
		}
		return nil
	}
	gray := frame.Grayscale()
	// Blocking here is deliberate. Every frame carries a fragment the peer is
	// waiting to have acknowledged, so dropping one costs a full retransmit
	// round; back-pressure onto the RTP reader is the cheaper of the two.
	select {
	case d.frames <- gray:
	case <-d.closeCh:
		return ErrTransportClosed
	}
	return nil
}

func (d *goDecoder) PopFrame() ([]byte, error) {
	select {
	case frame, ok := <-d.frames:
		if !ok {
			return nil, ErrTransportClosed
		}
		return frame, nil
	case <-d.closeCh:
		return nil, ErrTransportClosed
	}
}

// Close stops the decoder and unblocks PopFrame.
func (d *goDecoder) Close() {
	d.closeOnce.Do(func() {
		d.closed.Store(true)
		close(d.closeCh)
	})
}
