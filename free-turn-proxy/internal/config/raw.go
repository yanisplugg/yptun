package config

import (
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/samosvalishe/free-turn-proxy/internal/transport/kcptun"
	"github.com/samosvalishe/free-turn-proxy/internal/tunnel"
	"github.com/samosvalishe/free-turn-proxy/internal/uri"
	"github.com/samosvalishe/free-turn-proxy/internal/wire/rtpopus"
)

// raw - опции в том виде, в каком их даёт источник: CLI-флаги, freeturn:// URI
// или (позже) JSON мобильного хоста. Строки не разобраны, enum'ы не проверены.
//
// Единственный путь raw -> Client идёт через assemble, поэтому у всех
// источников ровно одна семантика: новый источник заполняет raw и не повторяет
// ни нормализацию, ни проверки.
type raw struct {
	Turn   string
	Port   string
	Listen string
	Peer   string

	Provider string
	Link     string // устаревший -link: одна ссылка
	Links    string // -links: через запятую

	N              int
	StreamsPerCred int
	Transport      string
	Mode           string
	Bond           bool

	ObfProfile string
	ObfKey     string
	ObfTiming  time.Duration
	GenObfKey  bool

	ManualCaptcha bool
	Platform      string

	DNSMode    string
	DNSServers string

	Debug    bool
	ClientID string
	SubURL   string
	Routes   bool

	TunnelMode   string
	TunnelConfig string
	TunnelMTU    int
}

// applyURI накладывает freeturn:// поверх raw: URI побеждает флаги, но только
// теми полями, которые в нём есть - остальное остаётся от источника.
func (r *raw) applyURI(u *uri.Config) {
	if u.Provider != "" {
		r.Provider = u.Provider
	}
	if u.Transport != "" {
		r.Transport = u.Transport
	}
	if u.Mode != "" {
		r.Mode = u.Mode
	}
	if u.Bond {
		r.Bond = true
	}
	if u.N > 0 {
		r.N = u.N
	}
	if u.StreamsPerCred > 0 {
		r.StreamsPerCred = u.StreamsPerCred
	}
	if u.ObfProfile != "" {
		r.ObfProfile = u.ObfProfile
	}
	if u.ObfKey != "" {
		r.ObfKey = u.ObfKey
	}
	if u.Peer != "" {
		r.Peer = u.Peer
	}
	if u.ClientID != "" {
		r.ClientID = u.ClientID
	}
	if u.Listen != "" {
		r.Listen = u.Listen
	}
	if u.DNSMode != "" {
		r.DNSMode = u.DNSMode
	}
	if u.DNSServers != "" {
		r.DNSServers = u.DNSServers
	}
	if u.ManualCaptcha {
		r.ManualCaptcha = true
	}
}

// Превращает raw в Client: разбирает энумы, нормализует ссылки, декодирует ключ.
func assemble(r raw) (*Client, error) {
	switch r.Transport {
	case TransportTCP, TransportUDP:
	default:
		return nil, fmt.Errorf("invalid -transport value %q: must be %s | %s", r.Transport, TransportTCP, TransportUDP)
	}
	switch r.Mode {
	case ModeUDP, ModeTCP:
	default:
		return nil, fmt.Errorf("invalid -mode value %q: must be %s | %s", r.Mode, ModeUDP, ModeTCP)
	}
	// Проверяется на raw: в Client комбинация уже свёрнута в один ProxyMode и
	// "-bond без tcp" от обычного udp не отличить.
	if r.Bond && r.Mode != ModeTCP {
		return nil, errors.New("-bond requires -mode tcp")
	}

	n := r.N
	if n <= 0 {
		n = DefaultStreams
	}
	platform := Platform(r.Platform)
	if platform == "" {
		platform = DefaultPlatform
	}
	tunnelMTU := r.TunnelMTU
	if tunnelMTU <= 0 {
		tunnelMTU = tunnel.DefaultMTU
	}

	c := &Client{
		TURN: TURNOpts{
			Host:         r.Turn,
			Port:         r.Port,
			TransportUDP: r.Transport == TransportUDP,
			N:            n,
		},
		Obf: ObfOpts{
			Profile: ObfProfile(r.ObfProfile),
			GenKey:  r.GenObfKey,
			Timing:  r.ObfTiming,
		},
		Proxy: ProxyOpts{
			Mode:   clientProxyMode(r.Mode, r.Bond),
			Listen: r.Listen,
			Peer:   r.Peer,
		},
		Provider: ProviderOpts{Name: r.Provider},
		VK: VKOpts{
			Links:          normalizeVKLinks(r.Links, r.Link),
			StreamsPerCred: r.StreamsPerCred,
			ManualCaptcha:  r.ManualCaptcha,
			Platform:       platform,
		},
		DNS: DNSOpts{Mode: r.DNSMode},
		Log: LogOpts{Debug: r.Debug},
		KCP: KCPOpts{
			Profile: kcptun.DefaultProfile(),
			FEC:     kcptun.FEC{},
		},
		Tunnel: TunnelOpts{
			Mode:   tunnel.Mode(r.TunnelMode),
			Config: r.TunnelConfig,
			MTU:    tunnelMTU,
		},
		ClientID: r.ClientID,
		SubURL:   r.SubURL,
		Routes:   r.Routes,
	}

	if r.DNSServers != "" {
		c.DNS.Servers = strings.Split(r.DNSServers, ",")
	}

	// -gen-obf-key печатает новый ключ и выходит: разбирать переданный незачем.
	if c.Obf.GenKey {
		return c, nil
	}
	if err := validateObfProfile(c.Obf.Profile); err != nil {
		return nil, err
	}
	key, err := rtpopus.DecodeKey(c.Obf.Enabled(), r.ObfKey)
	if err != nil {
		return nil, err
	}
	c.Obf.Key = key

	return c, nil
}

// Вытаскивает join-код из ссылки (принимает URL и голый код).
func normalizeVKLinks(links, link string) []string {
	items := strings.Split(links, ",")
	if len(items) == 1 && items[0] == "" {
		items = []string{link}
	}
	out := make([]string, 0, len(items))
	for _, item := range items {
		item = strings.TrimSpace(item)
		if item == "" {
			continue
		}
		parts := strings.Split(item, "join/")
		code := parts[len(parts)-1]
		if idx := strings.IndexAny(code, "/?#"); idx != -1 {
			code = code[:idx]
		}
		if code != "" {
			out = append(out, code)
		}
	}
	if len(out) == 0 {
		return nil
	}
	return out
}

func clientProxyMode(mode string, bond bool) ProxyMode {
	switch {
	case mode == ModeTCP && bond:
		return ProxyModeTCPFwdBond
	case mode == ModeTCP:
		return ProxyModeTCPFwd
	default:
		return ProxyModeUDP
	}
}

func serverProxyMode(mode string) ProxyMode {
	if mode == ModeTCP {
		return ProxyModeTCPFwd
	}
	return ProxyModeUDP
}
