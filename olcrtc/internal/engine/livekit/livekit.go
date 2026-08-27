// Package livekit implements an engine.Session backed by the LiveKit SFU
// protocol via the upstream livekit/server-sdk-go client.
//
// This engine is service-agnostic: it accepts a wss:// signaling URL and an
// access token, and provides byte-stream + video-track primitives over a
// LiveKit room. Service-specific token acquisition (e.g. WB Stream,
// or a self-hosted LiveKit deployment) lives in the auth package.
package livekit

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"time"

	protoLogger "github.com/livekit/protocol/logger"
	lksdk "github.com/owenewans/owenlivekit/v2"
	"github.com/pion/webrtc/v4"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/protect"
)

const (
	dataPublishTopic = "olcrtc"
	videoTrackName   = "videochannel"
	maxReconnects    = 10

	// leaveGrace is how long disconnect() lets the SFU act on our
	// LEAVE_REQUEST before returning. See sdkRoom.disconnect.
	leaveGrace = 2 * time.Second

	// roomReadyTimeout bounds how long the send worker waits for the room
	// to reach the connected state before giving up on a queued payload.
	// It covers a full reconnect cycle (connect + republish) with margin.
	roomReadyTimeout = 60 * time.Second
	roomReadyPoll    = 50 * time.Millisecond
)

var (
	// ErrSessionClosed is returned when an operation is attempted on a closed session.
	ErrSessionClosed = errors.New("livekit session closed")
	// ErrSendQueueFull is returned when the outbound queue cannot accept more data.
	ErrSendQueueFull = errors.New("livekit send queue full")
	// ErrRoomNotConnected is returned when the underlying room is not connected yet.
	ErrRoomNotConnected = errors.New("livekit room not connected")
	// ErrURLRequired is returned when no signaling URL was supplied.
	ErrURLRequired = errors.New("livekit signaling URL required")
	// ErrTokenRequired is returned when no access token was supplied.
	ErrTokenRequired = errors.New("livekit access token required")
)

type roomHandle interface {
	publishData(data []byte) error
	publishTrack(track webrtc.TrackLocal) error
	unpublishLocalTracks()
	disconnect()
	connectionState() lksdk.ConnectionState
}

type sdkRoom struct {
	room *lksdk.Room
}

func (r *sdkRoom) publishData(data []byte) error {
	if err := r.room.LocalParticipant.PublishDataPacket(
		lksdk.UserData(data),
		lksdk.WithDataPublishTopic(dataPublishTopic),
		lksdk.WithDataPublishReliable(true),
	); err != nil {
		return fmt.Errorf("publish data packet: %w", err)
	}
	return nil
}

func (r *sdkRoom) publishTrack(track webrtc.TrackLocal) error {
	_, err := r.room.LocalParticipant.PublishTrack(track, &lksdk.TrackPublicationOptions{Name: videoTrackName})
	if err != nil {
		return fmt.Errorf("publish track: %w", err)
	}
	return nil
}

func (r *sdkRoom) unpublishLocalTracks() {
	if r.room == nil || r.room.LocalParticipant == nil {
		return
	}
	for _, publication := range r.room.LocalParticipant.TrackPublications() {
		if publication.SID() == "" {
			continue
		}
		if err := r.room.LocalParticipant.UnpublishTrack(publication.SID()); err != nil {
			logger.Warnf("livekit unpublish track error: %v", err)
		}
	}
}

// disconnect leaves the room and, when we were actually joined, waits out a
// short grace period.
//
// The SDK sends LEAVE_REQUEST synchronously on the signalling websocket and
// then tears down local state, so there is nothing client-side left to wait
// for: the condition the old unconditional sleep was really waiting on is
// the server evicting the participant, which the SDK never surfaces. What we
// can do is stop paying for it when there is nobody to evict - a room that
// is already disconnected (the usual case on the reconnect path, where we
// got here from OnDisconnected) sent no LEAVE and needs no grace at all.
func (r *sdkRoom) disconnect() {
	if r.room == nil {
		return
	}
	if r.room.ConnectionState() == lksdk.ConnectionStateDisconnected {
		r.room.Disconnect()
		return
	}
	r.room.Disconnect()
	time.Sleep(leaveGrace)
}

