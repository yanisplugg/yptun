package videochannel

import (
	"context"
	"errors"
	"hash/crc32"
	"testing"
	"time"

	"github.com/pion/webrtc/v4"
	grtile "github.com/zarazaex69/gr/tile"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
	enginebuiltin "github.com/openlibrecommunity/olcrtc/internal/engine/builtin"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
	"github.com/openlibrecommunity/olcrtc/internal/transport/common"
)

var errVideoUnitBoom = errors.New("boom")

type fakeVideoStream struct {
	closeErr   error
	canSend    bool
	trackAdded bool
	trackCB    func(*webrtc.TrackRemote, *webrtc.RTPReceiver)
	reconnect  func()
	should     func() bool
	ended      func(string)
	watched    bool
	closed     bool
}

func (s *fakeVideoStream) Connect(context.Context) error     { return nil }
func (s *fakeVideoStream) Close() error                      { s.closed = true; return s.closeErr }
func (s *fakeVideoStream) SetReconnectCallback(cb func())    { s.reconnect = cb }
func (s *fakeVideoStream) SetShouldReconnect(fn func() bool) { s.should = fn }
func (s *fakeVideoStream) SetEndedCallback(cb func(string))  { s.ended = cb }
func (s *fakeVideoStream) WatchConnection(context.Context)   { s.watched = true }
func (s *fakeVideoStream) CanSend() bool                     { return s.canSend }
func (s *fakeVideoStream) SubscriberCanSend() bool           { return s.canSend }
func (s *fakeVideoStream) AddTrack(webrtc.TrackLocal) error  { s.trackAdded = true; return nil }
func (s *fakeVideoStream) Reconnect(string)                  {}
func (s *fakeVideoStream) SetTrackHandler(cb func(*webrtc.TrackRemote, *webrtc.RTPReceiver)) {
	s.trackCB = cb
}

// fakeEngineSession adapts fakeVideoStream so it satisfies engine.Session and
// engine.VideoTrackCapable, the two interfaces the videochannel transport
// looks up after the provider-layer collapse.
type fakeEngineSession struct {
	stream *fakeVideoStream
}

func (s *fakeEngineSession) Connect(ctx context.Context) error { return s.stream.Connect(ctx) }
func (s *fakeEngineSession) Send([]byte) error                 { return nil }
func (s *fakeEngineSession) Close() error                      { return s.stream.Close() }
func (s *fakeEngineSession) SetReconnectCallback(cb func())    { s.stream.SetReconnectCallback(cb) }
func (s *fakeEngineSession) SetShouldReconnect(fn func() bool) { s.stream.SetShouldReconnect(fn) }
func (s *fakeEngineSession) SetEndedCallback(cb func(string))  { s.stream.SetEndedCallback(cb) }
func (s *fakeEngineSession) WatchConnection(ctx context.Context) {
	s.stream.WatchConnection(ctx)
}
func (s *fakeEngineSession) CanSend() bool                           { return s.stream.CanSend() }
func (s *fakeEngineSession) SubscriberCanSend() bool                 { return s.stream.SubscriberCanSend() }
func (s *fakeEngineSession) GetBufferedAmount() uint64               { return 0 }
func (s *fakeEngineSession) Reconnect(string)                        {}
func (s *fakeEngineSession) AddVideoTrack(t webrtc.TrackLocal) error { return s.stream.AddTrack(t) }
func (s *fakeEngineSession) SetVideoTrackHandler(cb func(*webrtc.TrackRemote, *webrtc.RTPReceiver)) {
	s.stream.SetTrackHandler(cb)
}

type noVideoEngineSession struct {
	engine.Session
}

func TestNewCallbacksFeaturesAndClose(t *testing.T) {
	stream := &fakeVideoStream{canSend: true}
	name := "videochannel-unit-new"
	enginebuiltin.Register(name, func(context.Context, enginebuiltin.Config) (engine.Session, error) {
		return &fakeEngineSession{stream: stream}, nil
	})

	trIface, err := New(context.Background(), transport.Config{
		Provider: name,
		Options: Options{
			Width:      320,
			Height:     240,
			FPS:        30,
			Codec:      "qrcode",
			TileModule: -1,
			TileRS:     -1,
		},
	})
	if err != nil {
		t.Fatalf("New() error = %v", err)
	}
	tr, ok := trIface.(*streamTransport)
	if !ok {
		t.Fatalf("transport type = %T, want *streamTransport", trIface)
	}
	if !stream.trackAdded || stream.trackCB == nil {
		t.Fatal("New() did not attach track and handler")
	}
	tr.SetReconnectCallback(func() {})
	tr.SetShouldReconnect(func() bool { return true })
	tr.SetEndedCallback(func(string) {})
	tr.WatchConnection(context.Background())
	if stream.reconnect == nil || stream.should == nil || stream.ended == nil || !stream.watched {
		t.Fatal("callbacks/watch were not forwarded")
	}
	if !tr.CanSend() {
		t.Fatal("CanSend() = false, want true")
	}
	if features := tr.Features(); features.MaxPayloadSize == 0 {
		t.Fatalf("Features() = %+v", features)
	}
	if tr.videoQRSize != defaultFragmentSize || tr.videoTileModule != 4 || tr.videoTileRS != 20 {
		t.Fatalf("defaults qr=%d tileModule=%d tileRS=%d", tr.videoQRSize, tr.videoTileModule, tr.videoTileRS)
	}
	if err := tr.Close(); err != nil {
		t.Fatalf("Close() error = %v", err)
	}
}

