package server

import "testing"

// ai-generated: the server side asks for no ICE mode: it joins over UDP from a
// datacenter, and a room link's hint is for the client side alone (roomhint).
func TestTheServerSideAsksForNoICEMode(t *testing.T) {
	s := &Server{}
	if got := s.linkRole().DefaultICE; got != "" {
		t.Fatalf("server role DefaultICE = %q, want empty", got)
	}
}
