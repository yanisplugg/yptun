package tunnel

import (
	"errors"
	"fmt"
	"net/netip"
)

// DefaultMTU - WireGuard идёт поверх TURN (STUN-обёртка + UDP + IP), и дефолтные
// 1420 фрагментируются. 1280 - минимум IPv6, проходит везде. Серверная сторона
// держит то же значение (control.sh, WG_MTU).
const DefaultMTU = 1280

// KeyLen - длина ключа Curve25519 в байтах.
const KeyLen = 32

// Key - приватный, публичный или pre-shared ключ.
type Key [KeyLen]byte

// IsZero сообщает, что ключ не задан.
func (k Key) IsZero() bool { return k == Key{} }

// Peer - удалённая сторона туннеля.
type Peer struct {
	PublicKey    Key
	PresharedKey Key
	AllowedIPs   []netip.Prefix
	// Endpoint нужен только при работе через сокет. Когда туннель подключён к
	// релею напрямую (singlePeerBind), адрес игнорируется: сторона ровно одна.
	Endpoint  string
	Keepalive int // persistent-keepalive, секунды; 0 - выключен
}

// Config - типизированный конфиг туннеля. Собирается парсером wg-quick или
// хостом напрямую; в UAPI превращается одной функцией, без склейки текста.
type Config struct {
	PrivateKey Key
	// Addresses и DNS туннель сам не применяет: их выставляет платформа при
	// создании tun-интерфейса. Хранятся здесь, потому что приходят из того же
	// .conf и хосту нужны при настройке VpnService.
	Addresses []netip.Prefix
	DNS       []netip.Addr
	MTU       int
	Peers     []Peer
	// Amnezia пуст - протокол побитово совпадает с ванильным WireGuard.
	Amnezia AmneziaParams
}

// AmneziaParams - параметры маскировки AmneziaWG. Имена полей повторяют ключи
// конфига (Jc, S1, H1, I1...), потому что пользователь копирует их из клиента
// Amnezia как есть - переименование только запутало бы.
//
// Нулевое значение = ванильный WireGuard: в UAPI не уходит ни одного ключа.
// Обнулять по отдельности нельзя - устройство отвергает jc/jmin/jmax <= 0.
type AmneziaParams struct {
	Jc   int // junk-пакетов перед handshake
	Jmin int // минимальный размер junk-пакета
	Jmax int // максимальный размер junk-пакета

	S1 int // padding init-сообщения
	S2 int // padding response-сообщения
	S3 int // padding cookie-сообщения
	S4 int // padding transport-сообщения

	// H1..H4 - типы сообщений: число или диапазон "N-M".
	H1 string
	H2 string
	H3 string
	H4 string

	// I - спецификации i1..i5: пакеты-обманки, отправляемые до handshake.
	I [5]string
}

// Enabled сообщает, задан ли хоть один параметр маскировки.
func (p AmneziaParams) Enabled() bool { return p != AmneziaParams{} }

// Defaults - конфиг с дефолтами; ключи и пиры задаёт вызывающий.
func Defaults() Config {
	return Config{MTU: DefaultMTU}
}

// Validate проверяет, что конфига достаточно для подъёма туннеля.
func (c *Config) Validate() error {
	if c == nil {
		return errors.New("tunnel: nil config")
	}
	if c.PrivateKey.IsZero() {
		return errors.New("tunnel: PrivateKey is required")
	}
	if len(c.Peers) == 0 {
		return errors.New("tunnel: at least one peer is required")
	}
	for i := range c.Peers {
		p := &c.Peers[i]
		if p.PublicKey.IsZero() {
			return fmt.Errorf("tunnel: peer[%d]: PublicKey is required", i)
		}
		if len(p.AllowedIPs) == 0 {
			return fmt.Errorf("tunnel: peer[%d]: AllowedIPs is required", i)
		}
		if p.Keepalive < 0 {
			return fmt.Errorf("tunnel: peer[%d]: negative keepalive", i)
		}
	}
	if c.MTU <= 0 {
		return errors.New("tunnel: MTU must be positive")
	}
	return c.Amnezia.validate()
}

func (p AmneziaParams) validate() error {
	if !p.Enabled() {
		return nil
	}
	// Устройство отвергает неположительные jc/jmin/jmax, поэтому junk-параметры
	// задаются либо все вместе, либо никак.
	junk := []int{p.Jc, p.Jmin, p.Jmax}
	set := 0
	for _, v := range junk {
		if v > 0 {
			set++
		}
	}
	if set != 0 && set != len(junk) {
		return errors.New("tunnel: amnezia Jc/Jmin/Jmax must be set together")
	}
	if p.Jmin > p.Jmax {
		return errors.New("tunnel: amnezia Jmin must not exceed Jmax")
	}
	for _, v := range []int{p.S1, p.S2, p.S3, p.S4} {
		if v < 0 {
			return errors.New("tunnel: amnezia padding must not be negative")
		}
	}
	return nil
}
