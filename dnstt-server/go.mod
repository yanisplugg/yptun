// dnstt-server — the VPS end of the DNS tunnel, built natively (linux amd64/arm64) for the in-app
// one-tap installer. main.go is the upstream dnstt-server (David Fifield, www.bamsoftware.com), with
// a -domain flag + a built-in no-auth SOCKS5 upstream (socks.go) so the VPS is a self-contained exit.
// The dnstt protocol packages are reused from the vendored client module via a replace directive.
module olcvpn.local/dnstt-server

go 1.24.0

require (
	github.com/xtaci/kcp-go/v5 v5.6.8
	github.com/xtaci/smux v1.5.24
	www.bamsoftware.com/git/dnstt.git v0.0.0
)

require (
	github.com/flynn/noise v1.0.0 // indirect
	github.com/klauspost/cpuid/v2 v2.2.6 // indirect
	github.com/klauspost/reedsolomon v1.12.0 // indirect
	github.com/pkg/errors v0.9.1 // indirect
	github.com/templexxx/cpu v0.1.0 // indirect
	github.com/templexxx/xorsimd v0.4.2 // indirect
	github.com/tjfoc/gmsm v1.4.1 // indirect
	golang.org/x/crypto v0.47.0 // indirect
	golang.org/x/net v0.48.0 // indirect
	golang.org/x/sys v0.40.0 // indirect
)

replace www.bamsoftware.com/git/dnstt.git => ../dnstt
