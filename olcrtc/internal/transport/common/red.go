package common

import "errors"

// RED (RFC 2198) unwrapping, shared by the video transports: a VK Calls SFU
// forwards camera video as video/red even when the publisher sent no
// redundancy, so a receiver must look through the wrapper for the codec the
// primary block names.
const (
	redFollowBit      = 0x80 // F: another block header follows this one
	redPayloadMask    = 0x7f // the block's payload type
	redBlockHeader    = 4    // bytes of a redundant block's header
	redLengthHighByte = 2    // header byte holding the length's top two bits
	redLengthLowByte  = 3    // header byte holding the length's low eight bits
	redLengthHighMask = 0x03
)

// ErrREDHeader is returned for a RED payload whose block headers do not fit it.
var ErrREDHeader = errors.New("common: malformed RED header")

// REDPrimary returns the payload type and data of a RED payload's primary
// block. Block headers come first — four bytes while the F bit is set, one
// byte for the primary — then every redundant block's data in order, then
// the primary's.
func REDPrimary(payload []byte) (uint8, []byte, error) {
	offset, skip := 0, 0
	for {
		if offset >= len(payload) {
			return 0, nil, ErrREDHeader
		}
		if payload[offset]&redFollowBit == 0 {
			offset++
			break
		}
		if offset+redBlockHeader > len(payload) {
			return 0, nil, ErrREDHeader
		}
		high := int(payload[offset+redLengthHighByte] & redLengthHighMask)
		skip += high<<8 | int(payload[offset+redLengthLowByte])
		offset += redBlockHeader
	}
	primaryType := payload[offset-1] & redPayloadMask
	start := offset + skip
	if start > len(payload) {
		return 0, nil, ErrREDHeader
	}
	return primaryType, payload[start:], nil
}
