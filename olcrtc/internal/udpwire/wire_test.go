package udpwire

import (
	"bytes"
	"errors"
	"testing"
)

const testHost = "example.com"

func TestEncodeDecodeRoundTrip(t *testing.T) {
	tests := []Frame{
		{
			Type:     FrameTypePacket,
			FlowID:   1,
			Endpoint: Endpoint{Host: "127.0.0.1", Port: 53},
			Payload:  []byte("ipv4"),
		},
		{
			Type:     FrameTypePacket,
			FlowID:   2,
			Endpoint: Endpoint{Host: testHost, Port: 443},
			Payload:  []byte("domain"),
		},
		{
			Type:     FrameTypePacket,
			FlowID:   3,
			Endpoint: Endpoint{Host: "2001:db8::1", Port: 5353},
			Payload:  []byte("ipv6"),
		},
		{
			Type:   FrameTypeClose,
			FlowID: 4,
		},
	}

	for _, tt := range tests {
		encoded, err := Encode(tt)
		if err != nil {
			t.Fatalf("Encode(%+v) error = %v", tt, err)
		}
		got, err := Decode(encoded)
		if err != nil {
			t.Fatalf("Decode(%+v) error = %v", tt, err)
		}
		if got.Type != tt.Type || got.FlowID != tt.FlowID ||
			got.Endpoint != tt.Endpoint || !bytes.Equal(got.Payload, tt.Payload) {
			t.Fatalf("Decode(Encode(%+v)) = %+v", tt, got)
		}
	}
}

func TestDecodePayloadAliasesWireBuffer(t *testing.T) {
	encoded, err := Encode(Frame{
		Type:     FrameTypePacket,
		FlowID:   1,
		Endpoint: Endpoint{Host: "127.0.0.1", Port: 53},
		Payload:  []byte("payload"),
	})
	if err != nil {
		t.Fatalf("Encode() error = %v", err)
	}

	got, err := Decode(encoded)
	if err != nil {
		t.Fatalf("Decode() error = %v", err)
	}
	encoded[len(encoded)-1] = 'X'

	if string(got.Payload) != "payloaX" {
		t.Fatalf("decoded payload does not alias wire buffer: %q", got.Payload)
	}
}

func TestEncodeRejectsInvalidFrames(t *testing.T) {
	oversized := make([]byte, MaxPayloadSize+1)
	tests := []struct {
		name string
		in   Frame
		want error
	}{
		{
			name: "zero flow",
			in: Frame{
				Type:     FrameTypePacket,
				Endpoint: Endpoint{Host: testHost, Port: 53},
			},
			want: ErrInvalidFlowID,
		},
		{
			name: "packet missing endpoint",
			in:   Frame{Type: FrameTypePacket, FlowID: 1},
			want: ErrInvalidEndpoint,
		},
		{
			name: "packet zero port",
			in: Frame{
				Type:     FrameTypePacket,
				FlowID:   1,
				Endpoint: Endpoint{Host: testHost},
			},
			want: ErrInvalidEndpoint,
		},
		{
			name: "close with payload",
			in:   Frame{Type: FrameTypeClose, FlowID: 1, Payload: []byte("x")},
			want: ErrInvalidEndpoint,
		},
		{
			name: "oversized payload",
			in: Frame{
				Type:     FrameTypePacket,
				FlowID:   1,
				Endpoint: Endpoint{Host: testHost, Port: 53},
				Payload:  oversized,
			},
			want: ErrPayloadTooLarge,
		},
		{
			name: "domain control char",
			in: Frame{
				Type:     FrameTypePacket,
				FlowID:   1,
				Endpoint: Endpoint{Host: "bad\nhost", Port: 53},
			},
			want: ErrInvalidEndpoint,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := Encode(tt.in)
			if !errors.Is(err, tt.want) {
				t.Fatalf("Encode() error = %v, want %v", err, tt.want)
			}
		})
	}
}

func BenchmarkEncodeIPv4Packet(b *testing.B) {
	payload := bytes.Repeat([]byte{0xab}, MaxPayloadSize)
	frame := Frame{
		Type:     FrameTypePacket,
		FlowID:   1,
		Endpoint: Endpoint{Host: "8.8.8.8", Port: 53},
		Payload:  payload,
	}

	b.ReportAllocs()
	b.SetBytes(int64(len(payload)))
	b.ResetTimer()
	for range b.N {
		if _, err := Encode(frame); err != nil {
			b.Fatalf("Encode() error = %v", err)
		}
	}
}

func BenchmarkDecodeIPv4Packet(b *testing.B) {
	payload := bytes.Repeat([]byte{0xab}, MaxPayloadSize)
	wire, err := Encode(Frame{
		Type:     FrameTypePacket,
		FlowID:   1,
		Endpoint: Endpoint{Host: "8.8.8.8", Port: 53},
		Payload:  payload,
	})
	if err != nil {
		b.Fatalf("Encode() error = %v", err)
	}

	b.ReportAllocs()
	b.SetBytes(int64(len(payload)))
	b.ResetTimer()
	for range b.N {
		if _, err := Decode(wire); err != nil {
			b.Fatalf("Decode() error = %v", err)
		}
	}
}

func BenchmarkDecodeDomainPacket(b *testing.B) {
	payload := bytes.Repeat([]byte{0xab}, MaxPayloadSize)
	wire, err := Encode(Frame{
		Type:     FrameTypePacket,
		FlowID:   1,
		Endpoint: Endpoint{Host: testHost, Port: 443},
		Payload:  payload,
	})
	if err != nil {
		b.Fatalf("Encode() error = %v", err)
	}

	b.ReportAllocs()
	b.SetBytes(int64(len(payload)))
	b.ResetTimer()
	for range b.N {
		if _, err := Decode(wire); err != nil {
			b.Fatalf("Decode() error = %v", err)
		}
	}
}

func TestDecodeRejectsMalformedFrames(t *testing.T) {
	valid, err := Encode(Frame{
		Type:     FrameTypePacket,
		FlowID:   9,
		Endpoint: Endpoint{Host: testHost, Port: 53},
		Payload:  []byte("x"),
	})
	if err != nil {
		t.Fatalf("Encode(valid) error = %v", err)
	}

	tests := []struct {
		name string
		mut  func([]byte) []byte
		want error
	}{
		{
			name: "short",
			mut:  func([]byte) []byte { return []byte{1, 2, 3} },
			want: ErrFrameTooShort,
		},
		{
			name: "bad magic",
			mut: func(b []byte) []byte {
				b[0] = 'X'
				return b
			},
			want: ErrBadMagic,
		},
		{
			name: "bad version",
			mut: func(b []byte) []byte {
				b[2] = 99
				return b
			},
			want: ErrUnsupportedVersion,
		},
		{
			name: "unknown type",
			mut: func(b []byte) []byte {
				b[3] = 99
				return b
			},
			want: ErrUnknownFrameType,
		},
		{
			name: "zero flow",
			mut: func(b []byte) []byte {
				for i := 4; i < 12; i++ {
					b[i] = 0
				}
				return b
			},
			want: ErrInvalidFlowID,
		},
		{
			name: "bad address length",
			mut: func(b []byte) []byte {
				b[15] = 250
				return b
			},
			want: ErrFrameTooShort,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			buf := append([]byte(nil), valid...)
			_, err := Decode(tt.mut(buf))
			if !errors.Is(err, tt.want) {
				t.Fatalf("Decode() error = %v, want %v", err, tt.want)
			}
		})
	}
}
