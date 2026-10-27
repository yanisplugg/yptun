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
	"slices"
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
	// datagramPublishTopic carries the lossy lane: unreliable data packets the
	// receiver hands to OnDatagram instead of the byte stream.
	datagramPublishTopic = "olcrtc.udp"
	videoTrackName       = "videochannel"
	maxReconnects        = 10

	// leaveGrace is how long disconnect() lets the SFU act on our
	// LEAVE_REQUEST before returning. See sdkRoom.disconnect.
	leaveGrace = 2 * time.Second

	// roomReadyTimeout bounds how long the send worker waits for the room
	// to reach the connected state before giving up on a queued payload.
	// It covers a full reconnect cycle (connect + republish) with margin.
	roomReadyTimeout = 60 * time.Second
	roomReadyPoll    = 50 * time.Millisecond

	// ai-generated: publishRateLimit and this comment (olcrtc#26).
	// publishRateLimit is what a participant of this engine's service may
	// publish, in bytes a second, before the service acts on it.
	//
	// WB Stream removes a participant - its room-manager calling LiveKit's
	// RemoveParticipant, which arrives here as PARTICIPANT_REMOVED - about
	// 40 s after its published video passes roughly 12 Mbit/s. Measured
	// against a WB room on 2026-09-20 with a probe publishing a fixed rate:
	// 9.6 and 10.8 Mbit/s of sample ran 3 min untouched, 12 Mbit/s was
	// removed after 45 s and 24 Mbit/s after 43 s, while a 20 s burst at
	// 24 Mbit/s followed by a quiet minute was not, so what the service
	// judges is a sustained rate and not a total. The gate's server, which
	// pushes ~22 Mbit/s under six parallel downloads, was removed
	// mid-transfer in three of five CI runs.
	//
	// The value below leaves the measured boundary a margin and is still
	// several times what the tunnel needs: the release gate's floor is
	// 2 Mbit/s and the transports reach 5 Mbit/s of goodput under it.
	publishRateLimit = 1_200_000

	// ai-generated: connectTimeout and the reasoning for it (ghostlane#38).
	// connectTimeout bounds one join in the SDK: the signalling socket, the
	// JoinResponse and the peer connection reaching connected all run on
	// this one clock, and the SDK bounds its own resumes and its wait for
	// the publisher on a publish with it too. Left unset it is 5 s, which a
	// phone on cellular spends before ICE is done: an iPhone on MegaFon and
	// Yota LTE failed WB Stream with "could not connect after timeout" while
	// Wi-Fi worked, and a cellular start against Telemost has been measured
	// at 21 s to ready. The iOS extension waits 35 s for ready; 25 s leaves
	// 10 of them for WB auth (three HTTPS calls) before the join and the
	// olcRTC hello/welcome after it. Android waits 25 s in all, so there the
	// app gives up first and its Stop ends the join (see joinRoom).
	connectTimeout = 25 * time.Second
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
	publishDatagram(data []byte, peerID string) error
	publishTrack(track webrtc.TrackLocal) error
	unpublishLocalTracks()
	disconnect()
	connectionState() lksdk.ConnectionState
	// ai-generated: left and publisherReady.
	// left is closed once disconnect has begun, so a wait on the room can
	// give up on it.
	left() <-chan struct{}
	// publisherReady reports whether a data publish would go out now
	// instead of waiting in the SDK for the publisher peer connection.
	publisherReady() bool
	// disconnectReason is why the server ended the session, as the
	// protocol names it. ai-generated (olcrtc#19).
	disconnectReason() string
}