func (r *sdkRoom) connectionState() lksdk.ConnectionState {
	return r.room.ConnectionState()
}

type connectRoomFunc func(
	url, token string, callback *lksdk.RoomCallback, opts ...lksdk.ConnectOption,
) (roomHandle, error)

func connectSDKRoom(
	url, token string, callback *lksdk.RoomCallback, opts ...lksdk.ConnectOption,
) (roomHandle, error) {
	opts = append([]lksdk.ConnectOption{
		lksdk.WithAutoSubscribe(true),
		lksdk.WithLogger(protoLogger.GetDiscardLogger()),
	}, opts...)
	room, err := lksdk.ConnectToRoomWithToken(
		url,
		token,
		callback,
		opts...,
	)
	if err != nil {
		return nil, fmt.Errorf("connect to livekit room: %w", err)
	}
	return &sdkRoom{room: room}, nil
}

// Session is the LiveKit engine handle.
type Session struct {
	engine.Reconnector
	engine.VideoTrackState

	url          string
	token        string
	name         string
	refresh      func(ctx context.Context) (engine.Credentials, error)
	connectRoom  connectRoomFunc
	connectOpts  []lksdk.ConnectOption
	room         roomHandle
	roomMu       sync.RWMutex
	onData       func([]byte)
	closeCh      chan struct{}
	sendQueue    chan []byte
	closed       atomic.Bool
	reconnecting atomic.Bool
	done         chan struct{}
	queuedBytes  atomic.Int64
	// roomReady overrides roomReadyTimeout. Zero means the default; only
	// tests set it.
	roomReady      time.Duration
	shutdownOnce   sync.Once
	sendWorkerOnce sync.Once
	wg             sync.WaitGroup
}

// New creates a new LiveKit engine session.
//
// ctx is unused: nothing in the session is driven by a context created here.
// Shutdown is signalled through closeCh/done, which every internal loop
// already selects on, and Connect/reconnect take the caller's context.
func New(_ context.Context, cfg engine.Config) (engine.Session, error) {
	if cfg.URL == "" {
		return nil, ErrURLRequired
	}
	if cfg.Token == "" {
		return nil, ErrTokenRequired
	}
	httpClient := protect.NewHTTPClient(cfg.Resolver)
	wsDialer := protect.NewWebSocketDialer(0, cfg.Resolver)
	connectOpts := []lksdk.ConnectOption{
		lksdk.WithConnectHTTPClient(httpClient),
		lksdk.WithWebSocketDialer(&wsDialer),
	}
	applySettings, err := engine.NewPionSettings(engine.PionSettingsOptions{
		Resolver:    cfg.Resolver,
		ProxyDialer: true,
	})
	if err != nil {
		return nil, err //nolint:wrapcheck // shared builder already adds protected-net context
	}
	if applySettings != nil {
		connectOpts = append(connectOpts, lksdk.WithSettingEngineFunc(applySettings))
	}
	s := &Session{
		url:         cfg.URL,
		token:       cfg.Token,
		name:        cfg.Name,
		refresh:     cfg.Refresh,
		connectRoom: connectSDKRoom,
		connectOpts: connectOpts,
		onData:      cfg.OnData,
		closeCh:     make(chan struct{}),
		sendQueue:   make(chan []byte, engine.DefaultSendQueueSize),
		done:        make(chan struct{}),
	}
	s.Configure(engine.ReconnectorConfig{
		MaxAttempts: maxReconnects,
		Reconnect:   s.reconnect,
		OnError: func(err error) {
			logger.Debugf("livekit reconnect failed: %v", err)
		},
		OnLimit:     s.signalEnded,
		LimitReason: "reconnect limit reached",
	})
	return s, nil
}

