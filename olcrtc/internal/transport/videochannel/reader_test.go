package videochannel

import (
	"bytes"
	"fmt"
	"io"
	"testing"
	"time"

	"github.com/pion/interceptor"
	"github.com/pion/rtp"
	"github.com/pion/rtp/codecs"
)

// ai-generated: the whole file (the remote track's reader never waits on the
// VP8 decoder, and the frames it reads still reach the extractor in order).

// packetSource serves prepared RTP packets, then io.EOF.
type packetSource struct {
	packets []*rtp.Packet
	read    int
}

func (s *packetSource) ReadRTP() (*rtp.Packet, interceptor.Attributes, error) {
	if s.read == len(s.packets) {
		return nil, nil, io.EOF
	}
	s.read++
	return s.packets[s.read-1], nil, nil
}

// vp8Packets encodes each frame with the writer's encoder and packetizes it
// the way a TrackLocalStaticSample does, 30 fps apart.
func vp8Packets(t *testing.T, w, h int, frames [][]byte) []*rtp.Packet {
	t.Helper()
	enc := newGoEncoder(w, h, defaultFPS)
	defer enc.Close()
	packetizer := rtp.NewPacketizer(1200, 100, 0x5eed, &codecs.VP8Payloader{}, rtp.NewRandomSequencer(), 90000)
	packets := make([]*rtp.Packet, 0, 2*len(frames))
	for _, frame := range frames {
		sample, err := enc.EncodeFrame(frame)
		if err != nil {
			t.Fatalf("EncodeFrame() error = %v", err)
		}
		packets = append(packets, packetizer.Packetize(sample, 90000/defaultFPS)...)
	}
	return packets
}

// TestReaderNeverWaitsOnTheDecoder feeds a track far more frames than the
// decoder queues while nobody takes its decoded frames. The reader reading
// the track has to get through all of it: pion stamps transport-cc arrival
// times when a packet is read, and a reader held up behind the decoder made
// every packet look late to the bridge, which cut the downlink estimate and
// stopped forwarding the peer's video (olcrtc#14).
func TestReaderNeverWaitsOnTheDecoder(t *testing.T) {
	const w, h, count = 320, 240, 120
	idle := bytes.Repeat([]byte{0xff}, w*h)
	frames := make([][]byte, count)
	for i := range frames {
		frames[i] = idle
	}
	source := &packetSource{packets: vp8Packets(t, w, h, frames)}

	tr := &streamTransport{closeCh: make(chan struct{})}
	decoder := newGoDecoder() // nobody pops: its frame queue fills and PushSample blocks
	defer func() {
		tr.closed.Store(true)
		decoder.Close()
	}()
	samples := make(chan []byte, sampleQueueDepth)
	go tr.decodeSamples(decoder, samples)

	done := make(chan struct{})
	go func() {
		tr.readDecoderInput(source, 90000, decoder, samples, vp8CodecSpec())
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(10 * time.Second):
		t.Fatalf("reader stuck behind the decoder after %d of %d packets", source.read, len(source.packets))
	}
	if source.read != len(source.packets) {
		t.Fatalf("reader read %d of %d packets", source.read, len(source.packets))
	}
}

// TestReaderDeliversFramesInOrder runs QR frames through the reader, the
// decode goroutine and the extractor, as handleRemoteTrack wires them.
func TestReaderDeliversFramesInOrder(t *testing.T) {
	const w, h, count = 320, 240, 12
	vis, err := newVisualCodec(w, h, "qrcode", "low", defaultTileModule, defaultTileRS)
	if err != nil {
		t.Fatalf("newVisualCodec() error = %v", err)
	}
	want := make([][]byte, 0, count)
	frames := make([][]byte, 0, count)
	for i := range count {
		payload := fmt.Appendf(nil, "frame %02d", i)
		frame, err := vis.render(payload)
		if err != nil {
			t.Fatalf("render() error = %v", err)
		}
		want = append(want, payload)
		frames = append(frames, frame)
	}
	source := &packetSource{packets: vp8Packets(t, w, h, frames)}

	tr := &streamTransport{closeCh: make(chan struct{})}
	decoder := newGoDecoder()
	defer func() {
		tr.closed.Store(true) // the decode goroutine ends quietly, as it does on Close
		decoder.Close()
	}()
	samples := make(chan []byte, sampleQueueDepth)
	go tr.decodeSamples(decoder, samples)
	go tr.readDecoderInput(source, 90000, decoder, samples, vp8CodecSpec())

	// The builder holds a frame until the next one starts, so the last never
	// leaves it.
	for i, payload := range want[:count-1] {
		gray, err := decoder.PopFrame()
		if err != nil {
			t.Fatalf("PopFrame(%d) error = %v", i, err)
		}
		got, err := vis.extract(gray)
		if err != nil || !bytes.Equal(got, payload) {
			t.Fatalf("frame %d = %q, %v, want %q", i, got, err, payload)
		}
	}
}
