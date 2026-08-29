package config

import (
	"flag"
	"fmt"
	"strconv"

	"github.com/samosvalishe/free-turn-proxy/internal/transport/kcpmux"
)

// KCPOpts - параметры ARQ-слоя tcp-режима; в udp-режиме не используются.
type KCPOpts struct {
	Profile kcpmux.Profile
}

// kcpFlags держит указатели на -kcp-*; флаги регистрируются одинаково у клиента и
// сервера, потому что окна и MTU настраиваются на каждой стороне отдельно.
type kcpFlags struct {
	noDelay    *int
	interval   *int
	resend     *int
	nc         *int
	sndWnd     *int
	rcvWnd     *int
	mtu        *int
	ackNoDelay *bool
}

func registerKCPFlags(fs *flag.FlagSet, def kcpmux.Profile) *kcpFlags {
	return &kcpFlags{
		noDelay:    fs.Int("kcp-nodelay", def.NoDelay, "KCP nodelay: 0 (обычный) | 1 (быстрый)"),
		interval:   fs.Int("kcp-interval", def.Interval, "интервал внутреннего цикла KCP, мс; больше = меньше служебного трафика"),
		resend:     fs.Int("kcp-resend", def.Resend, "быстрая повторная отправка после N дубликатов ACK; 0=выкл"),
		nc:         fs.Int("kcp-nc", def.NC, "KCP congestion control: 0 (вкл) | 1 (выкл)"),
		sndWnd:     fs.Int("kcp-sndwnd", def.SndWnd, "окно отправки KCP в пакетах"),
		rcvWnd:     fs.Int("kcp-rcvwnd", def.RcvWnd, "окно приёма KCP в пакетах"),
		mtu:        fs.Int("kcp-mtu", def.MTU, "MTU сегмента KCP в байтах"),
		ackNoDelay: fs.Bool("kcp-acknodelay", def.ACKNoDelay, "отправлять ACK сразу; ниже задержка, выше служебный трафик. Все -kcp-* работают только с -mode tcp"),
	}
}

func (k *kcpFlags) profile() kcpmux.Profile {
	return kcpmux.Profile{
		NoDelay:    *k.noDelay,
		Interval:   *k.interval,
		Resend:     *k.resend,
		NC:         *k.nc,
		SndWnd:     *k.sndWnd,
		RcvWnd:     *k.rcvWnd,
		MTU:        *k.mtu,
		ACKNoDelay: *k.ackNoDelay,
	}
}

const (
	minKCPMTU = 300
	// Сегмент KCP едет внутри DTLS-записи (~37 B), obf-обёртки (до 40 B), TURN
	// ChannelData/Send (до 36 B) и IP/UDP (28 B): выше 1350 пакет режется по пути.
	maxKCPMTU = 1350
)

func validateKCP(p kcpmux.Profile) error {
	if p.NoDelay != 0 && p.NoDelay != 1 {
		return fmt.Errorf("invalid -kcp-nodelay value %d: must be 0 | 1", p.NoDelay)
	}
	if p.NC != 0 && p.NC != 1 {
		return fmt.Errorf("invalid -kcp-nc value %d: must be 0 | 1", p.NC)
	}
	if p.Interval <= 0 {
		return fmt.Errorf("invalid -kcp-interval value %d: must be positive", p.Interval)
	}
	if p.Resend < 0 {
		return fmt.Errorf("invalid -kcp-resend value %d: must be non-negative", p.Resend)
	}
	if p.SndWnd <= 0 || p.RcvWnd <= 0 {
		return fmt.Errorf("invalid KCP window %d/%d: -kcp-sndwnd and -kcp-rcvwnd must be positive", p.SndWnd, p.RcvWnd)
	}
	if p.MTU < minKCPMTU || p.MTU > maxKCPMTU {
		return fmt.Errorf("invalid -kcp-mtu value %d: must be %d..%d", p.MTU, minKCPMTU, maxKCPMTU)
	}
	return nil
}

// kcpJSON - секция "kcp" клиентского JSON; в udp-режиме игнорируется.
type kcpJSON struct {
	NoDelay    int  `json:"noDelay"`
	Interval   int  `json:"interval"`
	Resend     int  `json:"resend"`
	NC         int  `json:"nc"`
	SndWnd     int  `json:"sndWnd"`
	RcvWnd     int  `json:"rcvWnd"`
	MTU        int  `json:"mtu"`
	ACKNoDelay bool `json:"ackNoDelay"`
}

func kcpJSONFrom(p kcpmux.Profile) kcpJSON {
	return kcpJSON{
		NoDelay:    p.NoDelay,
		Interval:   p.Interval,
		Resend:     p.Resend,
		NC:         p.NC,
		SndWnd:     p.SndWnd,
		RcvWnd:     p.RcvWnd,
		MTU:        p.MTU,
		ACKNoDelay: p.ACKNoDelay,
	}
}

func (k kcpJSON) profile() kcpmux.Profile {
	return kcpmux.Profile{
		NoDelay:    k.NoDelay,
		Interval:   k.Interval,
		Resend:     k.Resend,
		NC:         k.NC,
		SndWnd:     k.SndWnd,
		RcvWnd:     k.RcvWnd,
		MTU:        k.MTU,
		ACKNoDelay: k.ACKNoDelay,
	}
}

// kcpArgs выводит только отличия от дефолта: -kcp-* валидны лишь в tcp-режиме.
func kcpArgs(p, def kcpmux.Profile) []string {
	var args []string
	addInt := func(flag string, v, d int) {
		if v != d {
			args = append(args, flag, strconv.Itoa(v))
		}
	}
	addInt("-kcp-nodelay", p.NoDelay, def.NoDelay)
	addInt("-kcp-interval", p.Interval, def.Interval)
	addInt("-kcp-resend", p.Resend, def.Resend)
	addInt("-kcp-nc", p.NC, def.NC)
	addInt("-kcp-sndwnd", p.SndWnd, def.SndWnd)
	addInt("-kcp-rcvwnd", p.RcvWnd, def.RcvWnd)
	addInt("-kcp-mtu", p.MTU, def.MTU)
	if p.ACKNoDelay != def.ACKNoDelay {
		args = append(args, "-kcp-acknodelay="+strconv.FormatBool(p.ACKNoDelay))
	}
	return args
}
