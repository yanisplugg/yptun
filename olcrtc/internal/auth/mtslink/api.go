// Package mtslink is the MTS Link auth provider: the guest's way into an
// endless meeting, measured against the live service on 2026-10-11.
//
// The service is the former webinar.ru. Its gateway is gw.mts-link.ru/api,
// and a guest needs no account: a login with a nickname is accepted without
// captcha or moderation and sets the cookies every later call presents. The
// rooms the platform makes are endless meetings («постоянные встречи»), which
// a guest enters with no organizer inside.
//
// What the web client does between a link and the media, and so what Issue
// does: look the event's session up, log the guest in, read the session's
// configuration for the SFU it lives on, make a conference - the record of
// one participant's published media, whose private key is the publish ticket
// - and take a join token. The join itself, one SDP exchange with the SFU, is
// the engine's (internal/engine/mtslink).
package mtslink

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

const (
	// defaultGateway is the API the web client talks to.
	defaultGateway = "https://gw.mts-link.ru"
	// webOrigin is the page the web client's calls come from.
	webOrigin = "https://my.mts-link.ru"
	userAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
	// replyLimit bounds a gateway reply; the largest the flow reads is the
	// event's public record, a few kilobytes.
	replyLimit = 1 << 20
	formType   = "application/x-www-form-urlencoded"
	// yes and no are how the service's forms and queries spell a flag.
	yes = "true"
	no  = "false"
)

var (
	// ErrRoomRef is a room reference that is not the event join link.
	ErrRoomRef = errors.New("mtslink: room must be the event join link (…/j/{ownerId}/{eventId})")
	// ErrRoomGone is the service no longer having the room: 404, or the 406
	// "Not Acceptable" it answers for an event or a session it does not know.
	ErrRoomGone = errors.New("mtslink: the service has no such room")
	// ErrNoSession is an event that has no session to join.
	ErrNoSession = errors.New("mtslink: the event has no session")
	// ErrNotAccepted is the service turning the guest away.
	ErrNotAccepted = errors.New("mtslink: the service did not accept the guest")
	// ErrBadStatus is any other status a gateway call was not measured to
	// answer. The call and the number ride along; the body never does.
	ErrBadStatus = errors.New("mtslink: unexpected status")
	// ErrIncompleteReply is a reply that does not name what the flow needs
	// from it.
	ErrIncompleteReply = errors.New("mtslink: incomplete reply")
	// ErrConferenceID is a conference id that is not a number.
	ErrConferenceID = errors.New("mtslink: conference id is not a number")
)

// parseEventLink reads the event, and the session when the link names one,
// out of a room reference: the join link the platform stores,
// https://my.mts-link.ru/j/{ownerId}/{eventId}, or the same with
// /session/{sessionId} behind it.
func parseEventLink(room string) (string, string, error) {
	u, err := url.Parse(strings.TrimSpace(room))
	if err != nil || u.Scheme == "" || u.Host == "" {
		return "", "", ErrRoomRef
	}
	parts := strings.Split(strings.Trim(u.Path, "/"), "/")
	at := -1
	for i, part := range parts {
		if part == "j" {
			at = i
			break
		}
	}
	if at < 0 || len(parts) < at+3 || !isID(parts[at+1]) || !isID(parts[at+2]) {
		return "", "", ErrRoomRef
	}
	event, session := parts[at+2], ""
	if len(parts) >= at+5 && parts[at+3] == "session" && isID(parts[at+4]) {
		session = parts[at+4]
	}
	return event, session, nil
}

// isID says whether s is a service id: digits, and not too many of them.
func isID(s string) bool {
	if s == "" || len(s) > 20 {
		return false
	}
	for _, c := range s {
		if c < '0' || c > '9' {
			return false
		}
	}
	return true
}

// isHost says whether s can be the host of a URL and nothing more: the SFU's
// name comes out of a reply and goes into one.
func isHost(s string) bool {
	if s == "" || len(s) > 253 {
		return false
	}
	for _, c := range s {
		switch {
		case c >= 'a' && c <= 'z', c >= 'A' && c <= 'Z', c >= '0' && c <= '9', c == '.', c == '-', c == ':':
		default:
			return false
		}
	}
	return true
}

