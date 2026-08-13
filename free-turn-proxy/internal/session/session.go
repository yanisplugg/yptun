// Package session - рантайм одной клиентской сессии: провайдер TURN-реквизитов,
// DNS, DTLS-диалеры и выбранный режим релея (udprelay / tcpfwd).
//
// Общий для cmd/client, пакета mobile и любого будущего потребителя: хост даёт
// конфиг, логгер и контекст, а взамен получает блокирующий Run и Snapshot для
// UI. Пакет не знает ни про CLI, ни про gomobile - ничего из os.Exit, флагов и
// платформенных типов здесь быть не должно.
//
// Одна активная сессия на процесс: dnsdial.InstallGlobalResolver и
// netctl.SetControl - process-global, две параллельные сессии затирали бы
// настройки друг друга.
package session

import (
	"context"
	"errors"
	"fmt"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/samosvalishe/free-turn-proxy/internal/client/dnsdial"
	"github.com/samosvalishe/free-turn-proxy/internal/config"
	"github.com/samosvalishe/free-turn-proxy/internal/logx"
	"github.com/samosvalishe/free-turn-proxy/internal/provider"
	"github.com/samosvalishe/free-turn-proxy/internal/provider/vk"
	"github.com/samosvalishe/free-turn-proxy/internal/proxy/bondclient"
	"github.com/samosvalishe/free-turn-proxy/internal/proxy/tcpfwd"
	"github.com/samosvalishe/free-turn-proxy/internal/proxy/udprelay"
	"github.com/samosvalishe/free-turn-proxy/internal/routemgr"
	"github.com/samosvalishe/free-turn-proxy/internal/stats"
	"github.com/samosvalishe/free-turn-proxy/internal/transport/dtlsdial"
)

// Phase - стадия подключения сессии.
type Phase string

const (
	PhaseIdle       Phase = "idle"
	PhaseConnecting Phase = "connecting"
	PhaseConnected  Phase = "connected"
	// PhaseCaptcha - пользователь решает captcha вручную. Отдельная стадия,
	// чтобы UI не показывал ошибку, а watchdog не считал это зависанием.
	PhaseCaptcha Phase = "captcha"
	// PhaseError выставляет хост по ошибке из Run: сама сессия к этому моменту
	// уже завершена.
	PhaseError Phase = "error"
)

var (
	ErrAlreadyRun = errors.New("session: already run")
	// ErrConnectTimeout - ни один поток не поднялся за ConnectTimeout.
	ErrConnectTimeout = errors.New("session: connect timeout: no stream connected within the deadline")
)

const (
	defaultStatusInterval       = 500 * time.Millisecond
	defaultRateInterval         = time.Second
	defaultUDPHandshakeTimeout  = 20 * time.Second
	defaultTCPHandshakeTimeout  = 30 * time.Second
	defaultHandshakeConcurrency = 3
)

// Options - тайминги и переключатели рантайма. Нулевое значение поля означает
// "дефолт пакета"; ConnectTimeout=0 - особый случай, см. поле.
type Options struct {
	// ConnectTimeout ограничивает ожидание первого поднявшегося стрима: если за
	// это время ни один не поднялся, Run возвращает ошибку вместо вечного
	// connecting. Падение отдельного стрима не считается - таймаут снимается,
	// пока жив хотя бы один. 0 отключает watchdog (поведение CLI: клиент ждёт
	// столько, сколько попросили).
	ConnectTimeout time.Duration

	StatusInterval       time.Duration
	RateInterval         time.Duration
	UDPHandshakeTimeout  time.Duration
	TCPHandshakeTimeout  time.Duration
	HandshakeConcurrency int

	// Traffic включает подсчёт байт и скорости. Нужен UI; CLI без него не
	// платит за атомики на пути пакета.
	Traffic bool
}

func (o Options) withDefaults() Options {
	if o.StatusInterval <= 0 {
		o.StatusInterval = defaultStatusInterval
	}
	if o.RateInterval <= 0 {
		o.RateInterval = defaultRateInterval
	}
	if o.UDPHandshakeTimeout <= 0 {
		o.UDPHandshakeTimeout = defaultUDPHandshakeTimeout
	}
	if o.TCPHandshakeTimeout <= 0 {
		o.TCPHandshakeTimeout = defaultTCPHandshakeTimeout
	}
	if o.HandshakeConcurrency <= 0 {
		o.HandshakeConcurrency = defaultHandshakeConcurrency
	}
	return o
}

