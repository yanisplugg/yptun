package awg

import (
	"net/netip"
	"strings"
	"testing"

	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
)

const testKeys = `PrivateKey = QFX/N1Nk0mCRQ4Zn+Ns0Uu4hL2xz0dGjJPBFO5mDT2g=
Address = 10.8.0.2/32
DNS = 1.1.1.1
`

const testPeer = `[Peer]
PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
AllowedIPs = 0.0.0.0/0
Endpoint = 127.0.0.1:51820
`

// loadInto runs the real startup path (parse → uapi → tolerant IpcSet) against a throwaway device.
func loadInto(t *testing.T, ini string) *wgConfig {
	t.Helper()
	cfg, err := parseConfig(ini)
	if err != nil {
		t.Fatalf("parseConfig: %v", err)
	}
	uapi, err := cfg.uapi()
	if err != nil {
		t.Fatalf("uapi: %v", err)
	}
	tunDev, _, err := netstack.CreateNetTUN(
		[]netip.Addr{netip.MustParseAddr("10.8.0.2")},
		[]netip.Addr{netip.MustParseAddr("1.1.1.1")}, 1280)
	if err != nil {
		t.Fatalf("nettun: %v", err)
	}
	d := device.NewDevice(tunDev, bindFor(cfg), device.NewLogger(device.LogLevelSilent, ""))
	t.Cleanup(d.Close)
	if err := ipcSetTolerant(d, uapi); err != nil {
		t.Fatalf("device rejected the config: %v\n--- uapi ---\n%s", err, uapi)
	}
	return cfg
}

// A config as a current Amnezia client writes it: J1..J3 + Itime (timed junk packets) are knobs the
// vendored core does not implement yet. Forwarding them verbatim made IpcSet fail with "invalid UAPI
// device key: j1", so the tunnel refused to start and ONLY configs without them (WARP) ever worked.
func TestAmneziaClientConfigWithTimedJunkStarts(t *testing.T) {
	loadInto(t, "[Interface]\n"+testKeys+`Jc = 4
Jmin = 40
Jmax = 70
S1 = 15
S2 = 30
H1 = 1000000-2000000
H2 = 2000001-3000000
H3 = 3000001-4000000
H4 = 4000001-5000000
I1 = <b 0xf1a2b3>
J1 = <b 0xc0ffee>
J2 = <b 0xdeadbe>
J3 = <b 0x010203>
Itime = 30
`+testPeer)
}

// H1..H4 as RANGES is normal AmneziaWG 2.0 output and has always been supported by the core — this
// pins that, so the "only WARP works" report is never mis-diagnosed as a range problem again.
func TestRangeHeadersAreAccepted(t *testing.T) {
	cfg := loadInto(t, "[Interface]\n"+testKeys+`Jc = 4
H1 = 1000000-2000000
H2 = 2000001-3000000
H3 = 3000001-4000000
H4 = 4000001-5000000
`+testPeer)
	uapi, _ := cfg.uapi()
	for _, want := range []string{"h1=1000000-2000000", "h4=4000001-5000000"} {
		if !strings.Contains(uapi, want) {
			t.Fatalf("uapi lost the range %q:\n%s", want, uapi)
		}
	}
}

// The knobs the core DOES implement must still reach it. S3/S4 pad transport packets and
// header_protection_key encrypts their headers: dropping either leaves the tunnel "up" with every
// data packet landing in the bin, so a too-eager filter would be worse than the original bug.
func TestSupportedKnobsAreNotDropped(t *testing.T) {
	cfg := loadInto(t, "[Interface]\n"+testKeys+`Jc = 4
S1 = 15
S2 = 30
S3 = 20
S4 = 25
H1 = 1
H2 = 2
H3 = 3
H4 = 4
I1 = <b 0xf1a2b3>
HeaderProtectionKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
`+testPeer)
	uapi, _ := cfg.uapi()
	for _, want := range []string{"s3=20", "s4=25", "i1=<b 0xf1a2b3>", "header_protection_key="} {
		if !strings.Contains(uapi, want) {
			t.Fatalf("uapi is missing %q:\n%s", want, uapi)
		}
	}
}

// A WARP config (Reserved, no obfuscation knobs at all) must keep working exactly as before.
func TestWarpConfigStillStarts(t *testing.T) {
	cfg := loadInto(t, "[Interface]\n"+testKeys+"Reserved = 12, 34, 56\n"+testPeer)
	if !cfg.hasReserved || cfg.reserved != [3]byte{12, 34, 56} {
		t.Fatalf("WARP reserved bytes lost: %v / %v", cfg.hasReserved, cfg.reserved)
	}
}

func TestUnknownUapiKeyParsing(t *testing.T) {
	cases := map[string]string{
		"IPC error -22: invalid UAPI device key: j1": "j1",
		"invalid UAPI device key: itime":             "itime",
		"some other failure":                         "",
	}
	for msg, want := range cases {
		if got := unknownUapiKey(errString(msg)); got != want {
			t.Fatalf("unknownUapiKey(%q) = %q, want %q", msg, got, want)
		}
	}
	// Only the named key goes; everything else, including a value that contains '=', stays.
	stripped := stripUapiKey("jc=4\nj1=<b 0x01>\nh1=1-2\n", "j1")
	if stripped != "jc=4\nh1=1-2\n" {
		t.Fatalf("stripUapiKey dropped the wrong lines: %q", stripped)
	}
}

type errString string

func (e errString) Error() string { return string(e) }