// guest is the session a login made: the cookies the gateway set, as the
// Cookie header that presents them. They are a live credential and reach no
// log and no error.
type guest struct {
	cookies string
	userID  string
}

// call is one gateway request: what it is called in an error, how it is
// made, and which statuses are an answer.
type call struct {
	name   string
	method string
	url    string
	form   url.Values
	want   int
}

// do makes the call and returns the reply's body and Set-Cookie pairs. A
// status that is not the one wanted is ErrRoomGone for a room the service
// does not know and ErrBadStatus for the rest.
func (c call) do(ctx context.Context, client *http.Client, cookies string) ([]byte, []string, error) {
	var body io.Reader = http.NoBody
	if c.form != nil {
		body = strings.NewReader(c.form.Encode())
	}
	req, err := http.NewRequestWithContext(ctx, c.method, c.url, body)
	if err != nil {
		return nil, nil, fmt.Errorf("mtslink: %s request: %w", c.name, err)
	}
	req.Header.Set("User-Agent", userAgent)
	req.Header.Set("Origin", webOrigin)
	req.Header.Set("Referer", webOrigin+"/")
	if c.form != nil {
		req.Header.Set("Content-Type", formType)
	}
	if cookies != "" {
		req.Header.Set("Cookie", cookies)
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, nil, fmt.Errorf("mtslink: %s: %w", c.name, err)
	}
	defer func() { _ = resp.Body.Close() }()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, replyLimit))

	switch resp.StatusCode {
	case c.want:
	case http.StatusNotFound, http.StatusNotAcceptable, http.StatusGone:
		return nil, nil, fmt.Errorf("%s: %w", c.name, ErrRoomGone)
	default:
		return nil, nil, fmt.Errorf("%s: %w %d", c.name, ErrBadStatus, resp.StatusCode)
	}

	var set []string
	for _, cookie := range resp.Cookies() {
		if cookie.Name != "" && cookie.Value != "" {
			set = append(set, cookie.Name+"="+cookie.Value)
		}
	}
	return raw, set, nil
}

// eventSession asks the event's public record for its session. An endless
// meeting has one, started when the room was made.
func eventSession(ctx context.Context, client *http.Client, gateway, event string) (string, error) {
	raw, _, err := call{
		name: "event", method: http.MethodGet, url: gateway + "/api/event/" + event, want: http.StatusOK,
	}.do(ctx, client, "")
	if err != nil {
		return "", err
	}
	var reply struct {
		EventSessions []struct {
			ID int64 `json:"id"`
		} `json:"eventSessions"` //nolint:tagliatelle // the service's wire is camelCase
	}
	if json.Unmarshal(raw, &reply) != nil || len(reply.EventSessions) == 0 || reply.EventSessions[0].ID <= 0 {
		return "", ErrNoSession
	}
	return strconv.FormatInt(reply.EventSessions[0].ID, 10), nil
}

// guestLogin registers a guest on the session. The reply's own `user` carries
// no id - the guest's user sits under `participation` - and the cookies are
// every one the reply set: the web client presents them all.
func guestLogin(ctx context.Context, client *http.Client, gateway, session, nickname string) (guest, error) {
	raw, cookies, err := call{
		name: "guestlogin", method: http.MethodPost,
		url:  gateway + "/api/eventsessions/" + session + "/guestlogin",
		form: url.Values{"nickname": {nickname}, "name": {""}, "secondName": {""}, "phone": {""}},
		want: http.StatusOK,
	}.do(ctx, client, "")
	if err != nil {
		return guest{}, err
	}
	var reply struct {
		IsAccepted    bool `json:"isAccepted"` //nolint:tagliatelle // the service's wire is camelCase
		Participation struct {
			User struct {
				ID int64 `json:"id"`
			} `json:"user"`
		} `json:"participation"`
	}
	if json.Unmarshal(raw, &reply) != nil {
		return guest{}, fmt.Errorf("guestlogin: %w", ErrIncompleteReply)
	}
	if !reply.IsAccepted {
		return guest{}, ErrNotAccepted
	}
	if reply.Participation.User.ID <= 0 || len(cookies) == 0 {
		return guest{}, fmt.Errorf("guestlogin: %w", ErrIncompleteReply)
	}
	return guest{
		cookies: strings.Join(cookies, "; "),
		userID:  strconv.FormatInt(reply.Participation.User.ID, 10),
	}, nil
}

