// Package kazcores exists only to pull both native cores into a single Go module so
// `gomobile bind` produces ONE .aar with a single shared Go runtime (one go.Seq / one
// libgojni.so). Binding the two cores as separate .aar files yields duplicate go.* classes
// and two Go runtimes in one process, which conflicts and crashes.
//
// gomobile is invoked with all package paths explicitly; these blank imports just keep the
// dependencies in the module graph.
package kazcores

import (
	_ "github.com/olc/awgproxy/awg"
	_ "github.com/olc/hysteria2proxy/hy2"
	_ "github.com/openlibrecommunity/olcrtc/mobile"
	_ "github.com/sagernet/sing-box/experimental/libbox"
	_ "github.com/samosvalishe/free-turn-proxy/freeturn"
	_ "wg-turn-client/wdttmobile"
	_ "www.bamsoftware.com/git/dnstt.git/dnsttmobile"
)