// Connect joins the LiveKit room.
func (s *Session) Connect(ctx context.Context) error {
	s.closed.Store(false)
	if err := s.connectSession(ctx); err != nil {
		return err
	}
	s.startSendWorker()
	return nil
}

func (s *Session) connectSession(_ context.Context) error {
	roomCB := &lksdk.RoomCallback{
		ParticipantCallback: lksdk.ParticipantCallback{
			OnDataReceived: func(data []byte, _ lksdk.DataReceiveParams) {
				if s.onData != nil {
					s.onData(data)
				}
			},
			OnTrackSubscribed: func(track *webrtc.TrackRemote, _ *lksdk.RemoteTrackPublication, _ *lksdk.RemoteParticipant) {
				if track.Kind() != webrtc.RTPCodecTypeVideo {
					return
				}
				cb := s.VideoTrackHandler()
				if cb != nil {
					cb(track, nil)
				}
			},
		},
		OnDisconnected: func() {
			if s.closed.Load() || s.reconnecting.Load() {
				return
			}
			if !s.queueReconnect() {
				s.signalEnded("disconnected from livekit")
			}
		},
	}

	room, err := s.connectRoom(s.url, s.token, roomCB, s.connectOpts...)
	if err != nil {
		return fmt.Errorf("connect to room: %w", err)
	}

	s.setRoom(room)
	return s.publishPendingTracks()
}

func (s *Session) publishPendingTracks() error {
	room := s.currentRoom()
	if room == nil {
		return ErrRoomNotConnected
	}
	var publishErr error
	s.RangeVideoTracks(func(track webrtc.TrackLocal, _ bool) {
		if publishErr != nil {
			return
		}
		if err := room.publishTrack(track); err != nil {
			publishErr = fmt.Errorf("failed to publish track: %w", err)
		}
	})
	return publishErr
}

func (s *Session) startSendWorker() {
	s.sendWorkerOnce.Do(func() {
		s.wg.Add(1)
		go s.processSendQueue()
	})
}

func (s *Session) processSendQueue() {
	defer s.wg.Done()
	for {
		select {
		case <-s.done:
			return
		case data, ok := <-s.sendQueue:
			if !ok {
				return
			}
			s.queuedBytes.Add(-int64(len(data)))
			room, err := s.waitForConnectedRoom()
			if err != nil {
				if errors.Is(err, ErrSessionClosed) {
					return
				}
				logger.Warnf("livekit dropping %d bytes: %v", len(data), err)
				continue
			}
			if err := room.publishData(data); err != nil {
				logger.Warnf("livekit publish data error: %v", err)
			}
		}
	}
}

// waitForConnectedRoom blocks until the room is connected, the session
// shuts down (ErrSessionClosed), or roomReadyTimeout elapses
// (ErrRoomNotConnected). Without the bound a single wedged reconnect would
// park the send worker for the lifetime of the process.
func (s *Session) waitForConnectedRoom() (roomHandle, error) {
	timeout := roomReadyTimeout
	if s.roomReady > 0 {
		timeout = s.roomReady
	}
	deadline := time.NewTimer(timeout)
	defer deadline.Stop()
	ticker := time.NewTicker(roomReadyPoll)
	defer ticker.Stop()
	for {
		room := s.currentRoom()
		if room != nil && room.connectionState() == lksdk.ConnectionStateConnected {
			return room, nil
		}
		select {
		case <-s.done:
			return nil, ErrSessionClosed
		case <-deadline.C:
			return nil, fmt.Errorf("%w after %s", ErrRoomNotConnected, timeout)
		case <-ticker.C:
		}
	}
}

// Send queues data for transmission.
func (s *Session) Send(data []byte) error {
	if s.closed.Load() {
		return ErrSessionClosed
	}
	select {
	case s.sendQueue <- data:
		s.queuedBytes.Add(int64(len(data)))
		return nil
	default:
		return ErrSendQueueFull
	}
}

