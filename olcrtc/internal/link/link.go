// Package link reads and prints olcrtc:// links in the grammar the olcbox app
// imports (LocationsDatasource.parseOlcRtcUri):
//
//	olcrtc://PROVIDER?TRANSPORT[<k=v&...>]@ROOM#KEY[%DEVICE][$LABEL]
//
// The provider runs to the first '?', the transport token to the first '@'
// after it, the room to the first '#' after that and the key to the first '%'
// or '$' after that. The label runs from that first '$' to the end of the
// line; the device is what lies between a '%' before it and the label.
// docs/uri.md describes the convention; the device slot is the app's.
//
// ai-generated: the whole package (release gate).
package link

import (
	"encoding/hex"
	"errors"
	"fmt"
	"strconv"
	"strings"
)

// ErrMalformed reports a line that is not an olcrtc:// link the engine can
// use. Its messages never quote the line: a link carries a room and a key.
var ErrMalformed = errors.New("malformed olcrtc link")

const (
	prefix = "olcrtc://"
	bom    = "\uFEFF"

	// The app's LocationConfig defaults and the bounds its sanitizeVp8Fps
	// and sanitizeVp8Batch clamp into.
	defaultVP8FPS   = 60
	defaultVP8Batch = 64
	maxVP8FPS       = 120
	maxVP8Batch     = 64

	// keyHexLen is a 32-byte key in hex, the only key the engine takes.
	keyHexLen = 64
)

// Link is one parsed line. Provider, Transport and Room are kept as written,
// only trimmed: the app's name aliases and its per-provider transport
// fallback are app policy, not grammar. A Link holds the secrets of a run,
// the room and the key: never log one, nor its String.
type Link struct {
	Provider  string // auth.provider: telemost, jitsi, wbstream, ...
	Transport string // net.transport: vp8channel, datachannel, ...
	Room      string // room.id: an id or a URL, as the provider takes it
	Key       string // crypto.key: 64 hex chars
	Device    string // after '%': a client id, which the app skips
	Label     string // after '$': the name the app shows
	VP8FPS    int    // vp8-fps or fps, 1..120, default 60
	VP8Batch  int    // vp8-batch or batch, 1..64, default 64
}

// Parse reads one line the way the app imports it: surrounding space and a
// byte order mark are dropped and every field is trimmed. The transport
// token's options give VP8FPS and VP8Batch; the key must be 64 hex chars.
// A line break inside is refused: the app splits a body into lines before
// it parses, so what follows a break is another link, never more label,
// device or key of this one.
func Parse(line string) (Link, error) {
	text := strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(line), bom))
	payload, ok := strings.CutPrefix(text, prefix)
	if !ok {
		return Link{}, fmt.Errorf("%w: no olcrtc:// prefix", ErrMalformed)
	}
	if strings.ContainsAny(payload, "\r\n") {
		return Link{}, fmt.Errorf("%w: more than one line", ErrMalformed)
	}
	q := strings.IndexByte(payload, '?')
	if q < 0 {
		return Link{}, fmt.Errorf("%w: no transport", ErrMalformed)
	}
	at := indexAfter(payload, '@', q)
	if at < 0 {
		return Link{}, fmt.Errorf("%w: no room", ErrMalformed)
	}
	hash := indexAfter(payload, '#', at)
	if hash < 0 {
		return Link{}, fmt.Errorf("%w: no key", ErrMalformed)
	}
	key, device, label := splitTail(payload[hash+1:])
	transport, fps, batch := parseTransport(strings.TrimSpace(payload[q+1 : at]))
	l := Link{
		Provider:  strings.TrimSpace(payload[:q]),
		Transport: transport,
		Room:      strings.TrimSpace(payload[at+1 : hash]),
		Key:       key,
		Device:    device,
		Label:     label,
		VP8FPS:    fps,
		VP8Batch:  batch,
	}
	if err := l.validate(); err != nil {
		return Link{}, err
	}
	return l, nil
}