// Deps - зависимости хоста. Всё, кроме Logger, опционально.
type Deps struct {
	Logger logx.Logger
	// Observer получает переходы состояния. nil - хост опрашивает Snapshot.
	Observer Observer
	// Solver - ручной решатель captcha. nil отключает ручной fallback.
	Solver vk.ManualSolverFunc
	// CaptchaActive сообщает, что прямо сейчас идёт ручное решение captcha:
	// на это время watchdog приостанавливает отсчёт ConnectTimeout, а Snapshot
	// отдаёт PhaseCaptcha. nil - captcha никогда не активна.
	CaptchaActive func() bool
	// LocalPipe подменяет локальный UDP-сокет каналом в памяти: так туннель,
	// поднятый внутри процесса, соединяется с релеем напрямую. nil - обычный
	// bind на cfg.Proxy.Listen. Только для udp-режима; сессия закрывает его сама.
	LocalPipe net.PacketConn
	Options   Options
}

// Snapshot - консистентный срез состояния сессии для UI: и стадия подключения,
// и статистика трафика. Один вызов на тик вместо нескольких геттеров - хост не
// ловит рассогласование от порядка чтения.
type Snapshot struct {
	Phase   Phase
	Streams int
	Total   int
	Err     string
	TxTotal uint64
	RxTotal uint64
	TxRate  int64
	RxRate  int64
}

type statusInfo struct {
	phase   Phase
	streams int
	total   int
	err     string
}

// Session - одна клиентская сессия. Создаётся New, отрабатывает один Run,
// повторно не используется.
type Session struct {
	cfg     *config.Client
	deps    Deps
	opts    Options
	total   int
	traffic *traffic

	connected atomic.Int32
	status    atomic.Pointer[statusInfo]
	started   atomic.Bool
}

// Сеть не трогает: блокирующие вызовы происходят в Run.
func New(cfg *config.Client, deps Deps) (*Session, error) {
	if cfg == nil {
		return nil, errors.New("session: nil config")
	}
	if deps.Logger == nil {
		deps.Logger = logx.Nop()
	}

	// Несколько ссылок расширяют пул: каждая даёт cfg.TURN.N стримов, все
	// объединяются в общий пул (больше параллельных TURN-аллокаций).
	total := cfg.TURN.N * max(len(cfg.VK.Links), 1)

	s := &Session{
		cfg:   cfg,
		deps:  deps,
		opts:  deps.Options.withDefaults(),
		total: total,
	}
	if s.opts.Traffic {
		s.traffic = newTraffic()
	}
	// Созданная сессия уже "подключается": хост читает Snapshot сразу после New,
	// и показывать ему idle до первой строки Run было бы враньём. Кладётся
	// молча - наблюдателя на этот момент ещё нет, первое событие даст Run.
	s.status.Store(&statusInfo{phase: PhaseConnecting, total: total})
	return s, nil
}

