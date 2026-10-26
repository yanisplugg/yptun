package sniff

import (
	"crypto/tls"
	"fmt"
	"net"
	"testing"
)

// helloBytes captures the bytes crypto/tls sends first for serverName: a real
// ClientHello, with whatever key shares and extensions this Go puts in it.
func helloBytes(serverName string) ([]byte, error) {
	a, b := net.Pipe()
	defer func() { _ = a.Close() }()
	defer func() { _ = b.Close() }()
	go func() {
		_ = tls.Client(a, &tls.Config{ServerName: serverName, InsecureSkipVerify: true}).Handshake() //nolint:gosec // G402: a hello is all this needs
	}()
	buf := make([]byte, MaxHead)
	n, err := b.Read(buf)
	if err != nil {
		return nil, fmt.Errorf("read hello: %w", err)
	}
	return buf[:n], nil
}

func clientHello(t *testing.T, serverName string) []byte {
	t.Helper()
	hello, err := helloBytes(serverName)
	if err != nil {
		t.Fatal(err)
	}
	return hello
}

func TestHostFromClientHello(t *testing.T) {
	for _, name := range []string{"yandex.ru", "www.gosuslugi.ru", "xn--80adxhks.xn--p1ai", "Mixed.Case.RU"} {
		hello := clientHello(t, name)
		got, need := Host(hello)
		want := name
		if name == "Mixed.Case.RU" {
			want = "mixed.case.ru"
		}
		if need || got != want {
			t.Errorf("Host(hello %s) = %q, need=%v; want %q", name, got, need, want)
		}
		// Every proper prefix of the record asks for more, never guesses.
		for cut := 1; cut < len(hello); cut++ {
			if got, need := Host(hello[:cut]); !need || got != "" {
				t.Fatalf("Host(hello[:%d]) = %q, need=%v; want \"\", more", cut, got, need)
			}
		}
		// Bytes after the record change nothing.
		if got, need := Host(append(append([]byte(nil), hello...), 1, 2, 3)); need || got != want {
			t.Errorf("Host(hello+tail) = %q, need=%v", got, need)
		}
	}
}

func TestHostFromClientHelloWithoutSNI(t *testing.T) {
	// An address as the server name: crypto/tls sends no server_name extension.
	hello := clientHello(t, "")
	if got, need := Host(hello); need || got != "" {
		t.Errorf("Host(hello without sni) = %q, need=%v", got, need)
	}
}

func TestHostFromHTTP(t *testing.T) {
	for req, want := range map[string]string{
		"GET / HTTP/1.1\r\nHost: Example.RU\r\n\r\n":                      "example.ru",
		"POST /x HTTP/1.1\r\nhost: example.ru:8080\r\nX: y\r\n\r\nbody":   "example.ru",
		"GET / HTTP/1.1\r\nHost: [2001:db8::1]:80\r\n\r\n":                "2001:db8::1",
		"OPTIONS * HTTP/1.1\r\nUser-Agent: x\r\nHOST:   a.b.ru \r\n\r\n":  "a.b.ru",
		"GET / HTTP/1.0\r\n\r\n":                                          "",
		"CONNECT example.ru:443 HTTP/1.1\r\nHost: example.ru:443\r\n\r\n": "example.ru",
	} {
		if got, need := Host([]byte(req)); need || got != want {
			t.Errorf("Host(%q) = %q, need=%v; want %q", req, got, need, want)
		}
	}
}

func TestHostAsksForMore(t *testing.T) {
	for _, head := range []string{"", "G", "GET ", "GET / HTTP/1.1\r\nHost: a.ru\r\n", "\x16\x03\x01", "\x16\x03\x01\x02\x00\x01"} {
		if got, need := Host([]byte(head)); !need || got != "" {
			t.Errorf("Host(%q) = %q, need=%v; want \"\", more", head, got, need)
		}
	}
}

func TestHostGivesUp(t *testing.T) {
	spanning := []byte{0x16, 0x03, 0x01, 0x00, 0x08, 0x01, 0x00, 0x10, 0x00, 0x03, 0x03, 0, 0}
	tooLong := []byte{0x16, 0x03, 0x01, 0x41, 0x00, 0x01}
	notHello := []byte{0x16, 0x03, 0x01, 0x00, 0x04, 0x02, 0x00, 0x00, 0x00}
	for _, head := range [][]byte{
		[]byte("SSH-2.0-OpenSSH_9.6\r\n"),
		[]byte("GETX / HTTP/1.1\r\n\r\n"),
		[]byte("\x05\x01\x00"),
		spanning, tooLong, notHello,
		make([]byte, MaxHead),
	} {
		if got, need := Host(head); need || got != "" {
			t.Errorf("Host(%.12q…) = %q, need=%v; want \"\", done", head, got, need)
		}
	}
}

func FuzzHost(f *testing.F) {
	if hello, err := helloBytes("fuzz.ru"); err == nil {
		f.Add(hello)
	}
	f.Add([]byte("GET / HTTP/1.1\r\nHost: a.ru\r\n\r\n"))
	f.Fuzz(func(_ *testing.T, head []byte) {
		_, _ = Host(head)
	})
}
