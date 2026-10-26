package mobile

// olcrtcVersion identifies the vendored olcRTC core build (upstream commit + update date). Bump it
// whenever the core is re-synced from upstream; surfaced in the app's settings.
const olcrtcVersion = "2026.10.26-b2c4b17"

// Version returns the olcRTC core version for display in the app.
func Version() string { return olcrtcVersion }
