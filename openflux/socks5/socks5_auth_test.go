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
