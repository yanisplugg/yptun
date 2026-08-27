// Package wbstream is the auth provider for the WB Stream service. It
// produces LiveKit credentials by registering a guest, joining an existing
// room, and exchanging the guest access token for a room token.
package wbstream

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"

	"github.com/openlibrecommunity/olcrtc/internal/auth"
)

const (
	defaultWSURL  = "wss://rtc-el-02.wb.ru"
	defaultAPIURL = "https://stream.wb.ru"
	userAgent     = "Mozilla/5.0 (Linux x86_64)"
)

var (
	errGuestRegister = errors.New("guest register failed")
	errJoinRoom      = errors.New("join room failed")
	errGetToken      = errors.New("get token failed")
)

type guestRegisterRequest struct {
	DisplayName string `json:"displayName"` //nolint:tagliatelle // upstream WB API uses camelCase
	Device      device `json:"device"`
}

type device struct {
	DeviceName string `json:"deviceName"` //nolint:tagliatelle // upstream WB API uses camelCase
	DeviceType string `json:"deviceType"` //nolint:tagliatelle // upstream WB API uses camelCase
}

type guestRegisterResponse struct {
	AccessToken string `json:"accessToken"` //nolint:tagliatelle // upstream WB API uses camelCase
}

type tokenResponse struct {
	RoomToken string `json:"roomToken"` //nolint:tagliatelle // upstream WB API uses camelCase
	ServerURL string `json:"serverUrl"` //nolint:tagliatelle // upstream WB API uses camelCase
}

// apiURL returns the REST base for this provider.
func (p Provider) apiURL() string {
	if p.apiBase == "" {
		return defaultAPIURL
	}
	return p.apiBase
}

func (p Provider) registerGuest(ctx context.Context, client *http.Client, displayName string) (string, error) {
	u := p.apiURL() + "/auth/api/v1/auth/user/guest-register"
	reqBody := guestRegisterRequest{
		DisplayName: displayName,
		Device: device{
			DeviceName: "Linux",
			DeviceType: "PARTICIPANT_DEVICE_TYPE_WEB_DESKTOP",
		},
	}

	body, err := json.Marshal(reqBody)
	if err != nil {
		return "", fmt.Errorf("marshal request body: %w", err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, u, bytes.NewBuffer(body))
	if err != nil {
		return "", fmt.Errorf("create request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", userAgent)

	res, err := auth.DoJSON[guestRegisterResponse](client, req, errGuestRegister)
	if err != nil {
		return "", fmt.Errorf("guest register: %w", err)
	}
	return res.AccessToken, nil
}

func (p Provider) joinRoom(ctx context.Context, client *http.Client, accessToken, roomID string) error {
	u := fmt.Sprintf("%s/api-room/api/v1/room/%s/join", p.apiURL(), url.PathEscape(roomID))
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, u, bytes.NewReader([]byte("{}")))
	if err != nil {
		return fmt.Errorf("create request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+accessToken)
	req.Header.Set("User-Agent", userAgent)

	if err := auth.Do(client, req, errJoinRoom); err != nil {
		return fmt.Errorf("join room: %w", err)
	}
	return nil
}

func (p Provider) getToken(
	ctx context.Context, client *http.Client, accessToken, roomID, displayName string,
) (tokenResponse, error) {
	u := fmt.Sprintf("%s/api-room-manager/v2/room/%s/connection-details", p.apiURL(), url.PathEscape(roomID))
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, http.NoBody)
	if err != nil {
		return tokenResponse{}, fmt.Errorf("create request: %w", err)
	}

	q := req.URL.Query()
	q.Add("deviceType", "PARTICIPANT_DEVICE_TYPE_WEB_DESKTOP")
	q.Add("displayName", displayName)
	req.URL.RawQuery = q.Encode()

	req.Header.Set("Authorization", "Bearer "+accessToken)
	req.Header.Set("User-Agent", userAgent)

	res, err := auth.DoJSON[tokenResponse](client, req, errGetToken)
	if err != nil {
		return tokenResponse{}, fmt.Errorf("get token: %w", err)
	}
	return res, nil
}
