// Package awg реализует tunnel.Backend поверх amneziawg-go.
package awg

import (
	"errors"
	"fmt"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"

	"github.com/samosvalishe/free-turn-proxy/internal/logx"
	"github.com/samosvalishe/free-turn-proxy/internal/tunnel"
)

type Deps struct {
	Bind conn.Bind
	Log  logx.Logger
	// Protect исключает сокеты bind из туннеля (VpnService.protect); nil - бэкенд поверх пайпа релея.
	Protect func(fd int) bool
}

type Backend struct {
	deps Deps

	mu  sync.Mutex
	dev *device.Device
}

func New(deps Deps) *Backend {
	deps.Log = logx.OrNop(deps.Log)
	return &Backend{deps: deps}
}

func (b *Backend) Up(cfg *tunnel.Config, tunFD int) error {
	uapi, err := tunnel.UAPI(cfg)
	if err != nil {
		CloseTUNFD(tunFD)
		return err
	}

	b.mu.Lock()
	defer b.mu.Unlock()
	if b.dev != nil {
		CloseTUNFD(tunFD)
		return errors.New("awg: tunnel is already up")
	}

	tunDev, err := openTUN(tunFD)
	if err != nil {
		return err
	}

	bind := b.deps.Bind
	if bind == nil {
		bind = conn.NewDefaultBind()
	}

	dev := device.NewDevice(tunDev, bind, b.logger())
	dev.DisableSomeRoamingForBrokenMobileSemantics()

	if err := dev.IpcSet(uapi); err != nil {
		dev.Close()
		return err
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		return err
	}

	b.protectBind(dev)

	b.dev = dev
	mode := "wg"
	if cfg.Amnezia.Enabled() {
		mode = "awg"
	}
	b.deps.Log.Infof("tunnel: %s up, mtu %d, peers %d", mode, cfg.MTU, len(cfg.Peers))
	return nil
}

func (b *Backend) Rebind() error {
	b.mu.Lock()
	dev := b.dev
	b.mu.Unlock()

	if dev == nil {
		return nil
	}
	if err := dev.BindUpdate(); err != nil {
		return fmt.Errorf("awg: bind update: %w", err)
	}
	b.protectBind(dev)
	b.deps.Log.Infof("tunnel: rebound")
	return nil
}

func (b *Backend) protectBind(dev *device.Device) {
	if b.deps.Protect == nil {
		return
	}
	peek, ok := dev.Bind().(conn.PeekLookAtSocketFd)
	if !ok {
		b.deps.Log.Warnf("tunnel: bind does not expose socket fd, protect skipped")
		return
	}
	protected := 0
	if fd, err := peek.PeekLookAtSocketFd4(); err == nil {
		if b.deps.Protect(fd) {
			protected++
		} else {
			b.deps.Log.Warnf("tunnel: failed to protect ipv4 socket fd=%d", fd)
		}
	}
	if fd, err := peek.PeekLookAtSocketFd6(); err == nil {
		if b.deps.Protect(fd) {
			protected++
		} else {
			b.deps.Log.Warnf("tunnel: failed to protect ipv6 socket fd=%d", fd)
		}
	}
	if protected == 0 {
		b.deps.Log.Warnf("tunnel: no bind socket protected")
	}
}

func (b *Backend) Down() error {
	b.mu.Lock()
	dev := b.dev
	b.dev = nil
	b.mu.Unlock()

	if dev == nil {
		return nil
	}
	dev.Close()
	b.deps.Log.Infof("tunnel: down")
	return nil
}

// Stats возвращает текущие счетчики трафика туннеля.
func (b *Backend) Stats() (tunnel.Stats, error) {
	b.mu.Lock()
	dev := b.dev
	b.mu.Unlock()

	if dev == nil {
		return tunnel.Stats{}, nil
	}
	raw, err := dev.IpcGet()
	if err != nil {
		return tunnel.Stats{}, err
	}
	return parseStats(raw), nil
}

func parseStats(uapi string) tunnel.Stats {
	var st tunnel.Stats
	var handshakeSec int64

	for _, ln := range strings.Split(uapi, "\n") {
		key, value, ok := strings.Cut(strings.TrimSpace(ln), "=")
		if !ok {
			continue
		}
		n, err := strconv.ParseInt(value, 10, 64)
		if err != nil {
			continue
		}
		switch key {
		case "rx_bytes":
			st.RxBytes += n
		case "tx_bytes":
			st.TxBytes += n
		case "last_handshake_time_sec":
			if n > handshakeSec {
				handshakeSec = n
			}
		}
	}
	if handshakeSec > 0 {
		st.LastHandshake = time.Unix(handshakeSec, 0)
	}
	return st
}

func (b *Backend) logger() *device.Logger {
	return &device.Logger{
		Verbosef: b.deps.Log.Debugf,
		Errorf:   b.deps.Log.Errorf,
	}
}
