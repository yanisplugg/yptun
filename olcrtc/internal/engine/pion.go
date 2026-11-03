package engine

import (
	"fmt"
	"net"
	"runtime"

	"github.com/pion/ice/v4"
	"github.com/pion/logging"
	"github.com/pion/webrtc/v4"

	"github.com/openlibrecommunity/olcrtc/internal/protect"
)

// PionSettingsOptions describes per-engine SettingEngine differences.
type PionSettingsOptions struct {
	Resolver         protect.Lookup
	LoggerFactory    logging.LoggerFactory
	IPv4Only         bool
	ProxyDialer      bool
	DisableMulticast bool
	// DTLSProfile selects a fixed ClientHello profile for this session; empty or
	// "off" keeps the stock Pion handshake. Validated before any dialing.
	DTLSProfile DTLSProfile
}

// PionSettings applies shared network settings to a pion SettingEngine.
type PionSettings func(*webrtc.SettingEngine)

// NewPionSettings prepares protected networking and per-engine pion settings.
func NewPionSettings(opts PionSettingsOptions) (PionSettings, error) {
	if err := ValidateDTLSProfile(opts.DTLSProfile); err != nil {
		return nil, err
	}
	useProtectedNet := protect.HasProtector() || opts.Resolver != nil || runtime.GOOS == "android"
	var protectedNet *protect.ProtectedNet
	if useProtectedNet {
		var err error
		protectedNet, err = protect.NewProtectedNet(opts.Resolver)
		if err != nil {
			return nil, fmt.Errorf("protected net: %w", err)
		}
	}
	if opts.LoggerFactory == nil && !opts.IPv4Only && protectedNet == nil {
		// A chosen DTLS profile still has to reach SDK-owned settings, so only
		// the stock profile keeps the nil hook.
		if opts.DTLSProfile == "" || opts.DTLSProfile == DTLSProfileOff {
			return nil, nil //nolint:nilnil // nil hook preserves SDK-owned pion settings
		}
		return dtlsOnlySettings(opts.DTLSProfile), nil
	}

	return networkSettings(opts, protectedNet), nil
}

// networkSettings is the settings hook for a request that reached the
// partial path: base options, the DTLS profile, and the protected net when
// one was built.
func networkSettings(opts PionSettingsOptions, protectedNet *protect.ProtectedNet) func(*webrtc.SettingEngine) {
	return func(settings *webrtc.SettingEngine) {
		if opts.LoggerFactory != nil {
			settings.LoggerFactory = opts.LoggerFactory
		}
		if opts.IPv4Only {
			settings.SetNetworkTypes([]webrtc.NetworkType{webrtc.NetworkTypeUDP4})
			settings.SetIPFilter(func(ip net.IP) bool { return ip.To4() != nil })
		}
		applyDTLS(settings, opts.DTLSProfile)
		if protectedNet == nil {
			return
		}
		settings.SetNet(protectedNet)
		if opts.ProxyDialer {
			settings.SetICEProxyDialer(protect.NewProxyDialer(opts.Resolver))
		}
		if opts.DisableMulticast {
			settings.SetICEMulticastDNSMode(ice.MulticastDNSModeDisabled)
		}
	}
}

// dtlsOnlySettings is the settings hook for a profile that must reach
// SDK-owned pion settings with nothing else requested.
func dtlsOnlySettings(profile DTLSProfile) func(*webrtc.SettingEngine) {
	return func(settings *webrtc.SettingEngine) { applyDTLS(settings, profile) }
}

// applyDTLS installs a profile whose validation already succeeded; a failure
// here is a programmer error, not a dial.
func applyDTLS(settings *webrtc.SettingEngine, profile DTLSProfile) {
	if err := ApplyDTLSProfile(settings, profile); err != nil {
		panic(err)
	}
}
