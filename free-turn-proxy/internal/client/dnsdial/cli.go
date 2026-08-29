package dnsdial

import (
	"net"
	"sync"
)

var (
	dohResolverOnce     sync.Once
	dohResolverInstance *DohResolver
)

func sharedDohResolver() *DohResolver {
	dohResolverOnce.Do(func() {
		dohResolverInstance = NewDohResolver(nil)
	})
	return dohResolverInstance
}

// AppDialer возвращает net.Dialer с DNS-транспортом по mode (udp | doh | auto).
func AppDialer(mode string) net.Dialer {
	return buildDialer(mode, sharedDohResolver())
}

// InstallGlobalResolver устанавливает net.DefaultResolver для библиотек со стандартным http.Client.
func InstallGlobalResolver(mode string) {
	d := AppDialer(mode)
	if d.Resolver != nil {
		net.DefaultResolver = d.Resolver
	}
}
