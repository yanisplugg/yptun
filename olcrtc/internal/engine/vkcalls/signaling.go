package vkcalls

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"sync"
	"time"

	"github.com/gorilla/websocket"

	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/protect"
)

// Signaling errors, all static for wrapping discipline.
var (
	ErrSignalingEndpoint  = errors.New("vkcalls: invalid signaling endpoint")
	ErrParticipantID      = errors.New("vkcalls: invalid participant id")
	ErrSignalingClosed    = errors.New("vkcalls: signaling socket closed")
	ErrSignalingRejected  = errors.New("vkcalls: signaling command rejected")
	ErrSignalingTimeout   = errors.New("vkcalls: signaling response timeout")
	ErrSignalingNotServer = errors.New("vkcalls: conversation is not in SERVER topology")
	// ErrSignalingPayload refuses a command payload that is not a JSON object.
	ErrSignalingPayload = errors.New("vkcalls: signaling command payload")
	// ErrSignalingFrameTooLarge refuses a command that would not fit one frame.
	ErrSignalingFrameTooLarge = errors.New("vkcalls: signaling command exceeds one frame")
)

const (
	wsHandshakeTimeout = 20 * time.Second
	commandTimeout     = 15 * time.Second
	connectionTimeout  = 30 * time.Second

	// signalingWriteBuffer bounds one command frame. The SFU parses every
	// text frame on its own and answers a continuation-split message with
	// invalid-request, so each command must leave as a single frame, and
	// gorilla splits a client message at its write buffer (4 KiB by
	// default). An accept-producer answer is ~50 KB for the 41-section
	// offer; the buffer leaves room for about five times that.
	signalingWriteBuffer = 256 << 10

	// Wire field names and values used more than once.
	fieldSequence    = "sequence"
	fieldDescription = "description"
	fieldPeerID      = "peerId"
	noteConnection   = "connection"
	noteTopology     = "topology-changed"
	frameTypeError   = "error"
	reasonHungup     = "HUNGUP"
	topologyServer   = "SERVER"

	// capabilitiesBitmask is the feature set this engine actually implements:
	// unified plan, single session and the producer-command data channel. The
	// SDK reference mask is a diagnostic control in the spike, not a constant
	// to copy (spec 7.4).
	capabilitiesBitmask = "2F7F"
)

// notification is one server frame. Only the fields the engine acts on are
// decoded; everything else stays raw for diagnostics.
type notification struct {
	Type         string `json:"type"`
	Sequence     int64  `json:"sequence"`
	Response     string `json:"response"`
	Notification string `json:"notification"`
	Stamp        int64  `json:"stamp"`
	PeerID       *struct {
		ID int64 `json:"id"`
	} `json:"peerId"` //nolint:tagliatelle // connector wire is camelCase
	SessionID   string `json:"sessionId"` //nolint:tagliatelle // connector wire is camelCase
	Description string `json:"description"`
	// Topology is the topology-changed notification's value; the
	// connection notification carries it inside Conversation instead.
	Topology     string `json:"topology"`
	Conversation *struct {
		Topology     string `json:"topology"`
		Participants []struct {
			ID    any    `json:"id"`
			State string `json:"state"`
		} `json:"participants"`
	} `json:"conversation"`
	ParticipantID any `json:"participantId"` //nolint:tagliatelle // connector wire is camelCase
}

// signalingClient is one signaling WebSocket: a single reader goroutine, a
// serialized writer and pending commands correlated by sequence.
type signalingClient struct {
	conn    *websocket.Conn
	writeMu sync.Mutex
	pending sync.Map // int64 -> chan notification

	sequence int64
	stamp    int64
	self     string

	notifications chan notification
	closed        chan struct{}
	closeOnce     sync.Once
}

// dialSignaling opens the signaling socket. The endpoint comes from the auth
// provider's join response and already carries the token and userId query.
func dialSignaling(ctx context.Context, endpoint string, resolver protect.Lookup) (*signalingClient, error) {
	target, err := signalingURL(endpoint)
	if err != nil {
		return nil, err
	}
	dialer := protect.NewWebSocketDialer(wsHandshakeTimeout, resolver)
	dialer.WriteBufferSize = signalingWriteBuffer
	if parsed, parseErr := url.Parse(target); parseErr == nil {
		q := parsed.Query()
		for _, k := range []string{"token", "userId", "conversationId", fieldPeerID} {
			if q.Has(k) {
				q.Set(k, "<r>")
			}
		}
		logger.Debugf("vkcalls: signaling url %s?%s", parsed.Path, q.Encode())
	}
	conn, response, err := dialer.DialContext(ctx, target, http.Header{
		"Origin":  {"https://vk.com"},
		"Referer": {"https://vk.com/"},
	})
	if response != nil && response.Body != nil {
		_ = response.Body.Close()
	}
	if err != nil {
		return nil, fmt.Errorf("vkcalls: signaling dial: %w", err)
	}
	client := &signalingClient{
		conn:          conn,
		self:          selfParticipant(endpoint),
		notifications: make(chan notification, 16),
		closed:        make(chan struct{}),
	}
	go client.readLoop()
	return client, nil
}