// Блокирует до отмены ctx (возвращает nil) или фатальной ошибки.
func (s *Session) Run(ctx context.Context) (err error) {
	if !s.started.CompareAndSwap(false, true) {
		return ErrAlreadyRun
	}
	// Первое событие сессии приходит всегда, даже если состояние совпало с
	// заготовленным в New: для наблюдателя это начало жизни, а не повтор.
	s.publish(&statusInfo{phase: PhaseConnecting, total: s.total}, true)
	defer func() {
		if err != nil {
			s.setStatus(PhaseError, 0, err.Error())
			return
		}
		s.setStatus(PhaseIdle, 0, "")
	}()

	log := s.deps.Logger
	dnsdial.SetLogger(log)
	if s.cfg.DNS.Servers != nil {
		dnsdial.SetUDPDNSServers(s.cfg.DNS.Servers)
		log.Infof("[DNS] using custom UDP servers: %v", s.cfg.DNS.Servers)
	}
	appDialer := dnsdial.AppDialer(s.cfg.DNS.Mode)
	dnsdial.InstallGlobalResolver(s.cfg.DNS.Mode)

	prov, err := buildProvider(s.cfg, appDialer, &s.connected, s.deps.Solver, log, s.total)
	if err != nil {
		return fmt.Errorf("provider init: %w", err)
	}
	log.Infof("provider=%s", prov.Name())
	if s.cfg.Obf.Enabled() {
		log.Infof("OBF profile=%s: peer server must use matching -obf-profile and -obf-key", s.cfg.Obf.Profile)
	}

	peer, err := net.ResolveUDPAddr("udp", s.cfg.Proxy.Peer)
	if err != nil {
		return fmt.Errorf("resolve peer addr: %w", err)
	}

	runCtx, cancel := context.WithCancel(ctx)
	defer cancel()

	var bg sync.WaitGroup
	if s.traffic != nil {
		bg.Add(1)
		go func() {
			defer bg.Done()
			s.traffic.rateMeter(runCtx, s.opts.RateInterval)
		}()
	}

	var watchdogErr error
	bg.Add(1)
	go func() {
		defer bg.Done()
		watchdogErr = s.watch(runCtx, cancel)
	}()

	relayErr := s.relay(runCtx, prov, peer)
	// Причину смотрим до cancel: собственный defer сделал бы любой выход
	// "отменённым" и проглотил бы настоящую ошибку релея.
	stopped := runCtx.Err() != nil
	cancel()
	bg.Wait()

	if relayErr != nil && !stopped {
		return relayErr
	}
	// Watchdog отменяет контекст сам, поэтому его ошибка приходит именно так.
	return watchdogErr
}

// Snapshot - текущее состояние сессии. Безопасен из любой горутины.
func (s *Session) Snapshot() Snapshot {
	st := s.status.Load()
	if st == nil {
		st = &statusInfo{phase: PhaseIdle}
	}
	snap := Snapshot{Phase: st.phase, Streams: st.streams, Total: st.total, Err: st.err}
	if s.traffic != nil {
		snap.TxTotal, snap.RxTotal = s.traffic.stats.Counters()
		snap.TxRate = s.traffic.txRate.Load()
		snap.RxRate = s.traffic.rxRate.Load()
	}
	return snap
}

// setStatus публикует состояние и, если оно изменилось, дёргает Observer.
func (s *Session) setStatus(phase Phase, streams int, errMsg string) {
	s.publish(&statusInfo{phase: phase, streams: streams, total: s.total, err: errMsg}, false)
}

// publish кладёт состояние и уведомляет наблюдателя. force шлёт событие даже
// при совпадении с предыдущим.
//
// Вызывается из одной горутины за раз: старт и терминальный статус - из Run,
// промежуточные - из watch, который к моменту терминального уже завершён.
func (s *Session) publish(next *statusInfo, force bool) {
	prev := s.status.Swap(next)
	if !force && prev != nil && *prev == *next {
		return
	}
	if s.deps.Observer != nil {
		s.deps.Observer.OnPhase(next.phase, next.streams, next.total, next.err)
	}
}

func (s *Session) captchaActive() bool {
	return s.deps.CaptchaActive != nil && s.deps.CaptchaActive()
}

// watch публикует стадию подключения и следит за ConnectTimeout. При срабатывании
// отменяет сессию через cancel и возвращает ошибку - её Run отдаёт наружу.
func (s *Session) watch(ctx context.Context, cancel context.CancelFunc) error {
	tick := time.NewTicker(s.opts.StatusInterval)
	defer tick.Stop()

	deadline := time.Now().Add(s.opts.ConnectTimeout)
	everConnected := false

	for {
		select {
		case <-ctx.Done():
			return nil
		case <-tick.C:
			n := int(s.connected.Load())
			if n > 0 {
				everConnected = true
			}

			if s.captchaActive() {
				deadline = time.Now().Add(s.opts.ConnectTimeout)
				s.setStatus(PhaseCaptcha, n, "")
				continue
			}

			phase := PhaseConnecting
			if n > 0 {
				phase = PhaseConnected
			}
			s.setStatus(phase, n, "")

			if s.opts.ConnectTimeout > 0 && !everConnected && time.Now().After(deadline) {
				cancel()
				return fmt.Errorf("%w (timeout=%s)", ErrConnectTimeout, s.opts.ConnectTimeout)
			}
		}
	}
}

