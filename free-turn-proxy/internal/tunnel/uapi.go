package tunnel

import (
	"encoding/hex"
	"strconv"
	"strings"
)

// UAPI собирает конфигурацию в формат, который понимает device.IpcSet.
// Формат: строки "ключ=значение", пиры идут после своего public_key.
//
// Параметры Amnezia печатаются только когда заданы: устройство отвергает
// jc/jmin/jmax <= 0, поэтому "выключено" - это отсутствие ключа, а не ноль.
// Отсюда же и совместимость с ванильным WireGuard: пустой AmneziaParams не
// добавляет в поток ни байта.
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
	// Порт не назначаем: исходящий канал даёт Bind, слушать нечего.
	line("listen_port", "0")
	writeAmnezia(line, c.Amnezia)
	line("replace_peers", "true")

	for i := range c.Peers {
		p := &c.Peers[i]
		line("public_key", hex.EncodeToString(p.PublicKey[:]))
		if !p.PresharedKey.IsZero() {
			line("preshared_key", hex.EncodeToString(p.PresharedKey[:]))
		}
		// Пир без endpoint'а никогда не начнёт handshake, поэтому строка есть
		// всегда: при работе через SinglePeerBind её содержимое игнорируется.
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
}
