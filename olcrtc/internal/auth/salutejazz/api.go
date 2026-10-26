// Package salutejazz is the auth provider for the SaluteJazz (Sber) video
// service. SaluteJazz needs no account: a room reference is
// "<code>:<password>", provisioned by one anonymous POST and joined after a
// "preconnect" call that resolves the WS connector URL to join on.
package salutejazz

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"regexp"
	"strings"

	"github.com/openlibrecommunity/olcrtc/internal/auth"
)

const (
	defaultAPIBase = "https://bk.salutejazz.ru"
	// defaultConnectorURL is the fallback join endpoint used only if a
	// preconnect response ever omits connectorUrl.
	defaultConnectorURL = "wss://ws.salutejazz.ru/connector"

	originHeader = "https://salutejazz.ru"
	userAgent    = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
)

var (
	errPreconnect    = errors.New("preconnect failed")
	errCreateMeeting = errors.New("create meeting failed")
)

// roomPartRE matches one half (code or password) of a "<code>:<password>"
// room reference: lowercase alphanumeric. The service hands out a 6-char
// code and an 8-char password; {4,16} leaves headroom without weakening the
// check that actually matters here (no colons, no whitespace, no case
// confusion in a value that is compared byte-for-byte downstream).
var roomPartRE = regexp.MustCompile(`^[a-z0-9]{4,16}$`)

// preconnectReply is the subset of the SaluteJazz /preconnect response this
// provider needs.
type preconnectReply struct {
	ConnectorURL string `json:"connectorUrl"` //nolint:tagliatelle // upstream SaluteJazz API uses camelCase
}

// createMeetingReply is the subset of the SaluteJazz /create-meeting
// response this provider needs. Go's default case-insensitive field
// matching maps the JSON keys "roomId"/"password" without struct tags.
type createMeetingReply struct {
	RoomID, Password string
}

// createMeetingRequest is the exact body the official web client sends to
// provision an anonymous room (captured live, see the design spec §2.1).
//
// Every //nolint:tagliatelle below is the same exception: the upstream
// SaluteJazz API uses camelCase JSON keys, not the snake_case this repo
// otherwise requires.
type createMeetingRequest struct {
	Title                             string   `json:"title"`
	GuestEnabled                      bool     `json:"guestEnabled"`                      //nolint:tagliatelle
	LobbyEnabled                      bool     `json:"lobbyEnabled"`                      //nolint:tagliatelle
	ServerVideoRecordAutoStartEnabled bool     `json:"serverVideoRecordAutoStartEnabled"` //nolint:tagliatelle
	SIPEnabled                        bool     `json:"sipEnabled"`                        //nolint:tagliatelle
	ModeratorEmails                   []string `json:"moderatorEmails"`                   //nolint:tagliatelle
	SummarizationEnabled              bool     `json:"summarizationEnabled"`              //nolint:tagliatelle
	Interpretation                    any      `json:"interpretation"`
	Room3DEnabled                     bool     `json:"room3dEnabled"` //nolint:tagliatelle
	Room3DScene                       string   `json:"room3dScene"`   //nolint:tagliatelle
}

// splitRoomRef splits a "<code>:<password>" room reference at the first
// colon. Both halves must match roomPartRE; a missing colon, an empty half,
// extra colons (the password half then fails the charset check), or
// uppercase letters are all rejected as a malformed room reference.
func splitRoomRef(ref string) (string, string, error) {
	// Never interpolate ref (or either half of it) into the returned error:
	// the password half is a credential, and this error is logged by
	// callers several layers up (engineconn -> builtin -> Logf). A static
	// reason is all a caller needs; mirrors wbstream.go's Issue, which
	// returns bare auth.ErrRoomIDRequired without echoing cfg.RoomURL.
	i := strings.IndexByte(ref, ':')
	if i < 0 {
		return "", "", fmt.Errorf("%w: expected \"<code>:<password>\"", auth.ErrRoomIDRequired)
	}
	code, password := ref[:i], ref[i+1:]
	if !roomPartRE.MatchString(code) || !roomPartRE.MatchString(password) {
		return "", "", fmt.Errorf("%w: malformed room reference", auth.ErrRoomIDRequired)
	}
	return code, password, nil
}

// apiURL returns the REST base for this provider.
func (p Provider) apiURL() string {
	if p.apiBase == "" {
		return defaultAPIBase
	}
	return p.apiBase
}

// preconnect calls POST /room/<code>/preconnect - the call the official
// client makes before every join. It validates the password early and
// resolves the WS connector URL to join on. The password travels only in
// the request body; it is never logged.
func (p Provider) preconnect(ctx context.Context, client *http.Client, code, password string) (string, error) {
	reqBody, err := json.Marshal(map[string]any{
		"password": password,
		// Mirrors the feature-flag object the web client sends alongside
		// the password; capability flags only, no credentials or account
		// state.
		"jazzNextMigration": map[string]any{
			"b2bBaseRoomSupport":               true,
			"demoRoomBaseSupport":              true,
			"demoRoomVersionSupport":           2,
			"mediaWithoutAutoSubscribeSupport": true,
			"webinarSpeakerSupport":            true,
			"webinarViewerSupport":             true,
			"sdkRoomSupport":                   true,
			"sberclassRoomSupport":             true,
		},
	})
	if err != nil {
		return "", fmt.Errorf("marshal preconnect body: %w", err)
	}

	u := p.apiURL() + "/room/" + code + "/preconnect"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, u, bytes.NewReader(reqBody))
	if err != nil {
		return "", fmt.Errorf("create request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Origin", originHeader)
	req.Header.Set("User-Agent", userAgent)

	res, err := auth.DoJSON[preconnectReply](client, req, errPreconnect)
	if err != nil {
		return "", fmt.Errorf("preconnect: %w", err)
	}
	return res.ConnectorURL, nil
}

// createMeeting calls POST /room/create-meeting, the single anonymous
// request that provisions a new SaluteJazz room: no token, no cookies, no
// opener account.
func (p Provider) createMeeting(ctx context.Context, client *http.Client) (string, string, error) {
	reqBody, err := json.Marshal(createMeetingRequest{
		Title:                             "Видеовстреча",
		GuestEnabled:                      true,
		LobbyEnabled:                      false,
		ServerVideoRecordAutoStartEnabled: false,
		SIPEnabled:                        false,
		ModeratorEmails:                   []string{},
		SummarizationEnabled:              false,
		Interpretation:                    nil,
		Room3DEnabled:                     false,
		Room3DScene:                       "XRLobby",
	})
	if err != nil {
		return "", "", fmt.Errorf("marshal create-meeting body: %w", err)
	}

	u := p.apiURL() + "/room/create-meeting"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, u, bytes.NewReader(reqBody))
	if err != nil {
		return "", "", fmt.Errorf("create request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Origin", originHeader)
	req.Header.Set("User-Agent", userAgent)

	res, err := auth.DoJSON[createMeetingReply](client, req, errCreateMeeting)
	if err != nil {
		return "", "", fmt.Errorf("create meeting: %w", err)
	}
	// Validate the server's reply against the same shape Issue requires, so
	// a malformed roomId/password fails here rather than surfacing later as
	// a confusing Issue error. Never echo res.Password: same rule as
	// splitRoomRef, and for the same reason (this error is logged upstream).
	if !roomPartRE.MatchString(res.RoomID) || !roomPartRE.MatchString(res.Password) {
		return "", "", fmt.Errorf("%w: malformed room reference in response", errCreateMeeting)
	}
	return res.RoomID, res.Password, nil
}