// Close terminates the session.
func (s *Session) Close() error {
	s.closed.Store(true)
	s.shutdown()
	return nil
}

func (s *Session) shutdown() {
	s.shutdownOnce.Do(func() {
		engine.CloseSignal(s.closeCh)
		engine.CloseSignal(s.done)
		if room := s.swapRoom(nil); room != nil {
			room.unpublishLocalTracks()
			room.disconnect()
		}
		s.wg.Wait()
	})
}

// WatchConnection monitors the connection lifecycle and reconnects as needed.
func (s *Session) WatchConnection(ctx context.Context) {
	s.Watch(ctx, s.closeCh)
}

func (s *Session) reconnect(ctx context.Context) error {
	s.reconnecting.Store(true)
	defer s.reconnecting.Store(false)

	if room := s.swapRoom(nil); room != nil {
		room.unpublishLocalTracks()
		room.disconnect()
	}

	if s.refresh != nil {
		creds, err := s.refresh(ctx)
		if err != nil {
			return fmt.Errorf("refresh credentials: %w", err)
		}
		engine.ApplyRefreshedCredentials(creds, &s.url, &s.token, nil)
	}

	if err := s.connectSession(ctx); err != nil {
		return err
	}
	s.NotifyReconnect()
	return nil
}

func (s *Session) queueReconnect() bool {
	return s.Request(s.closed.Load(), s.reconnecting.Load()) != engine.ReconnectRejected
}

// Reconnect asks the LiveKit session to tear down its room handle and rejoin.
// Triggered by upper layers when liveness probes declare the provider dead
// before LiveKit has noticed (silent data-path black-hole).
func (s *Session) Reconnect(reason string) {
	if s.closed.Load() {
		return
	}
	logger.Infof("livekit reconnect requested: %s", reason)
	s.queueReconnect()
}

func (s *Session) signalEnded(reason string) {
	s.closed.Store(true)
	s.shutdown()
	s.SignalEnded(reason)
}

// CanSend reports whether the session is ready to accept data.
func (s *Session) CanSend() bool {
	if s.closed.Load() || s.reconnecting.Load() || len(s.sendQueue) >= engine.DefaultSendQueueCapHard {
		return false
	}
	room := s.currentRoom()
	return room != nil && room.connectionState() == lksdk.ConnectionStateConnected
}

// SubscriberCanSend reports whether the subscriber path is ready to send.
func (s *Session) SubscriberCanSend() bool { return s.CanSend() }

// GetBufferedAmount reports the bytes queued in this engine's outbound
// channel and nothing else.
//
// The real wire-level figure lives on the SDK's data channels, but lksdk
// keeps the RTCEngine behind an unexported field on Room, so there is no
// supported way to read DataChannel.BufferedAmount from here. Upper layers
// therefore get our own queue depth as the backpressure signal - accurate
// for what we hold, blind to what the SDK and SCTP hold below us.
func (s *Session) GetBufferedAmount() uint64 {
	queued := s.queuedBytes.Load()
	if queued <= 0 {
		return 0
	}
	return uint64(queued)
}

// AddVideoTrack publishes a video track to the room.
func (s *Session) AddVideoTrack(track webrtc.TrackLocal) error {
	s.StoreVideoTrack(track)

	room := s.currentRoom()
	if room == nil {
		return nil
	}
	if err := room.publishTrack(track); err != nil {
		return fmt.Errorf("failed to publish track: %w", err)
	}
	return nil
}

func (s *Session) currentRoom() roomHandle {
	s.roomMu.RLock()
	defer s.roomMu.RUnlock()
	return s.room
}

func (s *Session) setRoom(room roomHandle) {
	s.roomMu.Lock()
	defer s.roomMu.Unlock()
	s.room = room
}

func (s *Session) swapRoom(room roomHandle) roomHandle {
	s.roomMu.Lock()
	defer s.roomMu.Unlock()
	old := s.room
	s.room = room
	return old
}
