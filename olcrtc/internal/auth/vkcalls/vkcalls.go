// Package vkcalls implements the guest auth provider for VK Calls rooms.
//
// The provider walks the five-step anonymous chain observed in the spike
// (docs/spikes/vkcalls/, spec section 4): bootstrap anonym token, call
// preview, per-call anonym token, SDK anonymous login, join by link. It
// never uses or stores an account credential; Config.Token is rejected as
// unsupported for Issue and is only read by RoomCreator (calls.start).
//
// The returned auth.Credentials follow the v1 contract of spec section 7.2:
// URL carries the signaling endpoint, Token the join token, and Extra the
// schema-tagged fields (room URL, ICE servers JSON, participant/peer ids,
// device index, client type, p2p flag). Bootstrap secrets (anonymous token,
// secret, session keys, device UUIDs) are consumed by the flow and not
// returned.
package vkcalls

import (
	"bytes"
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

const (
	apiVersion     = "5.276"
	sdkAppKey      = "CGMMEJLGDIHBABABA"
	sdkClientVer   = "1.0.1"
	fbProdBase     = "https://calls.okcdn.ru"
	fieldDeviceID  = "device_id"
	fieldLang      = "lang"
	fieldURLs      = "urls"
	fieldLink      = "link"
	stageJoin      = "vchat.joinConversationByLink"
	stageLogin     = "auth.anonymLogin"
	stageCallToken = "messages.getAnonymCallToken"
	stagePreview   = "messages.getCallPreview"
	stageAnonToken = "auth.getAnonymToken"
	requestLimit   = 8 << 20
	requestWait    = 20 * time.Second
	schemaVersion  = "1"
)

// ErrCreationUnknown reports that calls.start produced no definitive answer:
// the room may or may not exist. The caller must not retry blindly (spec
// section 3) and must not treat the link as usable.
var ErrCreationUnknown = errors.New("vkcalls: room creation result unknown")

// Static validation and transport errors; wrapped with context at call sites.
var (
	ErrRoomURLRequired    = errors.New("vkcalls: room url required")
	ErrTokenUnsupported   = errors.New("vkcalls: account token is not supported for guest issue")
	ErrOrganizerRequired  = errors.New("vkcalls: calls.start requires an organizer user token")
	ErrInvalidEndpoint    = errors.New("vkcalls: invalid signaling endpoint")
	ErrEndpointUserInfo   = errors.New("vkcalls: signaling endpoint must not carry userinfo or port")
	ErrEndpointToken      = errors.New("vkcalls: endpoint token mismatch")
	ErrMissingClientType  = errors.New("vkcalls: missing client_type")
	ErrInvalidDeviceIdx   = errors.New("vkcalls: invalid device_idx")
	ErrInvalidRoomURL     = errors.New("vkcalls: invalid room url")
	ErrRoomURLShape       = errors.New("vkcalls: room url must be https://vk.ru/call/join/<id>")
	ErrRoomURLExtra       = errors.New("vkcalls: room url must not carry userinfo, port, query or fragment")
	ErrCreatedLinkInvalid = errors.New("vkcalls: created link invalid")
	ErrICEServers         = errors.New("vkcalls: ice servers")
)

// GuestAPIError reports a VK-side API answer; Code carries error_code when
// present (14 captcha, 10 internal, 5 auth and the anonym_token.* family).
type GuestAPIError struct {
	Stage string
	Code  int
	Kind  string
}

func (e *GuestAPIError) Error() string {
	if e.Code != 0 {
		return fmt.Sprintf("vkcalls: %s: api error %d (%s)", e.Stage, e.Code, e.Kind)
	}
	return fmt.Sprintf("vkcalls: %s: %s", e.Stage, e.Kind)
}

// Provider issues guest credentials for VK Calls rooms.
type Provider struct {
	// APIBase and FBBase default to the production endpoints; tests point
	// them at a fake server.
	APIBase string
	FBBase  string
}

// New returns a provider with the production endpoints.
func New() Provider { return Provider{APIBase: "https://api.vk.me", FBBase: "https://calls.okcdn.ru"} }

// Engine reports which engine consumes this provider's credentials.
func (Provider) Engine() string { return "vkcalls" }

// DefaultServiceURL is empty: a room link is mandatory for VK Calls.
func (Provider) DefaultServiceURL() string { return "" }

// Credential keys of the v1 Extra contract (spec section 7.2).
const (
	strTrue  = "true"
	strFalse = "false"

	KeySchema        = "vkcalls_schema"
	KeyRoomURL       = "room_url"
	KeyICEServers    = "ice_servers"
	KeyParticipantID = "participant_id"
	KeyPeerIDHint    = "peer_id_hint"
	KeyDeviceIdx     = "device_idx"
	KeyClientType    = "client_type"
	KeyP2PForbidden  = "p2p_forbidden"
)

// Issue runs the guest chain for cfg.RoomURL.
func (p Provider) Issue(ctx context.Context, cfg auth.Config) (auth.Credentials, error) {
	if cfg.RoomURL == "" {
		return auth.Credentials{}, ErrRoomURLRequired
	}
	if cfg.Token != "" {
		return auth.Credentials{}, ErrTokenUnsupported
	}
	link, err := canonicalLink(cfg.RoomURL)
	if err != nil {
		return auth.Credentials{}, err
	}
	client := protect.NewHTTPClient(cfg.Resolver)
	ctx, cancel := context.WithTimeout(ctx, requestWait)
	defer cancel()

	deviceID := "olcrtc-" + randomToken(16)
	name := cfg.Name
	if name == "" {
		name = "guest-" + randomToken(4)
	}

	bootstrap := struct {
		Response struct {
			Token      string `json:"token"`
			ExpiredAt  int64  `json:"expired_at"`
			IsVerified bool   `json:"is_verified"`
		} `json:"response"`
	}{}
	if callErr := p.vkCall(ctx, client, stageAnonToken, map[string]string{
		"client_id": "8093730", fieldLink: link, fieldDeviceID: deviceID,
		"anonymName": name, "lang": "ru",
	}, &bootstrap); callErr != nil {
		return auth.Credentials{}, callErr
	}
	if bootstrap.Response.Token == "" {
		return auth.Credentials{}, &GuestAPIError{Stage: stageAnonToken, Kind: "missing_token"}
	}

	preview := struct {
		Response struct {
			UserID json.Number `json:"user_id"`
			Secret string      `json:"secret"`
		} `json:"response"`
	}{}
	if callErr := p.vkCall(ctx, client, stagePreview, map[string]string{
		"anonymous_token": bootstrap.Response.Token, fieldDeviceID: deviceID,
		fieldLink: link, fieldLang: "ru", "extended": "1", "fields": "first_name,last_name,photo_200",
	}, &preview); callErr != nil {
		return auth.Credentials{}, callErr
	}
	if preview.Response.Secret == "" {
		return auth.Credentials{}, &GuestAPIError{Stage: stagePreview, Kind: "missing_secret"}
	}
	userID := preview.Response.UserID.String()
	if userID == "" || strings.Trim(userID, "-0123456789") != "" {
		return auth.Credentials{}, &GuestAPIError{Stage: stagePreview, Kind: "invalid_user_id"}
	}

	issued := struct {
		Response struct {
			Token string `json:"token"`
		} `json:"response"`
	}{}
	if callErr := p.vkCall(ctx, client, stageCallToken, map[string]string{
		"anonymous_token": bootstrap.Response.Token, fieldDeviceID: deviceID,
		fieldLink: link, fieldLang: "ru", "name": name,
		"user_id": userID, "secret": preview.Response.Secret,
	}, &issued); callErr != nil {
		return auth.Credentials{}, callErr
	}
	if issued.Response.Token == "" {
		return auth.Credentials{}, &GuestAPIError{Stage: stageCallToken, Kind: "missing_token"}
	}

	session, loginErr := p.sdkLogin(ctx, client)
	if loginErr != nil {
		return auth.Credentials{}, loginErr
	}

	join := struct {
		Endpoint   string `json:"endpoint"`
		WTEndpoint string `json:"wt_endpoint"`
		Token      string `json:"token"`
		ID         string `json:"id"`
		DeviceIdx  any    `json:"device_idx"`
		ClientType string `json:"client_type"`
		P2PForbid  *bool  `json:"p2p_forbidden"`
		StunServer any    `json:"stun_server"`
		TurnServer any    `json:"turn_server"`
	}{}
	joinParams := map[string]string{
		"joinLink": linkID(link), "isVideo": strFalse, "protocolVersion": "5",
		"anonymToken": issued.Response.Token, "session_key": session.SessionKey,
	}
	if joinErr := p.fbCall(ctx, client, stageJoin, joinParams, &join); joinErr != nil {
		return auth.Credentials{}, joinErr
	}

	return credentialsFromJoin(link, userID, join.Endpoint, join.Token,
		join.DeviceIdx, join.ClientType, join.P2PForbid,
		join.StunServer, join.TurnServer)
}

// sdkSession is the answer of the SDK anonymous login step.
type sdkSession struct {
	SessionKey string `json:"session_key"`
}

// sdkLogin runs the SDK anonymous login (step 4) and returns its session.
func (p Provider) sdkLogin(ctx context.Context, client *http.Client) (sdkSession, error) {
	var session sdkSession
	sessionPayload := map[string]any{
		"version": 2, fieldDeviceID: "olcrtc-sdk-" + randomToken(16), "client_version": sdkClientVer,
	}
	sessionData, marshalErr := json.Marshal(sessionPayload)
	if marshalErr != nil {
		return session, fmt.Errorf("vkcalls: session data: %w", marshalErr)
	}
	if fbErr := p.fbCall(ctx, client, stageLogin, map[string]string{
		"session_data": string(sessionData),
	}, &session); fbErr != nil {
		return session, fbErr
	}
	if session.SessionKey == "" {
		return session, &GuestAPIError{Stage: stageLogin, Kind: "missing_session_key"}
	}
	return session, nil
}

// CreateRoom starts a call on behalf of the organizer whose user token is
// cfg.Token. An explicit API error is returned as-is; a lost or ambiguous
// response yields ErrCreationUnknown, and the caller must not retry the
// creation blindly (spec section 3).
func (p Provider) CreateRoom(ctx context.Context, cfg auth.Config) (string, error) {
	if cfg.Token == "" {
		return "", ErrOrganizerRequired
	}
	client := protect.NewHTTPClient(cfg.Resolver)
	ctx, cancel := context.WithTimeout(ctx, requestWait)
	defer cancel()
	body, err := p.raw(ctx, client, p.apiBase()+"/method/calls.start", map[string]string{
		"access_token": cfg.Token, "v": "5.199",
	})
	if err != nil {
		// A transport failure after the request may have reached VK leaves
		// the room state unknown; that is not a plain network error.
		return "", fmt.Errorf("%w: %w", ErrCreationUnknown, err)
	}
	var created struct {
		Response struct {
			JoinLink string `json:"join_link"`
		} `json:"response"`
		Error *struct {
			Code int    `json:"error_code"`
			Msg  string `json:"error_msg"`
		} `json:"error"`
	}
	if unmarshalErr := json.Unmarshal(body, &created); unmarshalErr != nil {
		return "", fmt.Errorf("%w: %w", ErrCreationUnknown, unmarshalErr)
	}
	if created.Error != nil {
		return "", &GuestAPIError{Stage: "calls.start", Code: created.Error.Code, Kind: created.Error.Msg}
	}
	if created.Response.JoinLink == "" {
		return "", fmt.Errorf("%w: empty join_link", ErrCreationUnknown)
	}
	link, err := canonicalLink(created.Response.JoinLink)
	if err != nil {
		return "", fmt.Errorf("%w: %w", ErrCreatedLinkInvalid, err)
	}
	return link, nil
}

func (p Provider) apiBase() string {
	if p.APIBase != "" {
		return p.APIBase
	}
	return "https://api.vk.me"
}

func (p Provider) fbBase() string {
	if p.FBBase != "" {
		return p.FBBase
	}
	return fbProdBase
}

func (p Provider) vkCall(
	ctx context.Context, client *http.Client, method string,
	params map[string]string, out any,
) error {
	params["v"] = apiVersion
	body, err := p.raw(ctx, client, p.apiBase()+"/method/"+method, params)
	if err != nil {
		return err
	}
	return decodeAPI(body, method, out)
}

func (p Provider) fbCall(
	ctx context.Context, client *http.Client, method string,
	params map[string]string, out any,
) error {
	params["method"] = method
	params["format"] = "JSON"
	params["application_key"] = sdkAppKey
	body, err := p.raw(ctx, client, p.fbBase()+"/fb.do", params)
	if err != nil {
		return err
	}
	return decodeAPI(body, method, out)
}

func (p Provider) raw(
	ctx context.Context, client *http.Client, endpoint string, params map[string]string,
) ([]byte, error) {
	form := make(url.Values, len(params))
	for k, v := range params {
		form.Set(k, v)
	}
	req, reqErr := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, strings.NewReader(form.Encode()))
	if reqErr != nil {
		return nil, fmt.Errorf("vkcalls: build request: %w", reqErr)
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, doErr := client.Do(req)
	if doErr != nil {
		return nil, fmt.Errorf("vkcalls: do: %w", doErr)
	}
	defer func() { _ = resp.Body.Close() }()
	body, readErr := io.ReadAll(io.LimitReader(resp.Body, requestLimit))
	if readErr != nil {
		return nil, fmt.Errorf("vkcalls: read body: %w", readErr)
	}
	if resp.StatusCode != http.StatusOK {
		//nolint:err113 // the redacted body only rides inside the error text
		return nil, fmt.Errorf("vkcalls: http %d: %.200s", resp.StatusCode, redact(body))
	}
	return body, nil
}

func decodeAPI(body []byte, stage string, out any) error {
	var envelope struct {
		Error *struct {
			Code int    `json:"error_code"`
			Msg  string `json:"error_msg"`
		} `json:"error"`
	}
	if err := json.Unmarshal(body, &envelope); err != nil {
		return &GuestAPIError{Stage: stage, Kind: "invalid_json"}
	}
	if envelope.Error != nil {
		return &GuestAPIError{Stage: stage, Code: envelope.Error.Code, Kind: envelope.Error.Msg}
	}
	dec := json.NewDecoder(bytes.NewReader(body))
	dec.UseNumber()
	if err := dec.Decode(out); err != nil {
		return &GuestAPIError{Stage: stage, Kind: "invalid_response"}
	}
	return nil
}

// credentialsFromJoin applies the v1 validations of spec section 7.2.
func credentialsFromJoin(link, userID, endpoint, token string, deviceIdx any,
	clientType string, p2p *bool, stun, turn any) (auth.Credentials, error) {
	if endpoint == "" || token == "" {
		return auth.Credentials{}, &GuestAPIError{Stage: stageJoin, Kind: "missing_endpoint_or_token"}
	}
	u, err := url.Parse(endpoint)
	if err != nil || u.Scheme != "wss" || !strings.HasSuffix(u.Host, ".okcdn.ru") {
		return auth.Credentials{}, ErrInvalidEndpoint
	}
	if u.User != nil || u.Port() != "" {
		return auth.Credentials{}, ErrEndpointUserInfo
	}
	if q := u.Query().Get("token"); q != "" && q != token {
		return auth.Credentials{}, ErrEndpointToken
	}
	if clientType == "" {
		return auth.Credentials{}, ErrMissingClientType
	}
	p2pValue := strTrue // absent or invalid forbids DIRECT (spec 7.2)
	if p2p != nil {
		p2pValue = strconv.FormatBool(*p2p)
	}
	deviceIdxStr, ok := decimalOf(deviceIdx)
	if !ok {
		return auth.Credentials{}, ErrInvalidDeviceIdx
	}
	ice, err := iceServersJSON(stun, turn)
	if err != nil {
		return auth.Credentials{}, err
	}
	extra := map[string]string{
		KeySchema:        schemaVersion,
		KeyRoomURL:       link,
		KeyICEServers:    ice,
		KeyParticipantID: userID,
		KeyDeviceIdx:     deviceIdxStr,
		KeyClientType:    clientType,
		KeyP2PForbidden:  p2pValue,
	}
	if hint := u.Query().Get("peerId"); hint != "" {
		extra[KeyPeerIDHint] = hint
	}
	return auth.Credentials{URL: endpoint, Token: token, Extra: extra}, nil
}

func iceServersJSON(stun, turn any) (string, error) {
	servers := []map[string]any{}
	if stun != nil {
		if stunURLs := urlsOf(stun); len(stunURLs) > 0 {
			servers = append(servers, map[string]any{fieldURLs: stunURLs})
		}
	}
	if turn != nil {
		m := map[string]any{}
		if turnURLs := urlsOf(turn); len(turnURLs) > 0 {
			m[fieldURLs] = turnURLs
		}
		if username, credential := credsOf(turn); username != "" {
			m["username"] = username
			m["credential"] = credential
		}
		if len(m) > 0 {
			servers = append(servers, m)
		}
	}
	b, err := json.Marshal(servers)
	if err != nil {
		return "", fmt.Errorf("%w: %w", ErrICEServers, err)
	}
	return string(b), nil
}

func urlsOf(server any) []string {
	m, ok := server.(map[string]any)
	if !ok {
		return nil
	}
	switch urls := m[fieldURLs].(type) {
	case string:
		return []string{urls}
	case []any:
		out := make([]string, 0, len(urls))
		for _, v := range urls {
			if s, ok := v.(string); ok && s != "" {
				out = append(out, s)
			}
		}
		return out
	}
	return nil
}

func credsOf(server any) (string, string) {
	m, ok := server.(map[string]any)
	if !ok {
		return "", ""
	}
	username, _ := m["username"].(string)
	credential, _ := m["credential"].(string)
	return username, credential
}

func decimalOf(v any) (string, bool) {
	switch t := v.(type) {
	case nil:
		return "", true // device_idx is optional; empty stays empty
	case json.Number:
		if t.String() == "" || strings.Trim(t.String(), "0123456789") != "" {
			return "", false
		}
		return t.String(), true
	case string:
		if strings.Trim(t, "0123456789") != "" {
			return "", false
		}
		return t, true
	case float64:
		if t != float64(int64(t)) || t < 0 {
			return "", false
		}
		return strconv.FormatInt(int64(t), 10), true
	}
	return "", false
}

// canonicalLink validates the join link shape: vk.ru or vk.com host, /call/
// join/<id> path, no userinfo, port or query (spec section 4).
func canonicalLink(raw string) (string, error) {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil {
		return "", fmt.Errorf("%w: %w", ErrInvalidRoomURL, err)
	}
	if u.Scheme != "https" || (u.Host != "vk.ru" && u.Host != "vk.com") {
		return "", ErrRoomURLShape
	}
	if u.User != nil || u.Port() != "" || u.RawQuery != "" || u.Fragment != "" {
		return "", ErrRoomURLExtra
	}
	parts := strings.Split(strings.Trim(u.Path, "/"), "/")
	if len(parts) != 3 || parts[0] != "call" || parts[1] != "join" || parts[2] == "" {
		return "", ErrRoomURLShape
	}
	return "https://" + u.Host + "/call/join/" + parts[2], nil
}

func linkID(link string) string {
	parts := strings.Split(strings.Trim(strings.TrimPrefix(link, "https://"), "/"), "/")
	return parts[len(parts)-1]
}

// randomToken returns a hex string of n bytes for device/nick uniqueness.
func randomToken(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		now := time.Now().UnixNano()
		for i := range b {
			b[i%len(b)] = byte(now >> (uint(i%8) * 8)) //nolint:gosec // fallback entropy only
		}
	}
	return hex.EncodeToString(b)
}

// redact strips newlines and bounds an upstream error body echo.
func redact(body []byte) string {
	s := strings.Map(func(r rune) rune {
		if r == '\n' || r == '\r' {
			return ' '
		}
		return r
	}, string(body))
	if len(s) > 200 {
		s = s[:200]
	}
	return s
}
