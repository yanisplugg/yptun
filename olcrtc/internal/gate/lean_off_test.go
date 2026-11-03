//go:build !olcrtc_lean

package gate

// ai-generated: whole file, the default half of the build switch the flavour
// tests read (the pattern internal/e2e uses).

// leanBuild says this test binary was built with olcrtc_lean; this one was
// not, so it carries no mobile flavour.
const leanBuild = false
