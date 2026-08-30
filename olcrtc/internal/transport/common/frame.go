package common

import (
	"encoding/binary"
	"errors"
	"hash/crc32"
)

// Wire format of the ack-based video transports (seichannel, videochannel).
//
// Both transports used to ship their own near-identical codec - seichannel
// magic "OVC1"/version 1 with a 22-byte data header and no role or binding
// fields, videochannel magic "OVV2"/version 3 with a 29-byte data header. The
// field set and its serialisation were otherwise the same, so they are one
// format here, at a new magic and version: an old frame of either flavour
// fails the magic check and is rejected instead of being misparsed against
// the new offsets.
//
//	[0:4]   magic
//	[4]     version
//	[5]     type (data / ack / hello)
//	[6]     sender role (any / server / client)
//	[7:11]  binding token - session isolation inside a shared room
//	[11:15] seq  (hello stops here)
//	[15:19] crc32 of the whole message
//
//	ack:  [19:21] fragIdx
//	data: [19:23] totalLen, [23:25] fragIdx, [25:27] fragTotal,
//	      [27:31] crc32 of this fragment, [31:] payload
//
// fragIdx is present on acks so a receiver acknowledges each fragment of a
// multi-fragment message independently and the sender retransmits only what
// was actually lost.
//
// The per-fragment crc32 exists because acks are per fragment while the
// message crc can only be checked once every fragment has arrived. Without
// it, a fragment corrupted past ECC recovery is acknowledged on arrival, the
// sender counts the message as delivered, and the receiver silently drops it
// when the message crc finally fails - the fragment the sender would have to
// resend was already acked. Validating each fragment on arrival keeps the ack
// honest: a damaged fragment is never acknowledged and is simply retransmitted.
const (
	// FrameMagic is the shared magic ("OLVC").
	FrameMagic uint32 = 0x4f4c5643
	// FrameVersion is the current wire version.
	FrameVersion byte = 5

	// FrameTypeData carries one fragment of a message.
	FrameTypeData byte = 1
	// FrameTypeAck acknowledges one fragment.
	FrameTypeAck byte = 2
	// FrameTypeHello announces presence; it carries no message payload.
	FrameTypeHello byte = 3

	// RoleAny matches any receiver role.
	RoleAny byte = 0
	// RoleServer marks a frame sent by the server side.
	RoleServer byte = 1
	// RoleClient marks a frame sent by the client side.
	RoleClient byte = 2
)

// Frame header offsets.
const (
	frameRoleOff     = 6
	frameBindingOff  = 7
	frameSeqOff      = 11
	frameCRCOff      = 15
	frameHelloLen    = frameSeqOff
	frameAckFragOff  = 19
	frameAckLen      = 21
	frameTotalLenOff = 19
	frameFragIdxOff  = 23
	frameFragTotOff  = 25
	frameFragCRCOff  = 27
	frameDataHdrLen  = 31
)

var (
	// ErrFrameTooShort is returned when the received frame is too short to decode.
	ErrFrameTooShort = errors.New("frame too short")
	// ErrUnexpectedMagic is returned when the frame magic bytes do not match.
	ErrUnexpectedMagic = errors.New("unexpected frame magic")
	// ErrUnexpectedVersion is returned when the frame protocol version does not match.
	ErrUnexpectedVersion = errors.New("unexpected frame version")
	// ErrAckTooShort is returned when the ack frame is shorter than expected.
	ErrAckTooShort = errors.New("ack frame too short")
	// ErrDataTooShort is returned when the data frame is shorter than expected.
	ErrDataTooShort = errors.New("data frame too short")
	// ErrHelloTooShort is returned when the hello frame is shorter than expected.
	ErrHelloTooShort = errors.New("hello frame too short")
	// ErrUnexpectedFrameType is returned for unknown frame type bytes.
	ErrUnexpectedFrameType = errors.New("unexpected frame type")
)

// LocalRole returns the role this side stamps into its frames. The server
// runs without a device ID; the client always has one.
func LocalRole(deviceID string) byte {
	if deviceID == "" {
		return RoleServer
	}
	return RoleClient
}

// RemoteRole returns the role this side expects to receive frames from.
func RemoteRole(deviceID string) byte {
	if deviceID == "" {
		return RoleClient
	}
	return RoleServer
}

// Frame is one decoded transport frame.
type Frame struct {
	Type      byte
	Role      byte
	Binding   uint32
	Seq       uint32
	CRC       uint32
	TotalLen  uint32
	FragIdx   uint16
	FragTotal uint16
	// FragCRC is the crc32 of Payload alone, checked before the fragment is
	// stored or acknowledged.
	FragCRC uint32
	Payload []byte
}

// AcceptedBy reports whether a receiver expecting remoteRole and holding
// binding should process this frame. A frame carrying no binding (zero) is
// accepted so a peer that has none configured still gets through.
func (f Frame) AcceptedBy(remoteRole byte, binding uint32) bool {
	roleOK := f.Role == RoleAny || f.Role == remoteRole
	bindingOK := f.Binding == 0 || f.Binding == binding
	return roleOK && bindingOK
}

// putFrameHeader writes the fixed prefix shared by every frame type.
func putFrameHeader(out []byte, typ, role byte, binding uint32) {
	binary.BigEndian.PutUint32(out[0:4], FrameMagic)
	out[4] = FrameVersion
	out[5] = typ
	out[frameRoleOff] = role
	binary.BigEndian.PutUint32(out[frameBindingOff:frameSeqOff], binding)
}

