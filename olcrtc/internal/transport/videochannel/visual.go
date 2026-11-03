package videochannel

import (
	"errors"
	"fmt"
	"strings"
	"sync"

	grqr "github.com/zarazaex69/gr/qr"
	grtile "github.com/zarazaex69/gr/tile"
)

var (
	// ErrUnexpectedQRFrameSize is returned when the decoded frame size does not match the expected dimensions.
	ErrUnexpectedQRFrameSize = errors.New("unexpected qr frame size")
	// ErrQRTooLarge is returned when a payload's QR symbol does not fit the frame.
	ErrQRTooLarge = errors.New("qr symbol larger than the frame")
)

// qrBlock is the side, in pixels, of the grid every QR module edge is drawn
// on: VP8's transform block. A module edge inside a 4x4 block leaves the
// quantiser a sharp step to code, and at the encoder's quantiser the residual
// rings: a pixel of the white quiet zone above the symbol comes back mid-gray,
// gozxing's pure-barcode search takes it for the symbol's corner and finds no
// code. Drawn to fill a 1920x1080 frame, the symbol of every whole fragment
// had 15 px modules and not one of those frames decoded (olcrtc#13). With the
// edges on the block grid every block is flat and the encoder codes it
// exactly.
//
// ai-generated: this constant and its note.
const qrBlock = 4

type visualCodec struct {
	mu     sync.Mutex
	qr     *grqr.Codec
	tile   *grtile.Codec
	idle   []byte
	codec  string
	width  int
	height int
}

func newVisualCodec(
	width, height int,
	codec, recoveryLevel string,
	tileModule, tileRS int,
) (*visualCodec, error) {
	visual := &visualCodec{
		idle:   make([]byte, width*height),
		codec:  codec,
		width:  width,
		height: height,
	}
	for i := range visual.idle {
		visual.idle[i] = 0xff
	}
	if codec == codecTile {
		tile, err := grtile.New(grtile.Config{Module: tileModule, RSPercent: tileRS})
		if err != nil {
			return nil, fmt.Errorf("tile codec: %w", err)
		}
		visual.tile = tile
		return visual, nil
	}
	qr, err := grqr.New(grqr.Config{
		FrameW: width,
		FrameH: height,
		Margin: 2,
		ECC:    eccLevel(recoveryLevel),
	})
	if err != nil {
		return nil, fmt.Errorf("qr codec: %w", err)
	}
	visual.qr = qr
	return visual, nil
}

