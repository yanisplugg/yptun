// SPDX-License-Identifier: WTFPL

//go:build android

package protect

import (
	"fmt"
	"net"

	"github.com/pion/transport/v4"
)

// loadInterfacesNetlink enumerates interfaces through net.Interfaces(), which
// goes over netlink on Linux.
//
// It is the fallback for both android builds: with cgo on API < 30, where
// SELinux does not yet restrict netlink_route_socket, and without cgo at any
// API level, where getifaddrs(3) is out of reach and this is the only option
// left (it fails on API 30+, which is no worse than the pre-cgo behaviour).
func loadInterfacesNetlink() ([]*transport.Interface, error) {
	ifs, err := net.Interfaces()
	if err != nil {
		return nil, fmt.Errorf("net interfaces: %w", err)
	}
	out := make([]*transport.Interface, 0, len(ifs))
	for i := range ifs {
		ifc := transport.NewInterface(ifs[i])
		addrs, err := ifs[i].Addrs()
		if err != nil {
			// Skip this interface rather than failing the whole
			// enumeration: one unreadable device must not cost us
			// every candidate.
			continue
		}
		for _, addr := range addrs {
			ifc.AddAddress(addr)
		}
		out = append(out, ifc)
	}
	return out, nil
}
