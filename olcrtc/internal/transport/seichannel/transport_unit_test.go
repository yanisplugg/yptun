package seichannel

import (
	"context"
	"errors"
	"hash/crc32"
	"testing"
	"time"

	"github.com/pion/webrtc/v4"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
	enginebuiltin "github.com/openlibrecommunity/olcrtc/internal/engine/builtin"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
	"github.com/openlibrecommunity/olcrtc/internal/transport/common"
)

var errBoom = errors.New("boom")

// fakeVideoStream is the stub implementation of the videoSession interface
// the seichannel transport consumes after engine.Session adaptation.
type fakeVideoStream struct {
	connectErr error
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

func (s *fakeVideoStream) Connect(context.Context) error { return s.connectErr }
func (s *fakeVideoStream) Close() error {
	s.closed = true
	return s.closeErr
}
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

// fakeEngineSession implements engine.Session and engine.VideoTrackCapable so
// it can be returned by enginebuiltin.Open in tests. It wraps a fakeVideoStream
// for the video-track methods the real engine session exposes.
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

func TestNewConnectCallbacksAndFeatures(t *testing.T) {
	stream := &fakeVideoStream{canSend: true}
	name := "seichannel-unit-new"
	enginebuiltin.Register(name, func(context.Context, enginebuiltin.Config) (engine.Session, error) {
		return &fakeEngineSession{stream: stream}, nil
	})

	trIface, err := New(t.Context(), transport.Config{
		Provider: name,
		Options: Options{
			FPS:          40,
			BatchSize:    3,
			FragmentSize: 512,
			AckTimeoutMS: 1500,
		},
	})
	if err != nil {
		t.Fatalf("New() error = %v", err)
	}
	tr, ok := trIface.(*streamTransport)
	if !ok {
		t.Fatalf("New() returned %T, want *streamTransport", trIface)
	}
	if !stream.trackAdded || stream.trackCB == nil {
		t.Fatal("New() did not attach track and handler")
	}
	if err := tr.Connect(context.Background()); err != nil {
		t.Fatalf("Connect() error = %v", err)
	}
	if !tr.writerUp.Load() {
		t.Fatal("Connect() did not start writer")
	}
	tr.SetReconnectCallback(func() {})
	tr.SetShouldReconnect(func() bool { return true })
	tr.SetEndedCallback(func(string) {})
	tr.WatchConnection(context.Background())
	if stream.reconnect == nil || stream.should == nil || stream.ended == nil || !stream.watched {
		t.Fatal("callbacks/watch were not forwarded")
	}
	if tr.CanSend() {
		t.Fatal("CanSend() = true before peer hello")
	}
	// The peer is the client side, so its hello carries the client role.
	peerHello := common.EncodeHello(common.RoleClient, tr.bindingToken)
	tr.handleSample(buildVideoAccessUnit(peerHello))
	if !tr.CanSend() {
		t.Fatal("CanSend() = false after peer hello")
	}
	if features := tr.Features(); features.MaxPayloadSize == 0 {
		t.Fatalf("Features() = %+v", features)
	}
	if tr.fragmentSize != 512 || tr.batchSize != 3 || tr.frameInterval != 25*time.Millisecond {
		t.Fatalf("seichannel settings fragment=%d batch=%d interval=%v",
			tr.fragmentSize, tr.batchSize, tr.frameInterval)
	}
	if err := tr.Close(); err != nil {
		t.Fatalf("Close() error = %v", err)
	}
}

func TestNewErrorPaths(t *testing.T) {
	enginebuiltin.Register("seichannel-create-fails", func(context.Context, enginebuiltin.Config) (engine.Session, error) {
		return nil, errBoom
	})
	_, err := New(context.Background(), transport.Config{Provider: "seichannel-create-fails"})
	if err == nil || err.Error() != "open engine session: boom" {
		t.Fatalf("New() error = %v", err)
	}

	enginebuiltin.Register("seichannel-no-video", func(context.Context, enginebuiltin.Config) (engine.Session, error) {
		return &noVideoEngineSession{Session: &fakeEngineSession{stream: &fakeVideoStream{}}}, nil
	})
	_, err = New(context.Background(), transport.Config{Provider: "seichannel-no-video"})
	if !errors.Is(err, ErrVideoTrackUnsupported) {
		t.Fatalf("New() error = %v, want %v", err, ErrVideoTrackUnsupported)
	}
}

func TestSendAckAndClosePaths(t *testing.T) {
	stream := &fakeVideoStream{canSend: true}
	closeCh := make(chan struct{})
	queue := common.NewOutboundQueue(closeCh, ErrTransportClosed)
	tr := &streamTransport{
		Lifecycle:  common.NewLifecycle(stream),
		stream:     stream,
		queue:      queue,
		closeCh:    closeCh,
		writerDone: make(chan struct{}),
		sender: common.NewSender(common.SenderConfig{
			FragmentSize:  4,
			MaxAttempts:   maxSendAttempts,
			FrameInterval: time.Millisecond,
			BatchSize:     1,
			AckFloor:      time.Second,
		}, queue),
	}

	// "payload" = 7 bytes; with a 4-byte fragment size that is two
	// fragments, and Send returns only once both are acked.
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

// TestResetPeerClearsReadiness locks in that peer readiness is not a one-way
// latch. It only ever moved to true, so after the peer left every send was
// accepted by CanSend and then burned its full retry budget into a session
// that no longer existed.
func TestResetPeerClearsReadiness(t *testing.T) {
	tr := &streamTransport{closeCh: make(chan struct{}), reassembler: common.NewReassembler(8)}
	tr.peerReady.Store(true)

	tr.ResetPeer()

	if tr.peerReady.Load() {
		t.Fatal("ResetPeer() left the peer marked ready")
	}
}
