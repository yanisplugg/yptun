// Package config описывает опции клиента и сервера и превращает в них любой
// источник настроек.
//
// Раскладка пакета:
//
//   - config.go   - типы опций (этот файл);
//   - defaults.go - все литералы дефолтов, единственное их место;
//   - raw.go      - сырые значения источника и сборка raw -> Client;
//   - validate.go - смысловые правила поверх собранного Client;
//   - cliflags.go - парсер CLI-флагов, тонкая обёртка над raw/assemble.
//
// Сборка и валидация без побочных эффектов: сеть, DNS и состояние процесса не
// трогаются - это ответственность вызывающего.
//
// Опции сгруппированы по доменам (TURN, Obf, Proxy, VK, DNS, Log) - структура
// зеркалит концептуальные слои прокси.
package config

import (
	"time"

	"github.com/samosvalishe/free-turn-proxy/internal/transport/kcptun"
	"github.com/samosvalishe/free-turn-proxy/internal/tunnel"
)

// ProxyMode выбирает payload прикладного уровня, который идёт через TURN-туннель.
// На клиенте доступны все три; на сервере только UDP / TCPFwd
// (bond определяется автоматически per-stream по magic-префиксу).
type ProxyMode string

const (
	ProxyModeUDP        ProxyMode = "udp"         // -mode udp (default): UDP-релей пакетов (WireGuard)
	ProxyModeTCPFwd     ProxyMode = "tcpfwd"      // -mode tcp: TCP-форвардер через smux
	ProxyModeTCPFwdBond ProxyMode = "tcpfwd-bond" // -mode tcp -bond: bond TCP по N smux-сессиям
)

// TURNOpts - опции TURN-сервера (куда и как подключаться).
type TURNOpts struct {
	Host         string // -turn: переопределить IP/host TURN-сервера
	Port         string // -port: переопределить порт TURN
	TransportUDP bool   // -transport udp: подключение к TURN по UDP (по умолчанию TCP/TLS)
	N            int    // -n: число TURN-потоков (только клиент)
}

// ObfProfile выбирает wire-профиль обфускации TURN-payload.
// Профили живут в internal/wire/<profile>/ - сейчас только rtpopus,
// под добавление новых (rtph264, vp8 и т.д.).
type ObfProfile string

const (
	ObfProfileNone     ObfProfile = "none"     // обфускация отключена
	ObfProfileRTPOpus  ObfProfile = "rtpopus"  // RTP/opus + ChaCha20-Poly1305 AEAD
	ObfProfileRTPOpus2 ObfProfile = "rtpopus2" // rtpopus + RTP header extension (мимикрия под современный WebRTC)
	ObfProfileRTPOpus3 ObfProfile = "rtpopus3" // rtpopus2 + abs-send-time + VAD + loss simulation + variable ts
)

// ObfOpts - опции обфускации TURN-payload.
type ObfOpts struct {
	Profile ObfProfile    // -obf-profile: none (default) | rtpopus | rtpopus2 | rtpopus3
	Key     []byte        // -obf-key (декодированный): 32-байтовый общий ключ; nil если Profile=none
	GenKey  bool          // -gen-obf-key: напечатать новый ключ и выйти
	Timing  time.Duration // -obf-timing: межпакетная задержка (RTP-мимикрия); 0=выкл
}

// Enabled возвращает true когда выбран реальный профиль обфускации.
func (o ObfOpts) Enabled() bool { return o.Profile != ObfProfileNone }

// ProxyOpts - опции прокси прикладного уровня.
type ProxyOpts struct {
	Mode    ProxyMode // udp | tcpfwd | tcpfwd-bond (сервер: udp | tcpfwd)
	Listen  string    // -listen: локальный bind (клиент: WG/TCP entry; сервер: TURN entry)
	Connect string    // -connect: backend (только сервер)
	Peer    string    // -peer: адрес серверного прокси, куда дозванивается клиент (только клиент)
}

// Platform выбирает класс устройства персоны (мобильность UA/device/client hints).
type Platform string

const (
	PlatformDesktop Platform = "desktop"
	PlatformMobile  Platform = "mobile"
)

// VKOpts - опции VK-учёток и captcha (только клиент, провайдер "vk").
type VKOpts struct {
	Links          []string // -links (нормализованные join-коды); несколько = больше стримов
	StreamsPerCred int      // -streams-per-cred
	ManualCaptcha  bool     // -manual-captcha
	Platform       Platform // -platform: desktop | mobile
}

// ProviderOpts выбирает реализацию provider.Provider.
type ProviderOpts struct {
	Name string // -provider: vk (default)
}

// Известные имена провайдеров.
const (
	ProviderVK = "vk"
)

// DNSOpts - опции DNS-резолвинга (только клиент).
type DNSOpts struct {
	Mode    string   // -dns-mode: plain | doh | auto
	Servers []string // -dns-servers (через запятую); nil если флаг пуст
}

// LogOpts - опции логирования.
type LogOpts struct {
	Debug bool // -debug
}

// KCPOpts - параметры KCP-туннеля, хардкодятся из DefaultProfile/FEC{}.
type KCPOpts struct {
	Profile kcptun.Profile
	FEC     kcptun.FEC
}

// TunnelOpts - userspace WireGuard/AmneziaWG поверх сессии. Опции хоста, у CLI
// эквивалента нет: там туннель поднимает внешний клиент, ходящий на Proxy.Listen.
//
// Config хранится текстом и разбирается в tunnel/wgconf на стороне хоста -
// config не смотрит внутрь и не зависит от реализации туннеля.
type TunnelOpts struct {
	Mode   tunnel.Mode // none (default) | wg | awg
	Config string      // конфиг в формате wg-quick
	MTU    int
}

// Enabled сообщает, что хост просит поднять туннель внутри процесса.
func (t TunnelOpts) Enabled() bool { return t.Mode != "" && t.Mode != tunnel.ModeNone }

// Client - собранные и провалидированные опции клиента.
type Client struct {
	TURN     TURNOpts
	Obf      ObfOpts
	Proxy    ProxyOpts
	Provider ProviderOpts
	VK       VKOpts
	DNS      DNSOpts
	Log      LogOpts
	KCP      KCPOpts
	Tunnel   TunnelOpts
	ClientID string
	SubURL   string
	Routes   bool // -routes: автоматическое управление маршрутами к TURN-серверам
}

// Server - собранные и провалидированные опции сервера.
type Server struct {
	Obf         ObfOpts
	Proxy       ProxyOpts
	Log         LogOpts
	KCP         KCPOpts
	ClientsFile string // -clients-file
}
