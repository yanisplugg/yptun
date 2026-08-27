package goolom

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/gorilla/websocket"
	"github.com/pion/webrtc/v4"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/protect"
)

// defaultSTUNURL is the bootstrap STUN server used before the SFU
// advertises its own ICE servers via serverHello. Yandex Telemost
// hands back the real STUN/TURN set in rtcConfiguration.
const defaultSTUNURL = "stun:stun.rtc.yandex.net:3478"

// Connect starts the WebRTC connection process.
func (s *Session) Connect(ctx context.Context) error {
	// Reconnect reaches here too, and Close cancels no context this path
	// uses. Without the check a reconnect racing Close clears closed, builds
	// a fresh PeerConnection pair, DataChannel and WebSocket, and leaves them
	// running behind a session the caller already closed.
	if s.terminated.Load() {
		return ErrSessionClosed
	}
	s.closed.Store(false)
	s.resetMediaState()

	config := webrtc.Configuration{
		ICEServers:   []webrtc.ICEServer{{URLs: []string{defaultSTUNURL}}},
		SDPSemantics: webrtc.SDPSemanticsUnifiedPlan,
	}

	if err := s.setupPeerConnections(config); err != nil {
		return err
	}

	keepAliveCh, sessionCloseCh := s.resetSession()
	var dcReady chan struct{}
	if s.onData != nil {
		dc, err := s.pubPC().CreateDataChannel("olcrtc", nil)
		if err != nil {
			return fmt.Errorf("create dc: %w", err)
		}
		s.dc.Store(dc)
		dcReady = make(chan struct{})
		s.setupDataChannelHandlers(dc, dcReady, sessionCloseCh)
	}

	if err := s.dialWebSocket(); err != nil {
		return err
	}

	s.setupICEHandlers()
	s.startBackgroundGoroutines(ctx, keepAliveCh)

	if s.onData != nil {
		select {
		case <-dcReady:
			return s.abortIfTerminated()
		case <-time.After(15 * time.Second):
			return ErrDataChannelTimeout
		case <-ctx.Done():
			return fmt.Errorf("connect context cancelled: %w", ctx.Err())
		}
	}

	if err := s.waitForMediaReady(ctx, 20*time.Second); err != nil {
		return err
	}
	return s.abortIfTerminated()
}

// abortIfTerminated tears down the generation Connect just built when Close
// ran while it was being built. Checking terminated once on entry is not
// enough: Close can finish its teardown between that check and the point
// where the new PeerConnections, DataChannel and WebSocket exist, and it has
// no way to see resources that were not published yet.
func (s *Session) abortIfTerminated() error {
	if !s.terminated.Load() {
		return nil
	}
	s.closeDataChannel()
	s.closePeerConns()
	s.closeWebSocket()
	return ErrSessionClosed
}

// waitForMediaReady blocks until the subscriber PC reports Connected.
//
// Only the subscriber side gates readiness: publisher readiness deliberately
// does not gate sending (see CanSend), because KCP buffers and retransmits
// while the publisher PC is still negotiating.
func (s *Session) waitForMediaReady(ctx context.Context, timeout time.Duration) error {
	timer := time.NewTimer(timeout)
	defer timer.Stop()

	select {
	case <-s.subscriberConnCh():
	case <-timer.C:
		return ErrSubscriberMediaTimeout
	case <-ctx.Done():
		return fmt.Errorf("connect context cancelled: %w", ctx.Err())
	}
	return nil
}

func (s *Session) dialWebSocket() error {
	wsDialer := protect.NewWebSocketDialer(wsHandshakeTimeout, s.resolver)
	ws, resp, err := wsDialer.Dial(s.signalingURL(), nil)
	if err != nil {
		return fmt.Errorf("dial ws: %w", err)
	}
	if resp != nil && resp.Body != nil {
		_ = resp.Body.Close()
	}
	s.wsMu.Lock()
	s.ws = ws
	s.wsMu.Unlock()

	ws.SetReadLimit(wsReadLimit)
	ws.SetPongHandler(func(string) error {
		_ = ws.SetReadDeadline(time.Now().Add(wsReadTimeout))
		return nil
	})
	_ = ws.SetReadDeadline(time.Now().Add(wsReadTimeout))
	return nil
}