// mediaServer reads the SFU the session lives on out of its configuration.
func mediaServer(ctx context.Context, client *http.Client, gateway, session string, g guest) (string, error) {
	raw, _, err := call{
		name: "configuration", method: http.MethodGet,
		url:  gateway + "/api/eventsession/" + session + "/configuration",
		want: http.StatusOK,
	}.do(ctx, client, g.cookies)
	if err != nil {
		return "", err
	}
	var reply struct {
		RTCServer string `json:"rtcServer"` //nolint:tagliatelle // the service's wire is camelCase
	}
	if json.Unmarshal(raw, &reply) != nil || !isHost(reply.RTCServer) {
		return "", fmt.Errorf("configuration: %w", ErrIncompleteReply)
	}
	return reply.RTCServer, nil
}

// conference is the record of one participant's published media.
type conference struct {
	id string
	// publishToken is the record's private key: what lets its owner publish.
	publishToken string
}

// newConference makes the guest's conference: a camera and no microphone,
// which is all the engine publishes.
func newConference(ctx context.Context, client *http.Client, gateway, session string, g guest) (conference, error) {
	raw, _, err := call{
		name: "conferences", method: http.MethodPost,
		url:  gateway + "/api/eventsessions/" + session + "/conferences",
		form: url.Values{"hasAudio": {no}, "hasVideo": {yes}},
		want: http.StatusCreated,
	}.do(ctx, client, g.cookies)
	if err != nil {
		return conference{}, err
	}
	var reply struct {
		ID         int64  `json:"id"`
		PrivateKey string `json:"privateKey"` //nolint:tagliatelle // the service's wire is camelCase
	}
	if json.Unmarshal(raw, &reply) != nil || reply.ID <= 0 || reply.PrivateKey == "" {
		return conference{}, fmt.Errorf("conferences: %w", ErrIncompleteReply)
	}
	return conference{id: strconv.FormatInt(reply.ID, 10), publishToken: reply.PrivateKey}, nil
}

// joinToken is the ticket the room join presents.
func joinToken(ctx context.Context, client *http.Client, gateway, session string, g guest) (string, error) {
	raw, _, err := call{
		name: "join-token", method: http.MethodGet,
		url:  gateway + "/api/eventsessions/" + session + "/join-token",
		want: http.StatusOK,
	}.do(ctx, client, g.cookies)
	if err != nil {
		return "", err
	}
	var reply struct {
		Data struct {
			JoinToken string `json:"joinToken"` //nolint:tagliatelle // the service's wire is camelCase
		} `json:"data"`
	}
	if json.Unmarshal(raw, &reply) != nil || reply.Data.JoinToken == "" {
		return "", fmt.Errorf("join-token: %w", ErrIncompleteReply)
	}
	return reply.Data.JoinToken, nil
}

// leave takes a conference back: DELETE /api/conferences/{id} with the cookies
// of the guest that made it (204, measured). The service drops the record by
// itself once its publisher has been on the SFU and left; one whose join never
// reached the SFU stays in the room's list until its guest takes it back. The
// engine does the same after a join the SFU refused (internal/engine/mtslink).
func leave(ctx context.Context, client *http.Client, gateway, conferenceID, cookies string) error {
	if !isID(conferenceID) {
		return ErrConferenceID
	}
	_, _, err := call{
		name: "leave", method: http.MethodDelete,
		url:  gateway + "/api/conferences/" + conferenceID,
		want: http.StatusNoContent,
	}.do(ctx, client, cookies)
	return err
}