type sdkRoom struct {
	room *lksdk.Room
	// leftCh is closed by the first disconnect. ai-generated: leftCh and
	// leftOnce.
	leftCh   chan struct{}
	leftOnce sync.Once
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

func (r *sdkRoom) publishDatagram(data []byte, peerID string) error {
	opts := []lksdk.DataPublishOption{
		lksdk.WithDataPublishTopic(datagramPublishTopic),
		lksdk.WithDataPublishReliable(false),
	}
	if peerID != "" {
		opts = append(opts, lksdk.WithDataPublishDestination([]string{peerID}))
	}
	if err := r.room.LocalParticipant.PublishDataPacket(lksdk.UserData(data), opts...); err != nil {
		return fmt.Errorf("publish datagram packet: %w", err)
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
	r.leftOnce.Do(func() { close(r.leftCh) }) // ai-generated: this line.
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

// left is closed by the first disconnect. ai-generated: left.
func (r *sdkRoom) left() <-chan struct{} {
	return r.leftCh
}

// publisherReady mirrors what the SDK waits for before a data publish: ICE
// up on the publisher peer connection and its data channels open, which
// they are as soon as its SCTP association is. A subscriber-primary room
// negotiates that connection only on its first publish.
//
// ai-generated: publisherReady.
func (r *sdkRoom) publisherReady() bool {
	if r.room == nil || r.room.LocalParticipant == nil {
		return false
	}
	pc := r.room.LocalParticipant.GetPublisherPeerConnection()
	if pc == nil || pc.ICEConnectionState() != webrtc.ICEConnectionStateConnected {
		return false
	}
	sctp := pc.SCTP()
	return sctp != nil && sctp.State() == webrtc.SCTPTransportStateConnected
}

// disconnectReason reads the reason the SDK recorded when the room ended.
// ai-generated (olcrtc#19).
func (r *sdkRoom) disconnectReason() string {
	if r.room == nil {
		return "UNKNOWN_REASON"
	}
	return r.room.DisconnectReason().String()
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
	return newSDKRoom(room), nil
}

// newSDKRoom wraps room. ai-generated: newSDKRoom.
func newSDKRoom(room *lksdk.Room) *sdkRoom {
	return &sdkRoom{room: room, leftCh: make(chan struct{})}
}

// Session is the LiveKit engine handle.
type Session struct {
	engine.Reconnector
	engine.VideoTrackState

	url         string
	token       string
	name        string
	refresh     func(ctx context.Context) (engine.Credentials, error)
	connectRoom connectRoomFunc
	connectOpts []lksdk.ConnectOption
	room        roomHandle
	roomMu      sync.RWMutex
	onData      func([]byte)
	onDatagram  func([]byte)
	// onPeerDatagram, when set, receives datagrams with their sender's
	// identity; a datagram whose sender is unknown falls back to onDatagram.
	onPeerDatagram func(peerID string, data []byte)
	closeCh        chan struct{}
	sendQueue      chan []byte
	closed         atomic.Bool
	reconnecting   atomic.Bool
	done           chan struct{}
	queuedBytes    atomic.Int64
	// joinTimeout overrides connectTimeout. Zero means the default; only
	// tests set it. ai-generated: this field.
	joinTimeout time.Duration
	// joinGen counts joins; a room's OnDisconnected acts only while its
	// join is the latest. ai-generated: this field.
	joinGen atomic.Uint64
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
		DTLSProfile: cfg.DTLSProfile,
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
		onDatagram:  cfg.OnDatagram,
		// onPeerDatagram is set below so the field list stays aligned.
		closeCh:   make(chan struct{}),
		sendQueue: make(chan []byte, engine.DefaultSendQueueSize),
		done:      make(chan struct{}),
	}
	s.onPeerDatagram = cfg.OnPeerDatagram
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

func (s *Session) connectSession(ctx context.Context) error {
	// ai-generated: gen and its check in OnDisconnected.
	// The SDK reports the end of a room we already left (its recovery
	// goroutine ends in OnDisconnected), and a join its caller gave up on
	// can be kicked by the next one. Neither may queue a reconnect of the
	// room that replaced it.
	gen := s.joinGen.Add(1)
	// ai-generated: joined, so the end of the room this join made is logged
	// with its reason (olcrtc#19).
	var joined atomic.Pointer[roomHandle]
	roomCB := &lksdk.RoomCallback{
		ParticipantCallback: lksdk.ParticipantCallback{
			OnDataPacket: s.handleDataPacket,
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
			if s.joinGen.Load() != gen || s.closed.Load() || s.reconnecting.Load() {
				return
			}
			var room roomHandle
			if p := joined.Load(); p != nil {
				room = *p
			}
			logRoomDropped(room)
			if !s.queueReconnect() {
				s.signalEnded("disconnected from livekit")
			}
		},
		// ai-generated: OnReconnecting and OnReconnected (olcrtc#19). The SDK
		// resumes a connection it lost before it gives up on the room.
		OnReconnecting: func() {
			if s.joinGen.Load() == gen {
				logger.Infof("livekit: connection to the room lost, the SDK is resuming it")
			}
		},
		OnReconnected: func() {
			if s.joinGen.Load() == gen {
				logger.Infof("livekit: connection to the room resumed")
			}
		},
	}

	// ai-generated: the join budget and the wait through joinRoom.
	budget := connectTimeout
	if s.joinTimeout > 0 {
		budget = s.joinTimeout
	}
	url, token := s.url, s.token
	opts := append(slices.Clip(s.connectOpts), lksdk.WithConnectTimeout(budget))
	room, err := s.joinRoom(ctx, func() (roomHandle, error) {
		return s.connectRoom(url, token, roomCB, opts...)
	})
	if err != nil {
		return fmt.Errorf("connect to room: %w", err)
	}
	joined.Store(&room)

	// ai-generated: leaving a room that lands after shutdown.
	if !s.setRoom(room) {
		go room.disconnect()
		return ErrSessionClosed
	}
	return s.publishPendingTracks()
}

// logRoomDropped says why the room ended the session, as the server put it:
// WB Stream has closed a server's connection with nothing in the log to say
// why (olcrtc#19). room is nil when the room ended before its join returned.
//
// ai-generated: the whole function.
func logRoomDropped(room roomHandle) {
	reason := "UNKNOWN_REASON"
	if room != nil {
		reason = room.disconnectReason()
	}
	logger.Infof("livekit: disconnected from the room, reason=%s", reason)
}

// joinRoom runs join and waits for it, for ctx or for the session to close.
//
// The SDK's join could take ctx, but only its signalling socket would watch
// it: the wait for the peer connection after that is a bare timer, so a
// caller that gave up would sit out the rest of connectTimeout. The join is
// waited on here instead. One that ends after its caller left fails on its
// own budget or, if it did connect, is disconnected straight away instead of
// staying in the room.
//
// ai-generated: joinRoom.
func (s *Session) joinRoom(ctx context.Context, join func() (roomHandle, error)) (roomHandle, error) {
	type joinResult struct {
		room roomHandle
		err  error
	}
	joined := make(chan joinResult, 1)
	go func() {
		room, err := join()
		joined <- joinResult{room: room, err: err}
	}()
	var err error
	select {
	case res := <-joined:
		return res.room, res.err
	case <-ctx.Done():
		err = fmt.Errorf("join abandoned: %w", ctx.Err())
	case <-s.done:
		err = ErrSessionClosed
	}
	go func() {
		if res := <-joined; res.err == nil {
			res.room.disconnect()
		}
	}()
	return nil, err
}

// handleDataPacket routes a received user packet by topic: the datagram topic
// feeds the lossy lane, everything else is the byte stream. The SDK's
// OnDataReceived is its deprecated twin and stays unset so nothing is
// delivered twice.
func (s *Session) handleDataPacket(packet lksdk.DataPacket, params lksdk.DataReceiveParams) {
	user, ok := packet.(*lksdk.UserDataPacket)
	if !ok {
		return
	}
	if user.Topic == datagramPublishTopic {
		switch {
		case s.onPeerDatagram != nil && params.SenderIdentity != "":
			s.onPeerDatagram(params.SenderIdentity, user.Payload)
		case s.onDatagram != nil:
			s.onDatagram(user.Payload)
		}
		return
	}
	if s.onData != nil {
		s.onData(user.Payload)
	}
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
			// ai-generated: publish in place of room.publishData.
			if err := s.publish(room, data); err != nil {
				if errors.Is(err, ErrSessionClosed) {
					return
				}
				logger.Warnf("livekit publish data error: %v", err)
			}
		}
	}
}

// publish hands data to room and waits for the SDK to take it, for the
// session to close or for the room to be left, whichever comes first.
//
// A data publish can sit in the SDK for the whole connectTimeout: it waits
// for the publisher peer connection on a bare timer that Disconnect does
// not end. Made inline, that wait held the send worker, so Close sat it out
// in wg.Wait and a room joined by reconnect got nothing until it ran out. A
// publish left behind here ends on that timer, its payload dropped as it
// would have been then.
//
// ai-generated: publish.
func (s *Session) publish(room roomHandle, data []byte) error {
	published := make(chan error, 1)
	go func() { published <- room.publishData(data) }()
	select {
	case err := <-published:
		return err
	case <-s.done:
		return ErrSessionClosed
	case <-room.left():
		return fmt.Errorf("%w: left with a publish still waiting on it", ErrRoomNotConnected)
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

// SendDatagram publishes one unordered, lossy data packet to the room.
func (s *Session) SendDatagram(data []byte) error {
	return s.SendDatagramTo("", data)
}

// SendDatagramTo publishes one unordered, lossy data packet to a participant.
// Datagrams skip the send queue: a packet that cannot go now is worth nothing
// later, so an unconnected room refuses it instead of parking it. So does a
// connected one whose publisher is not up yet, where the SDK would park the
// caller for up to connectTimeout. ai-generated: the publisher check.
func (s *Session) SendDatagramTo(peerID string, data []byte) error {
	if s.closed.Load() {
		return ErrSessionClosed
	}
	room := s.currentRoom()
	if room == nil || room.connectionState() != lksdk.ConnectionStateConnected || !room.publisherReady() {
		return ErrRoomNotConnected
	}
	return room.publishDatagram(data, peerID)
}

// DatagramCanSend reports whether a datagram would be published now.
// ai-generated: the publisher check.
func (s *Session) DatagramCanSend() bool {
	if !s.CanSend() {
		return false
	}
	room := s.currentRoom()
	return room != nil && room.publisherReady()
}

// PublishRateLimit is the ceiling a transport on this engine keeps its media
// under; see publishRateLimit. ai-generated: this method (olcrtc#26).
func (s *Session) PublishRateLimit() int { return publishRateLimit }

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
	if err := s.rejoin(ctx); err != nil {
		return err
	}
	s.NotifyReconnect()
	return nil
}

// rejoin leaves the room and joins it again on refreshed credentials.
//
// reconnecting covers the rejoin and ends before the reconnect callback. The
// flag holds CanSend false and keeps the room being left from queueing a
// reconnect of its own, and the callback is where the upper layer sends on
// the room just joined: the client's handshake, the server's close notices.
// Held through the callback, it kept every one of those frames off the wire
// until the callback gave up (olcrtc#19).
//
// ai-generated: rejoin, split out of reconnect so the flag ends before the callback.
func (s *Session) rejoin(ctx context.Context) error {
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

	return s.connectSession(ctx)
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

// setRoom installs room and reports whether it did. A session that has shut
// down refuses it and the caller leaves it. Shutdown closes done before it
// swaps the room out under roomMu, so a room is either refused here or left
// by shutdown. ai-generated: the done check and the result.
func (s *Session) setRoom(room roomHandle) bool {
	s.roomMu.Lock()
	defer s.roomMu.Unlock()
	select {
	case <-s.done:
		return false
	default:
	}
	s.room = room
	return true
}

func (s *Session) swapRoom(room roomHandle) roomHandle {
	s.roomMu.Lock()
	defer s.roomMu.Unlock()
	old := s.room
	s.room = room
	return old
}
