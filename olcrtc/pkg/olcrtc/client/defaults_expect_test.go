//go:build !olcrtc_lean

package client_test

// What the default build registers. The lean build's list is next to this file.
func expectedEngines() []string {
	return []string{"goolom", "jitsi", "livekit", "max", "mtslink", "salutejazz", "vkcalls"}
}

func expectedTransports() []string {
	return []string{"datachannel", "seichannel", "videochannel", "vp8channel"}
}
