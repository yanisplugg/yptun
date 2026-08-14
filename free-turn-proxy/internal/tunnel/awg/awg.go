// Package awg - реализация tunnel.Backend на amneziawg-go.
//
// Форк выбран единственной зависимостью: с пустыми параметрами маскировки он
// ведёт себя как обычный WireGuard, поэтому режимы wg и awg обслуживаются одним
// устройством вместо двух почти одинаковых кодовых баз.
package awg

import (
	"errors"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/conn"
	"github.com/amnezia-vpn/amneziawg-go/device"

	"github.com/samosvalishe/free-turn-proxy/internal/logx"
	"github.com/samosvalishe/free-turn-proxy/internal/tunnel"
)

// Deps - зависимости бэкенда.
type Deps struct {
	// Bind - канал, по которому устройство разговаривает с той стороной.
	// nil даёт обычный UDP-сокет; клиент передаёт tunnel.SinglePeerBind поверх
	// пары в памяти, соединённой с релеем.
	Bind conn.Bind
	Log  logx.Logger
}

// Backend управляет поднятием одного userspace-устройства.
type Backend struct {
	deps Deps

	mu  sync.Mutex
	dev *device.Device
}

// Устройство создается только в Up.
func New(deps Deps) *Backend {
	deps.Log = logx.OrNop(deps.Log)
	return &Backend{deps: deps}
}

func (b *Backend) Up(cfg *tunnel.Config, tunFD int) error {
	uapi, err := tunnel.UAPI(cfg)
	if err != nil {
		return err
	}

	b.mu.Lock()
	defer b.mu.Unlock()
	if b.dev != nil {
		return errors.New("awg: tunnel is already up")
	}

	tunDev, err := openTUN(tunFD, cfg.MTU)
	if err != nil {
		return err
	}

	bind := b.deps.Bind
	if bind == nil {
		bind = conn.NewDefaultBind()
	}

	dev := device.NewDevice(tunDev, bind, b.logger())
	// Endpoint пира фиксирован: с SinglePeerBind собеседник один, и роуминг по
	// адресу источника только сбивал бы устройство с толку.
	dev.DisableSomeRoamingForBrokenMobileSemantics()

	if err := dev.IpcSet(uapi); err != nil {
		dev.Close()
		return err
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		return err
	}

	b.dev = dev
	mode := "wg"
	if cfg.Amnezia.Enabled() {
		mode = "awg"
	}
	b.deps.Log.Infof("tunnel: %s up, mtu %d, peers %d", mode, cfg.MTU, len(cfg.Peers))
	return nil
}

// Down опускает туннель и закрывает tun-дескриптор. Повторный вызов безопасен.
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

// Stats читает счётчики устройства. До первого Up возвращает нули.
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

// parseStats суммирует счётчики по всем пирам: пир обычно один, но складывать
// дешевле, чем объяснять, почему берётся первый.
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

// logger переводит вывод устройства в наш логгер: Verbosef уходит в debug,
// чтобы обычный лог не тонул в пакетной трассировке.
func (b *Backend) logger() *device.Logger {
	return &device.Logger{
		Verbosef: b.deps.Log.Debugf,
		Errorf:   b.deps.Log.Errorf,
	}
}
