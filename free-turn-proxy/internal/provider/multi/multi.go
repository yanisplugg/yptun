// Package multi объединяет несколько provider.Provider с балансировкой round-robin.
package multi

import (
	"context"
	"fmt"

	"github.com/samosvalishe/free-turn-proxy/internal/provider"
)

// Provider распределяет запросы учетных данных по пулу провайдеров.
type Provider struct {
	providers []provider.Provider
	n         int
}

func New(providers []provider.Provider) *Provider {
	if len(providers) == 0 {
		panic("multi: empty providers")
	}
	return &Provider{providers: providers, n: len(providers)}
}

func (m *Provider) providerFor(streamID int) (provider.Provider, int) {
	idx := (streamID - 1) % m.n
	innerID := ((streamID - 1) / m.n) + 1
	return m.providers[idx], innerID
}

func (m *Provider) GetCredentials(ctx context.Context, streamID int) (provider.Credentials, error) {
	p, innerID := m.providerFor(streamID)
	return p.GetCredentials(ctx, innerID)
}

func (m *Provider) IsAuthError(err error) bool {
	return m.providers[0].IsAuthError(err)
}

func (m *Provider) HandleAuthError(streamID int) bool {
	p, innerID := m.providerFor(streamID)
	return p.HandleAuthError(innerID)
}

func (m *Provider) ResetErrors(streamID int) {
	p, innerID := m.providerFor(streamID)
	p.ResetErrors(innerID)
}

func (m *Provider) DropCredentials(streamID int) {
	p, innerID := m.providerFor(streamID)
	p.DropCredentials(innerID)
}

func (m *Provider) BackoffUntilUnix() int64 {
	var until int64
	for _, p := range m.providers {
		if v := p.BackoffUntilUnix(); v > until {
			until = v
		}
	}
	return until
}

func (m *Provider) Name() string {
	return fmt.Sprintf("multi(%d)", m.n)
}
