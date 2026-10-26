//go:build !olcrtc_lean

package session

// Whether this test binary was built with the olcrtc_lean tag, which leaves
// videochannel out of the registry; see transport_video_lean.go.
const leanBuild = false
