package awg

import (
	"bytes"
	"testing"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
)

type recordingBind struct {
	conn.Bind
	packets [][]byte
}

func (b *recordingBind) Send(packets [][]byte, _ conn.Endpoint) error {
	b.packets = packets
	return nil
}

func TestReservedBindPreservesSignaturePackets(t *testing.T) {
	inner := &recordingBind{}
	bind := newReservedBind(inner, [3]byte{1, 2, 3})
	signature := []byte{0xce, 0, 0, 0, 1, 8, 0x97, 0xa2}
	initiation := make([]byte, device.MessageInitiationSize)
	initiation[0] = byte(device.MessageInitiationType)
	data := make([]byte, device.MessageKeepaliveSize)
	data[0] = byte(device.MessageTransportType)
	if err := bind.Send([][]byte{bytes.Clone(signature), initiation, data}, nil); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(inner.packets[0], signature) {
		t.Fatalf("I1 bytes changed: %x", inner.packets[0])
	}
	for _, packet := range inner.packets[1:] {
		if !bytes.Equal(packet[1:4], []byte{1, 2, 3}) {
			t.Fatalf("missing reserved bytes: %x", packet[:4])
		}
	}
}
