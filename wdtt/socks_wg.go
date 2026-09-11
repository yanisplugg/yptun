package wdtt

import (
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"log"
	"net/netip"
	"strconv"
	"strings"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

type wgQuickConfig struct {
	privateKeyHex string
	addresses     []netip.Addr
	dns           []netip.Addr
	mtu           int
	peerPublicHex string
	allowedIPs    []string
	endpoint      string
	keepalive     uint16
}

func b64KeyToHex(b64 string) (string, error) {
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(b64))
	if err != nil {
		return "", err
	}
	if len(raw) != 32 {
		return "", fmt.Errorf("wireguard key must be 32 bytes, got %d", len(raw))
	}
	return hex.EncodeToString(raw), nil
}

func parseWgQuick(conf string) (*wgQuickConfig, error) {
	cfg := &wgQuickConfig{
		mtu:       1280,
		keepalive: 25,
	}
	section := ""
	for _, raw := range strings.Split(conf, "\n") {
		line := strings.TrimSpace(raw)
		if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, ";") {
			continue
		}
		if strings.HasPrefix(line, "[") && strings.HasSuffix(line, "]") {
			section = strings.ToLower(strings.TrimSpace(line[1 : len(line)-1]))
			continue
		}
		key, val, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		key = strings.TrimSpace(strings.ToLower(key))
		val = strings.TrimSpace(val)
		switch section {
		case "interface":
			switch key {
			case "privatekey":
				hexKey, err := b64KeyToHex(val)
				if err != nil {
					return nil, fmt.Errorf("PrivateKey: %w", err)
				}
				cfg.privateKeyHex = hexKey
			case "address":
				for _, part := range strings.Split(val, ",") {
					part = strings.TrimSpace(part)
					if part == "" {
						continue
					}
					prefix, err := netip.ParsePrefix(part)
					if err != nil {
						addr, err2 := netip.ParseAddr(part)
						if err2 != nil {
							return nil, fmt.Errorf("Address %q: %w", part, err)
						}
						cfg.addresses = append(cfg.addresses, addr)
						continue
					}
					cfg.addresses = append(cfg.addresses, prefix.Addr())
				}
			case "dns":
				for _, part := range strings.Split(val, ",") {
					part = strings.TrimSpace(part)
					if part == "" {
						continue
					}
					addr, err := netip.ParseAddr(part)
					if err != nil {
						return nil, fmt.Errorf("DNS %q: %w", part, err)
					}
					cfg.dns = append(cfg.dns, addr)
				}
			case "mtu":
				mtu, err := strconv.Atoi(val)
				if err != nil || mtu < 576 {
					return nil, fmt.Errorf("MTU %q: invalid", val)
				}
				cfg.mtu = mtu
			}
		case "peer":
			switch key {
			case "publickey":
				hexKey, err := b64KeyToHex(val)
				if err != nil {
					return nil, fmt.Errorf("PublicKey: %w", err)
				}
				cfg.peerPublicHex = hexKey
			case "allowedips":
				for _, part := range strings.Split(val, ",") {
					part = strings.TrimSpace(part)
					if part != "" {
						cfg.allowedIPs = append(cfg.allowedIPs, part)
					}
				}
			case "endpoint":
				cfg.endpoint = val
			case "persistentkeepalive":
				n, err := strconv.Atoi(val)
				if err != nil || n < 0 || n > 65535 {
					return nil, fmt.Errorf("PersistentKeepalive %q: invalid", val)
				}
				cfg.keepalive = uint16(n)
			}
		}
	}
	if cfg.privateKeyHex == "" || cfg.peerPublicHex == "" {
		return nil, fmt.Errorf("config missing PrivateKey or Peer PublicKey")
	}
	if len(cfg.addresses) == 0 {
		return nil, fmt.Errorf("config missing Address")
	}
	if cfg.endpoint == "" {
		return nil, fmt.Errorf("config missing Endpoint")
	}
	if len(cfg.allowedIPs) == 0 {
		cfg.allowedIPs = []string{"0.0.0.0/0"}
	}
	if len(cfg.dns) == 0 {
		cfg.dns = []netip.Addr{netip.MustParseAddr("1.1.1.1")}
	}
	return cfg, nil
}

func (c *wgQuickConfig) ipcRequest() string {
	var b strings.Builder
	fmt.Fprintf(&b, "private_key=%s\n", c.privateKeyHex)
	fmt.Fprintf(&b, "public_key=%s\n", c.peerPublicHex)
	fmt.Fprintf(&b, "endpoint=%s\n", c.endpoint)
	fmt.Fprintf(&b, "persistent_keepalive_interval=%d\n", c.keepalive)
	for _, ip := range c.allowedIPs {
		fmt.Fprintf(&b, "allowed_ip=%s\n", ip)
	}
	return b.String()
}

// startUserspaceWireGuard поднимает WG через netstack (без VpnService / kernel TUN).
func startUserspaceWireGuard(conf string) (*device.Device, *netstack.Net, error) {
	cfg, err := parseWgQuick(conf)
	if err != nil {
		return nil, nil, err
	}
	tunDev, tnet, err := netstack.CreateNetTUN(cfg.addresses, cfg.dns, cfg.mtu)
	if err != nil {
		return nil, nil, fmt.Errorf("CreateNetTUN: %w", err)
	}
	dev := device.NewDevice(tunDev, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "[WG-SOCKS] "))
	if err := dev.IpcSet(cfg.ipcRequest()); err != nil {
		dev.Close()
		return nil, nil, fmt.Errorf("IpcSet: %w", err)
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		return nil, nil, fmt.Errorf("Up: %w", err)
	}
	log.Printf("[SOCKS] Userspace WireGuard up (addr=%v endpoint=%s)", cfg.addresses, cfg.endpoint)
	return dev, tnet, nil
}