func (s *Session) startBackgroundGoroutines(ctx context.Context, keepAliveCh chan struct{}) {
	s.goLaunch(func() { s.keepAlive(keepAliveCh) })

	if err := s.sendHello(); err != nil {
		logger.Debugf("goolom: hello: %v", err)
	}

	s.goLaunch(func() { s.handleSignaling(ctx) })
}

func (s *Session) onConnectionStateChange(state webrtc.PeerConnectionState) {
	if !s.closed.Load() && state == webrtc.PeerConnectionStateFailed {
		s.queueReconnect()
	}
}

func (s *Session) onSubscriberConnectionStateChange(state webrtc.PeerConnectionState) {
	logger.Debugf("goolom subscriber state: %s", state.String())
	switch state {
	case webrtc.PeerConnectionStateConnected:
		s.subscriberReady.Store(true)
		s.signalSubscriberConn()
	case webrtc.PeerConnectionStateDisconnected,
		webrtc.PeerConnectionStateFailed,
		webrtc.PeerConnectionStateClosed:
		s.subscriberReady.Store(false)
	case webrtc.PeerConnectionStateUnknown,
		webrtc.PeerConnectionStateNew,
		webrtc.PeerConnectionStateConnecting:
	}
	s.onConnectionStateChange(state)
}

func (s *Session) onPublisherConnectionStateChange(state webrtc.PeerConnectionState) {
	logger.Debugf("goolom publisher state: %s", state.String())
	switch state {
	case webrtc.PeerConnectionStateConnected:
		s.publisherReady.Store(true)
	case webrtc.PeerConnectionStateDisconnected,
		webrtc.PeerConnectionStateFailed,
		webrtc.PeerConnectionStateClosed:
		s.publisherReady.Store(false)
		// Publisher failure triggers a full reconnect so the data VP8 track
		// (carried by the publisher PC) is restored. The subscriber PC will
		// also be re-established as part of the full reconnect.
		logger.Warnf("goolom publisher PC %s - triggering reconnect", state)
		s.queueReconnect()
		return
	case webrtc.PeerConnectionStateUnknown,
		webrtc.PeerConnectionStateNew,
		webrtc.PeerConnectionStateConnecting:
	}
	s.onConnectionStateChange(state)
}

// pcCloseTimeout bounds how long teardown waits for a pion
// PeerConnection to close. With the advertised TURN relays now retained
// (issue #95), PeerConnection.Close() tries to free the server-side TURN
// allocation; if that relay is unreachable pion blocks on allocation
// retransmissions for tens of seconds. A stalled close must never hold up
// session teardown or a reconnect, so the close is bounded.
const pcCloseTimeout = 2 * time.Second

// closePeerConns closes the publisher and subscriber PeerConnections
// without letting a stuck TURN deallocation block the caller. Each Close
// runs in its own goroutine; the call returns once both finish or
// pcCloseTimeout elapses, whichever comes first.
func (s *Session) closePeerConns() {
	var wg sync.WaitGroup
	for _, pc := range []*webrtc.PeerConnection{s.pubPC(), s.subPC()} {
		if pc == nil {
			continue
		}
		wg.Add(1)
		go func(pc *webrtc.PeerConnection) {
			defer wg.Done()
			_ = pc.Close()
		}(pc)
	}
	done := make(chan struct{})
	go func() {
		wg.Wait()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(pcCloseTimeout):
		logger.Warnf("goolom: peer connection close timed out after %s (stuck TURN dealloc?)", pcCloseTimeout)
	}
}

// closeDataChannel closes the live data channel, if any.
func (s *Session) closeDataChannel() {
	if dc := s.dc.Swap(nil); dc != nil {
		_ = dc.Close()
	}
}

// closeWebSocket sends the WebSocket close frame and tears the connection
// down. Clearing the pointer under wsMu makes every later writeJSON fail with
// ErrWebSocketClosed instead of writing to a dead socket.
func (s *Session) closeWebSocket() {
	s.wsMu.Lock()
	ws := s.ws
	s.ws = nil
	s.wsMu.Unlock()
	if ws == nil {
		return
	}
	_ = ws.WriteControl(websocket.CloseMessage,
		websocket.FormatCloseMessage(websocket.CloseNormalClosure, ""),
		time.Now().Add(time.Second))
	_ = ws.Close()
}

// sleepCtx waits for d or until ctx is cancelled, returning ctx.Err() when
// the context ends first. It lets the reconnect path bail out promptly
// during shutdown instead of sleeping through a fixed backoff.
func sleepCtx(ctx context.Context, d time.Duration) error {
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return fmt.Errorf("goolom sleep canceled: %w", ctx.Err())
	case <-timer.C:
		return nil
	}
}

