package socks5

import (
	"bytes"
	"io"
	"net"
	"testing"
)

// YPtun: the local port is protected with per-session credentials; wrong or missing ones must not pass.
func TestAuthenticate(t *testing.T) {
	cases := []struct {
		name    string
		methods []byte
		sub     []byte // client's RFC 1929 request, if it gets that far
		ok      bool
	}{
		{"right credentials", []byte{0x00, 0x02}, []byte{0x01, 1, 'u', 2, 'p', 'w'}, true},
		{"wrong password", []byte{0x02}, []byte{0x01, 1, 'u', 2, 'x', 'x'}, false},
		{"no-auth only", []byte{0x00}, nil, false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			server, client := net.Pipe()
			defer client.Close()
			s := &SOCKS5Server{}
			s.SetAuth("u", "pw")
			done := make(chan bool, 1)
			go func() { done <- s.authenticate(server, c.methods); server.Close() }()

			reply := make([]byte, 2)
			if _, err := io.ReadFull(client, reply); err != nil {
				t.Fatal(err)
			}
			if c.sub == nil {
				if !bytes.Equal(reply, []byte{0x05, 0xFF}) {
					t.Fatalf("method reply %x, want 05ff", reply)
				}
			} else {
				client.Write(c.sub)
				status := make([]byte, 2)
				io.ReadFull(client, status)
				if (status[1] == 0x00) != c.ok {
					t.Fatalf("auth status %x, want ok=%v", status, c.ok)
				}
			}
			if got := <-done; got != c.ok {
				t.Fatalf("authenticate = %v, want %v", got, c.ok)
			}
		})
	}
}

func TestNoCredentialsKeepsUpstreamNoAuth(t *testing.T) {
	server, client := net.Pipe()
	defer client.Close()
	s := &SOCKS5Server{}
	go func() { s.authenticate(server, []byte{0x00}); server.Close() }()
	reply := make([]byte, 2)
	io.ReadFull(client, reply)
	if !bytes.Equal(reply, []byte{0x05, 0x00}) {
		t.Fatalf("reply %x, want 0500", reply)
	}
}

type mockDialer struct {
	dialed string
}

func (m *mockDialer) DialTCP(address string) (net.Conn, error) {
	m.dialed = address
	c1, c2 := net.Pipe()
	go func() {
		_ = c2.Close()
	}()
	return c1, nil
}

func TestHandleConnectionDomainAndIPv4(t *testing.T) {
	dialer := &mockDialer{}
	s := NewSOCKS5Server("127.0.0.1:0", dialer)

	// 1. Connect to domain: example.com:443
	server, client := net.Pipe()
	go s.handleConnection(server)

	// Auth greeting: no-auth
	client.Write([]byte{0x05, 0x01, 0x00})
	authResp := make([]byte, 2)
	io.ReadFull(client, authResp)
	if !bytes.Equal(authResp, []byte{0x05, 0x00}) {
		t.Fatalf("auth resp: %x", authResp)
	}

	// CONNECT domain "example.com:443"
	domain := "example.com"
	req := append([]byte{0x05, 0x01, 0x00, 0x03, byte(len(domain))}, []byte(domain)...)
	req = append(req, 0x01, 0xBB) // port 443
	client.Write(req)

	resp := make([]byte, 10)
	io.ReadFull(client, resp)
	if resp[1] != 0x00 {
		t.Fatalf("connect resp status: %x", resp[1])
	}
	if dialer.dialed != "example.com:443" {
		t.Fatalf("dialed = %q, want example.com:443", dialer.dialed)
	}
	client.Close()

	// 2. Command not supported (e.g. UDP ASSOCIATE = 0x03)
	server2, client2 := net.Pipe()
	go s.handleConnection(server2)
	client2.Write([]byte{0x05, 0x01, 0x00})
	io.ReadFull(client2, authResp)

	// net.Pipe is synchronous: the server answers after the 4-byte header and never reads the address,
	// so a blocking 10-byte Write here would deadlock against the server's reply.
	go client2.Write([]byte{0x05, 0x03, 0x00, 0x01, 127, 0, 0, 1, 0x04, 0x38})
	resp2 := make([]byte, 10)
	io.ReadFull(client2, resp2)
	if resp2[1] != 0x07 { // 0x07 = Command not supported
		t.Fatalf("expected command not supported 0x07, got %x", resp2[1])
	}
	client2.Close()
}
