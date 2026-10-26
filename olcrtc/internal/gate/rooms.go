package gate

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"os"
	"slices"
	"strings"
	"time"

	"gopkg.in/yaml.v3"

	"github.com/openlibrecommunity/olcrtc/internal/auth"
	authSaluteJazz "github.com/openlibrecommunity/olcrtc/internal/auth/salutejazz"
)

// ai-generated: the whole file (rooms for the local target: a fresh Jitsi
// room on a host that answers, a fresh SaluteJazz room, a room of a pre-made
// pool in the form its provider joins by, fresh keys and channel ids).

var (
	// ErrNoJitsiHost is a run with no Jitsi host to put a room on: none
	// listed, or none that answered.
	ErrNoJitsiHost = errors.New("no reachable jitsi host")
	// ErrPoolRoom is a room pool with no room to take: empty, or the entry
	// the run lands on is blank or names no room.
	ErrPoolRoom = errors.New("no room in the pool")
)

const (
	// gateNameBytes is the random part of a room or channel name: 12 hex.
	gateNameBytes = 6
	// keyBytes is a session key: 32 bytes, 64 hex.
	keyBytes = 32
	// probeTimeout bounds the one request that tells a Jitsi host is up.
	probeTimeout = 5 * time.Second
	// telemostJoinPrefix is what the telemost provider glues a bare id onto.
	telemostJoinPrefix = "https://telemost.yandex.ru/j/"
)

// NewKey returns a fresh session key, 64 lowercase hex.
func NewKey() (string, error) {
	var raw [keyBytes]byte
	if _, err := rand.Read(raw[:]); err != nil {
		return "", fmt.Errorf("random key: %w", err)
	}
	return hex.EncodeToString(raw[:]), nil
}

// NewChannel returns a fresh channel id, gate-<12 hex>. Two runs sharing a
// pool room frame under different tokens when their channels differ, so a
// run never reads another's frames (amendment A4).
func NewChannel() (string, error) {
	return gateName()
}

// gateName is gate-<12 random hex>, the name of a Jitsi room or a channel.
func gateName() (string, error) {
	var raw [gateNameBytes]byte
	if _, err := rand.Read(raw[:]); err != nil {
		return "", fmt.Errorf("random name: %w", err)
	}
	return "gate-" + hex.EncodeToString(raw[:]), nil
}

// JitsiHosts is where Jitsi rooms may go: the override when it names any
// host (GATE_JITSI_HOSTS), else the instance list at instancesPath
// (docs/jitsi.instances.yaml), which is then never read. Entries may be
// bare hosts or URLs; blank ones are dropped.
func JitsiHosts(instancesPath string, override []string) ([]string, error) {
	if hosts := jitsiHostList(override); len(hosts) > 0 {
		return hosts, nil
	}
	raw, err := os.ReadFile(instancesPath)
	if err != nil {
		return nil, fmt.Errorf("read jitsi instances: %w", err)
	}
	var doc struct {
		Instances []string `yaml:"instances"`
	}
	if err := yaml.Unmarshal(raw, &doc); err != nil {
		return nil, fmt.Errorf("parse jitsi instances: %w", err)
	}
	hosts := jitsiHostList(doc.Instances)
	if len(hosts) == 0 {
		return nil, fmt.Errorf("%w: the instance list names none", ErrNoJitsiHost)
	}
	return hosts, nil
}

// jitsiHostList is the host of every entry that names one.
func jitsiHostList(entries []string) []string {
	hosts := make([]string, 0, len(entries))
	for _, e := range entries {
		rest, _ := cutScheme(strings.TrimSpace(e))
		host, _, _ := strings.Cut(rest, "/")
		if host = strings.TrimSpace(host); host != "" {
			hosts = append(hosts, host)
		}
	}
	return hosts
}

