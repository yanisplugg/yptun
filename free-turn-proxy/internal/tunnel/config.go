package tunnel

import (
	"errors"
	"fmt"
	"net/netip"
	"strconv"
	"strings"
)

// DefaultMTU - безопасный MTU для WireGuard поверх TURN без фрагментации.
const DefaultMTU = 1280

const KeyLen = 32

// Key - 32-байтный ключ WireGuard.
type Key [KeyLen]byte

func (k Key) IsZero() bool { return k == Key{} }

// Peer описывает конфигурацию удалённого узла туннеля.
type Peer struct {
	PublicKey    Key
	PresharedKey Key
	AllowedIPs   []netip.Prefix
	Endpoint     string
	Keepalive    int
}

type Config struct {
	PrivateKey Key
	Addresses  []netip.Prefix
	DNS        []netip.Addr
	MTU        int
	Peers      []Peer
	Amnezia    AmneziaParams
}

// MinHeaderProtectionPadding - минимум S1-S4, из которого AWG берёт nonce для защиты заголовка.
const MinHeaderProtectionPadding = 12

// AmneziaParams содержит параметры обфускации протокола AmneziaWG.
// Range-поля пустые либо в формате "N" / "LO-HI".
type AmneziaParams struct {
	Jc   int
	Jmin int
	Jmax int

	S1 int
	S2 int
	S3 int
	S4 int

	H1 string
	H2 string
	H3 string
	H4 string

	I [5]string

	// AWG 3+.
	HeaderProtectionKey    Key
	ContentPaddingAddition string
	RekeyAfterTime         string
	RekeyTimeout           string
	RejectAfterTime        string
	KeepaliveTimeout       string
	MaxHandshakeAttempts   string
	RandomTrailers         bool
	DisableCookies         bool
}

func (p AmneziaParams) Enabled() bool { return p != AmneziaParams{} }

// Defaults возвращает конфигурацию туннеля с дефолтным MTU.
func Defaults() Config {
	return Config{MTU: DefaultMTU}
}

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
	// Параметры junk (Jc, Jmin, Jmax) должны задаваться совместно.
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
	return p.validateAWG3()
}

func (p AmneziaParams) validateAWG3() error {
	ranges := []struct {
		name string
		val  string
	}{
		{"H1", p.H1}, {"H2", p.H2}, {"H3", p.H3}, {"H4", p.H4},
		{"ContentPaddingAddition", p.ContentPaddingAddition},
		{"RekeyAfterTime", p.RekeyAfterTime},
		{"RekeyTimeout", p.RekeyTimeout},
		{"RejectAfterTime", p.RejectAfterTime},
		{"KeepaliveTimeout", p.KeepaliveTimeout},
		{"MaxHandshakeAttempts", p.MaxHandshakeAttempts},
	}
	for _, r := range ranges {
		if err := ValidateRange(r.val); err != nil {
			return fmt.Errorf("tunnel: amnezia %s: %w", r.name, err)
		}
	}
	if p.HeaderProtectionKey.IsZero() {
		return nil
	}
	// Защита заголовка берёт nonce из crypto-паддинга, короткого ей не хватает.
	for _, v := range []int{p.S1, p.S2, p.S3, p.S4} {
		if v < MinHeaderProtectionPadding {
			return fmt.Errorf("tunnel: amnezia HeaderProtectionKey requires S1-S4 >= %d", MinHeaderProtectionPadding)
		}
	}
	return nil
}

// ValidateRange проверяет формат диапазона AWG ("N" или "LO-HI"); пустая строка - не задан.
func ValidateRange(s string) error {
	if s == "" {
		return nil
	}
	lo, hi, hasHi := strings.Cut(s, "-")
	low, err := strconv.ParseUint(lo, 10, 32)
	if err != nil {
		return fmt.Errorf("bad lower bound %q", lo)
	}
	if !hasHi {
		return nil
	}
	high, err := strconv.ParseUint(hi, 10, 32)
	if err != nil {
		return fmt.Errorf("bad upper bound %q", hi)
	}
	if high < low {
		return fmt.Errorf("upper bound %d below lower %d", high, low)
	}
	return nil
}
