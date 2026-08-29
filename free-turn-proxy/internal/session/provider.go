package session

import (
	"fmt"
	"net"
	"sync/atomic"

	"github.com/samosvalishe/free-turn-proxy/internal/config"
	"github.com/samosvalishe/free-turn-proxy/internal/logx"
	"github.com/samosvalishe/free-turn-proxy/internal/provider"
	"github.com/samosvalishe/free-turn-proxy/internal/provider/multi"
	"github.com/samosvalishe/free-turn-proxy/internal/provider/vk"
)

// buildProvider создаёт экземпляр provider.Provider в зависимости от конфигурации.
func buildProvider(
	cfg *config.Client,
	dialer net.Dialer,
	connected *atomic.Int32,
	solver vk.ManualSolverFunc,
	logger logx.Logger,
	total int,
) (provider.Provider, error) {
	switch cfg.Provider.Name {
	case config.ProviderVK:
		if len(cfg.VK.Links) == 0 {
			return nil, fmt.Errorf("vk: no links configured")
		}
		newVK := func(link string) (provider.Provider, error) {
			return vk.New(vk.Config{
				Link:            link,
				Dialer:          dialer,
				ManualOnly:      cfg.VK.ManualCaptcha,
				Platform:        string(cfg.VK.Platform),
				StreamsPerCache: cfg.VK.StreamsPerCred,
				StreamsAlive:    connected.Load,
				FingerprintSeed: cfg.ClientID,
				StatePaths:      vk.DefaultStatePaths(),
				Log:             logger,
				Debug:           cfg.Log.Debug,
			}, solver)
		}
		if len(cfg.VK.Links) == 1 {
			return newVK(cfg.VK.Links[0])
		}
		providers := make([]provider.Provider, 0, len(cfg.VK.Links))
		for i, link := range cfg.VK.Links {
			p, err := newVK(link)
			if err != nil {
				return nil, fmt.Errorf("vk provider [%d]: %w", i, err)
			}
			providers = append(providers, p)
		}
		logger.Infof("multi-provider: %d VK links, %d total streams", len(providers), total)
		return multi.New(providers), nil
	default:
		return nil, fmt.Errorf("unknown provider %q", cfg.Provider.Name)
	}
}
