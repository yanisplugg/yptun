package vk

import (
	"context"
	"errors"
	"fmt"
	"testing"
	"time"
)

func cooldownActive() bool {
	proxyPowCooldownMu.Lock()
	defer proxyPowCooldownMu.Unlock()
	return time.Until(proxyPowCooldownUntil) > 0
}

func TestRecordPowCaptureResult(t *testing.T) {
	t.Cleanup(func() { recordPowCaptureResult(nil) })

	tests := []struct {
		name string
		err  error
		want bool
	}{
		{"success", nil, false},
		{"canceled", context.Canceled, false},
		{"wrapped canceled", fmt.Errorf("capture: %w", context.Canceled), false},
		{"deadline", context.DeadlineExceeded, true},
		{"other", errors.New("boom"), true},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			recordPowCaptureResult(errors.New("previous failure"))
			recordPowCaptureResult(tc.err)
			if got := cooldownActive(); got != tc.want {
				t.Fatalf("cooldown active = %v, want %v", got, tc.want)
			}
		})
	}
}
