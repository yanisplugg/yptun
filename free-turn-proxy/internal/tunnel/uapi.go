package tunnel

import (
	"encoding/hex"
	"strconv"
	"strings"
)

// UAPI сериализует Config в строковый протокол IPC для device.IpcSet.
func UAPI(c *Config) (string, error) {
	if err := c.Validate(); err != nil {
		return "", err
	}

	var b strings.Builder
	line := func(k, v string) {
		b.WriteString(k)
		b.WriteByte('=')
		b.WriteString(v)
		b.WriteByte('\n')
	}

	line("private_key", hex.EncodeToString(c.PrivateKey[:]))
	line("listen_port", "0")
	writeAmnezia(line, c.Amnezia)
	line("replace_peers", "true")

	for i := range c.Peers {
		p := &c.Peers[i]
		line("public_key", hex.EncodeToString(p.PublicKey[:]))
		if !p.PresharedKey.IsZero() {
			line("preshared_key", hex.EncodeToString(p.PresharedKey[:]))
		}
		if p.Endpoint != "" {
			line("endpoint", p.Endpoint)
		} else {
			line("endpoint", EndpointSinglePeer)
		}
		if p.Keepalive > 0 {
			line("persistent_keepalive_interval", strconv.Itoa(p.Keepalive))
		}
		line("replace_allowed_ips", "true")
		for _, ip := range p.AllowedIPs {
			line("allowed_ip", ip.String())
		}
	}

	return b.String(), nil
}

func writeAmnezia(line func(k, v string), p AmneziaParams) {
	if !p.Enabled() {
		return
	}
	intKeys := []struct {
		key string
		val int
	}{
		{"jc", p.Jc}, {"jmin", p.Jmin}, {"jmax", p.Jmax},
		{"s1", p.S1}, {"s2", p.S2}, {"s3", p.S3}, {"s4", p.S4},
	}
	for _, kv := range intKeys {
		if kv.val > 0 {
			line(kv.key, strconv.Itoa(kv.val))
		}
	}
	strKeys := []struct {
		key string
		val string
	}{
		{"h1", p.H1}, {"h2", p.H2}, {"h3", p.H3}, {"h4", p.H4},
		{"content_padding_addition", p.ContentPaddingAddition},
		{"rekey_after_time", p.RekeyAfterTime},
		{"rekey_timeout", p.RekeyTimeout},
		{"reject_after_time", p.RejectAfterTime},
		{"keepalive_timeout", p.KeepaliveTimeout},
		{"max_handshake_attempts", p.MaxHandshakeAttempts},
	}
	for _, kv := range strKeys {
		if kv.val != "" {
			line(kv.key, kv.val)
		}
	}
	for i, spec := range p.I {
		if spec != "" {
			line("i"+strconv.Itoa(i+1), spec)
		}
	}
	if !p.HeaderProtectionKey.IsZero() {
		line("header_protection_key", hex.EncodeToString(p.HeaderProtectionKey[:]))
	}
	// Булевы шлём только во включённом виде - device и так стартует с false.
	if p.RandomTrailers {
		line("random_trailers", "true")
	}
	if p.DisableCookies {
		line("disable_cookies", "true")
	}
}
