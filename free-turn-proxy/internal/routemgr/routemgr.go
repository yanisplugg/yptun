package routemgr

import (
	"net"
	"sync"

	"github.com/samosvalishe/free-turn-proxy/internal/logx"
)

type Manager struct {
	gateway string
	mu      sync.Mutex
	added   map[string]struct{}
	log     logx.Logger
}

func New(log logx.Logger) (*Manager, error) {
	gw, err := discoverGateway()
	if err != nil {
		return nil, err
	}
	if gw == "" {
		return nil, nil // unsupported platform
	}
	return &Manager{
		gateway: gw,
		added:   make(map[string]struct{}),
		log:     logx.OrNop(log),
	}, nil
}

func (m *Manager) Gateway() string {
	if m == nil {
		return ""
	}
	return m.gateway
}

func (m *Manager) EnsureRouteToTURN(ip net.IP) {
	if m == nil {
		return
	}
	v4 := ip.To4()
	if v4 == nil {
		return // IPv6 not supported
	}
	key := v4.String()

	m.mu.Lock()
	if _, ok := m.added[key]; ok {
		m.mu.Unlock()
		return
	}
	m.added[key] = struct{}{}
	m.mu.Unlock()

	m.log.Infof("Ensuring route to %s/32 via %s", key, m.gateway)
	if err := addRoute(key, m.gateway); err != nil {
		m.log.Warnf("failed to add route to %s: %v", key, err)
		m.mu.Lock()
		delete(m.added, key)
		m.mu.Unlock()
	}
}

func (m *Manager) Close() error {
	if m == nil {
		return nil
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	var firstErr error
	for ip := range m.added {
		m.log.Infof("Removing route to %s/32", ip)
		if err := delRoute(ip); err != nil {
			m.log.Warnf("failed to remove route to %s: %v", ip, err)
			if firstErr == nil {
				firstErr = err
			}
		}
	}
	m.added = nil
	return firstErr
}

func (m *Manager) Callback() func(net.IP) {
	return m.EnsureRouteToTURN
}
