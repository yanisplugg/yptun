// Package kazcores exists only to pull both native cores into a single Go module so
// `gomobile bind` produces ONE .aar with a single shared Go runtime (one go.Seq / one
// libgojni.so). Binding the two cores as separate .aar files yields duplicate go.* classes
// and two Go runtimes in one process, which conflicts and crashes.
//
// gomobile is invoked with both package paths explicitly; these blank imports just keep the
// dependencies in the module graph.
package kazcores

import (
	_ "github.com/openlibrecommunity/olcrtc/mobile"
	_ "github.com/sagernet/sing-box/experimental/libbox"
)