// EncodeData serialises one fragment of a message.
func EncodeData(
	role byte,
	binding, seq, crc uint32,
	totalLen, fragIdx, fragTotal int,
	payload []byte,
) []byte {
	out := make([]byte, frameDataHdrLen+len(payload))
	putFrameHeader(out, FrameTypeData, role, binding)
	binary.BigEndian.PutUint32(out[frameSeqOff:frameCRCOff], seq)
	binary.BigEndian.PutUint32(out[frameCRCOff:frameTotalLenOff], crc)
	binary.BigEndian.PutUint32(out[frameTotalLenOff:frameFragIdxOff], uint32(totalLen)) //nolint:gosec,lll // G115: bounded conversion verified by surrounding logic
	binary.BigEndian.PutUint16(out[frameFragIdxOff:frameFragTotOff], uint16(fragIdx))   //nolint:gosec,lll // G115: bounded conversion verified by surrounding logic
	binary.BigEndian.PutUint16(out[frameFragTotOff:frameFragCRCOff], uint16(fragTotal)) //nolint:gosec,lll // G115: bounded conversion verified by surrounding logic
	binary.BigEndian.PutUint32(out[frameFragCRCOff:frameDataHdrLen], crc32.ChecksumIEEE(payload))
	copy(out[frameDataHdrLen:], payload)
	return out
}

// EncodeAck serialises the acknowledgement of a single fragment.
func EncodeAck(role byte, binding, seq, crc uint32, fragIdx uint16) []byte {
	out := make([]byte, frameAckLen)
	putFrameHeader(out, FrameTypeAck, role, binding)
	binary.BigEndian.PutUint32(out[frameSeqOff:frameCRCOff], seq)
	binary.BigEndian.PutUint32(out[frameCRCOff:frameAckFragOff], crc)
	binary.BigEndian.PutUint16(out[frameAckFragOff:frameAckLen], fragIdx)
	return out
}

// EncodeHello serialises the presence beacon transports emit while idle.
func EncodeHello(role byte, binding uint32) []byte {
	out := make([]byte, frameHelloLen)
	putFrameHeader(out, FrameTypeHello, role, binding)
	return out
}

// DecodeFrame parses one frame. Data payloads alias data and must be consumed
// before the input buffer is reused. Frames from an older wire version fail
// the magic or version check and are reported as such rather than decoded
// against mismatched offsets.
func DecodeFrame(data []byte) (Frame, error) {
	if err := validateFrameHeader(data); err != nil {
		return Frame{}, err
	}

	frame := Frame{Type: data[5]}
	if len(data) < frameHelloLen {
		return Frame{}, shortFrameError(frame.Type)
	}
	frame.Role = data[frameRoleOff]
	frame.Binding = binary.BigEndian.Uint32(data[frameBindingOff:frameSeqOff])

	switch frame.Type {
	case FrameTypeHello:
		return frame, nil
	case FrameTypeAck:
		return decodeAckBody(frame, data)
	case FrameTypeData:
		return decodeDataBody(frame, data)
	default:
		return Frame{}, ErrUnexpectedFrameType
	}
}

func validateFrameHeader(data []byte) error {
	if len(data) < 6 {
		return ErrFrameTooShort
	}
	if binary.BigEndian.Uint32(data[0:4]) != FrameMagic {
		return ErrUnexpectedMagic
	}
	if data[4] != FrameVersion {
		return ErrUnexpectedVersion
	}
	return nil
}

func shortFrameError(typ byte) error {
	switch typ {
	case FrameTypeAck:
		return ErrAckTooShort
	case FrameTypeData:
		return ErrDataTooShort
	case FrameTypeHello:
		return ErrHelloTooShort
	default:
		return ErrUnexpectedFrameType
	}
}

func decodeAckBody(frame Frame, data []byte) (Frame, error) {
	if len(data) < frameAckLen {
		return Frame{}, ErrAckTooShort
	}
	frame.Seq = binary.BigEndian.Uint32(data[frameSeqOff:frameCRCOff])
	frame.CRC = binary.BigEndian.Uint32(data[frameCRCOff:frameAckFragOff])
	frame.FragIdx = binary.BigEndian.Uint16(data[frameAckFragOff:frameAckLen])
	return frame, nil
}

func decodeDataBody(frame Frame, data []byte) (Frame, error) {
	if len(data) < frameDataHdrLen {
		return Frame{}, ErrDataTooShort
	}
	frame.Seq = binary.BigEndian.Uint32(data[frameSeqOff:frameCRCOff])
	frame.CRC = binary.BigEndian.Uint32(data[frameCRCOff:frameTotalLenOff])
	frame.TotalLen = binary.BigEndian.Uint32(data[frameTotalLenOff:frameFragIdxOff])
	frame.FragIdx = binary.BigEndian.Uint16(data[frameFragIdxOff:frameFragTotOff])
	frame.FragTotal = binary.BigEndian.Uint16(data[frameFragTotOff:frameFragCRCOff])
	frame.FragCRC = binary.BigEndian.Uint32(data[frameFragCRCOff:frameDataHdrLen])
	frame.Payload = data[frameDataHdrLen:]
	return frame, nil
}
