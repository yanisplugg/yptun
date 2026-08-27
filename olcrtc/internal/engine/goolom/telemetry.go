package goolom

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"time"

	"github.com/google/uuid"

	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/protect"
)

func (s *Session) startTelemetry(ctx context.Context, serverHello map[string]any) {
	endpoint, interval, ok := parseTelemetryCfg(serverHello)
	if !ok {
		return
	}
	if !s.telemetryActive.CompareAndSwap(false, true) {
		return
	}

	s.goLaunch(func() {
		defer s.telemetryActive.Store(false)

		ticker := time.NewTicker(interval)
		defer ticker.Stop()

		s.sendTelemetry(ctx, endpoint, "join")
		for {
			select {
			case <-ticker.C:
				s.sendTelemetry(ctx, endpoint, "stats")
			case <-s.telemetryCh:
				s.sendTelemetry(ctx, endpoint, "leave")
				return
			case <-s.closeCh:
				s.sendTelemetry(ctx, endpoint, "leave")
				return
			}
		}
	})
}

// parseTelemetryCfg reads the telemetry destination and cadence out of the
// media server's hello. Both values are attacker-controlled - the SFU is the
// one thing on this path we do not trust - so both are checked here rather
// than handed to net/http and time.NewTicker as they arrive.
func parseTelemetryCfg(serverHello map[string]any) (string, time.Duration, bool) {
	cfg, ok := serverHello["telemetryConfiguration"].(map[string]any)
	if !ok {
		return "", 0, false
	}
	endpoint, ok := cfg["logEndpoint"].(string)
	if !ok || endpoint == "" {
		endpoint, ok = cfg["endpoint"].(string)
		if !ok || endpoint == "" {
			endpoint, _ = cfg["url"].(string)
		}
	}
	if !telemetryEndpointAllowed(endpoint) {
		return "", 0, false
	}
	return endpoint, telemetryInterval(cfg), true
}

// telemetryEndpointAllowed rejects anything but an absolute https URL. The
// posted body carries the peer and room identity and the request carries the
// room link as its Referer, so a plaintext or scheme-less destination is not
// somewhere this may be sent.
func telemetryEndpointAllowed(endpoint string) bool {
	if endpoint == "" {
		return false
	}
	parsed, err := url.Parse(endpoint)
	if err != nil {
		return false
	}
	return parsed.Scheme == "https" && parsed.Host != ""
}

// telemetryInterval clamps the announced cadence. time.Duration truncates, so
// any sendingInterval below 1ms lands on zero, and time.NewTicker(0) panics in
// a goroutine with nothing to recover it.
func telemetryInterval(cfg map[string]any) time.Duration {
	raw, ok := cfg["sendingInterval"].(float64)
	if !ok {
		return defaultTelemetryInterval
	}
	interval := time.Duration(raw) * time.Millisecond
	if interval < minTelemetryInterval {
		return minTelemetryInterval
	}
	if interval > maxTelemetryInterval {
		return maxTelemetryInterval
	}
	return interval
}

func (s *Session) stopTelemetry() {
	if s.telemetryActive.Load() {
		select {
		case s.telemetryCh <- struct{}{}:
		default:
		}
	}
}

func (s *Session) sendTelemetry(ctx context.Context, endpoint, event string) {
	peerID, roomID, referer := s.credentialSnapshot()
	body, err := json.Marshal(map[string]any{
		"event":          event,
		"timestamp":      time.Now().UnixMilli(),
		"peerId":         peerID,
		"roomId":         roomID,
		"displayName":    s.name,
		"implementation": "browser",
		"dataChannel": map[string]any{
			"bufferedAmount": s.GetBufferedAmount(),
			"sendQueue":      len(s.sendQueue),
		},
	})
	if err != nil {
		return
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(body))
	if err != nil {
		logger.Verbosef("Telemetry req error: %v", err)
		return
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:149.0) Gecko/20100101 Firefox/149.0")
	if referer != "" {
		req.Header.Set("Referer", referer)
	}
	req.Header.Set("X-Requested-With", "XMLHttpRequest")
	req.Header.Set("Client-Instance-Id", uuid.New().String())
	req.Header.Set("X-Telemost-Client-Version", "187.1.0")
	req.Header.Set("Idempotency-Key", uuid.New().String())

	client := protect.NewHTTPClient(s.resolver)
	resp, err := client.Do(req)
	if err != nil {
		logger.Verbosef("Telemetry send error: %v", err)
		return
	}
	_ = resp.Body.Close()
}