func (c *visualCodec) render(payload []byte) ([]byte, error) {
	if len(payload) == 0 {
		return c.idle, nil
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.codec == codecTile {
		frame, err := c.tile.Encode(payload, 0, 1)
		if err != nil {
			return nil, fmt.Errorf("tile encode: %w", err)
		}
		return frame, nil
	}
	return renderQR(c.qr, payload, c.width, c.height)
}

func (c *visualCodec) extract(frame []byte) ([]byte, error) {
	if c.codec == codecTile && len(frame) != grtile.FrameW*grtile.FrameH {
		return nil, nil
	}
	if c.codec != codecTile && len(frame) != c.width*c.height {
		return nil, fmt.Errorf("%w: got %d expected %dx%d=%d",
			ErrUnexpectedQRFrameSize, len(frame), c.width, c.height, c.width*c.height)
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.codec == codecTile {
		result, err := c.tile.Decode(frame)
		if err != nil {
			return nil, nil
		}
		return result.Payload, nil
	}
	data, err := c.qr.Decode(frame)
	if err != nil {
		if strings.Contains(err.Error(), "NotFoundException") || strings.Contains(err.Error(), "not found") {
			return nil, nil
		}
		return nil, fmt.Errorf("decode: %w", err)
	}
	return data, nil
}

func eccLevel(level string) grqr.ECCLevel {
	switch level {
	case "medium":
		return grqr.ECCMedium
	case "high":
		return grqr.ECCQuartile
	case "highest":
		return grqr.ECCHigh
	default:
		return grqr.ECCLow
	}
}

func renderVisualFrame(
	payload []byte,
	width, height int,
	codec, recoveryLevel string,
	tileModule, tileRS int, //nolint:unparam // runtime-configurable transport settings
) ([]byte, error) {
	if codec == codecTile {
		return renderTileFrame(payload, tileModule, tileRS)
	}
	return renderQRFrame(payload, width, height, recoveryLevel)
}

func renderQRFrame(payload []byte, width, height int, recoveryLevel string) ([]byte, error) {
	if len(payload) == 0 {
		frame := make([]byte, width*height)
		for i := range frame {
			frame[i] = 0xff
		}
		return frame, nil
	}

	c, err := grqr.New(grqr.Config{
		FrameW: width,
		FrameH: height,
		Margin: 2,
		ECC:    eccLevel(recoveryLevel),
	})
	if err != nil {
		return nil, fmt.Errorf("qr codec: %w", err)
	}
	return renderQR(c, payload, width, height)
}

// renderQR draws payload's QR symbol, quiet zone included, centred in a white
// width x height frame with every module edge on the qrBlock grid.
//
// ai-generated: the whole function (was the library's Encode, which scales
// the symbol to fill the frame wherever its module edges fall).
func renderQR(c *grqr.Codec, payload []byte, width, height int) ([]byte, error) {
	grid, err := c.EncodeBitmap(payload)
	if err != nil {
		return nil, fmt.Errorf("qr encode: %w", err)
	}
	modules := len(grid)
	scale, left, top := qrPlacement(modules, width, height)
	if scale == 0 {
		return nil, fmt.Errorf("%w: %d modules in %dx%d", ErrQRTooLarge, modules, width, height)
	}
	frame := make([]byte, width*height)
	for i := range frame {
		frame[i] = 0xff
	}
	for row, bits := range grid {
		for col, black := range bits {
			if !black {
				continue
			}
			x := left + col*scale
			for y := top + row*scale; y < top+(row+1)*scale; y++ {
				clear(frame[y*width+x : y*width+x+scale])
			}
		}
	}
	return frame, nil
}

// qrPlacement is where a symbol modules wide goes in a width x height frame:
// the side of a module and the symbol's top-left corner, on the qrBlock grid
// so no transform block straddles a module edge. A module is one block, the
// smallest side the codec keeps exact: the providers forward VP8 as it was
// sent, so the symbol only has to survive the codec, and the less of the
// frame it covers, the fewer bytes and RTP packets the frame takes. A whole
// fragment at 1920x1080 is 12 KB this way, 31 KB at the largest aligned side.
// A symbol too dense for a block a module keeps the largest side that fits,
// and one that does not fit at a pixel a module gets a side of 0.
//
// ai-generated: the whole function.
func qrPlacement(modules, width, height int) (int, int, int) {
	if modules <= 0 {
		return 0, 0, 0
	}
	scale := min(qrBlock, min(width, height)/modules)
	if scale == 0 {
		return 0, 0, 0
	}
	side := modules * scale
	left := (width - side) / 2
	top := (height - side) / 2
	return scale, left - left%qrBlock, top - top%qrBlock
}

func renderTileFrame(payload []byte, tileModule, tileRS int) ([]byte, error) {
	if len(payload) == 0 {
		frame := make([]byte, grtile.FrameW*grtile.FrameH)
		for i := range frame {
			frame[i] = 0xff
		}
		return frame, nil
	}

	c, err := grtile.New(grtile.Config{Module: tileModule, RSPercent: tileRS})
	if err != nil {
		return nil, fmt.Errorf("tile codec: %w", err)
	}

	result, err := c.Encode(payload, 0, 1)
	if err != nil {
		return nil, fmt.Errorf("tile encode: %w", err)
	}
	return result, nil
}

func extractVisualPayload(
	frame []byte,
	width, height int,
	codec string,
	tileModule, tileRS int, //nolint:unparam // runtime-configurable transport settings
) ([]byte, error) {
	if codec == codecTile {
		return extractTilePayload(frame, tileModule, tileRS)
	}
	return extractQRPayload(frame, width, height)
}

func extractQRPayload(frame []byte, width, height int) ([]byte, error) {
	if len(frame) != width*height {
		return nil, fmt.Errorf("%w: got %d expected %dx%d=%d",
			ErrUnexpectedQRFrameSize, len(frame), width, height, width*height)
	}

	c, err := grqr.New(grqr.Config{
		FrameW: width,
		FrameH: height,
		Margin: 2,
	})
	if err != nil {
		return nil, fmt.Errorf("qr codec: %w", err)
	}

	data, err := c.Decode(frame)
	if err != nil {
		if strings.Contains(err.Error(), "NotFoundException") || strings.Contains(err.Error(), "not found") {
			return nil, nil
		}
		return nil, fmt.Errorf("decode: %w", err)
	}

	return data, nil
}

func extractTilePayload(frame []byte, tileModule, tileRS int) ([]byte, error) {
	if len(frame) != grtile.FrameW*grtile.FrameH {
		return nil, nil
	}

	c, err := grtile.New(grtile.Config{Module: tileModule, RSPercent: tileRS})
	if err != nil {
		return nil, fmt.Errorf("tile codec: %w", err)
	}

	result, err := c.Decode(frame)
	if err != nil {
		return nil, nil //nolint:nilerr // decode failures are treated as "no payload" by callers
	}

	return result.Payload, nil
}
