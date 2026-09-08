package mobile

import (
	"errors"
	"fmt"
	"math"
	"net"
	"net/netip"
	"strings"
	"time"

	"github.com/samosvalishe/free-turn-proxy/internal/config"
	"github.com/samosvalishe/free-turn-proxy/internal/logx"
	"github.com/samosvalishe/free-turn-proxy/internal/netconn"
	"github.com/samosvalishe/free-turn-proxy/internal/tunnel"
	"github.com/samosvalishe/free-turn-proxy/internal/tunnel/awg"
	"github.com/samosvalishe/free-turn-proxy/internal/tunnel/bind"
	"github.com/samosvalishe/free-turn-proxy/internal/tunnel/wgconf"
)

// TunnelSnapshot - срез состояния userspace-туннеля.
type TunnelSnapshot struct {
	Up      bool
	RxBytes int64
	TxBytes int64
	// HandshakeAgeSec - секунд с последнего handshake (-1 если не было).
	HandshakeAgeSec int64
}

func TunnelStats() *TunnelSnapshot {
	l := current.Load()
	if l == nil || l.tunnel == nil {
		return &TunnelSnapshot{HandshakeAgeSec: -1}
	}
	st, err := l.tunnel.backend.Stats()
	if err != nil {
		return &TunnelSnapshot{Up: true, HandshakeAgeSec: -1}
	}
	age := int64(-1)
	if !st.LastHandshake.IsZero() {
		age = int64(time.Since(st.LastHandshake).Seconds())
	}
	return &TunnelSnapshot{
		Up:              true,
		RxBytes:         st.RxBytes,
		TxBytes:         st.TxBytes,
		HandshakeAgeSec: age,
	}
}

// TunnelParams - параметры tun-интерфейса для платформы (VpnService.Builder, NEPacketTunnelProvider).
type TunnelParams struct {
	Addresses  string
	DNS        string
	AllowedIPs string
	MTU        int
}

// ParseTunnelConfig извлекает параметры tun-интерфейса для платформы из wg-конфига.
func ParseTunnelConfig(wgText string, mtu int) (*TunnelParams, error) {
	cfg, err := wgconf.Parse(wgText)
	if err != nil {
		return nil, fmt.Errorf("parse tunnel config: %w", err)
	}
	if mtu > 0 {
		cfg.MTU = mtu
	}

	addrs := make([]string, 0, len(cfg.Addresses))
	for _, p := range cfg.Addresses {
		addrs = append(addrs, p.String())
	}
	dns := make([]string, 0, len(cfg.DNS))
	for _, a := range cfg.DNS {
		dns = append(dns, a.String())
	}
	return &TunnelParams{
		Addresses:  strings.Join(addrs, ","),
		DNS:        strings.Join(dns, ","),
		AllowedIPs: strings.Join(allowedIPs(cfg.Peers), ","),
		MTU:        cfg.MTU,
	}, nil
}

// allowedIPs исключает дубликаты префиксов (addRoute бросает исключение на дублях).
func allowedIPs(peers []tunnel.Peer) []string {
	out := make([]string, 0, len(peers))
	seen := make(map[netip.Prefix]struct{}, len(peers))
	for i := range peers {
		for _, p := range peers[i].AllowedIPs {
			if _, dup := seen[p]; dup {
				continue
			}
			seen[p] = struct{}{}
			out = append(out, p.String())
		}
	}
	return out
}

type tunnelParts struct {
	backend    tunnel.Backend
	bind       *bind.SinglePeerBind
	deviceSide net.PacketConn
	relaySide  net.PacketConn
}

// buildTunnel собирает userspace WireGuard туннель через in-memory пайп к сессии релея.
func buildTunnel(cfg *config.Client, logger logx.Logger) (*tunnelParts, *tunnel.Config, error) {
	tunCfg, err := wgconf.Parse(cfg.Tunnel.Config)
	if err != nil {
		return nil, nil, err
	}
	if cfg.Tunnel.MTU > 0 {
		tunCfg.MTU = cfg.Tunnel.MTU
	}
	// При режиме wg маскировка отключается независимо от опций в конфиге.
	if cfg.Tunnel.Mode == tunnel.ModeWG && tunCfg.Amnezia.Enabled() {
		logger.Warnf("tunnel: mode=wg, параметры AmneziaWG из конфига игнорируются")
		tunCfg.Amnezia = tunnel.AmneziaParams{}
	}
	for i := range tunCfg.Peers {
		tunCfg.Peers[i].Endpoint = ""
	}

	deviceSide, relaySide := netconn.PacketPipe(tunCfg.MTU+tunnelPipeHeadroom, 0)
	parts := &tunnelParts{
		bind:       bind.NewSinglePeerBind(deviceSide),
		deviceSide: deviceSide,
		relaySide:  relaySide,
	}
	parts.backend = awg.New(awg.Deps{Bind: parts.bind, Log: logger})
	return parts, tunCfg, nil
}

