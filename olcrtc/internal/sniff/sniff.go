// Package sniff reads a server name off the first bytes of a connection: the
// server_name extension of a TLS ClientHello, or the Host header of an HTTP/1
// request. It exists for a SOCKS client that names its target by address - a
// tun2socks in front of the engine - while the rules that decide where a
// connection goes are written in names.
//
// ai-generated: the whole package (olcbox#28).
package sniff

import (
	"bytes"
	"strings"
)

// MaxHead is the most a caller should read before giving up on a name: the
// largest TLS record, plus its header.
const MaxHead = 16*1024 + tlsRecordHeader

const (
	tlsRecordHeader = 5
	tlsHandshake    = 0x16
	tlsVersionMajor = 3
	tlsClientHello  = 0x01
	tlsMaxRecord    = 16 * 1024
	tlsRandomLen    = 32
	extServerName   = 0
	sniHostName     = 0
	headersEnd      = "\r\n\r\n"
	headerHost      = "host"
)

// httpMethods are the request lines this recognises, each with the space
// that ends the method.
//
//nolint:gochecknoglobals // a constant table
var httpMethods = [][]byte{
	[]byte("GET "), []byte("POST "), []byte("PUT "), []byte("HEAD "), []byte("DELETE "),
	[]byte("OPTIONS "), []byte("PATCH "), []byte("CONNECT "), []byte("TRACE "),
}

// Host returns the server name found in head, lowercased and without a port,
// or "" when there is none. need reports that the answer is still open - a
// TLS record longer than head, an HTTP request whose headers have not ended -
// and the caller should read more, up to MaxHead.
func Host(head []byte) (string, bool) {
	if len(head) == 0 {
		return "", true
	}
	if head[0] == tlsHandshake {
		return tlsServerName(head)
	}
	if partial, complete := httpMethod(head); complete || partial {
		return httpHost(head, complete)
	}
	return "", false
}

// tlsServerName parses one handshake record holding one ClientHello.
func tlsServerName(head []byte) (string, bool) {
	if len(head) < tlsRecordHeader {
		return "", true
	}
	if head[1] != tlsVersionMajor {
		return "", false
	}
	record := int(head[3])<<8 | int(head[4])
	if record == 0 || record > tlsMaxRecord {
		return "", false
	}
	if len(head) < tlsRecordHeader+record {
		return "", true
	}
	body := head[tlsRecordHeader : tlsRecordHeader+record]
	if len(body) < 4 || body[0] != tlsClientHello {
		return "", false
	}
	hello := int(body[1])<<16 | int(body[2])<<8 | int(body[3])
	if hello > len(body)-4 {
		// A hello spanning records: rare, and not worth buffering for.
		return "", false
	}
	return clientHelloServerName(body[4 : 4+hello]), false
}

// clientHelloServerName walks a ClientHello body to its extensions.
func clientHelloServerName(hello []byte) string {
	c := cursor{b: hello}
	c.skip(2 + tlsRandomLen) // version, random
	c.skip(c.u8())           // session id
	c.skip(c.u16())          // cipher suites
	c.skip(c.u8())           // compression methods
	exts := cursor{b: c.take(c.u16())}
	for !exts.done() {
		typ := exts.u16()
		data := exts.take(exts.u16())
		if exts.bad {
			return ""
		}
		if typ == extServerName {
			return serverNameList(data)
		}
	}
	return ""
}

func serverNameList(data []byte) string {
	list := cursor{b: data}
	names := cursor{b: list.take(list.u16())}
	for !names.done() {
		typ := names.u8()
		name := names.take(names.u16())
		if names.bad {
			return ""
		}
		if typ == sniHostName && len(name) > 0 {
			return strings.ToLower(string(name))
		}
	}
	return ""
}

// cursor reads big-endian fields and remembers the first short read, so a
// parser can chain reads and check once.
type cursor struct {
	b   []byte
	off int
	bad bool
}

func (c *cursor) take(n int) []byte {
	if c.bad || n < 0 || c.off+n > len(c.b) {
		c.bad = true
		return nil
	}
	v := c.b[c.off : c.off+n]
	c.off += n
	return v
}

func (c *cursor) skip(n int) { _ = c.take(n) }

func (c *cursor) u8() int {
	v := c.take(1)
	if v == nil {
		return 0
	}
	return int(v[0])
}

func (c *cursor) u16() int {
	v := c.take(2)
	if v == nil {
		return 0
	}
	return int(v[0])<<8 | int(v[1])
}

func (c *cursor) done() bool { return c.bad || c.off >= len(c.b) }

// httpMethod reports whether head could still start with a request line
// (partial: fewer bytes than a method needs) and whether it does (complete).
func httpMethod(head []byte) (bool, bool) {
	partial := false
	for _, m := range httpMethods {
		if bytes.HasPrefix(head, m) {
			return false, true
		}
		if len(head) < len(m) && bytes.HasPrefix(m, head) {
			partial = true
		}
	}
	return partial, false
}

// httpHost finds the Host header once the request's headers have ended.
func httpHost(head []byte, complete bool) (string, bool) {
	if !complete {
		return "", true
	}
	end := bytes.Index(head, []byte(headersEnd))
	if end < 0 {
		return "", len(head) < MaxHead
	}
	headers := head[:end]
	for line := range bytes.SplitSeq(headers, []byte("\r\n")) {
		name, value, ok := bytes.Cut(line, []byte(":"))
		if !ok || !bytes.EqualFold(bytes.TrimSpace(name), []byte(headerHost)) {
			continue
		}
		return hostWithoutPort(strings.TrimSpace(string(value))), false
	}
	return "", false
}

// hostWithoutPort strips :port from host, [v6]:port and [v6] included.
func hostWithoutPort(host string) string {
	if strings.HasPrefix(host, "[") {
		if end := strings.IndexByte(host, ']'); end > 0 {
			return strings.ToLower(host[1:end])
		}
		return ""
	}
	if strings.Count(host, ":") == 1 {
		host = host[:strings.IndexByte(host, ':')]
	}
	return strings.ToLower(host)
}
