package runtime_test

import (
	"errors"
	"testing"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/control"
	"github.com/openlibrecommunity/olcrtc/internal/crypto"
	"github.com/openlibrecommunity/olcrtc/internal/runtime"
)

func TestSetupKeySetErrors(t *testing.T) {
	if _, err := runtime.SetupKeySet("", crypto.Client); !errors.Is(err, runtime.ErrKeyRequired) {
		t.Fatalf("empty key error = %v, want ErrKeyRequired", err)
	}
	if _, err := runtime.SetupKeySet("notHex", crypto.Client); err == nil {
		t.Fatalf("bad hex error = nil")
	}
	if _, err := runtime.SetupKeySet("00", crypto.Client); !errors.Is(err, runtime.ErrKeySize) {
		t.Fatalf("short key error = %v, want ErrKeySize", err)
	}
}

func TestSetupKeySetSuccess(t *testing.T) {
	key := "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
	c, err := runtime.SetupKeySet(key, crypto.Server)
	if err != nil {
		t.Fatalf("SetupKeySet() error = %v", err)
	}
	if c == nil {
		t.Fatal("SetupKeySet() returned nil key set")
	}
}

func TestSmuxWireOverhead(t *testing.T) {
	if crypto.WireOverhead != 44 {
		t.Fatalf("crypto.WireOverhead = %d, want 44", crypto.WireOverhead)
	}
	if runtime.SmuxWireOverhead != 52 {
		t.Fatalf("runtime.SmuxWireOverhead = %d, want 52", runtime.SmuxWireOverhead)
	}
}

func TestSmuxConfigDefault(t *testing.T) {
	cfg := runtime.SmuxConfig(0)
	if cfg.Version != 2 || cfg.MaxFrameSize != 32768 {
		t.Fatalf("SmuxConfig(0) = %+v", cfg)
	}
	if cfg.MaxReceiveBuffer != 32*1024*1024 || cfg.MaxStreamBuffer != 4*1024*1024 {
		t.Fatalf("SmuxConfig(0) buffers = %+v", cfg)
	}
	if cfg.KeepAliveDisabled || cfg.KeepAliveInterval != 10*time.Second ||
		cfg.KeepAliveTimeout != 30*time.Second {
		t.Fatalf("SmuxConfig(0) keepalive = %+v", cfg)
	}
}

func TestSmuxConfigLong(t *testing.T) {
	cfg := runtime.SmuxConfigLong(0)
	if cfg.KeepAliveInterval != 10*time.Second || cfg.KeepAliveTimeout != 120*time.Second {
		t.Fatalf("SmuxConfigLong(0) keepalive = %+v", cfg)
	}
}

func TestSmuxConfigShrinks(t *testing.T) {
	// 100-byte wire payload minus smux+crypto overhead is far below default
	// 32768, so MaxFrameSize must shrink.
	cfg := runtime.SmuxConfig(100)
	if cfg.MaxFrameSize >= 32768 {
		t.Fatalf("MaxFrameSize = %d, want shrunk", cfg.MaxFrameSize)
	}
	if cfg.MaxFrameSize+runtime.SmuxWireOverhead != 100 {
		t.Fatalf("wire size = %d, want 100", cfg.MaxFrameSize+runtime.SmuxWireOverhead)
	}
}

func TestControlSmuxConfigUsesSameFrameClamp(t *testing.T) {
	cfg := runtime.ControlSmuxConfig(100)
	if cfg.MaxFrameSize+runtime.SmuxWireOverhead != 100 {
		t.Fatalf("control wire size = %d, want 100", cfg.MaxFrameSize+runtime.SmuxWireOverhead)
	}
	if !cfg.KeepAliveDisabled {
		t.Fatal("ControlSmuxConfig() keepalive is enabled")
	}
}

func TestHealthTrackerEmitsOnEveryChange(t *testing.T) {
	var got []control.Status
	h := runtime.NewHealthTracker(func(s control.Status) {
		got = append(got, s)
	})

	h.RecordSession("s1")
	h.RecordPong(control.Health{LastSeen: time.Unix(100, 0), RTT: time.Millisecond})
	h.RecordMissed(2)
	h.RecordReconnect()
	h.RecordUnhealthy(3)

	if len(got) != 5 {
		t.Fatalf("notify count = %d, want 5", len(got))
	}
	if got[0].SessionID != "s1" {
		t.Fatalf("first snapshot session id = %q", got[0].SessionID)
	}
	if got[1].LastRTT != time.Millisecond {
		t.Fatalf("second snapshot rtt = %v", got[1].LastRTT)
	}
	final := h.Status()
	if final.Reconnects != 1 || final.UnhealthyEvents != 1 || final.MissedPongs != 3 {
		t.Fatalf("final snapshot = %+v", final)
	}
}

func TestHealthTrackerNilNotifyOK(t *testing.T) {
	h := runtime.NewHealthTracker(nil)
	h.RecordSession("s") // must not panic
	if h.Status().SessionID != "s" {
		t.Fatal("Status() did not record without notify")
	}
}
