package videochannel

import (
	"errors"
	"fmt"
	"strings"
	"sync"

	grqr "github.com/zarazaex69/gr/qr"
	grtile "github.com/zarazaex69/gr/tile"
)

// ErrUnexpectedQRFrameSize is returned when the decoded frame size does not match the expected dimensions.
var ErrUnexpectedQRFrameSize = errors.New("unexpected qr frame size")

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
	frame, err := c.qr.Encode(payload)
	if err != nil {
		return nil, fmt.Errorf("qr encode: %w", err)
	}
	return frame, nil
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

	result, err := c.Encode(payload)
	if err != nil {
		return nil, fmt.Errorf("qr encode: %w", err)
	}
	return result, nil
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
