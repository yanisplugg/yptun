package mobile

// YPtun patch (re-apply on every re-vendor, see ../YPTUN.md): the CLI takes the code.avito.ru account
// cookies from --avito-cookies / OPENFLUX_AVITO_COOKIES; an embedding app (the iOS Network Extension
// runs OpenFlux in-process) has no command line, so it hands them over here before Start*.

import "sync/atomic"

var avitoCookies atomic.Pointer[string]

// SetAvitoCookies sets the raw Cookie header the Avito carriers of the next Start* log in with;
// "" clears it.
func SetAvitoCookies(cookieHeader string) {
	avitoCookies.Store(&cookieHeader)
}

func yptunAvitoCookies() string {
	if p := avitoCookies.Load(); p != nil {
		return *p
	}
	return ""
}
