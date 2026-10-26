package client

import (
	"testing"

	"github.com/openlibrecommunity/olcrtc/internal/roomhint"
)

// ai-generated: the client side reaches a carrier's relay over TCP when the
// room link says nothing (MTS Link, #60): on mobile networks under an allow list
// UDP to the SFU goes dark for seconds at a time, and TCP 443 holds. A hint on
// the link still wins, and the server side asks for nothing (server/linkrole_test.go).
func TestTheClientSideAsksForTCPWhereTheLinkSaysNothing(t *testing.T) {
	c := &Client{deviceID: "device"}
	role := c.linkRole(Config{})
	if role.DefaultICE != roomhint.ICETCP {
		t.Fatalf("client role DefaultICE = %q, want %q", role.DefaultICE, roomhint.ICETCP)
	}
	if !role.RequireTargetedPeer || role.DeviceID != "device" {
		t.Fatalf("client role = %+v, want RequireTargetedPeer and the device id", role)
	}
}
