// Package udpwire defines the compact message format used by the lossy UDP
// data path. The payload carried here is still expected to be encrypted by the
// caller before it is handed to an SFU transport.
package udpwire

import (
	"encoding/binary"
	"errors"
	"fmt"
	"net/netip"
	"strings"
)

const (
	// Version is the current UDP wire protocol version.
	Version byte = 1
	// MaxPayloadSize keeps individual UDP frames below common WebRTC/QUIC MTUs.
	MaxPayloadSize = 1200
	// MaxHostLen is the maximum domain name length encodable in one frame.
	MaxHostLen = 255
)

const (
	headerLen = 16
	portLen   = 2
)

var magic = [2]byte{'O', 'U'} //nolint:gochecknoglobals // protocol marker

var (
	// ErrFrameTooShort is returned when a wire frame is shorter than the fixed header.
	ErrFrameTooShort = errors.New("udpwire: frame too short")
	// ErrBadMagic is returned when a frame does not carry the olcrtc UDP marker.
	ErrBadMagic = errors.New("udpwire: bad magic")
	// ErrUnsupportedVersion is returned when a peer sends an unknown wire version.
	ErrUnsupportedVersion = errors.New("udpwire: unsupported version")
	// ErrUnknownFrameType is returned for unknown frame types.
	ErrUnknownFrameType = errors.New("udpwire: unknown frame type")
	// ErrInvalidFlowID is returned when a frame uses the reserved zero flow ID.
	ErrInvalidFlowID = errors.New("udpwire: invalid flow id")
	// ErrInvalidEndpoint is returned when a packet frame has an unusable target endpoint.
	ErrInvalidEndpoint = errors.New("udpwire: invalid endpoint")
	// ErrPayloadTooLarge is returned when a payload exceeds MaxPayloadSize.
	ErrPayloadTooLarge = errors.New("udpwire: payload too large")
)

// FrameType identifies the semantic meaning of a frame.
type FrameType byte

const (
	// FrameTypePacket carries one UDP payload to or from an endpoint.
	FrameTypePacket FrameType = 1
	// FrameTypeClose tears down flow state for the given FlowID.
	FrameTypeClose FrameType = 2
)

const (
	addrNone   byte = 0
	addrIPv4   byte = 1
	addrDomain byte = 3
	addrIPv6   byte = 4
)

// Endpoint is a UDP target or source endpoint carried in a packet frame.
type Endpoint struct {
	Host string
	Port uint16
}

// Frame is one UDP wire-protocol message.
type Frame struct {
	Type     FrameType
	FlowID   uint64
	Endpoint Endpoint
	Payload  []byte
}

// Encode serializes f into a wire frame.
func Encode(f Frame) ([]byte, error) {
	if err := validateFrame(f); err != nil {
		return nil, err
	}
	addrType, addr, err := encodeHost(f.Endpoint.Host)
	if err != nil {
		return nil, err
	}
	if f.Type == FrameTypeClose {
		addrType = addrNone
		addr = nil
	}

	out := make([]byte, headerLen+len(addr)+len(f.Payload))
	copy(out[:len(magic)], magic[:])
	out[2] = Version
	out[3] = byte(f.Type)
	binary.BigEndian.PutUint64(out[4:12], f.FlowID)
	out[12] = addrType
	binary.BigEndian.PutUint16(out[13:15], f.Endpoint.Port)
	out[15] = byte(len(addr)) //nolint:gosec // G115: address length is capped at 255 by encodeHost.
	copy(out[headerLen:], addr)
	copy(out[headerLen+len(addr):], f.Payload)
	return out, nil
}

// Decode parses one wire frame.
//
// The returned Payload aliases data. Callers that need to retain a decoded
// packet after reusing the input buffer must copy Payload themselves.
func Decode(data []byte) (Frame, error) {
	if len(data) < headerLen {
		return Frame{}, ErrFrameTooShort
	}
	if data[0] != magic[0] || data[1] != magic[1] {
		return Frame{}, ErrBadMagic
	}
	if data[2] != Version {
		return Frame{}, fmt.Errorf("%w: %d", ErrUnsupportedVersion, data[2])
	}

	f := Frame{
		Type:   FrameType(data[3]),
		FlowID: binary.BigEndian.Uint64(data[4:12]),
	}
	if f.Type != FrameTypePacket && f.Type != FrameTypeClose {
		return Frame{}, fmt.Errorf("%w: %d", ErrUnknownFrameType, data[3])
	}
	if f.FlowID == 0 {
		return Frame{}, ErrInvalidFlowID
	}

	addrType := data[12]
	f.Endpoint.Port = binary.BigEndian.Uint16(data[13:15])
	addrLen := int(data[15])
	if len(data) < headerLen+addrLen {
		return Frame{}, ErrFrameTooShort
	}
	host, err := decodeHost(addrType, data[headerLen:headerLen+addrLen])
	if err != nil {
		return Frame{}, err
	}
	f.Endpoint.Host = host
	f.Payload = data[headerLen+addrLen:]
	if len(f.Payload) > MaxPayloadSize {
		return Frame{}, ErrPayloadTooLarge
	}
	if err := validateFrame(f); err != nil {
		return Frame{}, err
	}
	return f, nil
}

func validateFrame(f Frame) error {
	if f.Type != FrameTypePacket && f.Type != FrameTypeClose {
		return fmt.Errorf("%w: %d", ErrUnknownFrameType, f.Type)
	}
	if f.FlowID == 0 {
		return ErrInvalidFlowID
	}
	if len(f.Payload) > MaxPayloadSize {
		return ErrPayloadTooLarge
	}
	if f.Type == FrameTypeClose {
		if f.Endpoint.Host != "" || f.Endpoint.Port != 0 || len(f.Payload) != 0 {
			return ErrInvalidEndpoint
		}
		return nil
	}
	if f.Endpoint.Port == 0 || strings.TrimSpace(f.Endpoint.Host) == "" {
		return ErrInvalidEndpoint
	}
	return nil
}

func encodeHost(host string) (byte, []byte, error) {
	if host == "" {
		return addrNone, nil, nil
	}
	addr, err := netip.ParseAddr(host)
	if err == nil {
		if addr.Is4() {
			a4 := addr.As4()
			return addrIPv4, a4[:], nil
		}
		a16 := addr.As16()
		return addrIPv6, a16[:], nil
	}
	if len(host) > MaxHostLen {
		return 0, nil, ErrInvalidEndpoint
	}
	for _, r := range host {
		if r <= 0x20 || r > 0x7e {
			return 0, nil, ErrInvalidEndpoint
		}
	}
	return addrDomain, []byte(host), nil
}

func decodeHost(addrType byte, raw []byte) (string, error) {
	switch addrType {
	case addrNone:
		if len(raw) != 0 {
			return "", ErrInvalidEndpoint
		}
		return "", nil
	case addrIPv4:
		if len(raw) != 4 {
			return "", ErrInvalidEndpoint
		}
		addr := netip.AddrFrom4([4]byte(raw))
		return addr.String(), nil
	case addrIPv6:
		if len(raw) != 16 {
			return "", ErrInvalidEndpoint
		}
		addr := netip.AddrFrom16([16]byte(raw))
		return addr.String(), nil
	case addrDomain:
		host := string(raw)
		if host == "" || len(host) > MaxHostLen {
			return "", ErrInvalidEndpoint
		}
		for _, r := range host {
			if r <= 0x20 || r > 0x7e {
				return "", ErrInvalidEndpoint
			}
		}
		return host, nil
	default:
		return "", fmt.Errorf("%w: addr_type=%d", ErrInvalidEndpoint, addrType)
	}
}