// hostForms is every form an override host takes in what a run writes: as
// given, which the room URL carries; without its port, which a resolver's
// error, the Jitsi config the server fetches and an XMPP JID carry; and each
// lowercased, as a JID writes a domain. jitsiHostList keeps the port: the
// room URL needs it.
func hostForms(hosts []string) []string {
	// ai-generated: an override host withheld with and without its port.
	out := make([]string, 0, 4*len(hosts))
	for _, h := range hosts {
		bare := h
		if host, _, err := net.SplitHostPort(h); err == nil {
			bare = host
		}
		for _, f := range []string{h, strings.ToLower(h), bare, strings.ToLower(bare)} {
			if f != "" && !slices.Contains(out, f) {
				out = append(out, f)
			}
		}
	}
	return out
}

// JitsiRoom names a fresh room, https://<host>/gate-<12 hex>, on the first
// host probe finds up; Jitsi conjures a room on its first join. The error
// counts the hosts but never names them: an override is a secret.
func JitsiRoom(ctx context.Context, hosts []string, probe func(ctx context.Context, host string) bool) (string, error) {
	name, err := gateName()
	if err != nil {
		return "", err
	}
	for _, host := range hosts {
		if probe(ctx, host) {
			return "https://" + host + "/" + name, nil
		}
	}
	if err := ctx.Err(); err != nil {
		return "", fmt.Errorf("jitsi room: %w", err)
	}
	return "", fmt.Errorf("%w: %d probed", ErrNoJitsiHost, len(hosts))
}

