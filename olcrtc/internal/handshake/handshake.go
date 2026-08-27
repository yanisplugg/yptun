// Package handshake implements the olcrtc session handshake.
//
// The handshake runs on the first smux stream (control stream) of a tunnel.
// Wire format on the control stream is length-prefixed JSON: each message is
// a 4-byte big-endian length followed by that many bytes of JSON.
//
//	client                     server
//	  │  CLIENT_HELLO          │
//	  │ ─────────────────────► │
//	  │                        │ AuthHook(claims) → sessionID | err
//	  │  SERVER_WELCOME / REJECT│
//	  │ ◄───────────────────── │
//	  │                        │
//
// After the exchange the control stream stays open; tunnel traffic flows over
// additional smux streams opened by the client. The control stream then
// carries ping/pong liveness and future control messages.
package handshake

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/framing"
)

// ProtoVersion identifies the wire-format version. Bumped only on breaking
// changes to message layout or semantics.
const ProtoVersion = 3

// challengeSize gives every handshake a fresh 128-bit identity. The server
// echoes it in every reply so a valid encrypted reply captured for one client
// cannot be retargeted to another client that shares the room and PSK.
const challengeSize = 16

// MaxMessageSize caps a single handshake frame. 64 KiB is comfortably larger
// than any legitimate HELLO/WELCOME payload and prevents memory blowups from
// malicious peers.
const MaxMessageSize = 64 * 1024

// DefaultTimeout bounds how long either side will wait for the peer's reply
// before bailing out.
const DefaultTimeout = 15 * time.Second

// MsgType labels each protocol message.
type MsgType string

const (
	// TypeHello is the client's first message.
	TypeHello MsgType = "CLIENT_HELLO"
	// TypeWelcome is the server's success reply.
	TypeWelcome MsgType = "SERVER_WELCOME"
	// TypeReject is the server's failure reply.
	TypeReject MsgType = "SERVER_REJECT"
)

// Hello is sent by the client to begin a session.
type Hello struct {
	Version   int            `json:"version"`
	Type      MsgType        `json:"type"`
	DeviceID  string         `json:"device_id"`
	Challenge string         `json:"challenge"`
	Claims    map[string]any `json:"claims,omitempty"`
}

// Welcome is the server's response on a successful handshake.
type Welcome struct {
	Version   int     `json:"version"`
	Type      MsgType `json:"type"`
	SessionID string  `json:"session_id"`
	PeerID    string  `json:"peer_id,omitempty"`
	Challenge string  `json:"challenge"`
}

// Reject is the server's response when auth fails.
type Reject struct {
	Version   int     `json:"version"`
	Type      MsgType `json:"type"`
	Reason    string  `json:"reason"`
	Challenge string  `json:"challenge,omitempty"`
}

// Errors returned by [Client] and [Server].
var (
	// ErrRejected wraps a server-side rejection. The reason is in the error message.
	ErrRejected = errors.New("handshake rejected")
	// ErrProtocolVersion is returned when peer announces an incompatible version.
	ErrProtocolVersion = errors.New("incompatible protocol version")
	// ErrUnexpectedMessage is returned when a peer sends the wrong message type.
	ErrUnexpectedMessage = errors.New("unexpected handshake message")
	// ErrFrameTooLarge is returned when a peer announces a frame above [MaxMessageSize].
	ErrFrameTooLarge = framing.ErrFrameTooLarge
	// ErrChallengeMismatch is returned for a reply to a different CLIENT_HELLO.
	ErrChallengeMismatch = errors.New("handshake challenge mismatch")
	// ErrChallengeRequired is returned when CLIENT_HELLO lacks a valid challenge.
	ErrChallengeRequired = errors.New("handshake challenge required")
)

// AuthFunc is invoked by [Server] after parsing CLIENT_HELLO.
// It returns the session ID to send back to the client, or an error to reject
// the connection. The error's message is forwarded to the client as the
// reject reason, so it should not leak sensitive details.
type AuthFunc func(deviceID string, claims map[string]any) (sessionID string, err error)

// Client performs the client side of the handshake on rw and returns the
// session ID and authenticated routing peer ID assigned by the server.
func Client(rw io.ReadWriter, deviceID string, claims map[string]any) (string, string, error) {
	challenge, err := newChallenge()
	if err != nil {
		return "", "", err
	}
	hello := Hello{
		Version:   ProtoVersion,
		Type:      TypeHello,
		DeviceID:  deviceID,
		Challenge: challenge,
		Claims:    claims,
	}
	if err := writeFrame(rw, hello); err != nil {
		return "", "", fmt.Errorf("send hello: %w", err)
	}

	for {
		sessionID, peerID, matched, replyErr := readReply(rw, challenge)
		if !matched {
			continue
		}

		return sessionID, peerID, replyErr
	}
}

