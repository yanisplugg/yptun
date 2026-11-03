package gate

import (
	"encoding/binary"
	"strconv"
	"strings"
)

// ai-generated: the whole file (one DNS question for the resolver burst).

const (
	// dnsHeaderLen is the fixed header of a DNS message.
	dnsHeaderLen = 12
	// dnsMaxLabel is the longest label; its length byte must stay under 64.
	dnsMaxLabel = 63
	// dnsMaxName is the longest name as text, 255 bytes on the wire.
	dnsMaxName = 253
)

// BuildDNSQuery encodes a standard query with recursion desired and one
// question: name (a trailing dot is optional), type A, class IN. It panics on
// a name DNS cannot carry (an empty label, a label over 63 bytes, a name over
// 253 bytes): names come from the caller's code, and a malformed question
// would only show up as a resolver that never answered.
func BuildDNSQuery(id uint16, name string) []byte {
	host := strings.TrimSuffix(name, ".")
	if len(host) > dnsMaxName {
		panic("gate: " + strconv.Quote(name) + " is too long for DNS")
	}
	q := make([]byte, dnsHeaderLen, dnsHeaderLen+len(host)+6)
	binary.BigEndian.PutUint16(q, id)
	q[2] = 0x01 // RD
	q[5] = 1    // QDCOUNT
	for label := range strings.SplitSeq(host, ".") {
		n := len(label)
		if n == 0 || n > dnsMaxLabel {
			panic("gate: " + strconv.Quote(name) + " has a label DNS cannot carry")
		}
		q = append(q, byte(n))
		q = append(q, label...)
	}
	return append(q, 0, 0, 1, 0, 1) // the root, QTYPE A, QCLASS IN
}

// DNSResponseID returns the id of a DNS message, false when pkt is shorter
// than a DNS header.
func DNSResponseID(pkt []byte) (uint16, bool) {
	if len(pkt) < dnsHeaderLen {
		return 0, false
	}
	return binary.BigEndian.Uint16(pkt), true
}
