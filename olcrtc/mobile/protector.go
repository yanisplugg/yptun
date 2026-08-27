package mobile

import "github.com/openlibrecommunity/olcrtc/internal/protect"

// SocketProtector protects sockets from VPN routing on Android.
type SocketProtector interface {
	Protect(fd int) bool
}

// SetProtector sets process-wide Android VPN socket protection.
// Android VpnService protection is process-wide even when multiple Runtime values coexist.
func (r *Runtime) SetProtector(p SocketProtector) {
	if p == nil {
		protect.SetProtector(nil)
		return
	}
	protect.SetProtector(func(fd int) bool { return p.Protect(fd) })
}
