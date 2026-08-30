// Package tunnel предоставляет встроенный userspace WireGuard/AmneziaWG туннель.
package tunnel

import "time"

// EndpointSinglePeer - маркер адреса пира для SinglePeerBind.
const EndpointSinglePeer = "single-peer"

type Mode string

const (
	ModeNone Mode = "none"
	ModeWG   Mode = "wg"
	ModeAWG  Mode = "awg"
)

func (m Mode) Valid() bool {
	switch m {
	case ModeNone, ModeWG, ModeAWG:
		return true
	default:
		return false
	}
}

type Stats struct {
	RxBytes       int64
	TxBytes       int64
	LastHandshake time.Time
}

// Backend представляет реализацию userspace-туннеля.
type Backend interface {
	// Up запускает туннель поверх переданного дескриптора tunFD.
	Up(cfg *Config, tunFD int) error
	Down() error
	Stats() (Stats, error)
}
