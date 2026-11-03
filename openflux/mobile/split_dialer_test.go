package mobile

import (
	"errors"
	"net"
	"testing"
)

var errTunnel = errors.New("via tunnel")

type tunnelOnly struct{}

func (tunnelOnly) DialTCP(string) (net.Conn, error) { return nil, errTunnel }
func (tunnelOnly) DialUDP(string) (net.Conn, error) { return nil, errTunnel }

func TestSplitDialerRoutesBypassedDomainsDirectly(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	_, port, _ := net.SplitHostPort(ln.Addr().String())

	d := newSplitDialer(tunnelOnly{}, []string{" Localhost ", ""})
	conn, err := d.DialTCP(net.JoinHostPort("localhost", port))
	if err != nil {
		t.Fatalf("bypassed domain should be dialed directly: %v", err)
	}
	conn.Close()
	if _, err := d.DialTCP("example.com:443"); !errors.Is(err, errTunnel) {
		t.Fatalf("other domains must go through the tunnel, got %v", err)
	}
	if !d.matches("api.localhost") || d.matches("notlocalhost") {
		t.Fatal("suffix matching must respect label boundaries")
	}

	udp, err := d.DialUDP(net.JoinHostPort("localhost", "53"))
	if err != nil {
		t.Fatalf("bypassed UDP should be dialed directly: %v", err)
	}
	udp.Close()
	if _, err := d.DialUDP("example.com:53"); !errors.Is(err, errTunnel) {
		t.Fatalf("other UDP must go through the tunnel, got %v", err)
	}
}
