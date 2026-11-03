// Package maxcalls is the auth provider for calls of the MAX messenger
// (VK). A call is joined with a calls session on the ok-calls API: the
// session key asks vchat.joinConversationByLink for the room's connection -
// the SFU's WebSocket endpoint, the signaling token, the TURN and STUN
// servers - which is what the engine joins with.
//
// Config.Token is the calls session key, used as it is, or a calls token of
// the web client (the "An_" or "$"-prefixed value the SDK keeps in the
// browser's storage), exchanged for a session first through
// auth.anonymLogin. The room is a join link, https://max.ru/joincall/<hash>.
// Neither the token nor the session key is returned in the credentials.
package maxcalls

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/auth"
	"github.com/openlibrecommunity/olcrtc/internal/protect"
)

// ai-generated: the whole file.

const (
	defaultAPIBase = "https://calls.okcdn.ru"
	// applicationKey is the calls SDK's public application key, sent with
	// every fb.do call of the web client.
	applicationKey = "CNHIJPLGDIHBABABA"
	// clientVersion is the web client's, as its SDK names it to the API.
	clientVersion = "26.10.3"
	webOrigin     = "https://web.max.ru"
	userAgent     = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
		"(KHTML, like Gecko) Version/18.6 Safari/605.1.15"

	methodAnonymLogin = "auth.anonymLogin"
	methodJoinByLink  = "vchat.joinConversationByLink"
	fieldSessionKey   = "session_key"
	strTrue           = "true"

	joinPath    = "/joincall/"
	requestWait = 20 * time.Second
	bodyLimit   = 1 << 20
)

// Errors.
var (
	ErrTokenRequired    = errors.New("maxcalls: a calls session key or token (auth.token) is required")
	ErrJoinLinkRequired = errors.New("maxcalls: a join link (https://max.ru/joincall/<hash>) is required")
	ErrAPI              = errors.New("maxcalls: api")
	ErrNoSessionKey     = errors.New("maxcalls: the login answered no session key")
	ErrNoEndpoint       = errors.New("maxcalls: the join answered no endpoint")
	ErrInvalidEndpoint  = errors.New("maxcalls: the join endpoint is not a wss URL")
	ErrNoUserID         = errors.New("maxcalls: no user id in the login or the join")
)

// APIError is an answer of the calls API that names an error.
type APIError struct {
	Method string
	Code   int
	Msg    string
}

func (e *APIError) Error() string {
	return fmt.Sprintf("maxcalls: %s: api error %d (%s)", e.Method, e.Code, e.Msg)
}

// Provider issues call credentials for the MAX messenger.
type Provider struct {
	// APIBase is the calls API; empty is the production one.
	APIBase string
}

// Engine reports which engine consumes this provider's credentials.
func (Provider) Engine() string { return "max" }

// DefaultServiceURL is empty: a join link is mandatory.
func (Provider) DefaultServiceURL() string { return "" }

// Issue joins the call of cfg.RoomURL with cfg.Token.
func (p Provider) Issue(ctx context.Context, cfg auth.Config) (auth.Credentials, error) {
	if cfg.Token == "" {
		return auth.Credentials{}, ErrTokenRequired
	}
	hash := joinHash(cfg.RoomURL)
	if hash == "" {
		return auth.Credentials{}, ErrJoinLinkRequired
	}
	resolver := cfg.Resolver
	if resolver == nil {
		resolver = protect.NewResolver(cfg.DNSServer)
	}
	client := protect.NewHTTPClient(resolver)
	ctx, cancel := context.WithTimeout(ctx, requestWait)
	defer cancel()

	sessionKey, uid := cfg.Token, int64(0)
	if isCallsToken(cfg.Token) {
		session, err := p.anonymLogin(ctx, client, cfg.Token, deviceID(cfg.Name))
		if err != nil {
			return auth.Credentials{}, err
		}
		sessionKey, uid = session.SessionKey, session.UID
	}
	join, err := p.joinByLink(ctx, client, sessionKey, hash)
	if err != nil {
		return auth.Credentials{}, err
	}
	endpoint, err := url.Parse(join.Endpoint)
	if err != nil || endpoint.Scheme != "wss" || endpoint.Host == "" {
		return auth.Credentials{}, ErrInvalidEndpoint
	}
	if uid == 0 {
		// A session key names no uid of its own; the join's endpoint does.
		uid, _ = strconv.ParseInt(endpoint.Query().Get("userId"), 10, 64)
	}
	if uid <= 0 {
		return auth.Credentials{}, ErrNoUserID
	}
	return auth.Credentials{
		URL:   join.Endpoint,
		Token: join.Token,
		Extra: map[string]string{
			"turnUrl":        strings.Join(join.TURN.URLs, ","),
			"turnUsername":   join.TURN.Username,
			"turnCredential": join.TURN.Credential,
			"stunUrl":        strings.Join(join.STUN.URLs, ","),
			"userId":         strconv.FormatInt(uid, 10),
		},
	}, nil
}