func (s *Session) relay(ctx context.Context, prov provider.Provider, peer *net.UDPAddr) error {
	log := s.deps.Logger
	getCreds := func(ctx context.Context, streamID int) (string, string, []string, error) {
		c, err := prov.GetCredentials(ctx, streamID)
		if err != nil {
			return "", "", nil, err
		}
		return c.User, c.Pass, c.ServerAddrs, nil
	}

	// Управление маршрутами: создаём host-route для IP TURN-серверов через
	// реальный шлюз, чтобы VPN не перехватывал TURN-трафик.
	var routeCallback func(net.IP)
	if s.cfg.Routes && !s.cfg.Tunnel.Enabled() {
		rm, rmErr := routemgr.New(log)
		if rmErr != nil {
			log.Warnf("route manager disabled: %v", rmErr)
		} else if rm != nil {
			defer func() {
				_ = rm.Close()
			}()
			routeCallback = rm.Callback()
			log.Infof("route manager: gateway=%s", rm.Gateway())
		}
	}

	if s.cfg.Proxy.Mode != config.ProxyModeUDP {
		dialer := &dtlsdial.Dialer{
			HandshakeTimeout: s.opts.TCPHandshakeTimeout,
			HandshakeSem:     make(chan struct{}, s.opts.HandshakeConcurrency),
		}
		bond := &bondclient.Handler{Deps: bondclient.Deps{Log: log}}
		deps := &tcpfwd.Deps{
			DTLSDialer:       dialer,
			Log:              log,
			BondHandler:      bond.Handle,
			ConnectedStreams: &s.connected,
			OnTURNServer:     routeCallback,
		}
		params := &tcpfwd.Params{
			Host:         s.cfg.TURN.Host,
			Port:         s.cfg.TURN.Port,
			TransportUDP: s.cfg.TURN.TransportUDP,
			Profile:      string(s.cfg.Obf.Profile),
			ObfKey:       s.cfg.Obf.Key,
			GetCreds:     tcpfwd.GetCredsFunc(getCreds),
			KCPProfile:   s.cfg.KCP.Profile,
			KCPFEC:       s.cfg.KCP.FEC,
			ClientID:     s.cfg.ClientID,
			TrafficStats: s.trafficStats(),
		}
		return tcpfwd.Run(ctx, deps, params, peer, s.cfg.Proxy.Listen, s.total, s.cfg.Proxy.Mode == config.ProxyModeTCPFwdBond)
	}

	local, err := s.localConn(ctx)
	if err != nil {
		return err
	}

	dialer := &dtlsdial.Dialer{
		HandshakeTimeout: s.opts.UDPHandshakeTimeout,
		HandshakeSem:     make(chan struct{}, s.opts.HandshakeConcurrency),
	}
	params := &udprelay.Params{
		Host:         s.cfg.TURN.Host,
		Port:         s.cfg.TURN.Port,
		TransportUDP: s.cfg.TURN.TransportUDP,
		Profile:      string(s.cfg.Obf.Profile),
		ObfKey:       s.cfg.Obf.Key,
		ObfTiming:    s.cfg.Obf.Timing,
		GetCreds:     udprelay.GetCredsFunc(getCreds),
		ClientID:     s.cfg.ClientID,
		TrafficStats: s.trafficStats(),
	}
	return udprelay.Run(ctx, dialer, prov, log, &s.connected, routeCallback, params, peer, local, s.total)
}

// localConn открывает канал до локального пира. Обычно это UDP-сокет на
// cfg.Proxy.Listen, куда ходит внешний WireGuard. Если хост дал LocalPipe -
// туннель живёт в этом же процессе, и петля через 127.0.0.1 не нужна.
func (s *Session) localConn(ctx context.Context) (net.PacketConn, error) {
	if s.deps.LocalPipe != nil {
		s.deps.Logger.Infof("local peer: in-process pipe (no loopback socket)")
		return s.deps.LocalPipe, nil
	}
	conn, err := (&net.ListenConfig{}).ListenPacket(ctx, "udp", s.cfg.Proxy.Listen)
	if err != nil {
		return nil, fmt.Errorf("udprelay listen %s: %w", s.cfg.Proxy.Listen, err)
	}
	return conn, nil
}

func (s *Session) trafficStats() *stats.Stats {
	if s.traffic == nil {
		return nil
	}
	return s.traffic.stats
}
