package mtslink

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
)

// MTS Link's SFU ("odin" in its answers) speaks SDP over plain HTTP, measured
// against the live service on 2026-10-11:
//
//	POST {join}                      the offer in, the answer out
//	POST {origin}/rtc/peer/{id}/pin  {"streams":[""]}, the web client's call
//	                                 after every join; the peer is the
//	                                 answer's s= line
//
// No WebSocket and no trickle: the answer carries the SFU's candidates and
// the guest's reach it as peer-reflexive ones. The SFU authenticates by the
// join's own tokens and reads no cookie.

var (
	// ErrJoinRefused is the SFU answering the join with anything but 200. Its
	// body names the rule the offer broke ("SDP validation error: ...") and
	// rides along; it is the SFU's text about our SDP, never a credential.
	ErrJoinRefused = errors.New("mtslink: the SFU refused the join")
	// ErrNoAnswer is a join that answered 200 and no SDP.
	ErrNoAnswer = errors.New("mtslink: the room join did not answer an SDP")
	// ErrNoPeerID is an answer that names no peer.
	ErrNoPeerID = errors.New("mtslink: the answer named no peer")
)

const (
	// maxSignalBody bounds a signaling reply: an answer to the fourteen
	// sections the web client offers is forty kilobytes.
	maxSignalBody = 1 << 20
	// maxRefusalText is how much of a refusal's body an error carries.
	maxRefusalText = 200
	// pinNothing is the body of the pin the web client sends after a join:
	// no stream held in a slot, the SFU fills them as it sees fit.
	pinNothing = `{"streams":[""]}`
	// publishCodec is what the join asks the SFU to take from the published
	// track, in the service's own spelling.
	publishCodec = "vp9"
)

// offerURL is the provider's room join with what describes the offer added:
// the transport-wide congestion feedback the offer carries, the m-line the
// SFU sends the room's audio on, and the codec of the published track.
func offerURL(join, audioPlayerMid string) (string, error) {
	u, err := url.Parse(join)
	if err != nil || u.Scheme == "" || u.Host == "" {
		return "", fmt.Errorf("%w: the join URL does not parse", ErrNoEndpoint)
	}
	q := u.Query()
	q.Set("twcc", "true")
	q.Set("originMid", audioPlayerMid)
	q.Set("preferredPublishCodec", publishCodec)
	u.RawQuery = q.Encode()
	return u.String(), nil
}

// joinOffer POSTs the offer and returns the answer's SDP.
func joinOffer(ctx context.Context, client *http.Client, joinURL, offer string) (string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, joinURL, strings.NewReader(offer))
	if err != nil {
		return "", fmt.Errorf("mtslink: join request: %w", err)
	}
	req.Header.Set("Content-Type", "application/sdp")
	resp, err := client.Do(req)
	if err != nil {
		return "", fmt.Errorf("mtslink: join: %w", redactURL(err))
	}
	defer func() { _ = resp.Body.Close() }()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, maxSignalBody))
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("%w: status %d: %s", ErrJoinRefused, resp.StatusCode, refusalText(body))
	}
	answer := string(body)
	if !strings.HasPrefix(answer, "v=0") {
		return "", ErrNoAnswer
	}
	return answer, nil
}

// refusalText is the first line of a refusal, bounded and printable.
func refusalText(body []byte) string {
	line, _, _ := strings.Cut(strings.TrimSpace(string(body)), "\n")
	line = strings.Map(func(r rune) rune {
		if r < ' ' || r == 0x7f {
			return -1
		}
		return r
	}, line)
	if len(line) > maxRefusalText {
		line = line[:maxRefusalText]
	}
	return line
}

// redactURL keeps a transport error's reason and drops its URL: the join's
// carries both of the guest's tokens.
func redactURL(err error) error {
	var uerr *url.Error
	if errors.As(err, &uerr) {
		return fmt.Errorf("%s: %w", uerr.Op, uerr.Err)
	}
	return err
}

// peerOf is the peer an answer names: its session name, the `s=` line. The id
// goes into a path, so one that could not be a path segment is none.
func peerOf(answer string) string {
	for _, line := range strings.Split(answer, "\n") {
		id, ok := strings.CutPrefix(strings.TrimSpace(line), "s=")
		if !ok {
			continue
		}
		if id == "" || id == "-" || strings.ContainsAny(id, "/?#% \t") {
			return ""
		}
		return id
	}
	return ""
}

// pinPeer sends the pin the web client sends after a join. The media flows
// without it (measured), so the caller treats a failure as a note, not as a
// join that failed.
func pinPeer(ctx context.Context, client *http.Client, joinURL, peerID string) error {
	u, err := url.Parse(joinURL)
	if err != nil {
		return fmt.Errorf("mtslink: pin url: %w", err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		u.Scheme+"://"+u.Host+"/rtc/peer/"+peerID+"/pin", strings.NewReader(pinNothing))
	if err != nil {
		return fmt.Errorf("mtslink: pin request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("mtslink: pin: %w", redactURL(err))
	}
	defer func() { _ = resp.Body.Close() }()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, maxSignalBody))
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("mtslink: pin: status %d", resp.StatusCode) //nolint:err113 // a note for the log, matched by nobody
	}
	return nil
}

// takeBack removes the guest's conference from the room's list: DELETE
// {gateway}/api/conferences/{id} with the guest's cookies (204, measured).
// The service does it by itself for a publisher that was on the SFU and
// left; a conference whose join never got there stays until this is called.
func takeBack(ctx context.Context, client *http.Client, gateway, conferenceID, cookies string) error {
	if gateway == "" || conferenceID == "" || strings.Trim(conferenceID, "0123456789") != "" {
		return nil
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodDelete, gateway+"/api/conferences/"+conferenceID, http.NoBody)
	if err != nil {
		return fmt.Errorf("mtslink: leave request: %w", err)
	}
	if cookies != "" {
		req.Header.Set("Cookie", cookies)
	}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("mtslink: leave: %w", redactURL(err))
	}
	defer func() { _ = resp.Body.Close() }()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, maxSignalBody))
	return nil
}
