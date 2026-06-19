// Trimmed vendor of www.bamsoftware.com/git/dnstt.git (David Fifield's DNS tunnel, the dnstt-xyz
// app's go_src). Only the packages the mobile client needs are kept — dns/, noise/, turbotunnel/
// and the gomobile wrapper dnsttmobile/ (renamed from `mobile` to avoid a gomobile package clash
// with olcrtc's `mobile`). The dnstt-client/dnstt-server/desktop CLIs are dropped, which removes the
// utls/quic/http dependencies and keeps this module's footprint minimal (no conflict with cores).
module www.bamsoftware.com/git/dnstt.git

go 1.24.0

require (
	github.com/flynn/noise v1.0.0
	github.com/xtaci/kcp-go/v5 v5.6.8
	github.com/xtaci/smux v1.5.24
	golang.org/x/crypto v0.47.0
)

require (
	github.com/klauspost/cpuid/v2 v2.2.6 // indirect
	github.com/klauspost/reedsolomon v1.12.0 // indirect
	github.com/pkg/errors v0.9.1 // indirect
	github.com/templexxx/cpu v0.1.0 // indirect
	github.com/templexxx/xorsimd v0.4.2 // indirect
	github.com/tjfoc/gmsm v1.4.1 // indirect
	golang.org/x/net v0.48.0 // indirect
	golang.org/x/sys v0.40.0 // indirect
)
