package config

import (
	"github.com/samosvalishe/free-turn-proxy/internal/transport/kcptun"
	"github.com/samosvalishe/free-turn-proxy/internal/tunnel"
)

// Сырые значения флагов -transport и -mode. Client хранит их уже разобранными
// (TransportUDP bool, ProxyMode), поэтому строки живут только на входе.
const (
	TransportTCP = "tcp"
	TransportUDP = "udp"

	ModeUDP = "udp"
	ModeTCP = "tcp"
)

// Режимы клиентского резолвера (-dns-mode).
const (
	DNSModePlain = "plain"
	DNSModeDoH   = "doh"
	DNSModeAuto  = "auto"
)

// Дефолты. Единственное место, где живут литералы: их берут и CLI-флаги (как
// значения по умолчанию), и хосты, строящие конфиг из другого источника.
const (
	DefaultListen         = "127.0.0.1:9000"
	DefaultStreams        = 10
	DefaultStreamsPerCred = 10
	DefaultTransport      = TransportTCP
	DefaultMode           = ModeUDP
	DefaultDNSMode        = DNSModeAuto
	DefaultProvider       = ProviderVK
	DefaultPlatform       = PlatformDesktop
	DefaultObfProfile     = ObfProfileNone

	DefaultServerListen = "0.0.0.0:56000"
)

func defaultRaw() raw {
	return raw{
		Listen:         DefaultListen,
		Provider:       DefaultProvider,
		N:              DefaultStreams,
		StreamsPerCred: DefaultStreamsPerCred,
		Transport:      DefaultTransport,
		Mode:           DefaultMode,
		ObfProfile:     string(DefaultObfProfile),
		Platform:       string(DefaultPlatform),
		DNSMode:        DefaultDNSMode,
		Routes:         false,
		TunnelMode:     string(tunnel.ModeNone),
		TunnelMTU:      tunnel.DefaultMTU,
	}
}

// Defaults - конфиг клиента с дефолтными значениями. Не провалидирован: peer и
// ссылки знает только вызывающий. Нужен хостам, которые показывают дефолты в UI
// и не должны дублировать литералы у себя.
func Defaults() Client {
	c, err := assemble(defaultRaw())
	if err != nil {
		// Дефолты не проходят собственную сборку - это ошибка в этом файле.
		panic("config: defaults must assemble: " + err.Error())
	}
	return *c
}

// ServerDefaults - конфиг сервера с дефолтными значениями; backend (-connect)
// задаёт вызывающий.
func ServerDefaults() Server {
	return Server{
		Obf:   ObfOpts{Profile: DefaultObfProfile},
		Proxy: ProxyOpts{Mode: ProxyModeUDP, Listen: DefaultServerListen},
		KCP:   KCPOpts{Profile: kcptun.DefaultProfile()},
	}
}
