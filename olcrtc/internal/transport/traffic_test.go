package transport

import (
	"errors"
	"testing"
	"time"
)

func TestNewShaperReturnsNilWhenDisabled(t *testing.T) {
	if got := NewShaper(TrafficConfig{}, Features{}); got != nil {
		t.Fatalf("NewShaper(zero config) = %v, want nil", got)
	}
}

func TestNilShaperSendsDirectly(t *testing.T) {
	var shaper *Shaper

	var got []byte

	err := shaper.Send(func(data []byte) error {
		got = data

		return nil
	}, []byte("payload"))
	if err != nil {
		t.Fatalf("Send() error = %v", err)
	}

	if string(got) != "payload" {
		t.Fatalf("Send() delivered %q, want payload", got)
	}
}

func TestShaperClampsCapToTransportFeatures(t *testing.T) {
	shaper := NewShaper(TrafficConfig{MaxPayloadSize: 10}, Features{MaxPayloadSize: 5})

	if got := shaper.Features(Features{MaxPayloadSize: 5}); got.MaxPayloadSize != 5 {
		t.Fatalf("Features().MaxPayloadSize = %d, want 5", got.MaxPayloadSize)
	}

	sent := 0
	send := func([]byte) error {
		sent++

		return nil
	}

	if err := shaper.Send(send, []byte("123456")); !errors.Is(err, ErrTrafficPayloadTooLarge) {
		t.Fatalf("Send(oversized) error = %v, want %v", err, ErrTrafficPayloadTooLarge)
	}

	if sent != 0 {
		t.Fatalf("oversized payload reached the transport %d times", sent)
	}

	if err := shaper.Send(send, []byte("12345")); err != nil {
		t.Fatalf("Send(max sized) error = %v", err)
	}

	if sent != 1 {
		t.Fatalf("sent = %d, want 1", sent)
	}
}

func TestShaperNarrowsUnboundedFeatures(t *testing.T) {
	shaper := NewShaper(TrafficConfig{MaxPayloadSize: 64}, Features{})

	if got := shaper.Features(Features{}); got.MaxPayloadSize != 64 {
		t.Fatalf("Features().MaxPayloadSize = %d, want 64", got.MaxPayloadSize)
	}
}

func TestShaperAppliesMinimumDelay(t *testing.T) {
	shaper := NewShaper(TrafficConfig{MinDelay: 2 * time.Millisecond}, Features{})

	start := time.Now()
	if err := shaper.Send(func([]byte) error { return nil }, []byte("x")); err != nil {
		t.Fatalf("Send() error = %v", err)
	}

	if elapsed := time.Since(start); elapsed < 2*time.Millisecond {
		t.Fatalf("Send() elapsed = %v, want at least 2ms", elapsed)
	}
}

func TestShaperJitterStaysInRange(t *testing.T) {
	shaper := NewShaper(TrafficConfig{MinDelay: time.Millisecond, MaxDelay: 3 * time.Millisecond}, Features{})

	for range 50 {
		delay := shaper.nextDelay()
		if delay < time.Millisecond || delay >= 3*time.Millisecond {
			t.Fatalf("nextDelay() = %v, want [1ms, 3ms)", delay)
		}
	}
}
