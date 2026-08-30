package uri

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/url"
	"strings"
)

const scheme = "freeturn://"

const currentVersion = 1

// Config представляет параметры подключения из share-ссылки freeturn://.
type Config struct {
	Version        int
	Provider       string
	Peer           string
	Transport      string
	Mode           string
	ObfProfile     string
	ObfKey         string
	N              int
	StreamsPerCred int
	ClientID       string
	Listen         string
	DNSMode        string
	DNSServers     string
	ManualCaptcha  bool
	KCP            *KCP
	Comment        string
}

// wire - JSON-схема freeturn:// ссылок.
type wire struct {
	V              int    `json:"v"`
	Provider       string `json:"provider"`
	Peer           string `json:"peer"`
	Transport      string `json:"transport,omitempty"`
	Mode           string `json:"mode,omitempty"`
	Obf            string `json:"obf,omitempty"`
	Key            string `json:"key,omitempty"`
	N              int    `json:"n,omitempty"`
	StreamsPerCred int    `json:"spc,omitempty"`
	ClientID       string `json:"cid,omitempty"`
	Listen         string `json:"listen,omitempty"`
	DNSMode        string `json:"dns,omitempty"`
	DNSServers     string `json:"dnss,omitempty"`
	ManualCaptcha  bool   `json:"mcap,omitempty"`
	KCP            *KCP   `json:"kcp,omitempty"`
	Name           string `json:"name,omitempty"`
}

// Parse разбирает строку freeturn://<base64url(json)>.
func Parse(s string) (*Config, error) {
	if !strings.HasPrefix(s, scheme) {
		return nil, errors.New("invalid scheme, expected freeturn://")
	}
	payload := strings.TrimPrefix(s, scheme)
	if payload == "" {
		return nil, errors.New("empty payload")
	}

	// Primary: new base64url(json) wire format. On ANY failure (decode/json/version) fall back
	// to the legacy text format below — the olcvpn panel still emits the original
	// freeturn://<provider>?<transport>...@peer#key$name links (carrying a custom wg= param the
	// Kotlin client reads), and those MUST keep parsing so existing VK-TURN configs don't break.
	if cfg, ok := parseWire(payload); ok {
		return cfg, nil
	}
	return parseLegacy(payload)
}

// parseWire decodes the new base64url(json) payload. Returns ok=false (not an error) when the
// payload isn't a valid current-version wire object, so the caller can try the legacy format.
func parseWire(payload string) (*Config, bool) {
	raw, err := base64.RawURLEncoding.DecodeString(payload)
	if err != nil {
		return nil, false
	}
	var w wire
	if err := json.Unmarshal(raw, &w); err != nil {
		return nil, false
	}
	if w.V != currentVersion || w.Provider == "" || w.Peer == "" {
		return nil, false
	}
	return &Config{
		Version:        w.V,
		Provider:       w.Provider,
		Peer:           w.Peer,
		Transport:      w.Transport,
		Mode:           w.Mode,
		ObfProfile:     w.Obf,
		ObfKey:         w.Key,
		N:              w.N,
		StreamsPerCred: w.StreamsPerCred,
		ClientID:       w.ClientID,
		Listen:         w.Listen,
		DNSMode:        w.DNSMode,
		DNSServers:     w.DNSServers,
		ManualCaptcha:  w.ManualCaptcha,
		KCP:            w.KCP,
		Comment:        w.Name,
	}, true
}

// parseLegacy - ЛОКАЛЬНЫЙ ПАТЧ (upstream парсит только base64url-формат). Панель olcvpn
// до сих пор выдаёт СТАРЫЙ текстовый формат, и Kotlin-клиент вычитывает из него wg=,
// поэтому фоллбэк обязан пережить любой ре-вендор: без него отваливаются ВСЕ
// существующие VK-TURN ссылки и конфиги.
//
// Формат:
//   freeturn://<Provider>?<Transport><mode=..&obf-profile=..&bond=1>@<Peer>#<ObfKey>$<Comment>
// payload — строка БЕЗ префикса схемы. Кастомный параметр wg= внутри <...> здесь не нужен
// (его читает Kotlin-клиент из исходной ссылки); url.ParseQuery его молча игнорирует.
func parseLegacy(payload string) (*Config, error) {
	s := payload
	cfg := &Config{Version: currentVersion}

	// 1. Comment (после $)
	if idx := strings.Index(s, "$"); idx != -1 {
		cfg.Comment = s[idx+1:]
		s = s[:idx]
	}
	// 2. ObfKey (после #)
	if idx := strings.Index(s, "#"); idx != -1 {
		cfg.ObfKey = s[idx+1:]
		s = s[:idx]
	}
	// 3. Peer (после последнего @)
	if idx := strings.LastIndex(s, "@"); idx != -1 {
		cfg.Peer = s[idx+1:]
		s = s[:idx]
	}

	// Остаток: <Provider>?<Transport><key=val&...>
	parts := strings.SplitN(s, "?", 2)
	if parts[0] == "" {
		return nil, errors.New("missing provider")
	}
	cfg.Provider = parts[0]

	if len(parts) == 2 {
		transportPart := parts[1]
		if startIdx := strings.Index(transportPart, "<"); startIdx != -1 {
			if endIdx := strings.Index(transportPart, ">"); endIdx != -1 && endIdx > startIdx {
				inner := transportPart[startIdx+1 : endIdx]
				cfg.Transport = transportPart[:startIdx]
				vals, err := url.ParseQuery(inner)
				if err != nil {
					return nil, err
				}
				cfg.Mode = vals.Get("mode")
				cfg.ObfProfile = vals.Get("obf-profile")
			} else {
				cfg.Transport = transportPart
			}
		} else {
			cfg.Transport = transportPart
		}
	}

	if cfg.Peer == "" {
		return nil, errors.New("missing peer")
	}
	return cfg, nil
}

// String кодирует Config в freeturn://<base64url(json)>.
func (c *Config) String() string {
	w := wire{
		V:              currentVersion,
		Provider:       c.Provider,
		Peer:           c.Peer,
		Transport:      c.Transport,
		Mode:           c.Mode,
		N:              c.N,
		StreamsPerCred: c.StreamsPerCred,
		ClientID:       c.ClientID,
		Listen:         c.Listen,
		DNSMode:        c.DNSMode,
		DNSServers:     c.DNSServers,
		ManualCaptcha:  c.ManualCaptcha,
		KCP:            c.KCP,
		Name:           c.Comment,
	}
	if c.ObfProfile != "" && c.ObfProfile != "none" {
		w.Obf = c.ObfProfile
		w.Key = c.ObfKey
	}

	raw, err := json.Marshal(w)
	if err != nil {
		return ""
	}
	return scheme + base64.RawURLEncoding.EncodeToString(raw)
}

type KCP struct {
	NoDelay    int  `json:"nodelay"`
	Interval   int  `json:"interval"`
	Resend     int  `json:"resend"`
	NC         int  `json:"nc"`
	SndWnd     int  `json:"sndwnd"`
	RcvWnd     int  `json:"rcvwnd"`
	MTU        int  `json:"mtu"`
	ACKNoDelay bool `json:"acknodelay"`
}
