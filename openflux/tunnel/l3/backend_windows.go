//go:build windows

package l3

func newBackend() (L3Backend, error) {
	// Windows backend is not wired to WinDivert yet.
	// Use proxy mode, or integrate tunnel/windivert manually.
	return nil, errBackendUnavailable
}
