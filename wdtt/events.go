package wdtt

import (
	"encoding/json"
	"fmt"
	"log"
	"os"
	"sync/atomic"
)

var eventOutputEnabled = os.Getenv("WDTT_EVENTS") == "1"

type eventType string

const (
	eventStarted        eventType = "STARTED"
	eventStopped        eventType = "STOPPED"
	eventReady          eventType = "READY"
	eventConfig         eventType = "CONFIG"
	eventStats          eventType = "STATS"
	eventError          eventType = "ERROR"
	eventCaptchaRequest eventType = "CAPTCHA_REQUEST"
	eventCaptchaDone    eventType = "CAPTCHA_DONE"
)

func emitEvent(t eventType, payload map[string]any) {
	if !eventOutputEnabled {
		return
	}
	var p []byte
	if len(payload) > 0 {
		var err error
		p, err = json.Marshal(payload)
		if err != nil {
			log.Printf("[EVENT] failed to marshal %s event: %v", t, err)
			return
		}
	}
	fmt.Printf("__WDTT_EVENT__|%s|%s\n", t, string(p))
}

func emitError(code, message string, fatal bool) {
	emitEvent(eventError, map[string]any{
		"code":    code,
		"message": message,
		"fatal":   fatal,
	})
}

func emitStats(s *Stats) {
	// Local Stats keeps plain int32/int64 fields (accessed via sync/atomic helpers), not atomic.Int*,
	// so use the Load* helpers here instead of the upstream method form (s.Field.Load()).
	emitEvent(eventStats, map[string]any{
		"active":     atomic.LoadInt32(&s.ActiveConnections),
		"bytes_up":   atomic.LoadInt64(&s.TotalBytesUp),
		"bytes_down": atomic.LoadInt64(&s.TotalBytesDown),
	})
}

func emitReady() {
	emitEvent(eventReady, nil)
}

func emitConfig(config string) {
	emitEvent(eventConfig, map[string]any{"config": config})
}

func emitCaptchaRequest(mode, redirectURI, sessionToken string) {
	emitEvent(eventCaptchaRequest, map[string]any{
		"mode":          mode,
		"redirect_uri":  redirectURI,
		"session_token": sessionToken,
	})
}

func emitCaptchaDone(success bool, err string) {
	payload := map[string]any{"success": success}
	if err != "" {
		payload["error"] = err
	}
	emitEvent(eventCaptchaDone, payload)
}
