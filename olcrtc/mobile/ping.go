package mobile

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"time"
)

const (
	defaultHTTPPingURL    = "https://www.google.com/generate_204"
	httpPingWarmupTimeout = 1500 * time.Millisecond
	httpPingSampleTimeout = 1500 * time.Millisecond
	httpPingSamples       = 3
	httpPingSampleDelay   = 80 * time.Millisecond
)

//nolint:gochecknoglobals,nolintlint // sentinel identities are immutable public API values
var (
	ErrHTTPPingTimeout      = errors.New("HTTP ping timed out")
	ErrUnexpectedHTTPStatus = errors.New("unexpected HTTP status")
)

func httpPingThroughSocks(parentCtx context.Context, socksAddr, targetURL string) (int64, error) {
	normalizedURL, err := normalizeHTTPPingURL(targetURL)
	if err != nil {
		return 0, err
	}
	httpClient, closeClient := newHTTPPingClient(socksAddr)
	defer closeClient()
	_, _ = singleHTTPPingRequest(parentCtx, httpClient, normalizedURL, httpPingWarmupTimeout)
	return bestHTTPPingSample(parentCtx, httpClient, normalizedURL)
}

func normalizeHTTPPingURL(targetURL string) (string, error) {
	if targetURL == "" {
		targetURL = defaultHTTPPingURL
	}
	parsed, err := url.ParseRequestURI(targetURL)
	if err != nil {
		return "", fmt.Errorf("parse HTTP ping URL: %w", err)
	}
	if parsed.Scheme != "http" && parsed.Scheme != "https" {
		return "", fmt.Errorf("parse HTTP ping URL: %w: unsupported scheme %q", ErrInvalidConfig, parsed.Scheme)
	}
	return targetURL, nil
}

func newHTTPPingClient(socksAddr string) (*http.Client, func()) {
	transport := &http.Transport{
		Proxy:                 http.ProxyURL(&url.URL{Scheme: "socks5", Host: socksAddr}),
		DisableKeepAlives:     false,
		MaxIdleConns:          4,
		MaxIdleConnsPerHost:   4,
		IdleConnTimeout:       10 * time.Second,
		ForceAttemptHTTP2:     false,
		TLSHandshakeTimeout:   httpPingSampleTimeout,
		ResponseHeaderTimeout: httpPingSampleTimeout,
		ExpectContinueTimeout: 500 * time.Millisecond,
	}
	httpClient := &http.Client{
		Transport: transport,
		Timeout:   httpPingSampleTimeout,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	return httpClient, transport.CloseIdleConnections
}

func bestHTTPPingSample(parentCtx context.Context, httpClient *http.Client, targetURL string) (int64, error) {
	var best int64
	var lastErr error
	for i := range httpPingSamples {
		elapsed, err := singleHTTPPingRequest(parentCtx, httpClient, targetURL, httpPingSampleTimeout)
		if err != nil {
			lastErr = err
		} else {
			best = bestPositiveLatency(best, elapsed)
		}
		if i < httpPingSamples-1 && !waitPingSample(parentCtx) {
			break
		}
	}
	if best > 0 {
		return best, nil
	}
	if lastErr != nil {
		return 0, lastErr
	}
	return 0, ErrHTTPPingTimeout
}

func waitPingSample(ctx context.Context) bool {
	timer := time.NewTimer(httpPingSampleDelay)
	defer timer.Stop()
	select {
	case <-timer.C:
		return true
	case <-ctx.Done():
		return false
	}
}

func bestPositiveLatency(currentBest, next int64) int64 {
	if next <= 0 {
		return currentBest
	}
	if currentBest == 0 || next < currentBest {
		return next
	}
	return currentBest
}

func singleHTTPPingRequest(
	parentCtx context.Context,
	httpClient *http.Client,
	targetURL string,
	timeout time.Duration,
) (int64, error) {
	ctx, cancel := context.WithTimeout(parentCtx, timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, targetURL, http.NoBody)
	if err != nil {
		return 0, fmt.Errorf("create HTTP ping request: %w", err)
	}
	req.Header.Set("User-Agent", "Olcbox-Android")
	req.Header.Set("Connection", "keep-alive")
	req.Header.Set("Cache-Control", "no-cache")
	started := time.Now()
	resp, err := httpClient.Do(req)
	if err != nil {
		return 0, fmt.Errorf("perform HTTP ping request: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()
	elapsed := time.Since(started).Milliseconds()
	_, _ = io.Copy(io.Discard, resp.Body)
	if resp.StatusCode < http.StatusOK || resp.StatusCode > http.StatusPermanentRedirect {
		return 0, fmt.Errorf("%w: %d", ErrUnexpectedHTTPStatus, resp.StatusCode)
	}
	return elapsed, nil
}
