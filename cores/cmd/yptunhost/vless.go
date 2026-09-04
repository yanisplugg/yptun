package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/url"
	"strconv"
	"strings"
)

// vlessToXray turns a vless:// share link into a complete xray config whose only inbound is a
// loopback SOCKS5 on socksPort. Same field set the Kotlin VlessUriParser understands (tcp/ws/grpc/
// httpupgrade/xhttp + none/tls/reality), so a link that works in the app works here.
func vlessToXray(link string, socksPort int) (string, error) {
	u, err := url.Parse(strings.TrimSpace(link))
	if err != nil {
		return "", err
	}
	if u.Scheme != "vless" {
		return "", fmt.Errorf("unsupported scheme %q (only vless:// here)", u.Scheme)
	}
	uuid := u.User.Username()
	if uuid == "" {
		return "", errors.New("vless link has no uuid")
	}
	host := u.Hostname()
	port, _ := strconv.Atoi(u.Port())
	if host == "" || port == 0 {
		return "", errors.New("vless link has no host:port")
	}
	q := u.Query()
	get := func(k string) string { return strings.TrimSpace(q.Get(k)) }

	network := get("type")
	if network == "" {
		network = "tcp"
	}
	security := get("security")
	if security == "" {
		security = "none"
	}

	stream := map[string]any{"network": network, "security": security}

	switch security {
	case "tls":
		tls := map[string]any{
			"serverName":    firstNonEmpty(get("sni"), host),
			"allowInsecure": get("allowInsecure") == "1" || get("allowInsecure") == "true",
		}
		if fp := get("fp"); fp != "" {
			tls["fingerprint"] = fp
		}
		if alpn := get("alpn"); alpn != "" {
			tls["alpn"] = strings.Split(alpn, ",")
		}
		stream["tlsSettings"] = tls
	case "reality":
		reality := map[string]any{
			"serverName": firstNonEmpty(get("sni"), host),
			"publicKey":  get("pbk"),
			"shortId":    get("sid"),
			"spiderX":    firstNonEmpty(get("spx"), "/"),
		}
		if fp := get("fp"); fp != "" {
			reality["fingerprint"] = fp
		} else {
			reality["fingerprint"] = "chrome"
		}
		stream["realitySettings"] = reality
	}

	switch network {
	case "ws":
		ws := map[string]any{"path": firstNonEmpty(get("path"), "/")}
		if h := get("host"); h != "" {
			ws["headers"] = map[string]string{"Host": h}
		}
		stream["wsSettings"] = ws
	case "httpupgrade":
		hu := map[string]any{"path": firstNonEmpty(get("path"), "/")}
		if h := get("host"); h != "" {
			hu["host"] = h
		}
		stream["httpupgradeSettings"] = hu
	case "xhttp", "splithttp":
		stream["network"] = "xhttp"
		xh := map[string]any{"path": firstNonEmpty(get("path"), "/")}
		if h := get("host"); h != "" {
			xh["host"] = h
		}
		if m := get("mode"); m != "" {
			xh["mode"] = m
		}
		stream["xhttpSettings"] = xh
	case "grpc":
		grpc := map[string]any{"serviceName": get("serviceName")}
		if get("mode") == "multi" {
			grpc["multiMode"] = true
		}
		stream["grpcSettings"] = grpc
	case "tcp":
		if get("headerType") == "http" {
			hosts := []string{}
			if h := get("host"); h != "" {
				hosts = strings.Split(h, ",")
			}
			stream["tcpSettings"] = map[string]any{
				"header": map[string]any{
					"type": "http",
					"request": map[string]any{
						"path":    []string{firstNonEmpty(get("path"), "/")},
						"headers": map[string]any{"Host": hosts},
					},
				},
			}
		}
	}

	user := map[string]any{"id": uuid, "encryption": "none"}
	if flow := get("flow"); flow != "" {
		user["flow"] = flow
	}

	cfg := map[string]any{
		"log": map[string]any{"loglevel": "warning"},
		"inbounds": []any{map[string]any{
			"listen":   "127.0.0.1",
			"port":     socksPort,
			"protocol": "socks",
			"settings": map[string]any{"auth": "noauth", "udp": true},
			"sniffing": map[string]any{"enabled": true, "destOverride": []string{"http", "tls"}},
		}},
		"outbounds": []any{
			map[string]any{
				"protocol": "vless",
				"settings": map[string]any{"vnext": []any{map[string]any{
					"address": host,
					"port":    port,
					"users":   []any{user},
				}}},
				"streamSettings": stream,
			},
			map[string]any{"protocol": "freedom", "tag": "direct"},
		},
	}
	out, err := json.Marshal(cfg)
	return string(out), err
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if v != "" {
			return v
		}
	}
	return ""
}

// freePort asks the OS for an unused loopback port and hands it to a core to bind.
// ponytail: tiny TOCTOU window between close and re-bind; retry on start error if it ever bites.
func freePort() (int, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	port := ln.Addr().(*net.TCPAddr).Port
	_ = ln.Close()
	return port, nil
}
