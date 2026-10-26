//go:build !olcrtc_lean

package gate

// ai-generated: the whole file (the mobile flavour's absence from a build
// without the lean tag).

// MobileClient is nil in a build without the lean tag: the phones' flavour
// only exists in the phones' build.
func MobileClient() Client { return nil }
