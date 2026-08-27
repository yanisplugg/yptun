package auth

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"

	"github.com/openlibrecommunity/olcrtc/internal/protect"
)

const (
	// statusBodyLimit bounds how much of an upstream error body is echoed
	// back in the returned error.
	statusBodyLimit = 4096
	// successBodyLimit bounds a JSON success body. Error bodies were already
	// bounded; a provider that answers 200 with an endless stream would
	// otherwise be decoded until the process ran out of memory.
	successBodyLimit = 8 << 20
)

// DoJSON sends req on client and decodes a JSON success body into T.
//
// A non-200 response is reported as sentinel wrapped with the bounded and
// redacted upstream body, so callers can match with errors.Is. The response
// body is always closed. Build one client per provider flow and reuse it for
// every request so connections are pooled.
func DoJSON[T any](client *http.Client, req *http.Request, sentinel error) (T, error) {
	var out T
	//nolint:gosec // G704: the URL is provider-controlled, built from a fixed API base
	resp, err := client.Do(req)
	if err != nil {
		return out, fmt.Errorf("do request: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode != http.StatusOK {
		return out, fmt.Errorf("status: %w", protect.StatusError(sentinel, resp, statusBodyLimit))
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, successBodyLimit)).Decode(&out); err != nil {
		return out, fmt.Errorf("decode response: %w", err)
	}
	return out, nil
}

// Do sends req on client and discards the success body. Use it for endpoints
// whose response carries nothing the caller needs. Error handling matches
// DoJSON.
func Do(client *http.Client, req *http.Request, sentinel error) error {
	//nolint:gosec // G704: the URL is provider-controlled, built from a fixed API base
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("do request: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("status: %w", protect.StatusError(sentinel, resp, statusBodyLimit))
	}
	return nil
}
