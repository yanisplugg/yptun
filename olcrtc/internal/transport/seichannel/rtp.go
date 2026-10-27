package seichannel

// ai-generated: the whole file (the SEI payloads of an RTP packet, read as
// the packet arrives).

import "github.com/pion/rtp"

// H264 RTP payload types this reader knows (RFC 6184).
const (
	nalTypeSEI   = 6
	nalTypeSTAPA = 24
	nalTypeFUA   = 28

	nalTypeMask  = 0x1f
	nalRefIDCMsk = 0xe0
	fuStartBit   = 0x80
	fuEndBit     = 0x40

	stapASizeLen = 2
	fuHeaderLen  = 2
)

// packetReader turns RTP packets into the transport frames their SEI
// messages carry.
//
// It replaces the sample builder the inbound path used to run. A sample
// builder holds a frame until the first packet of the next one arrives, a
// whole frame interval later on each side of a round trip, and holds
// everything behind a lost packet until 128 more have piled up - seconds on
// a stream that is mostly idle. Nothing here needs a whole access unit: one
// fragment rides one SEI NAL, and the frame above carries its own sequence
// number, so a payload is complete the moment its packet is.
type packetReader struct {
	// fu is the fragmented SEI NAL being reassembled, fuSeq the sequence
	// number of its last piece, and fuOK says the pieces so far are
	// contiguous.
	fu    []byte
	fuSeq uint16
	fuOK  bool
}

// payloads appends the transport payloads packet carries to out.
func (r *packetReader) payloads(packet *rtp.Packet, out [][]byte) [][]byte {
	body := packet.Payload
	if len(body) == 0 {
		return out
	}
	switch body[0] & nalTypeMask {
	case nalTypeSTAPA:
		return appendSTAPAPayloads(body, out)
	case nalTypeFUA:
		return r.appendFUAPayloads(packet, out)
	default:
		return appendNALPayloads(body, out)
	}
}

// appendNALPayloads appends the payloads of one whole NAL unit.
func appendNALPayloads(nal []byte, out [][]byte) [][]byte {
	if len(nal) < 2 || nal[0]&nalTypeMask != nalTypeSEI {
		return out
	}
	found, err := extractTransportSEI(nal[1:])
	if err != nil {
		return out
	}
	return append(out, found...)
}

// appendSTAPAPayloads walks the NAL units an aggregation packet carries.
func appendSTAPAPayloads(body []byte, out [][]byte) [][]byte {
	for pos := 1; pos+stapASizeLen <= len(body); {
		size := int(body[pos])<<8 | int(body[pos+1])
		pos += stapASizeLen
		if size == 0 || pos+size > len(body) {
			return out
		}
		out = appendNALPayloads(body[pos:pos+size], out)
		pos += size
	}
	return out
}

// appendFUAPayloads reassembles a NAL unit split across packets. A piece
// that does not follow the previous one drops the whole unit: the fragment
// it carries is retransmitted anyway, and half a NAL is not worth parsing.
func (r *packetReader) appendFUAPayloads(packet *rtp.Packet, out [][]byte) [][]byte {
	body := packet.Payload
	if len(body) <= fuHeaderLen {
		r.fuOK = false
		return out
	}
	header := body[1]
	naluType := header & nalTypeMask
	switch {
	case header&fuStartBit != 0:
		r.fu = append(r.fu[:0], (body[0]&nalRefIDCMsk)|naluType)
		r.fu = append(r.fu, body[fuHeaderLen:]...)
		r.fuOK = naluType == nalTypeSEI
	case r.fuOK && packet.SequenceNumber == r.fuSeq+1:
		r.fu = append(r.fu, body[fuHeaderLen:]...)
	default:
		r.fuOK = false
	}
	r.fuSeq = packet.SequenceNumber
	if !r.fuOK || header&fuEndBit == 0 {
		return out
	}
	r.fuOK = false
	return appendNALPayloads(r.fu, out)
}
