package maxcalls

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
	"github.com/pion/webrtc/v4"

	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/protect"
)

// ai-generated: the whole file (the SFU's signaling socket).

// The SFU's frame kinds, commands and notifications the engine speaks.
const (
	frameResponse     = "response"
	frameNotification = "notification"
	frameError        = "error"

	cmdAcceptCall   = "accept-call"
	cmdTransmitData = "transmit-data"

	noteConnection      = "connection"
	noteRegisteredPeer  = "registered-peer"
	noteTransmittedData = "transmitted-data"

	keepalivePing = "ping"
	keepalivePong = "pong"

	// The keys of a relayed frame's command.
	keyType          = "type"
	keyParticipantID = "participantId"
	keyData          = "data"
	keySDP           = "sdp"
	keyCandidate     = "candidate"
)

const (
	// clientQuery is what the web client adds to the join endpoint: the SFU
	// answers a bare one "Parameter appVersion is required".
	clientQuery = "platform=web&appVersion=26.10.3&version=3&device=web&capabilities=webrtc&clientType=SDK_JS"
	// webOrigin and userAgent are the web client's.
	webOrigin = "https://web.max.ru"
	userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
		"(KHTML, like Gecko) Version/18.6 Safari/605.1.15"
	// dialWait bounds the socket's dial and handshake.
	dialWait = 15 * time.Second
	// writeWait bounds one frame's write, so a socket that stopped draining
	// cannot hold the writer lock for good.
	writeWait = 10 * time.Second
)

// ErrSignalingClosed is a frame written to a socket that has gone.
var ErrSignalingClosed = errors.New("max: signaling closed")

// frame is one message from the SFU: a command's response, a notification
// or an error. Every field a kind does not carry stays zero.
type frame struct {
	Type         string `json:"type"`
	Response     string `json:"response"`
	Notification string `json:"notification"`
	Sequence     int    `json:"sequence"`
	Error        string `json:"error"`
	Message      string `json:"message"`
	// ParticipantID is the uid a notification is about, and on a relayed
	// frame its sender.
	ParticipantID  int64           `json:"participantId"`  //nolint:tagliatelle // the SFU's wire is camelCase
	ParticipantIDs []int64         `json:"participantIds"` //nolint:tagliatelle // the SFU's wire is camelCase
	PeerID         peerRef         `json:"peerId"`         //nolint:tagliatelle // the SFU's wire is camelCase
	Data           json.RawMessage `json:"data"`
	Conversation   struct {
		ID       string `json:"id"`
		Topology string `json:"topology"`
	} `json:"conversation"`
	Params conversationParams `json:"conversationParams"` //nolint:tagliatelle // the SFU's wire is camelCase

	// ping is the raw keepalive, the one frame that is not JSON.
	ping bool
}

// peerRef is a connection's id: one join of a participant. A participant
// that joins again keeps its uid and gets a new one.
type peerRef struct {
	ID int64 `json:"id"`
}

// conversationParams is what the connection frame says about ICE.
type conversationParams struct {
	TURN iceServer `json:"turn"`
	STUN iceServer `json:"stun"`
}

type iceServer struct {
	URLs       []string `json:"urls"`
	Username   string   `json:"username"`
	Credential string   `json:"credential"`
}

// relayPayload is the data of a relayed frame: one SDP or one candidate.
type relayPayload struct {
	SDP       *webrtc.SessionDescription `json:"sdp"`
	Candidate *webrtc.ICECandidateInit   `json:"candidate"`
}

// signaling is the JSON socket to the SFU. One goroutine reads it; every
// write goes through the mutex, as gorilla allows one writer at a time.
type signaling struct {
	conn *websocket.Conn
	mu   sync.Mutex
	seq  int
}

// dialSignaling opens the socket of a join endpoint.
func dialSignaling(ctx context.Context, endpoint string, resolver protect.Lookup) (*signaling, error) {
	dialer := protect.NewWebSocketDialer(dialWait, resolver)
	header := http.Header{}
	header.Set("Origin", webOrigin)
	header.Set("User-Agent", userAgent)
	conn, resp, err := dialer.DialContext(ctx, withClientQuery(endpoint), header)
	if resp != nil && resp.Body != nil {
		_ = resp.Body.Close()
	}
	if err != nil {
		return nil, fmt.Errorf("max: signaling dial: %w", err)
	}
	return &signaling{conn: conn}, nil
}

