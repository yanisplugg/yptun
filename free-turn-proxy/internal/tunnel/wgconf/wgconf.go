// Package wgconf разбирает конфиг в формате wg-quick (в том числе с
// расширениями AmneziaWG) в tunnel.Config.
//
// Хост передаёт текст конфига как есть - тем, который пользователь получил от
// сервера. Разбор и проверка живут здесь, чтобы приложение не резало строки
// регулярками и не знало формат.
package wgconf

import (
	"encoding/base64"
	"fmt"
	"net/netip"
	"strconv"
	"strings"

	"github.com/samosvalishe/free-turn-proxy/internal/tunnel"
)

// Ключи wg-quick, которые userspace-туннелю не нужны: маршруты, правила
// файрвола и скрипты применяет платформа, а не мы. Встретив их, парсер молчит;
// на всём остальном незнакомом - ругается, чтобы опечатка не потерялась.
var ignoredKeys = map[string]bool{
	"table":       true,
	"fwmark":      true,
	"listenport":  true,
	"saveconfig":  true,
	"preup":       true,
	"postup":      true,
	"predown":     true,
	"postdown":    true,
	"description": true,
}

// Возвращает провалидированный tunnel.Config из wg-quick текста.
func Parse(text string) (*tunnel.Config, error) {
	cfg := tunnel.Defaults()

	const (
		sectionNone = iota
		sectionInterface
		sectionPeer
	)
	section := sectionNone

	for i, rawLine := range strings.Split(text, "\n") {
		line := stripComment(rawLine)
		if line == "" {
			continue
		}

		if strings.HasPrefix(line, "[") && strings.HasSuffix(line, "]") {
			switch strings.ToLower(line) {
			case "[interface]":
				section = sectionInterface
			case "[peer]":
				section = sectionPeer
				cfg.Peers = append(cfg.Peers, tunnel.Peer{})
			default:
				return nil, lineErr(i, fmt.Errorf("unknown section %s", line))
			}
			continue
		}

		key, value, ok := strings.Cut(line, "=")
		if !ok {
			return nil, lineErr(i, fmt.Errorf("expected key = value, got %q", line))
		}
		key = strings.ToLower(strings.TrimSpace(key))
		value = strings.TrimSpace(value)
		if ignoredKeys[key] {
			continue
		}

		var err error
		switch section {
		case sectionInterface:
			err = applyInterface(&cfg, key, value)
		case sectionPeer:
			err = applyPeer(&cfg.Peers[len(cfg.Peers)-1], key, value)
		default:
			err = fmt.Errorf("key %q outside of a section", key)
		}
		if err != nil {
			return nil, lineErr(i, err)
		}
	}

	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	return &cfg, nil
}

func applyInterface(cfg *tunnel.Config, key, value string) error {
	switch key {
	case "privatekey":
		k, err := parseKey(value)
		if err != nil {
			return err
		}
		cfg.PrivateKey = k
	case "address":
		prefixes, err := parsePrefixes(value)
		if err != nil {
			return err
		}
		cfg.Addresses = append(cfg.Addresses, prefixes...)
	case "dns":
		cfg.DNS = append(cfg.DNS, parseDNS(value)...)
	case "mtu":
		mtu, err := strconv.Atoi(value)
		if err != nil {
			return fmt.Errorf("mtu: %w", err)
		}
		cfg.MTU = mtu
	case "jc", "jmin", "jmax", "s1", "s2", "s3", "s4":
		n, err := strconv.Atoi(value)
		if err != nil {
			return fmt.Errorf("%s: %w", key, err)
		}
		setAmneziaInt(&cfg.Amnezia, key, n)
	case "h1":
		cfg.Amnezia.H1 = value
	case "h2":
		cfg.Amnezia.H2 = value
	case "h3":
		cfg.Amnezia.H3 = value
	case "h4":
		cfg.Amnezia.H4 = value
	case "i1", "i2", "i3", "i4", "i5":
		idx := int(key[1] - '1')
		cfg.Amnezia.I[idx] = value
	default:
		return fmt.Errorf("unknown [Interface] key %q", key)
	}
	return nil
}

func setAmneziaInt(p *tunnel.AmneziaParams, key string, n int) {
	switch key {
	case "jc":
		p.Jc = n
	case "jmin":
		p.Jmin = n
	case "jmax":
		p.Jmax = n
	case "s1":
		p.S1 = n
	case "s2":
		p.S2 = n
	case "s3":
		p.S3 = n
	case "s4":
		p.S4 = n
	}
}

func applyPeer(peer *tunnel.Peer, key, value string) error {
	switch key {
	case "publickey":
		k, err := parseKey(value)
		if err != nil {
			return err
		}
		peer.PublicKey = k
	case "presharedkey":
		k, err := parseKey(value)
		if err != nil {
			return err
		}
		peer.PresharedKey = k
	case "allowedips":
		prefixes, err := parsePrefixes(value)
		if err != nil {
			return err
		}
		peer.AllowedIPs = append(peer.AllowedIPs, prefixes...)
	case "endpoint":
		peer.Endpoint = value
	case "persistentkeepalive":
		if strings.EqualFold(value, "off") {
			peer.Keepalive = 0
			return nil
		}
		seconds, err := strconv.Atoi(value)
		if err != nil {
			return fmt.Errorf("persistentkeepalive: %w", err)
		}
		peer.Keepalive = seconds
	default:
		return fmt.Errorf("unknown [Peer] key %q", key)
	}
	return nil
}

func parseKey(value string) (tunnel.Key, error) {
	var k tunnel.Key
	raw, err := base64.StdEncoding.DecodeString(value)
	if err != nil {
		return k, fmt.Errorf("key is not base64: %w", err)
	}
	if len(raw) != tunnel.KeyLen {
		return k, fmt.Errorf("key must be %d bytes, got %d", tunnel.KeyLen, len(raw))
	}
	copy(k[:], raw)
	return k, nil
}

// parsePrefixes принимает список через запятую. Голый адрес без маски
// достраивается до /32 или /128: клиенты пишут и так, и так.
func parsePrefixes(value string) ([]netip.Prefix, error) {
	var out []netip.Prefix
	for _, item := range strings.Split(value, ",") {
		item = strings.TrimSpace(item)
		if item == "" {
			continue
		}
		if prefix, err := netip.ParsePrefix(item); err == nil {
			out = append(out, prefix)
			continue
		}
		addr, err := netip.ParseAddr(item)
		if err != nil {
			return nil, fmt.Errorf("bad address %q", item)
		}
		out = append(out, netip.PrefixFrom(addr, addr.BitLen()))
	}
	return out, nil
}

// parseDNS берёт только адреса: wg-quick разрешает в этой же строке search
// domains, но userspace-туннелю они не нужны.
func parseDNS(value string) []netip.Addr {
	var out []netip.Addr
	for _, item := range strings.Split(value, ",") {
		if addr, err := netip.ParseAddr(strings.TrimSpace(item)); err == nil {
			out = append(out, addr)
		}
	}
	return out
}

func stripComment(line string) string {
	if idx := strings.IndexAny(line, "#;"); idx >= 0 {
		line = line[:idx]
	}
	return strings.TrimSpace(line)
}

func lineErr(idx int, err error) error {
	return fmt.Errorf("wgconf: line %d: %w", idx+1, err)
}
