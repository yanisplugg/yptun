package builtin

import (
	"github.com/openlibrecommunity/olcrtc/internal/engine"
	"github.com/openlibrecommunity/olcrtc/internal/engine/livekit"
)

// registerLivekitEngine adds the livekit engine: the default for the "none"
// provider, and the engine the wbstream provider reports. It is registered in
// every build, the olcrtc_lean mobile bind included. That bind once left it
// out on the belief that no provider a phone talks to was served by it; WB
// Stream is, and the app failed at session start with "engine new: engine not
// found" while the server side of the same room was fine (olcbox#22). Its SDK
// does register protobuf descriptors and a CEL validator at init - about
// 3 MB of heap and 20 MB of binary - and a provider that works is worth that;
// registry_test.go keeps every provider's engine linked.
func registerLivekitEngine() {
	engine.Register("livekit", livekit.New)
}