// signalingURL extends the join endpoint with the client parameters the SDK
// sends on a fresh join. A peer id belongs to a retry join only; carrying it
// on a fresh join makes the SFU answer the session differently.
func signalingURL(endpoint string) (string, error) {
	u, err := url.Parse(endpoint)
	// ws:// is the local-test transport; production endpoints arrive from the
	// auth provider, which accepts only wss.
	if err != nil || (u.Scheme != "wss" && u.Scheme != "ws") {
		return "", fmt.Errorf("%w: %w", ErrSignalingEndpoint, err)
	}
	q := u.Query()
	q.Set("platform", "WEB")
	q.Set("appVersion", "1.1")
	q.Set("version", "5")
	q.Set("device", "browser")
	q.Set("capabilities", capabilitiesBitmask)
	q.Set("clientType", "VK")
	q.Set("tgt", "join")
	u.RawQuery = q.Encode()
	return u.String(), nil
}

func selfParticipant(endpoint string) string {
	u, err := url.Parse(endpoint)
	if err != nil {
		return ""
	}
	return u.Query().Get("userId")
}

// Self returns this session's participant id.
func (c *signalingClient) Self() string { return c.self }

// Notifications exposes the server frames; the reader drops nothing.
func (c *signalingClient) Notifications() <-chan notification { return c.notifications }

// Closed is closed once the socket is gone.
func (c *signalingClient) Closed() <-chan struct{} { return c.closed }

// readLoop is the socket's only reader.
func (c *signalingClient) readLoop() {
	defer c.shutdown()
	for {
		messageType, data, err := c.conn.ReadMessage()
		if err != nil {
			return
		}
		if messageType != websocket.TextMessage {
			continue
		}
		if string(data) == "ping" {
			_ = c.write(websocket.TextMessage, []byte("pong"))
			continue
		}
		var note notification
		if err := json.Unmarshal(data, &note); err != nil {
			// Frames are never logged raw: they carry the endpoint's token.
			logger.Debugf("vkcalls: invalid signaling frame: %d bytes: %v", len(data), err)
			continue
		}
		if note.Stamp != 0 {
			c.stamp = note.Stamp
		}
		c.deliver(note)
	}
}

func (c *signalingClient) deliver(note notification) {
	if note.Sequence != 0 {
		if loaded, ok := c.pending.LoadAndDelete(note.Sequence); ok {
			if ch, ok := loaded.(chan notification); ok {
				select {
				case ch <- note:
				default:
				}
			}
			return
		}
	}
	select {
	case c.notifications <- note:
	case <-c.closed:
	default:
		logger.Debugf("vkcalls: notification dropped, queue full")
	}
}

// command sends one command frame and waits for its response or error frame.
func (c *signalingClient) command(ctx context.Context, name string, payload any) (notification, error) {
	c.writeMu.Lock()
	sequence := c.sequence + 1
	c.sequence = sequence
	// The response can arrive before the write returns: the waiter is in
	// place before the first byte goes out.
	ch := make(chan notification, 1)
	c.pending.Store(sequence, ch)
	defer c.pending.Delete(sequence)
	raw, err := orderedCommandFrame(name, sequence, payload)
	if err == nil && len(raw) > signalingWriteBuffer {
		err = fmt.Errorf("%w: %d bytes", ErrSignalingFrameTooLarge, len(raw))
	}
	if err == nil {
		err = c.conn.WriteMessage(websocket.TextMessage, raw)
	}
	c.writeMu.Unlock()
	if err != nil {
		return notification{}, fmt.Errorf("vkcalls: signaling write %s: %w", name, err)
	}

	timer := time.NewTimer(commandTimeout)
	defer timer.Stop()
	select {
	case note := <-ch:
		if note.Type == frameTypeError {
			return note, fmt.Errorf("%w: %s", ErrSignalingRejected, name)
		}
		return note, nil
	case <-timer.C:
		return notification{}, fmt.Errorf("%w: %s", ErrSignalingTimeout, name)
	case <-c.closed:
		return notification{}, fmt.Errorf("%w: %s", ErrSignalingClosed, name)
	case <-ctx.Done():
		return notification{}, fmt.Errorf("vkcalls: signaling %s: %w", name, ctx.Err())
	}
}

