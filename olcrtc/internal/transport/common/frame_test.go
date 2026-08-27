package common_test

import (
	"bytes"
	"encoding/binary"
	"errors"
	"testing"

	"github.com/openlibrecommunity/olcrtc/internal/transport/common"
)

func TestDataFrameRoundTrip(t *testing.T) {
	encoded := common.EncodeData(
		common.RoleClient, 0x12345678, 42, 0xdeadbeef, 1024, 1, 3, []byte("chunk"))

	got, err := common.DecodeFrame(encoded)
	if err != nil {
		t.Fatalf("DecodeFrame() error = %v", err)
	}
	if got.Type != common.FrameTypeData || got.Role != common.RoleClient ||
		got.Binding != 0x12345678 || got.Seq != 42 || got.CRC != 0xdeadbeef {
		t.Fatalf("unexpected frame header: %+v", got)
	}
	if got.TotalLen != 1024 || got.FragIdx != 1 || got.FragTotal != 3 {
		t.Fatalf("unexpected fragmentation fields: %+v", got)
	}
	if !bytes.Equal(got.Payload, []byte("chunk")) {
		t.Fatalf("payload mismatch: got=%q", got.Payload)
	}
}

func TestAckFrameRoundTrip(t *testing.T) {
	got, err := common.DecodeFrame(common.EncodeAck(common.RoleServer, 0x99, 7, 0x1234, 5))
	if err != nil {
		t.Fatalf("DecodeFrame() error = %v", err)
	}
	if got.Type != common.FrameTypeAck || got.Role != common.RoleServer ||
		got.Binding != 0x99 || got.Seq != 7 || got.CRC != 0x1234 || got.FragIdx != 5 {
		t.Fatalf("ack = %+v", got)
	}
}

func TestHelloFrameRoundTrip(t *testing.T) {
	got, err := common.DecodeFrame(common.EncodeHello(common.RoleClient, 0x4242))
	if err != nil {
		t.Fatalf("DecodeFrame(hello) error = %v", err)
	}
	if got.Type != common.FrameTypeHello || got.Role != common.RoleClient || got.Binding != 0x4242 {
		t.Fatalf("hello = %+v", got)
	}
}

func TestDecodeFrameErrors(t *testing.T) {
	header := func(typ byte, extra ...byte) []byte {
		out := make([]byte, 6, 6+len(extra))
		binary.BigEndian.PutUint32(out[0:4], common.FrameMagic)
		out[4] = common.FrameVersion
		out[5] = typ
		return append(out, extra...)
	}

	tests := []struct {
		name string
		data []byte
		want error
	}{
		{"truncated", []byte{1, 2, 3}, common.ErrFrameTooShort},
		{"bad magic", []byte{0, 0, 0, 0, common.FrameVersion, common.FrameTypeAck}, common.ErrUnexpectedMagic},
		{"bad version", []byte{0x4f, 0x4c, 0x56, 0x43, 9, common.FrameTypeAck}, common.ErrUnexpectedVersion},
		{"short ack header", header(common.FrameTypeAck), common.ErrAckTooShort},
		{"short data header", header(common.FrameTypeData), common.ErrDataTooShort},
		{"short hello", header(common.FrameTypeHello), common.ErrHelloTooShort},
		{"unknown type", header(99), common.ErrUnexpectedFrameType},
		{
			"ack body truncated",
			header(common.FrameTypeAck, make([]byte, 8)...),
			common.ErrAckTooShort,
		},
		{
			"data body truncated",
			header(common.FrameTypeData, make([]byte, 8)...),
			common.ErrDataTooShort,
		},
	}
	for _, tt := range tests {
		if _, err := common.DecodeFrame(tt.data); !errors.Is(err, tt.want) {
			t.Fatalf("%s: DecodeFrame() error = %v, want %v", tt.name, err, tt.want)
		}
	}
}

// TestDecodeFrameRejectsSupersededLayouts pins the compatibility break down.
// seichannel used magic "OVC1" with a 22-byte data header and videochannel
// used "OVV2"/version 3 with a 29-byte one. Neither may be decoded against the
// current offsets: a stale peer has to fail loudly, not deliver garbage.
func TestDecodeFrameRejectsSupersededLayouts(t *testing.T) {
	legacy := func(magic uint32, version, typ byte, size int) []byte {
		out := make([]byte, size)
		binary.BigEndian.PutUint32(out[0:4], magic)
		out[4] = version
		out[5] = typ
		return out
	}

	// seichannel v1 data frame, 22-byte header.
	if _, err := common.DecodeFrame(legacy(0x4f564331, 1, 1, 22)); !errors.Is(err, common.ErrUnexpectedMagic) {
		t.Fatalf("legacy OVC1 frame error = %v, want %v", err, common.ErrUnexpectedMagic)
	}
	// videochannel v3 data frame, 29-byte header.
	if _, err := common.DecodeFrame(legacy(0x4f565632, 3, 1, 29)); !errors.Is(err, common.ErrUnexpectedMagic) {
		t.Fatalf("legacy OVV2 frame error = %v, want %v", err, common.ErrUnexpectedMagic)
	}
	// Same magic, older version: rejected on the version byte.
	if _, err := common.DecodeFrame(legacy(common.FrameMagic, 3, 1, 29)); !errors.Is(err, common.ErrUnexpectedVersion) {
		t.Fatalf("older version error = %v, want %v", err, common.ErrUnexpectedVersion)
	}
}

func TestFrameAcceptedBy(t *testing.T) {
	server := common.Frame{Role: common.RoleClient, Binding: 10}
	if !server.AcceptedBy(common.RoleClient, 10) {
		t.Fatal("server rejected client frame")
	}
	if server.AcceptedBy(common.RoleServer, 10) {
		t.Fatal("server accepted its own role")
	}
	if server.AcceptedBy(common.RoleClient, 11) {
		t.Fatal("server accepted a foreign binding")
	}

	unbound := common.Frame{Role: common.RoleAny}
	if !unbound.AcceptedBy(common.RoleServer, 20) {
		t.Fatal("role-any/binding-zero frame rejected")
	}
}

func TestLocalAndRemoteRole(t *testing.T) {
	if common.LocalRole("") != common.RoleServer || common.RemoteRole("") != common.RoleClient {
		t.Fatal("empty device id must be the server side")
	}
	if common.LocalRole("dev") != common.RoleClient || common.RemoteRole("dev") != common.RoleServer {
		t.Fatal("a device id must be the client side")
	}
}