// String prints l in the same grammar, so Parse gives back any Link it
// returned: the options block only when fps or batch is off its default,
// the device and the label only when set. Fps and batch print as they are,
// so a Link built by hand sets both: a 0 would read back as 1. The result
// carries the room and the key.
func (l Link) String() string {
	s := prefix + l.Provider + "?" + l.Transport
	if l.VP8FPS != defaultVP8FPS || l.VP8Batch != defaultVP8Batch {
		s += "<vp8-fps=" + strconv.Itoa(l.VP8FPS) + "&vp8-batch=" + strconv.Itoa(l.VP8Batch) + ">"
	}
	s += "@" + l.Room + "#" + l.Key
	if l.Device != "" {
		s += "%" + l.Device
	}
	if l.Label != "" {
		s += "$" + l.Label
	}
	return s
}

// validate rejects an empty field and a key the engine would refuse. The hex
// error is dropped rather than wrapped: it quotes a byte of the key.
func (l Link) validate() error {
	switch {
	case l.Provider == "":
		return fmt.Errorf("%w: empty provider", ErrMalformed)
	case l.Transport == "":
		return fmt.Errorf("%w: empty transport", ErrMalformed)
	case l.Room == "":
		return fmt.Errorf("%w: empty room", ErrMalformed)
	}
	if _, err := hex.DecodeString(l.Key); err != nil || len(l.Key) != keyHexLen {
		return fmt.Errorf("%w: key is not %d hex chars", ErrMalformed, keyHexLen)
	}
	return nil
}

// indexAfter is the index of the first c in s after position i, or -1.
func indexAfter(s string, c byte, i int) int {
	j := strings.IndexByte(s[i+1:], c)
	if j < 0 {
		return -1
	}
	return i + 1 + j
}

// splitTail cuts what follows the '#' into the key, the device and the label.
// The label runs from the first '$' to the end, so a '%' after it belongs to
// the label; the device lies between a '%' before it and the label.
func splitTail(tail string) (string, string, string) {
	keyEnd, label := len(tail), ""
	if i := strings.IndexByte(tail, '$'); i >= 0 {
		keyEnd, label = i, strings.TrimSpace(tail[i+1:])
	}
	device := ""
	if i := strings.IndexByte(tail[:keyEnd], '%'); i >= 0 {
		device = strings.TrimSpace(tail[i+1 : keyEnd])
		keyEnd = i
	}
	return strings.TrimSpace(tail[:keyEnd]), device, label
}

// parseTransport splits a token such as vp8channel<vp8-fps=30> into the
// transport name and the vp8 fps and batch, as the app reads it: the options
// sit between the first '<' and the last '>', vp8-fps wins over fps and
// vp8-batch over batch, and the values are clamped into the app's bounds.
// A token without a closed options block is the name as written.
func parseTransport(token string) (string, int, int) {
	open, end := strings.IndexByte(token, '<'), strings.LastIndexByte(token, '>')
	if open < 0 || end <= open {
		return token, defaultVP8FPS, defaultVP8Batch
	}
	opts := parseOptions(token[open+1 : end])
	fps := firstOption(opts, defaultVP8FPS, "vp8-fps", "fps")
	batch := firstOption(opts, defaultVP8Batch, "vp8-batch", "batch")
	return strings.TrimSpace(token[:open]), min(max(fps, 1), maxVP8FPS), min(max(batch, 1), maxVP8Batch)
}

// parseOptions reads k=v&k=v with the keys lowercased. A part whose value is
// not an int32 (the app's toIntOrNull) is skipped, so is one without a '='.
func parseOptions(s string) map[string]int {
	opts := make(map[string]int)
	for part := range strings.SplitSeq(s, "&") {
		k, v, _ := strings.Cut(part, "=")
		n, err := strconv.ParseInt(strings.TrimSpace(v), 10, 32)
		if err != nil {
			continue
		}
		opts[strings.ToLower(strings.TrimSpace(k))] = int(n)
	}
	return opts
}

// firstOption is the value of the first of keys set in opts, else fallback.
func firstOption(opts map[string]int, fallback int, keys ...string) int {
	for _, k := range keys {
		if v, ok := opts[k]; ok {
			return v
		}
	}
	return fallback
}
