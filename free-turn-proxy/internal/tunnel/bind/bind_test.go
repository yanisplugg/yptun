package bind

import (
	"bytes"
	"errors"
	"net"
	"testing"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/conn"
	"github.com/samosvalishe/free-turn-proxy/internal/netconn"
	"github.com/samosvalishe/free-turn-proxy/internal/tunnel"
)

// Bind обязан удовлетворять интерфейсу устройства.
var _ conn.Bind = (*SinglePeerBind)(nil)

func TestSinglePeerBindRoundTrip(t *testing.T) {
	device, relay := netconn.PacketPipe(0, 0)
	t.Cleanup(func() {
		_ = device.Close()
		_ = relay.Close()
	})

	bind := NewSinglePeerBind(device)
	fns, port, err := bind.Open(0)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	if len(fns) != 1 || port != 0 {
		t.Fatalf("Open() = %d fns, port %d", len(fns), port)
	}

	// Устройство -> релей.
	out := []byte("handshake")
	if sendErr := bind.Send([][]byte{out}, theEndpoint); sendErr != nil {
		t.Fatalf("Send() error = %v", sendErr)
	}
	buf := make([]byte, 64)
	n, _, err := relay.ReadFrom(buf)
	if err != nil || !bytes.Equal(buf[:n], out) {
		t.Fatalf("relay read %q, %v", buf[:n], err)
	}

	// Релей -> устройство.
	in := []byte("response")
	if _, writeErr := relay.WriteTo(in, nil); writeErr != nil {
		t.Fatal(writeErr)
	}
	packets := [][]byte{make([]byte, 64)}
	sizes := make([]int, 1)
	eps := make([]conn.Endpoint, 1)
	count, err := fns[0](packets, sizes, eps)
	if err != nil {
		t.Fatalf("receive error = %v", err)
	}
	if count != 1 || !bytes.Equal(packets[0][:sizes[0]], in) {
		t.Fatalf("receive = %d, %q", count, packets[0][:sizes[0]])
	}
	if eps[0] != theEndpoint {
		t.Errorf("endpoint = %v, want the single-peer endpoint", eps[0])
	}
}

func TestSinglePeerBindSendsBatch(t *testing.T) {
	device, relay := netconn.PacketPipe(0, 0)
	t.Cleanup(func() {
		_ = device.Close()
		_ = relay.Close()
	})

	bind := NewSinglePeerBind(device)
	if err := bind.Send([][]byte{[]byte("one"), []byte("two")}, theEndpoint); err != nil {
		t.Fatalf("Send() error = %v", err)
	}
	buf := make([]byte, 16)
	for _, want := range []string{"one", "two"} {
		n, _, err := relay.ReadFrom(buf)
		if err != nil || string(buf[:n]) != want {
			t.Fatalf("relay read %q, %v; want %q", buf[:n], err, want)
		}
	}
}

// Устройство прекращает цикл только по net.ErrClosed - Close обязан разбудить
// читателя именно этой ошибкой, иначе получим busy loop на дедлайнах.
func TestSinglePeerBindCloseWakesReceiver(t *testing.T) {
	device, relay := netconn.PacketPipe(0, 0)
	t.Cleanup(func() {
		_ = device.Close()
		_ = relay.Close()
	})

	bind := NewSinglePeerBind(device)
	fns, _, err := bind.Open(0)
	if err != nil {
		t.Fatal(err)
	}

	errCh := make(chan error, 1)
	go func() {
		_, rerr := fns[0]([][]byte{make([]byte, 64)}, make([]int, 1), make([]conn.Endpoint, 1))
		errCh <- rerr
	}()

	time.Sleep(20 * time.Millisecond)
	if err := bind.Close(); err != nil {
		t.Fatalf("Close() error = %v", err)
	}

	select {
	case rerr := <-errCh:
		if !errors.Is(rerr, net.ErrClosed) {
			t.Fatalf("receive after Close error = %v, want net.ErrClosed", rerr)
		}
	case <-time.After(time.Second):
		t.Fatal("Close() did not wake the receiver")
	}

	// Повторный Close безопасен: его зовут и хост, и устройство.
	if err := bind.Close(); err != nil {
		t.Errorf("second Close() error = %v", err)
	}
}

func TestSinglePeerBindParseEndpointIgnoresInput(t *testing.T) {
	device, relay := netconn.PacketPipe(0, 0)
	t.Cleanup(func() {
		_ = device.Close()
		_ = relay.Close()
	})

	bind := NewSinglePeerBind(device)
	ep, err := bind.ParseEndpoint("whatever:1234")
	if err != nil || ep != theEndpoint {
		t.Fatalf("ParseEndpoint() = %v, %v", ep, err)
	}
	if ep.DstToString() != tunnel.EndpointSinglePeer {
		t.Errorf("DstToString() = %q, want %q", ep.DstToString(), tunnel.EndpointSinglePeer)
	}
	if bind.BatchSize() != 1 {
		t.Errorf("BatchSize() = %d, want 1", bind.BatchSize())
	}
	if err := bind.SetMark(42); err != nil {
		t.Errorf("SetMark() error = %v", err)
	}
}
