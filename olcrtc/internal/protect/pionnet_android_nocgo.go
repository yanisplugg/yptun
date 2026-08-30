// SPDX-License-Identifier: WTFPL

//go:build android && !cgo

package protect

import (
	"github.com/pion/transport/v4"
)

// loadInterfaces falls back to netlink on android without cgo.
// getifaddrs(3) requires cgo; without it there is no netlink-free path.
func loadInterfaces() ([]*transport.Interface, error) {
	return loadInterfacesNetlink()
}