func TestNewErrorPaths(t *testing.T) {
	enginebuiltin.Register(
		"videochannel-create-fails",
		func(context.Context, enginebuiltin.Config) (engine.Session, error) {
			return nil, errVideoUnitBoom
		},
	)
	_, err := New(context.Background(), transport.Config{Provider: "videochannel-create-fails"})
	if err == nil || err.Error() != "open engine session: boom" {
		t.Fatalf("New() error = %v", err)
	}

	enginebuiltin.Register("videochannel-no-video", func(context.Context, enginebuiltin.Config) (engine.Session, error) {
		return &noVideoEngineSession{Session: &fakeEngineSession{stream: &fakeVideoStream{}}}, nil
	})
	_, err = New(context.Background(), transport.Config{Provider: "videochannel-no-video"})
	if !errors.Is(err, ErrVideoTrackUnsupported) {
		t.Fatalf("New() error = %v, want %v", err, ErrVideoTrackUnsupported)
	}
}

func TestSendAckAndClosePaths(t *testing.T) {
	stream := &fakeVideoStream{canSend: true}
	closeCh := make(chan struct{})
	queue := common.NewOutboundQueue(closeCh, ErrTransportClosed)
	tr := &streamTransport{
		Lifecycle:   common.NewLifecycle(stream),
		stream:      stream,
		queue:       queue,
		closeCh:     closeCh,
		writerDone:  make(chan struct{}),
		videoQRSize: 4,
		sender: common.NewSender(common.SenderConfig{
			FragmentSize:  4,
			MaxAttempts:   maxSendAttempts,
			FrameInterval: time.Millisecond,
			BatchSize:     writerBatchSize,
			AckFloor:      time.Second,
		}, queue),
	}

	// "payload" = 7 bytes; with qrSize=4 -> two fragments. Send returns
	// only after both fragIdx 0 and 1 have been acked.
	done := make(chan error, 1)
	payload := []byte("payload")
	go func() { done <- tr.Send(payload) }()

	wantCRC := crc32.ChecksumIEEE(payload)
	for seen := range 2 {
		frame, ok := waitForFrame(t, tr)
		if !ok {
			t.Fatalf("Send() did not enqueue fragment %d", seen)
		}
		decoded, err := common.DecodeFrame(frame)
		if err != nil {
			t.Fatalf("DecodeFrame() error = %v", err)
		}
		tr.resolveAck(decoded.Seq, wantCRC, decoded.FragIdx)
	}

	if err := <-done; err != nil {
		t.Fatalf("Send() error = %v", err)
	}
	if err := tr.Close(); err != nil {
		t.Fatalf("Close() error = %v", err)
	}
	if err := tr.Send([]byte("closed")); !errors.Is(err, ErrTransportClosed) {
		t.Fatalf("Send(closed) error = %v, want %v", err, ErrTransportClosed)
	}
}

func TestOutboundPriorityRenderAndClosedEnqueue(t *testing.T) {
	stream := &fakeVideoStream{canSend: true}
	closeCh := make(chan struct{})
	tr := &streamTransport{
		Lifecycle:       common.NewLifecycle(stream),
		stream:          stream,
		queue:           common.NewOutboundQueue(closeCh, ErrTransportClosed),
		closeCh:         closeCh,
		writerDone:      make(chan struct{}),
		videoW:          16,
		videoH:          16,
		videoQRRecovery: "highest",
		videoCodec:      "qrcode",
		videoTileModule: 4,
		videoTileRS:     20,
	}

	if err := tr.queue.Enqueue([]byte("data"), false); err != nil {
		t.Fatalf("Enqueue(data) error = %v", err)
	}
	if err := tr.queue.Enqueue([]byte("ack"), true); err != nil {
		t.Fatalf("Enqueue(ack) error = %v", err)
	}
	if got, ok := tr.queue.Next(); !ok || string(got) != "ack" {
		t.Fatalf("first Next() = %q/%v, want ack/true", got, ok)
	}
	if got, ok := tr.queue.Next(); !ok || string(got) != "data" {
		t.Fatalf("second Next() = %q/%v, want data/true", got, ok)
	}
	if got, ok := tr.queue.Next(); !ok || got != nil {
		t.Fatalf("idle Next() = %q/%v, want nil/true", got, ok)
	}

	idle, err := tr.renderFrame(nil)
	if err != nil {
		t.Fatalf("renderFrame(nil) error = %v", err)
	}
	if len(idle) != tr.videoW*tr.videoH {
		t.Fatalf("idle frame len = %d, want %d", len(idle), tr.videoW*tr.videoH)
	}
	if features := tr.Features(); features.MaxPayloadSize != defaultMaxPayloadSize {
		t.Fatalf("Features() = %+v", features)
	}

	tr.videoQRSize = defaultMaxPayloadSize
	if features := tr.Features(); features.MaxPayloadSize <= defaultMaxPayloadSize {
		t.Fatalf("Features(large qr) = %+v", features)
	}

	close(closeCh)
	if err := tr.queue.Enqueue([]byte("closed"), false); !errors.Is(err, ErrTransportClosed) {
		t.Fatalf("Enqueue(closed) error = %v, want %v", err, ErrTransportClosed)
	}
}

