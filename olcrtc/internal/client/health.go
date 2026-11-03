package client

import (
	"context"
	"errors"
	"sync"
	"time"

	"github.com/xtaci/smux"

	"github.com/openlibrecommunity/olcrtc/internal/control"
	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
	"github.com/openlibrecommunity/olcrtc/internal/tunnelcore"
)

func (c *Client) startControlLoop(
	ctx context.Context,
	cfg Config,
	cancel context.CancelFunc,
	stream *smux.Stream,
) {
	controlCtx, stop := context.WithCancel(ctx)
	// The peer is told we are leaving on this stream, once, whoever gets
	// there first: the watcher inside control.Run when a stop cancels the
	// context, or the teardown below when it ends the session itself. Once
	// makes the second caller wait for the first, so nothing closes the
	// transport out from under a notice that is still on its way.
	var notified sync.Once
	notify := func() {
		notified.Do(func() { tunnelcore.NotifyControlClose(stream) })
	}
	c.sessMu.Lock()
	c.controlStop = stop
	c.controlNotify = notify
	c.sessMu.Unlock()
	pingInterval := cfg.Liveness.Interval
	if pingInterval <= 0 {
		pingInterval = control.DefaultInterval
	}
	runner := tunnelcore.ControlRunner{
		Transport: c.ln, Config: cfg.Liveness, Health: c.health,
		BeforeClose: notify,
		LogFields: func() string {
			c.sessMu.RLock()
			defer c.sessMu.RUnlock()
			return "role=client session=" + c.sessionID
		},
		OnPong: func(control.Health) {
			c.controlLastPong.Store(time.Now())
			c.notifyLinkHealth(false)
		},
		// A peer that closes the control stream on purpose says this
		// session is over, not that this room is: our own server sends the
		// same notice when its provider rebuilt underneath it and when its
		// liveness gave up on us, and in both it is still in the room and
		// about to answer again. So the close is taken as the death of the
		// session it belongs to, told apart from a silent one only in the
		// log and in how fast it is noticed - a close arrives at once, a
		// silence takes the liveness window. What decides whether the room
		// is worth keeping is the handshake that follows: see afterFailedRound.
		//
		// ai-generated: the closed-by-peer branch (the port of olcrtc#39,
		// resolved against olcrtc#19's recovery).
		OnDeath: func(err error) {
			reason := reconnectLiveness
			if errors.Is(err, control.ErrClosedByPeer) {
				reason = reconnectPeerClose
			}
			c.onSessionDeath(ctx, cfg, cancel, stream, reason)
		},
		// Payload on our streams, not every frame that opens: the server's
		// next session seals under the same key and its frames would vouch
		// for a session it has already closed (olcbox#25).
		Progress: func() uint64 {
			c.sessMu.RLock()
			defer c.sessMu.RUnlock()
			return c.conn.PayloadBytes()
		},
		// The data conn, not the control one: the control plane is a separate
		// KCP session and stays healthy while the data plane is wedged. That
		// disagreement is the whole point of asking.
		SendStalled: func() bool {
			c.sessMu.RLock()
			defer c.sessMu.RUnlock()
			return c.conn.SendStalled()
		},
	}
	c.goTracked(func() { c.watchControlStaleness(controlCtx, pingInterval) })
	c.goTracked(func() { runner.Run(controlCtx, stream) })
}

// watchControlStaleness is not a second reconnect detector. It supplies early
// session-specific evidence to LinkHealthObserver after two missed intervals,
// while control.Run retains sole ownership of session teardown and reconnect.
func (c *Client) watchControlStaleness(ctx context.Context, interval time.Duration) {
	const staleFactor = 2
	threshold := staleFactor * interval
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			last, ok := c.controlLastPong.Load().(time.Time)
			c.notifyLinkHealth(ok && time.Since(last) > threshold)
		}
	}
}

// Status returns the latest client-side control health snapshot.
func (c *Client) Status() control.Status {
	return c.health.Status()
}

func (c *Client) notifyLinkHealth(unhealthy bool) {
	if observer, ok := c.ln.(transport.LinkHealthObserver); ok {
		observer.NotifyLinkHealth(unhealthy)
	}
}

func (c *Client) shutdown() {
	c.sessMu.RLock()
	notify := c.controlNotify
	c.sessMu.RUnlock()
	// Before anything is closed, and synchronously: a notice still in flight
	// when the transport goes is a notice the server never reads.
	if notify != nil {
		notify()
	}
	c.sessMu.Lock()
	pair := c.pair
	controlStop := c.controlStop
	session := c.session
	controlSession := c.controlSess
	conn := c.conn
	controlConn := c.controlConn
	c.pair = nil
	controlStream := c.controlStrm
	c.controlStrm, c.controlStop = nil, nil
	c.controlNotify = nil
	c.session, c.controlSess = nil, nil
	c.conn, c.controlConn = nil, nil
	c.sessMu.Unlock()
	if controlStop != nil {
		controlStop()
	}
	closeClientPair(pair, session, controlSession)
	if pair == nil {
		if conn != nil {
			_ = conn.Close()
		}
		if controlConn != nil {
			_ = controlConn.Close()
		}
	}
	if c.ln != nil {
		_ = c.ln.Close()
	}
	if controlStream != nil {
		_ = controlStream.Close()
	}
	c.closeSocksConns()
	c.waitGoroutines()
}

func (c *Client) waitGoroutines() {
	grace := c.shutdownGrace
	if grace <= 0 {
		grace = defaultShutdownGrace
	}
	done := make(chan struct{})
	go func() {
		c.wg.Wait()
		close(done)
	}()
	timer := time.NewTimer(grace)
	defer timer.Stop()
	select {
	case <-done:
	case <-timer.C:
		logger.Warnf("client shutdown: goroutines still running after %s", grace)
	}
}

func (c *Client) onData(data []byte) {
	c.sessMu.RLock()
	conn := c.conn
	c.sessMu.RUnlock()
	tunnelcore.PushData(conn, data)
}
