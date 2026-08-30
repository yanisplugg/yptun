package freeturn

import (
	"context"

	"github.com/samosvalishe/free-turn-proxy/internal/provider"
)

// multiProvider fans the per-stream TURN credentials across several underlying providers — e.g.
// several distinct VK calls. The single tunnel's parallel TURN streams (udprelay distributes the
// WireGuard packets across them by work-stealing) are spread over multiple calls round-robin by
// streamID, so the throughput aggregates past any per-call bandwidth cap VK enforces.
type multiProvider struct {
	providers []provider.Provider
}

func (m *multiProvider) pick(streamID int) provider.Provider {
	n := len(m.providers)
	idx := streamID % n
	if idx < 0 {
		idx += n
	}
	return m.providers[idx]
}

func (m *multiProvider) GetCredentials(ctx context.Context, streamID int) (provider.Credentials, error) {
	return m.pick(streamID).GetCredentials(ctx, streamID)
}

func (m *multiProvider) IsAuthError(err error) bool { return m.providers[0].IsAuthError(err) }

func (m *multiProvider) HandleAuthError(streamID int) bool {
	return m.pick(streamID).HandleAuthError(streamID)
}

func (m *multiProvider) ResetErrors(streamID int) { m.pick(streamID).ResetErrors(streamID) }

func (m *multiProvider) DropCredentials(streamID int) { m.pick(streamID).DropCredentials(streamID) }

func (m *multiProvider) Name() string { return m.providers[0].Name() }

// BackoffUntilUnix reports the earliest moment ANY provider is ready (min positive deadline),
// or 0 if at least one provider is ready now — so one throttled call doesn't stall the others.
func (m *multiProvider) BackoffUntilUnix() int64 {
	var best int64
	for _, p := range m.providers {
		b := p.BackoffUntilUnix()
		if b == 0 {
			return 0
		}
		if best == 0 || b < best {
			best = b
		}
	}
	return best
}
