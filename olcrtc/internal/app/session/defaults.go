package session

import (
	"github.com/openlibrecommunity/olcrtc/internal/auth"
	"github.com/openlibrecommunity/olcrtc/internal/control"
)

// ApplyDefaults applies provider, transport, and liveness defaults in that order.
func ApplyDefaults(cfg Config) Config {
	cfg = ApplyProviderDefaults(cfg)
	return ApplyLivenessDefaults(ApplyTransportDefaults(cfg))
}

// ApplyProviderDefaults fills engine and URL from the selected provider.
func ApplyProviderDefaults(cfg Config) Config {
	if cfg.Provider == providerNone || cfg.Provider == "" {
		return cfg
	}
	provider, err := auth.Get(cfg.Provider)
	if err != nil {
		return cfg
	}
	if cfg.Engine == "" {
		cfg.Engine = provider.Engine()
	}
	if cfg.URL == "" {
		cfg.URL = provider.DefaultServiceURL()
	}
	return cfg
}

// ApplyTransportDefaults fills documented transport defaults.
func ApplyTransportDefaults(cfg Config) Config {
	switch cfg.Transport {
	case transportVideo:
		return applyVideoDefaults(cfg)
	case transportVP8:
		return applyVP8Defaults(cfg)
	case transportSEI:
		return applySEIDefaults(cfg)
	default:
		return cfg
	}
}

// ApplyLivenessDefaults fills documented control-stream liveness defaults.
func ApplyLivenessDefaults(cfg Config) Config {
	if cfg.LivenessInterval == "" {
		cfg.LivenessInterval = control.DefaultInterval.String()
	}
	if cfg.LivenessTimeout == "" {
		cfg.LivenessTimeout = control.DefaultTimeout.String()
	}
	if cfg.LivenessFailures == 0 {
		cfg.LivenessFailures = control.DefaultFailures
	}
	return cfg
}

func applyVideoDefaults(cfg Config) Config {
	if cfg.Video.Codec == "" {
		cfg.Video.Codec = videoCodecQRCode
	}
	width := defaultVideoWidth
	if cfg.Video.Codec == videoCodecTile {
		width = defaultVideoHeight
	}
	if cfg.Video.Width == 0 {
		cfg.Video.Width = width
	}
	if cfg.Video.Height == 0 {
		cfg.Video.Height = defaultVideoHeight
	}
	if cfg.Video.FPS == 0 {
		cfg.Video.FPS = defaultVideoFPS
	}
	if cfg.Video.QRRecovery == "" {
		cfg.Video.QRRecovery = defaultVideoQRRecovery
	}
	return cfg
}

func applyVP8Defaults(cfg Config) Config {
	if cfg.VP8.FPS == 0 {
		cfg.VP8.FPS = defaultVP8FPS
	}
	if cfg.VP8.BatchSize == 0 {
		cfg.VP8.BatchSize = defaultVP8BatchSize
	}
	return cfg
}

func applySEIDefaults(cfg Config) Config {
	if cfg.SEI.FPS == 0 {
		cfg.SEI.FPS = defaultSEIFPS
	}
	if cfg.SEI.BatchSize == 0 {
		cfg.SEI.BatchSize = defaultSEIBatchSize
	}
	if cfg.SEI.FragmentSize == 0 {
		cfg.SEI.FragmentSize = defaultSEIFragmentSize
	}
	if cfg.SEI.AckTimeoutMS == 0 {
		cfg.SEI.AckTimeoutMS = defaultSEIAckTimeoutMS
	}
	return cfg
}
