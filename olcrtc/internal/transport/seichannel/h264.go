package seichannel

import (
	"bytes"
	"encoding/hex"
	"errors"
)

// ErrInvalidH264Constant is returned by mustDecodeHex when a hardcoded
// constant cannot be parsed.
var ErrInvalidH264Constant = errors.New("invalid hardcoded h264 constant")

var (
	// ErrSEIPayloadTruncated is returned when the SEI payload is
	// shorter than expected.
	ErrSEIPayloadTruncated = errors.New("sei payload truncated")
	// ErrSEIValueTruncated is returned when reading a SEI length-value runs past the buffer.
	ErrSEIValueTruncated = errors.New("sei value truncated")

	videoSEIUUID = [16]byte{ //nolint:gochecknoglobals // package-level state intentional
		0x5d, 0xc0, 0x3b, 0xa8,
		0x45, 0x0f,
		0x4b, 0x55,
		0x9a, 0x77,
		0x1f, 0x91, 0x6c, 0x5b, 0x07, 0x39,
	}
	//nolint:gochecknoglobals // hardcoded H264 constants
	baseSPS = mustDecodeHex("6742c00addec0440000003004000000300a3c489e0")
	//nolint:gochecknoglobals // hardcoded H264 constants
	basePPS = mustDecodeHex("68ce0fc8")
	//nolint:gochecknoglobals // hardcoded H264 constants
	baseIDR = mustDecodeHex("6588843a2628000902e0")
)

func buildVideoAccessUnit(payload []byte) []byte {
	return buildVideoAccessUnitInto(nil, payload)
}

func buildVideoAccessUnitInto(dst, payload []byte) []byte {
	maxEscapedPayload := len(payload) + len(payload)/2
	want := len(baseSPS) + len(basePPS) + len(baseIDR) + maxEscapedPayload + 64
	var out []byte
	if cap(dst) < want {
		out = make([]byte, 0, want)
	} else {
		out = dst[:0]
	}
	out = appendStartCode(out, baseSPS)
	out = appendStartCode(out, basePPS)
	if len(payload) > 0 {
		out = append(out, 0x00, 0x00, 0x00, 0x01, 0x06)
		out = appendEscapedSEIRBSP(out, payload)
	}
	out = appendStartCode(out, baseIDR)
	return out
}

func extractVideoPayloads(accessUnit []byte) [][]byte {
	payloads := make([][]byte, 0, 1)
	for pos := 0; ; {
		nal, next, ok := nextAnnexBNAL(accessUnit, pos)
		if !ok {
			return payloads
		}
		pos = next
		if len(nal) < 2 || nal[0]&0x1f != 6 {
			continue
		}

		found, err := extractTransportSEI(nal[1:])
		if err != nil {
			continue
		}
		payloads = append(payloads, found...)
	}
}

func nextAnnexBNAL(data []byte, pos int) ([]byte, int, bool) {
	start, prefixLen := findStartCode(data, pos)
	if start < 0 {
		return nil, len(data), false
	}
	nalStart := start + prefixLen
	next, _ := findStartCode(data, nalStart)
	if next < 0 {
		next = len(data)
	}
	return data[nalStart:next], next, true
}

func findStartCode(data []byte, start int) (int, int) {
	for i := start; i+2 < len(data); i++ {
		if data[i] != 0 || data[i+1] != 0 {
			continue
		}
		if data[i+2] == 1 {
			return i, 3
		}
		if i+3 < len(data) && data[i+2] == 0 && data[i+3] == 1 {
			return i, 4
		}
	}
	return -1, 0
}

func appendEscapedSEIRBSP(dst, payload []byte) []byte {
	zeroCount := 0
	dst = appendEscapedSEIValue(dst, 5, &zeroCount)
	dst = appendEscapedSEIValue(dst, len(videoSEIUUID)+len(payload), &zeroCount)
	dst = appendEscapedBytes(dst, videoSEIUUID[:], &zeroCount)
	dst = appendEscapedBytes(dst, payload, &zeroCount)
	return appendEscapedByte(dst, 0x80, &zeroCount)
}

