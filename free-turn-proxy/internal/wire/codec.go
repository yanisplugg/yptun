// Package wire - зонтик wire-профилей обфускации TURN-payload. Codec - общий
// контракт профиля; NewClientCodec/Listen диспатчат по имени профиля. Профили
// живут в подпакетах (rtpopus, rtpopus2, …) и реализуют Codec структурно, не
// импортируя этот пакет.
package wire

import (
	"fmt"
	"net"

	dtlsnet "github.com/pion/dtls/v3/pkg/net"

	"github.com/samosvalishe/free-turn-proxy/internal/wire/rtpopus"
	"github.com/samosvalishe/free-turn-proxy/internal/wire/rtpopus2"
)

// Имена wire-профилей; совпадают со значениями флага -obf-profile.
const (
	ProfileNone     = "none"
	ProfileRTPOpus  = "rtpopus"
	ProfileRTPOpus2 = "rtpopus2"
)

// Codec - клиентский кодек wire-профиля: AEAD-обёртка payload с мимикрией.
// In-place API (WrapInPlace/UnwrapInPlace) - горячий UDP-путь; копирующий
// (WrapInto/Unwrap) - для RelayPacketConn. HeaderLen/Overhead/MaxWire задают
// раскладку буфера: у профилей разный размер заголовка.
type Codec interface {
	WrapInPlace(buf []byte, plainLen int) (int, error)
	UnwrapInPlace(wire []byte) ([]byte, error)
	WrapInto(dst, payload []byte) (int, error)
	Unwrap(wire, dst []byte) (int, error)
	HeaderLen() int
	Overhead() int
	MaxWire(payloadLen int) int
}

// NewClientCodec строит клиентский Codec для профиля. profile none/"" -> (nil, nil)
// (обфускация выключена). Длину ключа проверяет конструктор профиля.
func NewClientCodec(profile string, key []byte) (Codec, error) {
	switch profile {
	case ProfileNone, "":
		return nil, nil
	case ProfileRTPOpus:
		return rtpopus.NewConn(key, false)
	case ProfileRTPOpus2:
		return rtpopus2.NewConn(key, false)
	default:
		return nil, fmt.Errorf("wire: unknown obf profile %q", profile)
	}
}

// Listen строит серверный PacketListener, AEAD-разворачивающий каждый принятый
// PacketConn по профилю. Зовётся только при включённой обфускации.
func Listen(profile string, addr *net.UDPAddr, key []byte) (dtlsnet.PacketListener, error) {
	switch profile {
	case ProfileRTPOpus:
		return rtpopus.Listen(addr, key)
	case ProfileRTPOpus2:
		return rtpopus2.Listen(addr, key)
	default:
		return nil, fmt.Errorf("wire: profile %q has no server listener", profile)
	}
}