// tunnelPipeHeadroom - запас MTU под оверхед заголовков WireGuard и junk-префиксы AmneziaWG.
const tunnelPipeHeadroom = 512

// Bind закрывается первым для разблокировки читающей горутины device.Close().
func (t *tunnelParts) close() {
	if t == nil {
		return
	}
	if t.bind != nil {
		_ = t.bind.Close()
	}
	if t.backend != nil {
		_ = t.backend.Down()
	}
	if t.deviceSide != nil {
		_ = t.deviceSide.Close()
	}
	if t.relaySide != nil {
		_ = t.relaySide.Close()
	}
}

// StartTunnel запускает сессию с переданным tun FD (хост передаёт дубликат fd).
func StartTunnel(configJSON string, tunFD int) error {
	if err := checkTunFD(tunFD); err != nil {
		return err
	}
	mu.Lock()
	defer mu.Unlock()
	if current.Load() != nil {
		awg.CloseTUNFD(tunFD)
		return errors.New("already running")
	}
	return startLocked(configJSON, tunFD, true)
}

// checkTunFD валидирует FD до передачи ядру (0 - stdin процесса).
func checkTunFD(fd int) error {
	if fd <= 0 {
		return fmt.Errorf("bad tun fd %d", fd)
	}
	return nil
}

const directHandshakeStale = 180 * time.Second

func StartDirectTunnel(wgText string, mtu int, tunFD int) error {
	if err := checkTunFD(tunFD); err != nil {
		return err
	}
	mu.Lock()
	defer mu.Unlock()
	if current.Load() != nil {
		awg.CloseTUNFD(tunFD)
		return errors.New("already running")
	}

	cfg, err := directConfig(wgText, mtu)
	if err != nil {
		awg.CloseTUNFD(tunFD)
		return err
	}

	if protector.Load() == nil {
		coreLog().Warnf("tunnel: SetProtect не задан - сокеты прямого туннеля не защищены")
	}

	backend := awg.New(awg.Deps{Log: coreLog(), Protect: protectFD})

	if err := backend.Up(cfg, tunFD); err != nil {
		return fmt.Errorf("start direct tunnel: %w", err)
	}

	done := make(chan struct{})
	close(done)
	current.Store(&live{
		cancel: func() {},
		done:   done,
		tunnel: &tunnelParts{backend: backend},
	})
	finishing.Store(nil)
	lastStop.Store(nil)
	return nil
}

func directConfig(wgText string, mtu int) (*tunnel.Config, error) {
	cfg, err := wgconf.Parse(wgText)
	if err != nil {
		return nil, fmt.Errorf("parse tunnel config: %w", err)
	}
	if mtu > 0 {
		cfg.MTU = mtu
	}
	for i := range cfg.Peers {
		if _, err := netip.ParseAddrPort(cfg.Peers[i].Endpoint); err != nil {
			return nil, fmt.Errorf("peer[%d]: Endpoint must be ip:port for a direct tunnel, got %q", i, cfg.Peers[i].Endpoint)
		}
	}
	return cfg, nil
}

func directSnapshot(l *live) *Snapshot {
	st, err := l.tunnel.backend.Stats()
	if err != nil {
		return &Snapshot{State: StateError, ErrMsg: err.Error(), Streams: 1, Total: 1}
	}
	snap := &Snapshot{
		State:   StateConnecting,
		Streams: 1,
		Total:   1,
		TxTotal: st.TxBytes,
		RxTotal: st.RxBytes,
	}
	if !st.LastHandshake.IsZero() {
		snap.State = StateConnected
	}
	l.fillRates(snap)
	if snap.State == StateConnected && snap.TxRate > 0 && time.Since(st.LastHandshake) > directHandshakeStale {
		snap.State = StateConnecting
	}
	return snap
}

func (l *live) fillRates(s *Snapshot) {
	l.fillRatesAt(s, time.Now())
}

func (l *live) fillRatesAt(s *Snapshot, now time.Time) {
	l.rateMu.Lock()
	defer l.rateMu.Unlock()

	if dt := now.Sub(l.prevAt).Seconds(); !l.prevAt.IsZero() && dt > 0 {
		s.TxRate = int64(math.Round(float64(s.TxTotal-l.prevTx) / dt))
		s.RxRate = int64(math.Round(float64(s.RxTotal-l.prevRx) / dt))
	}
	l.prevTx, l.prevRx, l.prevAt = s.TxTotal, s.RxTotal, now
}
