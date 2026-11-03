//go:build olcrtc_lean

package client_test

// What a lean build registers: every engine, no videochannel transport. This
// is the contract the mobile bind relies on when it passes the tag. The tag
// gates that one transport and nothing else - no engine is behind it - so
// this list is the full one, and it stays that way: a lean bind that dropped
// livekit is what olcbox#22 was, and the wbstream provider it serves stopped
// working.
func expectedEngines() []string {
	return []string{"goolom", "jitsi", "livekit", "max", "mtslink", "salutejazz", "vkcalls"}
}

func expectedTransports() []string { return []string{"datachannel", "seichannel", "vp8channel"} }
