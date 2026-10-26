package videochannel

import (
	"bytes"
	"errors"
	"hash/crc32"
	"math/rand/v2"
	"testing"

	grqr "github.com/zarazaex69/gr/qr"

	"github.com/openlibrecommunity/olcrtc/internal/transport/common"
)

// ai-generated: the whole file (a QR frame has to decode after the VP8 round
// trip the peer's decoder sees, not just as rendered, and where its modules go).

// vp8RoundTrip renders payload the way the writer does, encodes it with the
// writer's encoder, decodes it with the reader's decoder and extracts it the
// way handleFrame does. The error is the extraction's.
func vp8RoundTrip(t *testing.T, vis *visualCodec, w, h int, payload []byte) ([]byte, error) {
	t.Helper()
	raw, err := vis.render(payload)
	if err != nil {
		t.Fatalf("render(%d bytes) error = %v", len(payload), err)
	}
	enc := newGoEncoder(w, h, defaultFPS)
	defer enc.Close()
	sample, err := enc.EncodeFrame(raw)
	if err != nil {
		t.Fatalf("EncodeFrame() error = %v", err)
	}
	dec := newGoDecoder()
	defer dec.Close()
	if err = dec.PushSample(sample); err != nil {
		t.Fatalf("PushSample() error = %v", err)
	}
	gray, err := dec.PopFrame()
	if err != nil {
		t.Fatalf("PopFrame() error = %v", err)
	}
	return vis.extract(gray)
}

// TestFullFragmentSurvivesVP8 is the frame every bulk write is made of: a data
// frame that carries a whole fragment, at the default 1920x1080. Drawn to fill
// the frame, its modules were 15 px, their edges fell inside VP8's 4x4
// transform blocks, and the quantiser rang a mid-gray pixel into the quiet zone
// above the symbol. gozxing took that pixel for the symbol's corner and found
// nothing, so not one such frame decoded, no bulk write was ever acknowledged
// and a pull delivered no bytes (olcrtc#13).
func TestFullFragmentSurvivesVP8(t *testing.T) {
	vis, err := newVisualCodec(defaultWidth, defaultHeight, "qrcode", "low", defaultTileModule, defaultTileRS)
	if err != nil {
		t.Fatalf("newVisualCodec() error = %v", err)
	}
	for i := range 3 {
		fragment := fixture(byte(i), defaultFragmentSize)
		frame := common.EncodeData(common.RoleServer, 0x5eed, uint32(i+1), crc32.ChecksumIEEE(fragment),
			len(fragment)*4, i, 4, fragment)
		if got, err := vp8RoundTrip(t, vis, defaultWidth, defaultHeight, frame); !bytes.Equal(got, frame) {
			t.Fatalf("fragment %d: %d-byte data frame did not survive VP8: %d bytes, %v", i, len(frame), len(got), err)
		}
	}
}

// TestFullFragmentFrameIsSmall bounds what a whole fragment costs on the
// wire at the default 1920x1080: about a dozen RTP packets. Drawn at the
// largest aligned side it was 31 KB, and every packet of it is one more a
// single loss takes the frame down with.
func TestFullFragmentFrameIsSmall(t *testing.T) {
	const maxFrame = 16 << 10
	vis, err := newVisualCodec(defaultWidth, defaultHeight, "qrcode", "low", defaultTileModule, defaultTileRS)
	if err != nil {
		t.Fatalf("newVisualCodec() error = %v", err)
	}
	fragment := fixture(1, defaultFragmentSize)
	frame := common.EncodeData(common.RoleServer, 0x5eed, 1, crc32.ChecksumIEEE(fragment),
		len(fragment), 0, 1, fragment)
	raw, err := vis.render(frame)
	if err != nil {
		t.Fatalf("render() error = %v", err)
	}
	enc := newGoEncoder(defaultWidth, defaultHeight, defaultFPS)
	defer enc.Close()
	sample, err := enc.EncodeFrame(raw)
	if err != nil {
		t.Fatalf("EncodeFrame() error = %v", err)
	}
	if len(sample) > maxFrame {
		t.Fatalf("a whole fragment encodes to %d bytes, want at most %d", len(sample), maxFrame)
	}
}