// TestPerAttemptAckTimeoutScalesWithFragments locks in the rule that the
// per-attempt ack budget covers a full FPS-paced round trip through every
// fragment. Without this, multi-fragment payloads trigger premature
// retransmits that pile fragments into the outbound channel and starve
// the encoder until it is killed.
func TestPerAttemptAckTimeoutScalesWithFragments(t *testing.T) {
	// Tiny payload: floor at defaultAckTimeout.
	if got := perAttemptAckTimeout(1, 25); got != defaultAckTimeout {
		t.Fatalf("perAttemptAckTimeout(1,25) = %v, want %v", got, defaultAckTimeout)
	}
	if got := perAttemptAckTimeout(2, 25); got != defaultAckTimeout {
		t.Fatalf("perAttemptAckTimeout(2,25) = %v, want %v", got, defaultAckTimeout)
	}

	// 16 fragments @ 25 FPS: 16 * 40ms * 3 = 1920ms.
	if got, want := perAttemptAckTimeout(16, 25), 1920*time.Millisecond; got != want {
		t.Fatalf("perAttemptAckTimeout(16,25) = %v, want %v", got, want)
	}

	// Large payload caps at 30s.
	if got, want := perAttemptAckTimeout(10000, 25), 30*time.Second; got != want {
		t.Fatalf("perAttemptAckTimeout(10000,25) = %v, want %v", got, want)
	}

	// Zero/negative fps falls back to 25 FPS default.
	if got := perAttemptAckTimeout(1, 0); got != defaultAckTimeout {
		t.Fatalf("perAttemptAckTimeout(1,0) = %v, want %v", got, defaultAckTimeout)
	}
}

func TestNextOutboundFrameStopsWhenClosed(t *testing.T) {
	closeCh := make(chan struct{})
	queue := common.NewOutboundQueue(closeCh, ErrTransportClosed)
	close(closeCh)
	if got, ok := queue.Next(); ok || got != nil {
		t.Fatalf("Next(closed) = %q/%v, want nil/false", got, ok)
	}
}

func TestZeroOptionsGetDefaultsAndWriterSurvives(t *testing.T) {
	stream := &fakeVideoStream{canSend: true}
	name := "videochannel-unit-zero-options"
	enginebuiltin.Register(name, func(context.Context, enginebuiltin.Config) (engine.Session, error) {
		return &fakeEngineSession{stream: stream}, nil
	})

	trIface, err := New(context.Background(), transport.Config{Provider: name})
	if err != nil {
		t.Fatalf("New() error = %v", err)
	}
	tr, ok := trIface.(*streamTransport)
	if !ok {
		t.Fatalf("transport type = %T, want *streamTransport", trIface)
	}
	if tr.videoFPS != defaultFPS || tr.videoW != defaultWidth || tr.videoH != defaultHeight {
		t.Fatalf("defaults = %dx%d@%d, want %dx%d@%d",
			tr.videoW, tr.videoH, tr.videoFPS, defaultWidth, defaultHeight, defaultFPS)
	}

	// Zero FPS used to divide by zero inside the writer goroutine.
	if err := tr.Connect(context.Background()); err != nil {
		t.Fatalf("Connect() error = %v", err)
	}
	time.Sleep(50 * time.Millisecond)
	if err := tr.Close(); err != nil {
		t.Fatalf("Close() error = %v", err)
	}
}

func TestOptionsWithDefaultsTileDimensions(t *testing.T) {
	got := Options{Codec: codecTile}.withDefaults()
	if got.Width != grtile.FrameW || got.Height != grtile.FrameH || got.FPS != defaultFPS {
		t.Fatalf("tile defaults = %+v", got)
	}

	kept := Options{Width: 100, Height: 200, FPS: 5}.withDefaults()
	if kept.Width != 100 || kept.Height != 200 || kept.FPS != 5 {
		t.Fatalf("withDefaults() overwrote explicit values: %+v", kept)
	}
}

// waitForFrame polls the outbound queue until a frame shows up.
func waitForFrame(t *testing.T, tr *streamTransport) ([]byte, bool) {
	t.Helper()

	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		frame, open := tr.queue.Next()
		if !open {
			return nil, false
		}
		if frame != nil {
			return frame, true
		}
		time.Sleep(time.Millisecond)
	}

	return nil, false
}
