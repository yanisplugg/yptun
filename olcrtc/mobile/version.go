package mobile

import "log"

// olcrtcVersion identifies the vendored olcRTC core build (upstream commit + update date). Bump it
// whenever the core is re-synced from upstream; surfaced in the app's settings.
const olcrtcVersion = "2026.08.26-f616f57"

// Version returns the olcRTC core version for display in the app.
func Version() string { return olcrtcVersion }

// LogWriter receives log messages from olcRTC.
type LogWriter interface {
	WriteLog(msg string)
}

// SetLogWriter redirects the stdlib logger (used by olcRTC's internal packages) to w. Process-wide,
// like log.SetOutput itself; independent of which Runtime instance calls it.
func (r *Runtime) SetLogWriter(w LogWriter) {
	if w != nil {
		log.SetOutput(&logBridge{w: w})
	}
}

// logBridge adapts LogWriter to io.Writer.
type logBridge struct {
	w LogWriter
}

func (b *logBridge) Write(p []byte) (int, error) {
	b.w.WriteLog(string(p))
	return len(p), nil
}
