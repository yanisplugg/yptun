package config

import (
	"io"
	"reflect"
	"testing"
)

func TestDefaultsAssemble(t *testing.T) {
	d := Defaults()
	if d.Proxy.Listen != DefaultListen {
		t.Errorf("Listen = %q, want %q", d.Proxy.Listen, DefaultListen)
	}
	if d.TURN.N != DefaultStreams || d.VK.StreamsPerCred != DefaultStreamsPerCred {
		t.Errorf("N = %d, StreamsPerCred = %d", d.TURN.N, d.VK.StreamsPerCred)
	}
	if d.TURN.TransportUDP {
		t.Error("TransportUDP must be false for default -transport tcp")
	}
	if d.Proxy.Mode != ProxyModeUDP {
		t.Errorf("Mode = %q, want %q", d.Proxy.Mode, ProxyModeUDP)
	}
	if d.DNS.Mode != DefaultDNSMode || d.VK.Platform != DefaultPlatform {
		t.Errorf("DNS.Mode = %q, Platform = %q", d.DNS.Mode, d.VK.Platform)
	}
	if d.Obf.Enabled() || d.Obf.Key != nil {
		t.Errorf("Obf must be off by default: %+v", d.Obf)
	}
}

// Дефолты флагов и Defaults() обязаны совпадать: расхождение означает, что
// литерал где-то продублирован в обход defaults.go.
func TestFlagDefaultsMatchDefaults(t *testing.T) {
	parsed, err := ParseClient(validClientArgs(), io.Discard)
	if err != nil {
		t.Fatal(err)
	}

	want := Defaults()
	want.Proxy.Peer = parsed.Proxy.Peer
	want.VK.Links = parsed.VK.Links

	if !reflect.DeepEqual(*parsed, want) {
		t.Errorf("ParseClient defaults differ from Defaults():\n got %+v\nwant %+v", *parsed, want)
	}
}

func TestServerDefaults(t *testing.T) {
	d := ServerDefaults()
	if d.Proxy.Listen != DefaultServerListen {
		t.Errorf("Listen = %q, want %q", d.Proxy.Listen, DefaultServerListen)
	}
	if d.Proxy.Mode != ProxyModeUDP || d.Obf.Enabled() {
		t.Errorf("unexpected server defaults: %+v", d)
	}
}

func TestParseServerDefaultsMatchServerDefaults(t *testing.T) {
	parsed, err := ParseServer([]string{"-connect", "127.0.0.1:51820"}, io.Discard)
	if err != nil {
		t.Fatal(err)
	}
	want := ServerDefaults()
	want.Proxy.Connect = parsed.Proxy.Connect
	want.KCP = parsed.KCP

	if !reflect.DeepEqual(*parsed, want) {
		t.Errorf("ParseServer defaults differ from ServerDefaults():\n got %+v\nwant %+v", *parsed, want)
	}
}
