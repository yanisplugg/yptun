//go:build !olcrtc_lean

package config

// Whether this test binary was built with the olcrtc_lean tag, which leaves
// videochannel out of the registry; see internal/app/session/transport_video_lean.go.
const leanBuild = false