// ProbeHTTPS is the default probe: one GET of the host's front page within
// 5 s, up unless it fails or answers 5xx.
func ProbeHTTPS(ctx context.Context, host string) bool {
	probeCtx, cancel := context.WithTimeout(ctx, probeTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(probeCtx, http.MethodGet, "https://"+host+"/", http.NoBody)
	if err != nil {
		return false
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return false
	}
	_ = resp.Body.Close()
	return resp.StatusCode < http.StatusInternalServerError
}

// SaluteJazzRoom makes a fresh SaluteJazz room with the provider's own
// anonymous create call, the one the engine's auth provider makes: no
// account, no token, and nothing to give back, since Sber has no delete. The
// room is "<code>:<password>", the pair's alone, as a Jitsi room is. The
// provider's HTTP client bounds the call.
func SaluteJazzRoom(ctx context.Context) (string, error) {
	room, err := authSaluteJazz.New().CreateRoom(ctx, auth.Config{})
	if err != nil {
		return "", fmt.Errorf("create room: %w", err)
	}
	return room, nil
}

// maxKeys splits the two comma-separated calls session keys, trimmed;
// anything but two is left empty so the pairs fail naming EnvMaxKeys
// instead of half-joining under one identity.
func maxKeys(v string) []string {
	parts := strings.Split(v, ",")
	if len(parts) != 2 {
		return nil
	}
	for i := range parts {
		parts[i] = strings.TrimSpace(parts[i])
		if parts[i] == "" {
			return nil
		}
	}
	return parts
}

// PoolRoom takes the entry of a pre-made pool the run number lands on, so
// runs in sequence take turns. Runs at the same time can still land in one
// room (a pool of one always puts them there): the workflow's concurrency
// group runs one gate per ref, and a push to a branch with an open pull
// request starts two. The per-pair channel id keeps their frames apart. The
// entry is returned trimmed, as written: TelemostURL and WBStreamRoomID give
// it its provider's form.
func PoolRoom(pool []string, runNumber int) (string, error) {
	if len(pool) == 0 {
		return "", fmt.Errorf("%w: the pool is empty", ErrPoolRoom)
	}
	i := runNumber % len(pool)
	if i < 0 {
		i += len(pool)
	}
	room := strings.TrimSpace(pool[i])
	if room == "" {
		return "", fmt.Errorf("%w: entry %d is blank", ErrPoolRoom, i)
	}
	return room, nil
}

// TelemostURL is the form the telemost provider joins by. It takes an
// https URL as it is and glues anything else onto its join prefix, so a
// bare id becomes https://telemost.yandex.ru/j/<id>, while an http URL or a
// URL without its scheme is given https instead of being glued on.
func TelemostURL(idOrURL string) string {
	s := strings.TrimSpace(idOrURL)
	rest, scheme := cutScheme(s)
	switch {
	case s == "":
		return ""
	case scheme || strings.Contains(s, "/"):
		return "https://" + rest
	default:
		return telemostJoinPrefix + s
	}
}

// WBStreamRoomID is the form the wbstream provider joins by: the bare room
// id. The provider path-escapes what it gets into one segment, so a room URL
// (https://stream.wb.ru/room/<id>) would join a room that does not exist;
// its last path segment is the id. A bare id passes unchanged, and a URL
// without a room in its path gives "".
func WBStreamRoomID(idOrURL string) string {
	s := strings.TrimSpace(idOrURL)
	rest, scheme := cutScheme(s)
	if !scheme && !strings.Contains(s, "/") {
		return s
	}
	if i := strings.IndexAny(rest, "?#"); i >= 0 {
		rest = rest[:i]
	}
	_, roomPath, _ := strings.Cut(rest, "/")
	id := lastSegment(roomPath)
	if unescaped, err := url.PathUnescape(id); err == nil {
		id = unescaped
	}
	return id
}

// cutScheme drops a leading http:// or https://, in any case, and says
// whether there was one.
func cutScheme(s string) (string, bool) {
	for _, scheme := range []string{"https://", "http://"} {
		if len(s) >= len(scheme) && strings.EqualFold(s[:len(scheme)], scheme) {
			return s[len(scheme):], true
		}
	}
	return s, false
}

// lastSegment is what follows the last slash of s, trailing slashes aside:
// the room name of a room URL, s itself when it has no slash.
func lastSegment(s string) string {
	s = strings.TrimRight(s, "/")
	return s[strings.LastIndexByte(s, '/')+1:]
}

// mtsLinkOwner is the account an MTS Link room belongs to: the room is
// ".../j/<owner>/<event>", its last segment the event and the one before it
// the owner. It is empty for another provider's room.
func mtsLinkOwner(provider, room string) string {
	if provider != providerMTSLink {
		return ""
	}
	room, _, _ = strings.Cut(room, "#")
	room, _, _ = strings.Cut(room, "?")
	room = strings.TrimRight(room, "/")
	return lastSegment(strings.TrimSuffix(room, "/"+lastSegment(room)))
}

// Secrets lists what of the endpoint must never leave a run: the room as
// given; its last path segment, as it is and cut at a query or a fragment,
// where each differs (the part the engine logs on its own, e.g. "joining
// MUC host/<name>", and the id the telemost provider leaves bare when it
// escapes the whole room URL into its API path); each half of a SaluteJazz
// room, the code and the password, which a request or a log may carry
// alone; the key and the channel id. Empty ones are left out. Feed them to
// Scrub.
func (e Endpoint) Secrets() []string {
	out := make([]string, 0, 5)
	if e.Room != "" {
		out = append(out, e.Room)
		// ai-generated: the id cut at a query or fragment, as the CI's mask cuts it.
		path, _, _ := strings.Cut(e.Room, "#")
		path, _, _ = strings.Cut(path, "?")
		for _, name := range []string{lastSegment(e.Room), lastSegment(path)} {
			if name != "" && !slices.Contains(out, name) {
				out = append(out, name)
			}
		}
		// ai-generated: each half of a SaluteJazz "<code>:<password>" too.
		if code, password, ok := strings.Cut(e.Room, ":"); ok && e.Provider == providerSaluteJazz {
			for _, half := range []string{code, password} {
				if half != "" && !slices.Contains(out, half) {
					out = append(out, half)
				}
			}
		}
	}
	if owner := mtsLinkOwner(e.Provider, e.Room); owner != "" && !slices.Contains(out, owner) {
		out = append(out, owner)
	}
	for _, s := range []string{e.Key, e.Channel} {
		if s != "" {
			out = append(out, s)
		}
	}
	return out
}
