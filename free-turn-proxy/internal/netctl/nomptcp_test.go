package netctl

import (
	"net"
	"testing"
)

func TestNoMPTCPForcesMPTCPOff(t *testing.T) {
	cfg := &net.ListenConfig{}
	cfg.SetMultipathTCP(true)
	if !cfg.MultipathTCP() {
		t.Skip("MPTCP cannot be enabled explicitly on this platform")
	}
	if NoMPTCP(cfg).MultipathTCP() {
		t.Fatal("NoMPTCP left MPTCP enabled")
	}
}

func TestNoMPTCPAcceptsNil(t *testing.T) {
	cfg := NoMPTCP(nil)
	if cfg == nil || cfg.MultipathTCP() {
		t.Fatal("NoMPTCP(nil) must return a config with MPTCP disabled")
	}
}