// Close terminates the session and releases resources.
func (s *Session) Close() error {
	s.terminated.Store(true)
	alreadyClosing := s.closed.Swap(true)
	s.sendQueueClosed.Store(true)

	if !alreadyClosing {
		leaveUID := uuid.New().String()
		leaveAck := s.registerAckWaiter(leaveUID)
		// 2s matches our jitsi tear-down budget. The reason is the same:
		// without giving the server time to register the leave, a
		// back-to-back reconnection from the same client collides with a
		// still-alive ghost participant on the SFU side and inherits
		// stale media-flow state.
		if s.sendLeave(leaveUID) {
			_ = s.waitForAck(leaveUID, leaveAck, 2*time.Second)
		} else {
			s.removeAckWaiter(leaveUID)
		}
	}

	s.closeOnce.Do(func() { close(s.closeCh) })
	s.stopSession()

	s.closeDataChannel()
	s.closePeerConns()
	s.closeWebSocket()

	s.stopLaunching()

	done := make(chan struct{})
	go func() {
		s.wg.Wait()
		close(done)
	}()

	select {
	case <-done:
	case <-time.After(2 * time.Second):
	}
	return nil
}

// WatchConnection monitors the connection lifecycle and reconnects as needed.
func (s *Session) WatchConnection(ctx context.Context) {
	s.Watch(ctx, s.closeCh)
}

func (s *Session) reconnect(ctx context.Context) error {
	if s.terminated.Load() {
		return ErrSessionClosed
	}
	logger.Warnf("goolom: full reconnect triggered")
	s.reconnecting.Store(true)
	defer s.reconnecting.Store(false)

	s.sendLeave(uuid.New().String())
	if err := sleepCtx(ctx, 500*time.Millisecond); err != nil {
		return err
	}
	s.stopSession()

	s.closeDataChannel()
	s.closePeerConns()
	s.closeWebSocket()

	if err := sleepCtx(ctx, 3*time.Second); err != nil {
		return err
	}
	if s.refresh == nil {
		return ErrNoRefresh
	}
	creds, err := s.refresh(ctx)
	if err != nil {
		return fmt.Errorf("reconnect refresh: %w", err)
	}
	s.credMu.Lock()
	engine.ApplyRefreshedCredentials(creds, &s.mediaServerURL, &s.peerID, map[string]*string{
		credentialKeyRoomID:           &s.roomID,
		credentialKeyCredentials:      &s.credentials,
		credentialKeyRoomURL:          &s.roomURL,
		credentialKeyTelemetryReferer: &s.telemetryReferer,
	})
	s.credMu.Unlock()

	if err := s.Connect(ctx); err != nil {
		return err
	}
	s.NotifyReconnect()
	return nil
}

func (s *Session) queueReconnect() {
	s.Request(s.closed.Load(), s.reconnecting.Load())
}

// Reconnect asks the goolom session to tear down its peer connections and
// rejoin the room. Triggered by upper layers when they detect liveness loss
// before the underlying PC has reported failure (silent black-hole on the
// data path).
func (s *Session) Reconnect(reason string) {
	if s.closed.Load() {
		return
	}
	logger.Infof("goolom reconnect requested: %s", reason)
	s.stopSession()
	s.queueReconnect()
}

func (s *Session) stopSession() {
	s.stopTelemetry()
	s.sessionMu.Lock()
	engine.CloseSignal(s.keepAliveCh)
	engine.CloseSignal(s.sessionCloseCh)
	s.sessionMu.Unlock()
}

func (s *Session) resetSession() (chan struct{}, chan struct{}) {
	s.sessionMu.Lock()
	defer s.sessionMu.Unlock()
	s.keepAliveCh = make(chan struct{})
	s.sessionCloseCh = make(chan struct{})
	return s.keepAliveCh, s.sessionCloseCh
}

func (s *Session) resetMediaState() {
	s.subscriberReady.Store(false)
	s.publisherReady.Store(false)
	s.mediaMu.Lock()
	s.subscriberConn = make(chan struct{})
	s.mediaMu.Unlock()
}

func (s *Session) signalEnded(reason string) {
	s.closed.Store(true)
	s.stopTelemetry()
	s.SignalEnded(reason)
}
