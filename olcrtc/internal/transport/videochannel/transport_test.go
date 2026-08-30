package videochannel

import (
	"bytes"
	"sync"
	"testing"
)

func TestVisualRoundTrip(t *testing.T) {
	payload := []byte("hello over visual videochannel")
	frame, err := renderVisualFrame(payload, 320, 240, "qrcode", "low", 4, 20)
	if err != nil {
		t.Fatalf("renderVisualFrame failed: %v", err)
	}

	got, err := extractVisualPayload(frame, 320, 240, "qrcode", 4, 20)
	if err != nil {
		t.Fatalf("extractVisualPayload failed: %v", err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatalf("payload mismatch: got=%q want=%q", got, payload)
	}
}

func TestIdleFrameIgnored(t *testing.T) {
	frame, err := renderVisualFrame(nil, 320, 240, "qrcode", "low", 4, 20)
	if err != nil {
		t.Fatalf("renderVisualFrame failed: %v", err)
	}

	got, err := extractVisualPayload(frame, 320, 240, "qrcode", 4, 20)
	if err == nil && len(got) != 0 {
		t.Fatalf("expected idle frame to be ignored, got=%q", got)
	}
}

func TestTileVisualRoundTrip(t *testing.T) {
	payload := []byte("hello over tile videochannel")
	frame, err := renderVisualFrame(payload, 1080, 1080, "tile", "", 4, 20)
	if err != nil {
		t.Fatalf("renderVisualFrame tile failed: %v", err)
	}

	got, err := extractVisualPayload(frame, 1080, 1080, "tile", 4, 20)
	if err != nil {
		t.Fatalf("extractVisualPayload tile failed: %v", err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatalf("payload mismatch: got=%q want=%q", got, payload)
	}
}

func TestTileIdleFrameIgnored(t *testing.T) {
	frame, err := renderVisualFrame(nil, 1080, 1080, "tile", "", 4, 20)
	if err != nil {
		t.Fatalf("renderVisualFrame tile failed: %v", err)
	}

	got, err := extractVisualPayload(frame, 1080, 1080, "tile", 4, 20)
	if err == nil && len(got) != 0 {
		t.Fatalf("expected tile idle frame to be ignored, got=%q", got)
	}
}

func TestStreamTransportReusesIdleFrame(t *testing.T) {
	tr := &streamTransport{videoW: 320, videoH: 240, videoCodec: "qrcode"}
	first, err := tr.renderFrame(nil)
	if err != nil {
		t.Fatalf("renderFrame(first) error = %v", err)
	}
	second, err := tr.renderFrame(nil)
	if err != nil {
		t.Fatalf("renderFrame(second) error = %v", err)
	}
	if len(first) == 0 || &first[0] != &second[0] {
		t.Fatal("renderFrame() did not reuse the transport idle frame")
	}
	if allocs := testing.AllocsPerRun(100, func() {
		_, _ = tr.renderFrame(nil)
	}); allocs != 0 {
		t.Fatalf("renderFrame(idle) allocations = %v, want 0", allocs)
	}
}

func TestVisualCodecConcurrentRoundTrip(t *testing.T) {
	tr := &streamTransport{
		videoW:          320,
		videoH:          240,
		videoCodec:      "qrcode",
		videoQRRecovery: "low",
		videoTileModule: 4,
		videoTileRS:     20,
	}
	const workers = 8
	var wg sync.WaitGroup
	wg.Add(workers)
	for range workers {
		go func() {
			defer wg.Done()
			payload := []byte{1, 2, 3, 4, 5}
			frame, renderErr := tr.renderFrame(payload)
			if renderErr != nil {
				t.Errorf("render() error = %v", renderErr)
				return
			}
			got, extractErr := tr.extractFrame(frame)
			if extractErr != nil {
				t.Errorf("extract() error = %v", extractErr)
				return
			}
			if !bytes.Equal(got, payload) {
				t.Errorf("round trip = %v, want %v", got, payload)
			}
		}()
	}
	wg.Wait()
}
