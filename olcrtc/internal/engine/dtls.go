package engine

import (
	"errors"
	"fmt"

	"github.com/pion/dtls/v3"
	"github.com/pion/dtls/v3/pkg/protocol/handshake"
	"github.com/pion/webrtc/v4"
	"github.com/theodorsm/covert-dtls/pkg/fingerprints"
	"github.com/theodorsm/covert-dtls/pkg/mimicry"
)

// DTLSProfile names a fixed ClientHello profile for a session. Empty and "off"
// keep the stock Pion handshake; anything else must be a known profile id and
// is rejected before any network activity. The id is fixed for the lifetime of
// a session; changing it means creating a new session.
type DTLSProfile string

// DTLSProfileOff keeps the stock Pion ClientHello (default for old configs).
const DTLSProfileOff DTLSProfile = "off"

// DTLSProfileChrome138CompatV1 mirrors Chrome 138's ClientHello minus the five
// cipher suites the pinned Pion DTLS cannot negotiate (c009, c013, 009c, 002f,
// 0035). It is NOT the exact Chrome 138 profile: extensions and SRTP profiles
// are byte-identical to the original, the suite list is reduced. Audit and
// forced-suite negotiation results: docs/spikes/dtls/integration-results.md.
const DTLSProfileChrome138CompatV1 DTLSProfile = "chrome-linux-138-compat-v1"

// chrome138CompatUnsupportedSuites are advertised by the original fingerprint
// but absent from the pinned Pion DTLS; releasing a profile that advertises
// them is blocked by spec rule 5.3.3.
//
//nolint:gochecknoglobals // the gap list is fixed by the pinned fingerprint and Pion version
var chrome138CompatUnsupportedSuites = map[uint16]bool{
	0xc009: true, // TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA
	0xc013: true, // TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA
	0x009c: true, // TLS_RSA_WITH_AES_128_GCM_SHA256
	0x002f: true, // TLS_RSA_WITH_AES_128_CBC_SHA
	0x0035: true, // TLS_RSA_WITH_AES_256_CBC_SHA256
}

// Static validation and build errors; wrapped with context at the call site.
var (
	ErrUnknownProfile  = errors.New("dtls: unknown profile")
	ErrNoMimicPayload  = errors.New("dtls: profile has no mimic payload")
	ErrSuiteOutsideGap = errors.New("dtls: profile advertises a suite outside the known gap list")
	ErrNoUsableSuite   = errors.New("dtls: profile keeps no usable suite")
)

// ValidateDTLSProfile rejects unknown profile ids before dialing.
func ValidateDTLSProfile(profile DTLSProfile) error {
	switch profile {
	case "", DTLSProfileOff, DTLSProfileChrome138CompatV1:
		return nil
	default:
		return fmt.Errorf("%w: %q", ErrUnknownProfile, string(profile))
	}
}

// buildMimic loads the profile fingerprint and strips suites the pinned Pion
// DTLS cannot negotiate. Everything else is kept byte-identical; random, cookie
// and session id still come from the live Pion handshake on every exchange.
func buildMimic(profile DTLSProfile) (*mimicry.MimickedClientHello, error) {
	if err := ValidateDTLSProfile(profile); err != nil {
		return nil, err
	}
	if profile != DTLSProfileChrome138CompatV1 {
		return nil, fmt.Errorf("%w: %q", ErrNoMimicPayload, string(profile))
	}
	m := &mimicry.MimickedClientHello{}
	if err := m.LoadFingerprint(fingerprints.Chrome_linux_138_0_7204_94); err != nil {
		return nil, fmt.Errorf("dtls: load fingerprint: %w", err)
	}
	supported := make(map[uint16]bool, len(dtls.CipherSuites()))
	for _, suite := range dtls.CipherSuites() {
		supported[suite.ID] = true
	}
	kept := make([]uint16, 0, len(m.CipherSuiteIDs))
	for _, id := range m.CipherSuiteIDs {
		if chrome138CompatUnsupportedSuites[id] {
			continue
		}
		if !supported[id] {
			return nil, fmt.Errorf("%w: %04x", ErrSuiteOutsideGap, id)
		}
		kept = append(kept, id)
	}
	if len(kept) == 0 {
		return nil, ErrNoUsableSuite
	}
	m.CipherSuiteIDs = kept
	return m, nil
}

// ApplyDTLSProfile installs the ClientHello hook for one SettingEngine. The
// hook builds fresh mimic state for every handshake: publisher and subscriber
// PCs, simultaneous sessions and reconnect generations must never share one
// MimickedClientHello, and a single SettingEngine may be applied to several
// PCs by the LiveKit SDK. Random, cookie and session id still come from the
// live Pion handshake on every exchange. Off profiles leave the settings
// untouched.
func ApplyDTLSProfile(settings *webrtc.SettingEngine, profile DTLSProfile) error {
	if profile == "" || profile == DTLSProfileOff {
		return nil
	}
	if err := ValidateDTLSProfile(profile); err != nil {
		return err
	}
	settings.SetDTLSClientHelloMessageHook(func(ch handshake.MessageClientHello) handshake.Message {
		m, err := buildMimic(profile)
		if err != nil {
			// buildMimic only fails on a fingerprint/dependency defect; the
			// profile was validated and built once at apply time already.
			return &ch
		}
		return m.Hook(ch)
	})
	probe, err := buildMimic(profile)
	if err != nil {
		return err
	}
	profiles := make([]dtls.SRTPProtectionProfile, len(probe.SRTPProtectionProfiles))
	copy(profiles, probe.SRTPProtectionProfiles)
	settings.SetSRTPProtectionProfiles(profiles...)
	return nil
}