// TestEveryQRVersionSurvivesVP8 walks the symbol versions a frame of each
// size carries a transport frame in, one payload per version, up to a few
// fragments' worth. Drawn to fill the frame, four versions failed at
// 1920x1080 (the whole fragment's among them), five at 1280x720 and eight at
// 640x480.
func TestEveryQRVersionSurvivesVP8(t *testing.T) {
	if testing.Short() {
		t.Skip("encodes and decodes a frame per QR version")
	}
	for _, size := range [][2]int{{1920, 1080}, {1280, 720}, {640, 480}} {
		w, h := size[0], size[1]
		vis, err := newVisualCodec(w, h, "qrcode", "low", defaultTileModule, defaultTileRS)
		if err != nil {
			t.Fatalf("newVisualCodec() error = %v", err)
		}
		seen := map[int]bool{}
		for n := 1; n <= 700; n++ {
			modules := qrModules(t, vis.qr, n)
			if seen[modules] {
				continue
			}
			seen[modules] = true
			payload := fixture(byte(n), n)
			if got, err := vp8RoundTrip(t, vis, w, h, payload); !bytes.Equal(got, payload) {
				t.Errorf("%dx%d: %d bytes (%d modules) did not survive VP8: %d bytes, %v", w, h, n, modules, len(got), err)
			}
		}
	}
}

// fixture is n pseudo-random bytes, the same for a seed on every run.
func fixture(seed byte, n int) []byte {
	out := make([]byte, n)
	_, _ = rand.NewChaCha8([32]byte{seed}).Read(out)
	return out
}

// qrModules is the side of the symbol n bytes need, quiet zone included.
func qrModules(t *testing.T, codec *grqr.Codec, n int) int {
	t.Helper()
	grid, err := codec.EncodeBitmap(make([]byte, n))
	if err != nil {
		t.Fatalf("EncodeBitmap(%d bytes) error = %v", n, err)
	}
	return len(grid)
}

// TestQRPlacementIsOnTheBlockGrid holds every symbol version to one transform
// block a module and a corner on the block grid, centred and inside the frame,
// and a symbol too dense for that to the largest side that fits.
func TestQRPlacementIsOnTheBlockGrid(t *testing.T) {
	sizes := [][2]int{{1920, 1080}, {1280, 720}, {640, 480}, {320, 240}, {1080, 1920}, {101, 97}}
	for _, size := range sizes {
		w, h := size[0], size[1]
		for modules := 29; modules <= 185; modules += 4 { // versions 1 to 40, quiet zone included
			scale, left, top := qrPlacement(modules, w, h)
			fits := min(w, h) / modules
			switch {
			case fits == 0:
				if scale != 0 {
					t.Errorf("%dx%d, %d modules: side %d, want 0 (does not fit)", w, h, modules, scale)
				}
				continue
			case scale != min(qrBlock, fits):
				t.Errorf("%dx%d, %d modules: side %d, want %d", w, h, modules, scale, min(qrBlock, fits))
			}
			side := modules * scale
			if left%qrBlock != 0 || top%qrBlock != 0 {
				t.Errorf("%dx%d, %d modules: corner (%d,%d) off the block grid", w, h, modules, left, top)
			}
			if left < 0 || top < 0 || left+side > w || top+side > h {
				t.Errorf("%dx%d, %d modules: %d px at (%d,%d) leaves the frame", w, h, modules, side, left, top)
			}
			if (w-side)/2-left >= qrBlock || (h-side)/2-top >= qrBlock {
				t.Errorf("%dx%d, %d modules: corner (%d,%d) not centred", w, h, modules, left, top)
			}
		}
	}
	if scale, _, _ := qrPlacement(0, 1920, 1080); scale != 0 {
		t.Fatalf("qrPlacement(0 modules) side = %d, want 0", scale)
	}
}

// TestQRLargerThanTheFrameIsAnError is a symbol no frame pixel per module can
// hold: an error the writer logs, where drawing it would index outside the
// frame.
func TestQRLargerThanTheFrameIsAnError(t *testing.T) {
	vis, err := newVisualCodec(16, 16, "qrcode", "low", defaultTileModule, defaultTileRS)
	if err != nil {
		t.Fatalf("newVisualCodec() error = %v", err)
	}
	if _, err := vis.render([]byte("does not fit")); !errors.Is(err, ErrQRTooLarge) {
		t.Fatalf("render() error = %v, want %v", err, ErrQRTooLarge)
	}
}