// withClientQuery completes the endpoint's query with the web client's.
func withClientQuery(endpoint string) string {
	if strings.Contains(endpoint, "?") {
		return endpoint + "&" + clientQuery
	}
	return endpoint + "?" + clientQuery
}

// read returns the next frame. A frame that is not JSON is skipped; one
// with a field of an unexpected type keeps the fields that did parse.
func (s *signaling) read() (*frame, error) {
	for {
		kind, data, err := s.conn.ReadMessage()
		if err != nil {
			return nil, fmt.Errorf("max: signaling read: %w", err)
		}
		if kind != websocket.TextMessage {
			continue
		}
		if string(data) == keepalivePing {
			return &frame{ping: true}, nil
		}
		var f frame
		if err := json.Unmarshal(data, &f); err != nil {
			var typeErr *json.UnmarshalTypeError
			if !errors.As(err, &typeErr) {
				logger.Debugf("max: a %d-byte frame that is not JSON, skipped", len(data))
				continue
			}
		}
		return &f, nil
	}
}

// awaitConnection reads up to the SFU's connection frame, the one that
// assigns our peer id and names the room's topology; a keepalive before it
// is answered. Nothing else is expected before it.
func (s *signaling) awaitConnection(wait time.Duration) (*frame, error) {
	_ = s.conn.SetReadDeadline(time.Now().Add(wait))
	defer func() { _ = s.conn.SetReadDeadline(time.Time{}) }()
	for {
		f, err := s.read()
		if err != nil {
			return nil, fmt.Errorf("%w: %w", ErrNoConnectionFrame, err)
		}
		switch {
		case f.ping:
			if err := s.pong(); err != nil {
				return nil, err
			}
		case f.Type == frameNotification && f.Notification == noteConnection && f.PeerID.ID != 0:
			return f, nil
		default:
			logger.Debugf("max: a %s frame before the connection frame, skipped", f.Type)
		}
	}
}

// command writes one command with the next sequence number and returns it.
func (s *signaling) command(name string, params map[string]any) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.seq++
	msg := make(map[string]any, len(params)+2)
	for k, v := range params {
		msg[k] = v
	}
	msg["command"] = name
	msg["sequence"] = s.seq
	_ = s.conn.SetWriteDeadline(time.Now().Add(writeWait))
	if err := s.conn.WriteJSON(msg); err != nil {
		return 0, fmt.Errorf("%w: %s: %w", ErrSignalingClosed, name, err)
	}
	return s.seq, nil
}

// acceptCall takes our seat with the camera flag the web client sends; it
// publishes nothing.
func (s *signaling) acceptCall() (int, error) {
	return s.command(cmdAcceptCall, map[string]any{
		"mediaSettings": map[string]bool{
			"isAudioEnabled": false, "isVideoEnabled": true,
			"isScreenSharingEnabled": false, "isFastScreenSharingEnabled": false,
			"isAudioSharingEnabled": false, "isAnimojiEnabled": false,
		},
	})
}

// relaySDP sends an offer or an answer to a participant.
func (s *signaling) relaySDP(to int64, desc webrtc.SessionDescription) error {
	_, err := s.command(cmdTransmitData, map[string]any{
		keyParticipantID: to,
		keyData: map[string]any{
			keySDP:           map[string]string{keyType: desc.Type.String(), keySDP: desc.SDP},
			"animojiVersion": 1,
		},
	})
	return err
}

// relayCandidate sends one ICE candidate: one a frame, under the relay's
// size cap, with the three fields the measured exchange carried (pion's own
// encoding adds a null usernameFragment).
func (s *signaling) relayCandidate(to int64, c webrtc.ICECandidateInit) error {
	cand := map[string]any{keyCandidate: c.Candidate}
	if c.SDPMid != nil {
		cand["sdpMid"] = *c.SDPMid
	}
	if c.SDPMLineIndex != nil {
		cand["sdpMLineIndex"] = *c.SDPMLineIndex
	}
	_, err := s.command(cmdTransmitData, map[string]any{
		keyParticipantID: to,
		keyData:          map[string]any{keyCandidate: cand},
	})
	return err
}

// pong answers the raw keepalive.
func (s *signaling) pong() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	_ = s.conn.SetWriteDeadline(time.Now().Add(writeWait))
	if err := s.conn.WriteMessage(websocket.TextMessage, []byte(keepalivePong)); err != nil {
		return fmt.Errorf("%w: pong: %w", ErrSignalingClosed, err)
	}
	return nil
}

// close shuts the socket; the reader returns.
func (s *signaling) close() {
	_ = s.conn.Close()
}
