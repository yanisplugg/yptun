package xraybridge

import (
	"bytes"
	"strings"
	"testing"

	"github.com/xtls/xray-core/infra/conf/serial"
)

// The vendored xray-core carries three local patches (see ../go.mod). Each one exists because
// upstream turned something this client's configs rely on into a hard error, so the check that
// matters is simply: does a config carrying it still load?
func loadConfig(t *testing.T, json string) {
	t.Helper()
	if _, err := serial.LoadJSONConfig(bytes.NewReader([]byte(json))); err != nil {
		t.Fatalf("config rejected: %v", err)
	}
}

const outboundEnvelope = `{"inbounds":[{"tag":"in","protocol":"socks","port":10800,"listen":"127.0.0.1",
"settings":{"udp":true}}],"outbounds":[%s]}`

func envelope(outbounds string) string {
	return strings.Replace(outboundEnvelope, "%s", outbounds, 1)
}

// Patch 1: a plain VLESS/Trojan outbound to a PUBLIC address with no TLS is the routine inner leg of
// a cascade whose outer leg carries the encryption. Upstream #6303 rejects it outright.
func TestPlainVlessToPublicAddressIsAccepted(t *testing.T) {
	loadConfig(t, envelope(`{"tag":"exit","protocol":"vless","settings":{"address":"1.2.3.4","port":443,
"id":"b831381d-6324-4d53-ad4f-8cda48b30811","encryption":"none"},
"streamSettings":{"network":"tcp","security":"none"}}`))
}

func TestPlainTrojanToPublicAddressIsAccepted(t *testing.T) {
	loadConfig(t, envelope(`{"tag":"exit","protocol":"trojan","settings":{"address":"1.2.3.4","port":443,
"password":"pw"},"streamSettings":{"network":"tcp","security":"none"}}`))
}

// Patch 2: panels still emitting the pre-26.7.28 xhttp "sessionPlacement"/"sessionKey" spellings must
// keep them instead of silently falling back to placement "path".
func TestLegacyXhttpSessionKeysAreHonoured(t *testing.T) {
	loadConfig(t, envelope(`{"tag":"exit","protocol":"vless","settings":{"address":"1.2.3.4","port":443,
"id":"b831381d-6324-4d53-ad4f-8cda48b30811","encryption":"none"},
"streamSettings":{"network":"xhttp","security":"none",
"xhttpSettings":{"path":"/x","sessionPlacement":"query","sessionKey":"sid"}}}`))
}

// Patch 3: 26.9.8 removed outbound "proxySettings"; raw configs in the wild still carry it, so the tag
// is migrated onto sockopt.dialerProxy rather than failing the whole config.
func TestLegacyProxySettingsIsMigrated(t *testing.T) {
	loadConfig(t, envelope(`{"tag":"base","protocol":"freedom","settings":{}},
{"tag":"exit","protocol":"vless","settings":{"address":"1.2.3.4","port":443,
"id":"b831381d-6324-4d53-ad4f-8cda48b30811","encryption":"none"},
"streamSettings":{"network":"tcp","security":"tls"},"proxySettings":{"tag":"base"}}`))
}

// A proxySettings block with no usable tag has nothing to migrate: still a config error.
func TestProxySettingsWithoutTagStillFails(t *testing.T) {
	_, err := serial.LoadJSONConfig(bytes.NewReader([]byte(envelope(
		`{"tag":"exit","protocol":"freedom","settings":{},"proxySettings":{}}`))))
	if err == nil {
		t.Fatal("expected an error for proxySettings without a tag")
	}
}