func appendEscapedSEIValue(dst []byte, value int, zeroCount *int) []byte {
	for value >= 0xff {
		dst = appendEscapedByte(dst, 0xff, zeroCount)
		value -= 0xff
	}
	return appendEscapedByte(dst, byte(value), zeroCount) //nolint:gosec // bounded remainder
}

func appendEscapedBytes(dst, src []byte, zeroCount *int) []byte {
	for _, value := range src {
		dst = appendEscapedByte(dst, value, zeroCount)
	}
	return dst
}

func appendEscapedByte(dst []byte, value byte, zeroCount *int) []byte {
	if *zeroCount >= 2 && value <= 0x03 {
		dst = append(dst, 0x03)
		*zeroCount = 0
	}
	dst = append(dst, value)
	if value == 0 {
		*zeroCount++
	} else {
		*zeroCount = 0
	}
	return dst
}

func extractTransportSEI(rbsp []byte) ([][]byte, error) {
	data := unescapeRBSP(rbsp)
	out := make([][]byte, 0, 1)

	for pos := 0; pos < len(data); {
		if data[pos] == 0x80 && pos == len(data)-1 {
			break
		}

		payloadType, next, err := consumeSEIValue(data, pos)
		if err != nil {
			return nil, err
		}
		pos = next

		payloadSize, next, err := consumeSEIValue(data, pos)
		if err != nil {
			return nil, err
		}
		pos = next

		if pos+payloadSize > len(data) {
			return nil, ErrSEIPayloadTruncated
		}

		payload := data[pos : pos+payloadSize]
		pos += payloadSize

		if payloadType != 5 || len(payload) < len(videoSEIUUID) {
			continue
		}
		if !bytes.Equal(payload[:len(videoSEIUUID)], videoSEIUUID[:]) {
			continue
		}

		frame := make([]byte, len(payload)-len(videoSEIUUID))
		copy(frame, payload[len(videoSEIUUID):])
		out = append(out, frame)
	}

	return out, nil
}

func appendSEIValue(dst []byte, value int) []byte {
	for value >= 0xff {
		dst = append(dst, 0xff)
		value -= 0xff
	}
	return append(dst, byte(value)) //nolint:gosec // G115: bounded conversion verified by surrounding logic
}

func consumeSEIValue(data []byte, pos int) (int, int, error) {
	value := 0
	for {
		if pos >= len(data) {
			return 0, pos, ErrSEIValueTruncated
		}
		b := int(data[pos])
		pos++
		value += b
		if b != 0xff {
			return value, pos, nil
		}
	}
}

func appendStartCode(dst, nalu []byte) []byte {
	dst = append(dst, 0x00, 0x00, 0x00, 0x01)
	return append(dst, nalu...)
}

func escapeRBSP(rbsp []byte) []byte {
	out := make([]byte, 0, len(rbsp)+8)
	zeroCount := 0
	for _, b := range rbsp {
		if zeroCount >= 2 && b <= 0x03 {
			out = append(out, 0x03)
			zeroCount = 0
		}
		out = append(out, b)
		if b == 0x00 {
			zeroCount++
		} else {
			zeroCount = 0
		}
	}
	return out
}

func unescapeRBSP(rbsp []byte) []byte {
	out := make([]byte, 0, len(rbsp))
	for i, b := range rbsp {
		if i >= 2 && b == 0x03 && rbsp[i-1] == 0x00 && rbsp[i-2] == 0x00 {
			continue
		}
		out = append(out, b)
	}
	return out
}

func mustDecodeHex(value string) []byte {
	data, err := hex.DecodeString(value)
	if err != nil {
		panic(errors.Join(ErrInvalidH264Constant, err))
	}
	return data
}