// orderedCommandFrame renders a command frame with command and sequence
// first, then the payload's own fields: the order the SDK writes. The
// invalid-request first blamed on a sorted-key map marshal was the
// continuation split (see signalingWriteBuffer); the SDK order stays, as the
// captured frames are the only shape known to be accepted.
func orderedCommandFrame(name string, sequence int64, payload any) ([]byte, error) {
	nameJSON, err := json.Marshal(name)
	if err != nil {
		return nil, err //nolint:wrapcheck // marshalling a plain string
	}
	head := append([]byte(`{"command":`), nameJSON...)
	// The SDK's key order, as captured.
	//nolint:gocritic // building JSON in a fixed key order
	head = append(head, fmt.Sprintf(`,"%s":%d,`, fieldSequence, sequence)...)
	body, err := json.Marshal(payload)
	if err != nil {
		return nil, err //nolint:wrapcheck // caller-provided payload
	}
	// No payload, or one without fields, closes the frame after the sequence.
	if bytes.Equal(body, []byte("null")) || bytes.Equal(body, []byte("{}")) {
		return append(head[:len(head)-1], '}'), nil
	}
	if body[0] != '{' {
		return nil, fmt.Errorf("%w: %s payload is not an object", ErrSignalingPayload, name)
	}
	body = bytes.TrimPrefix(body, []byte{'{'})
	body = bytes.TrimSuffix(body, []byte{'}'})
	out := head
	out = append(out, body...)
	return append(out, '}'), nil
}

// write is the serialized low-level writer used by pong and hangup.
func (c *signalingClient) write(messageType int, data []byte) error {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	return c.conn.WriteMessage(messageType, data) //nolint:wrapcheck // transport-level failure, caller logs
}

// hangup leaves the conversation best-effort before close.
func (c *signalingClient) hangup() {
	raw, err := json.Marshal(map[string]any{"command": "hangup", fieldSequence: c.sequence + 1, "reason": reasonHungup})
	if err != nil {
		return
	}
	_ = c.write(websocket.TextMessage, raw)
}

func (c *signalingClient) shutdown() {
	c.closeOnce.Do(func() {
		_ = c.conn.Close()
		close(c.closed)
		c.pending.Range(func(_, value any) bool {
			if ch, ok := value.(chan notification); ok {
				select {
				case ch <- notification{Type: frameTypeError}:
				default:
				}
			}
			return true
		})
	})
}

// close hangs up and tears the socket down.
func (c *signalingClient) close() {
	c.hangup()
	c.shutdown()
}

// awaitJoin waits for this socket's connection notification - the moment
// the participant counts in the conversation - whatever its topology.
func (c *signalingClient) awaitJoin(ctx context.Context) (notification, error) {
	timer := time.NewTimer(connectionTimeout)
	defer timer.Stop()
	for {
		select {
		case note := <-c.notifications:
			if note.Notification == noteConnection {
				return note, nil
			}
		case <-timer.C:
			return notification{}, fmt.Errorf("%w: connection", ErrSignalingTimeout)
		case <-c.closed:
			return notification{}, fmt.Errorf("%w: connection", ErrSignalingClosed)
		case <-ctx.Done():
			return notification{}, fmt.Errorf("vkcalls: connection wait: %w", ctx.Err())
		}
	}
}

// drainNotifications consumes the socket's notifications until they end, so
// a signaling-only guest's queue never fills: its reader drops nothing and
// the debug log stays quiet.
func (c *signalingClient) drainNotifications() {
	for {
		select {
		case <-c.notifications:
		case <-c.closed:
			return
		}
	}
}

// waitServerTopology ends when the conversation stands in SERVER topology -
// immediately for a room that opened there, and on the topology-changed
// notification a third simultaneous participant triggers for a fresh room's
// DIRECT (two alone never switch; see recruitGuests).
func (c *signalingClient) waitServerTopology(ctx context.Context, connection notification) error {
	waited := false
	for connection.Conversation == nil || connection.Conversation.Topology != topologyServer {
		if !waited {
			waited = true
			logger.Debugf("vkcalls: conversation opened outside SERVER topology; " +
				"waiting for the switch a second participant triggers")
		}
		select {
		case note := <-c.notifications:
			switch {
			case note.Notification == noteTopology && note.Topology == topologyServer:
				// The connection note's participant list is stale now; the
				// run loop learns every join as its own notification.
				return nil
			case note.Notification == noteConnection:
				connection = note
			}
		case <-c.closed:
			return fmt.Errorf("%w: waiting for SERVER topology", ErrSignalingClosed)
		case <-ctx.Done():
			return fmt.Errorf("%w: %w", ErrSignalingNotServer, ctx.Err())
		}
	}
	return nil
}

// participantIDs returns the numeric ids of participants that are still in
// the conversation, as decimal strings (json.Number-safe).
func participantIDs(note notification, self string) []string {
	if note.Conversation == nil {
		return nil
	}
	var out []string
	for _, p := range note.Conversation.Participants {
		if p.State == reasonHungup || p.State == "REJECTED" {
			continue
		}
		id, err := decimalID(p.ID)
		if err != nil || id == self {
			continue
		}
		out = append(out, id)
	}
	return out
}

func decimalID(v any) (string, error) {
	switch t := v.(type) {
	case float64:
		if t != float64(int64(t)) {
			return "", fmt.Errorf("%w: non-integer", ErrParticipantID)
		}
		return strconv.FormatInt(int64(t), 10), nil
	case string:
		if _, err := strconv.ParseInt(t, 10, 64); err != nil {
			return "", fmt.Errorf("%w: non-numeric", ErrParticipantID)
		}
		return t, nil
	default:
		return "", fmt.Errorf("%w: unsupported type", ErrParticipantID)
	}
}