// session is what auth.anonymLogin answers.
type session struct {
	UID        int64  `json:"uid"`
	SessionKey string `json:"session_key"`
}

// joinAnswer is what vchat.joinConversationByLink answers.
type joinAnswer struct {
	Endpoint string `json:"endpoint"`
	Token    string `json:"token"`
	TURN     struct {
		URLs       []string `json:"urls"`
		Username   string   `json:"username"`
		Credential string   `json:"credential"`
	} `json:"turn_server"`
	STUN struct {
		URLs []string `json:"urls"`
	} `json:"stun_server"`
}

// anonymLogin exchanges a calls token for a session.
func (p Provider) anonymLogin(ctx context.Context, client *http.Client, token, device string) (session, error) {
	var s session
	sessionData, err := json.Marshal(map[string]any{
		"version": 3, "device_id": device, "client_version": clientVersion,
		"client_type": "SDK_JS", "auth_token": token,
	})
	if err != nil {
		return s, fmt.Errorf("maxcalls: session data: %w", err)
	}
	if err := p.call(ctx, client, methodAnonymLogin, url.Values{"session_data": {string(sessionData)}}, &s); err != nil {
		return s, err
	}
	if s.SessionKey == "" {
		return s, ErrNoSessionKey
	}
	return s, nil
}

// joinByLink joins the call behind a join link and answers its connection.
func (p Provider) joinByLink(ctx context.Context, client *http.Client, sessionKey, hash string) (joinAnswer, error) {
	var j joinAnswer
	err := p.call(ctx, client, methodJoinByLink, url.Values{
		fieldSessionKey: {sessionKey}, "joinLink": {hash}, "isVideo": {strTrue}, "isAudio": {strTrue},
	}, &j)
	if err != nil {
		return j, err
	}
	if j.Endpoint == "" {
		return j, ErrNoEndpoint
	}
	return j, nil
}

// call is one fb.do method call. The API answers an error as a 200 with an
// error envelope, which comes back as an APIError.
func (p Provider) call(ctx context.Context, client *http.Client, method string, form url.Values, out any) error {
	form.Set("method", method)
	form.Set("format", "JSON")
	form.Set("application_key", applicationKey)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, p.apiBase()+"/fb.do", strings.NewReader(form.Encode()))
	if err != nil {
		return fmt.Errorf("maxcalls: %s: build request: %w", method, err)
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Origin", webOrigin)
	req.Header.Set("User-Agent", userAgent)
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("maxcalls: %s: %w", method, err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("maxcalls: %s: %w", method, protect.StatusError(ErrAPI, resp, bodyLimit))
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, bodyLimit))
	if err != nil {
		return fmt.Errorf("maxcalls: %s: read: %w", method, err)
	}
	var envelope struct {
		Code int    `json:"error_code"`
		Msg  string `json:"error_msg"`
	}
	if err := json.Unmarshal(body, &envelope); err != nil {
		return fmt.Errorf("maxcalls: %s: %w: not JSON", method, ErrAPI)
	}
	if envelope.Code != 0 || envelope.Msg != "" {
		return &APIError{Method: method, Code: envelope.Code, Msg: envelope.Msg}
	}
	if err := json.Unmarshal(body, out); err != nil {
		return fmt.Errorf("maxcalls: %s: %w: %w", method, ErrAPI, err)
	}
	return nil
}

func (p Provider) apiBase() string {
	if p.APIBase != "" {
		return p.APIBase
	}
	return defaultAPIBase
}

// joinHash is the hash a join link ends in, or the hash given bare.
func joinHash(room string) string {
	room = strings.TrimSpace(room)
	if i := strings.LastIndex(room, joinPath); i >= 0 {
		room = room[i+len(joinPath):]
	}
	if j := strings.IndexAny(room, "/?#"); j >= 0 {
		room = room[:j]
	}
	if strings.ContainsAny(room, ":@ ") {
		return ""
	}
	return room
}

// isCallsToken says the token is the web client's calls token, which the
// login exchanges, rather than a session key, which joins as it is.
func isCallsToken(token string) bool {
	return strings.HasPrefix(token, "An_") || strings.HasPrefix(token, "$")
}

// deviceID is the device the login names: the caller's name, or a fresh one.
func deviceID(name string) string {
	if name != "" {
		return name
	}
	var b [8]byte
	_, _ = rand.Read(b[:])
	return "olcrtc-" + hex.EncodeToString(b[:])
}
