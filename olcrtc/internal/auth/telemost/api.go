// Package telemost is the auth provider for the Yandex Telemost service.
// It fetches the connection metadata (media server URL, peer ID, room ID,
// signing credentials) the Goolom engine needs to join a conference.
//
// Telemost does not expose an API to create rooms - they originate in the
// Yandex UI - so this provider does not implement auth.RoomCreator.
package telemost

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/url"

	"github.com/google/uuid"

	"github.com/openlibrecommunity/olcrtc/internal/auth"
)

const defaultAPIURL = "https://cloud-api.yandex.ru/telemost_front/v2/telemost"

// ErrAPI marks failures returned by the Telemost HTTP API.
var ErrAPI = errors.New("api error")

//nolint:gochecknoglobals // optional Yandex auth cookies, set from the mobile binding
var cookies string

// SetCookies stores the Yandex auth cookie header (e.g. "Session_id=...; yandexuid=...") sent with
// Telemost API requests. Empty disables it. Lets a signed-in account join restricted conferences.
//
// DELIBERATELY NEVER WIRED TO A REAL PERSONAL ACCOUNT: attaching a real Yandex identity to the
// circumvention tunnel would deanonymize exactly what olcRTC exists to protect. Leave this unset.
func SetCookies(c string) { cookies = c }

// ConnectionInfo describes the connection metadata returned by the Telemost API.
type ConnectionInfo struct {
	RoomID       string `json:"room_id"`
	PeerID       string `json:"peer_id"`
	Credentials  string `json:"credentials"`
	ClientConfig struct {
		MediaServerURL string `json:"media_server_url"`
	} `json:"client_configuration"`
}

// apiURL returns the REST base for this provider.
func (p Provider) apiURL() string {
	if p.apiBase == "" {
		return defaultAPIURL
	}
	return p.apiBase
}

// connectionInfo fetches connection metadata for the given Telemost room URL.
func (p Provider) connectionInfo(
	ctx context.Context, client *http.Client, roomURL, displayName string,
) (ConnectionInfo, error) {
	u := fmt.Sprintf("%s/conferences/%s/connection", p.apiURL(), url.QueryEscape(roomURL))

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, http.NoBody)
	if err != nil {
		return ConnectionInfo{}, fmt.Errorf("failed to create request: %w", err)
	}

	q := req.URL.Query()
	q.Add("next_gen_media_platform_allowed", "true")
	q.Add("display_name", displayName)
	q.Add("waiting_room_supported", "true")
	req.URL.RawQuery = q.Encode()

	req.Header.Set("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:149.0) Gecko/20100101 Firefox/149.0")
	req.Header.Set("Accept", "*/*")
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Client-Instance-Id", uuid.New().String())
	req.Header.Set("X-Telemost-Client-Version", "187.1.0")
	req.Header.Set("Idempotency-Key", uuid.New().String())
	req.Header.Set("Origin", "https://telemost.yandex.ru")
	req.Header.Set("Referer", "https://telemost.yandex.ru/")
	if cookies != "" {
		req.Header.Set("Cookie", cookies)
	}

	info, err := auth.DoJSON[ConnectionInfo](client, req, ErrAPI)
	if err != nil {
		return ConnectionInfo{}, fmt.Errorf("telemost api: %w", err)
	}
	return info, nil
}