func readReply(rw io.Reader, challenge string) (string, string, bool, error) {
	raw, err := readFrame(rw)
	if err != nil {
		return "", "", true, fmt.Errorf("read welcome: %w", err)
	}

	var probe struct {
		Type      MsgType `json:"type"`
		Challenge string  `json:"challenge"`
	}
	if err := json.Unmarshal(raw, &probe); err != nil {
		return "", "", true, fmt.Errorf("parse reply: %w", err)
	}
	if probe.Challenge != challenge {
		return "", "", false, nil
	}

	switch probe.Type {
	case TypeWelcome:
		sessionID, peerID, parseErr := parseWelcome(raw, challenge)
		return sessionID, peerID, true, parseErr
	case TypeReject:
		return "", "", true, parseReject(raw, challenge)
	case TypeHello:
		return "", "", true, fmt.Errorf("%w: got %q", ErrUnexpectedMessage, probe.Type)
	default:
		return "", "", true, fmt.Errorf("%w: got %q", ErrUnexpectedMessage, probe.Type)
	}
}

func parseWelcome(raw []byte, challenge string) (string, string, error) {
	var w Welcome
	if err := json.Unmarshal(raw, &w); err != nil {
		return "", "", fmt.Errorf("parse welcome: %w", err)
	}
	if w.Version != ProtoVersion {
		return "", "", fmt.Errorf("%w: server v%d, client v%d",
			ErrProtocolVersion, w.Version, ProtoVersion)
	}
	if w.Challenge != challenge {
		return "", "", ErrChallengeMismatch
	}
	return w.SessionID, w.PeerID, nil
}

func parseReject(raw []byte, challenge string) error {
	var r Reject
	if err := json.Unmarshal(raw, &r); err != nil {
		return fmt.Errorf("parse reject: %w", err)
	}
	if r.Challenge != challenge {
		return ErrChallengeMismatch
	}
	return fmt.Errorf("%w: %s", ErrRejected, r.Reason)
}

func newChallenge() (string, error) {
	var raw [challengeSize]byte
	if _, err := rand.Read(raw[:]); err != nil {
		return "", fmt.Errorf("generate handshake challenge: %w", err)
	}

	return hex.EncodeToString(raw[:]), nil
}

// Server performs the server side of the handshake. It reads CLIENT_HELLO,
// invokes auth, and writes the corresponding WELCOME or REJECT. On success it
// returns the parsed Hello and the session ID produced by auth.
func Server(rw io.ReadWriter, auth AuthFunc, peerID string) (Hello, string, error) {
	raw, err := readFrame(rw)
	if err != nil {
		return Hello{}, "", fmt.Errorf("read hello: %w", err)
	}

	var h Hello
	if parseErr := json.Unmarshal(raw, &h); parseErr != nil {
		_ = writeFrame(rw, Reject{Version: ProtoVersion, Type: TypeReject, Reason: "malformed hello"})
		return Hello{}, "", fmt.Errorf("parse hello: %w", parseErr)
	}
	if h.Type != TypeHello {
		_ = writeFrame(rw, Reject{Version: ProtoVersion, Type: TypeReject, Reason: "expected CLIENT_HELLO"})
		return h, "", fmt.Errorf("%w: got %q", ErrUnexpectedMessage, h.Type)
	}
	if h.Version != ProtoVersion {
		_ = writeFrame(rw, Reject{
			Version: ProtoVersion, Type: TypeReject,
			Reason: "protocol version mismatch", Challenge: h.Challenge,
		})
		return h, "", fmt.Errorf("%w: client v%d, server v%d",
			ErrProtocolVersion, h.Version, ProtoVersion)
	}
	if !validChallenge(h.Challenge) {
		_ = writeFrame(rw, Reject{
			Version: ProtoVersion, Type: TypeReject,
			Reason: "invalid handshake challenge", Challenge: h.Challenge,
		})
		return h, "", ErrChallengeRequired
	}

	sessionID, err := auth(h.DeviceID, h.Claims)
	if err != nil {
		_ = writeFrame(rw, Reject{
			Version: ProtoVersion, Type: TypeReject,
			Reason: err.Error(), Challenge: h.Challenge,
		})
		return h, "", fmt.Errorf("auth: %w", err)
	}

	if err := writeFrame(rw, Welcome{
		Version:   ProtoVersion,
		Type:      TypeWelcome,
		SessionID: sessionID,
		PeerID:    peerID,
		Challenge: h.Challenge,
	}); err != nil {
		return h, sessionID, fmt.Errorf("send welcome: %w", err)
	}
	return h, sessionID, nil
}

func validChallenge(challenge string) bool {
	decoded, err := hex.DecodeString(challenge)
	return err == nil && len(decoded) == challengeSize
}

func writeFrame(w io.Writer, msg any) error {
	if err := framing.WriteJSON(w, msg, MaxMessageSize); err != nil {
		return fmt.Errorf("handshake: %w", err)
	}
	return nil
}

func readFrame(r io.Reader) ([]byte, error) {
	body, err := framing.ReadBytes(r, MaxMessageSize)
	if err != nil {
		return nil, fmt.Errorf("handshake: %w", err)
	}
	return body, nil
}
