package mobile

import (
	"errors"
	"fmt"
	"net"
	"time"

	"github.com/samosvalishe/free-turn-proxy/internal/config"
	"github.com/samosvalishe/free-turn-proxy/internal/logx"
	"github.com/samosvalishe/free-turn-proxy/internal/netconn"
	"github.com/samosvalishe/free-turn-proxy/internal/tunnel"
	"github.com/samosvalishe/free-turn-proxy/internal/tunnel/awg"
	"github.com/samosvalishe/free-turn-proxy/internal/tunnel/bind"
	"github.com/samosvalishe/free-turn-proxy/internal/tunnel/wgconf"
)

// TunnelSnapshot - состояние userspace-туннеля. Отдельно от Snapshot: сессия и
// туннель живут своими жизнями, и мешать их счётчики в одну структуру значило бы
// заставлять UI гадать, чьи это байты.
type TunnelSnapshot struct {
	Up bool
	// RxBytes/TxBytes - трафик внутри туннеля (после расшифровки).
	RxBytes int64
	TxBytes int64
	// HandshakeAgeSec - сколько секунд назад был последний handshake;
	// -1, если его ещё не было.
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

// tunnelParts - всё, что нужно свернуть при остановке сессии с туннелем.
type tunnelParts struct {
	backend   tunnel.Backend
	bind      *bind.SinglePeerBind
	relaySide net.PacketConn
}

// buildTunnel собирает связку "устройство <-> релей" по конфигу.
//
// Между WireGuard и релеем нет сокета: пара в памяти отдаёт один конец сессии
// как локальный пир, второй оборачивается в SinglePeerBind. Отсюда нет ни
// петли через 127.0.0.1, ни необходимости защищать сокеты устройства - своих у
// него не осталось.
func buildTunnel(cfg *config.Client, logger logx.Logger) (*tunnelParts, *tunnel.Config, error) {
	tunCfg, err := wgconf.Parse(cfg.Tunnel.Config)
	if err != nil {
		return nil, nil, err
	}
	if cfg.Tunnel.MTU > 0 {
		tunCfg.MTU = cfg.Tunnel.MTU
	}
	// Режим - выбор пользователя, а не свойство файла: в wg маскировка снимается,
	// даже если конфиг принесли от AmneziaWG.
	if cfg.Tunnel.Mode == tunnel.ModeWG && tunCfg.Amnezia.Enabled() {
		logger.Warnf("tunnel: mode=wg, параметры AmneziaWG из конфига игнорируются")
		tunCfg.Amnezia = tunnel.AmneziaParams{}
	}
	// Endpoint из файла не нужен: собеседник один и достижим через релей.
	for i := range tunCfg.Peers {
		tunCfg.Peers[i].Endpoint = ""
	}

	deviceSide, relaySide := netconn.PacketPipe(tunCfg.MTU+tunnelPipeHeadroom, 0)
	parts := &tunnelParts{
		bind:      bind.NewSinglePeerBind(deviceSide),
		relaySide: relaySide,
	}
	parts.backend = awg.New(awg.Deps{Bind: parts.bind, Log: logger})
	return parts, tunCfg, nil
}

// tunnelPipeHeadroom - запас поверх MTU туннеля: пакет обрастает заголовком
// WireGuard, а с маскировкой AmneziaWG - ещё и junk-префиксом.
const tunnelPipeHeadroom = 512

// close сворачивает туннель. Порядок обратный сборке: сначала устройство
// (оно закроет tun-дескриптор), затем Bind, затем половина пары со стороны
// устройства. Половину релея закрывает сессия по отмене ctx.
func (t *tunnelParts) close() {
	if t == nil {
		return
	}
	if t.backend != nil {
		_ = t.backend.Down()
	}
	if t.bind != nil {
		_ = t.bind.Close()
	}
}

// Хост обязан передать оторванный fd (detachFd) и не закрывать его сам.
func StartTunnel(configJSON string, tunFD int) error {
	if tunFD < 0 {
		return fmt.Errorf("bad tun fd %d", tunFD)
	}
	mu.Lock()
	defer mu.Unlock()
	if current.Load() != nil {
		return errors.New("already running")
	}
	return startLocked(configJSON, tunFD, true)
}
