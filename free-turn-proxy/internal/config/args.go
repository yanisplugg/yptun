package config

import (
	"encoding/hex"
	"strconv"
	"strings"
)

// ClientArgs собирает argv, эквивалентный конфигу: ParseClient(ClientArgs(c))
// возвращает тот же Client. Нужен хостам, которые показывают пользователю
// "какая команда сейчас работает" - строка гарантированно не расходится с тем,
// что реально запущено, потому что выводится из того же Client.
//
// Печатаются обязательные поля и всё, что отличается от Defaults(): читаемо и
// при этом самодостаточно - остальное восстановит парсер.
func ClientArgs(c *Client) []string {
	if c == nil {
		return nil
	}
	def := Defaults()
	var args []string
	add := func(flag string, value ...string) {
		args = append(args, flag)
		args = append(args, value...)
	}

	add("-peer", c.Proxy.Peer)
	if len(c.VK.Links) > 0 {
		add("-links", strings.Join(c.VK.Links, ","))
	}
	if c.Provider.Name != def.Provider.Name {
		add("-provider", c.Provider.Name)
	}
	if c.Proxy.Listen != def.Proxy.Listen {
		add("-listen", c.Proxy.Listen)
	}
	if c.TURN.Host != "" {
		add("-turn", c.TURN.Host)
	}
	if c.TURN.Port != "" {
		add("-port", c.TURN.Port)
	}
	if c.TURN.N != def.TURN.N {
		add("-n", strconv.Itoa(c.TURN.N))
	}
	if c.VK.StreamsPerCred != def.VK.StreamsPerCred {
		add("-streams-per-cred", strconv.Itoa(c.VK.StreamsPerCred))
	}
	if c.TURN.TransportUDP != def.TURN.TransportUDP {
		add("-transport", TransportUDP)
	}
	switch c.Proxy.Mode {
	case ProxyModeTCPFwd:
		add("-mode", ModeTCP)
	case ProxyModeTCPFwdBond:
		add("-mode", ModeTCP)
		args = append(args, "-bond")
	case ProxyModeUDP:
	}
	if c.Obf.Enabled() {
		add("-obf-profile", string(c.Obf.Profile))
		add("-obf-key", hex.EncodeToString(c.Obf.Key))
	}
	if c.Obf.Timing > 0 {
		add("-obf-timing", c.Obf.Timing.String())
	}
	if c.VK.ManualCaptcha {
		args = append(args, "-manual-captcha")
	}
	if c.VK.Platform != def.VK.Platform {
		add("-platform", string(c.VK.Platform))
	}
	if c.DNS.Mode != def.DNS.Mode {
		add("-dns-mode", c.DNS.Mode)
	}
	if len(c.DNS.Servers) > 0 {
		add("-dns-servers", strings.Join(c.DNS.Servers, ","))
	}
	if c.ClientID != "" {
		add("-client-id", c.ClientID)
	}
	if c.SubURL != "" {
		add("-sub", c.SubURL)
	}
	if c.Log.Debug {
		args = append(args, "-debug")
	}
	return args
}
