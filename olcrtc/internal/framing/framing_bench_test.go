package framing

import (
	"bytes"
	"encoding/binary"
	"testing"
)

func BenchmarkReadBytes256B(b *testing.B) {
	body := make([]byte, 256)
	frame := make([]byte, 4+len(body))
	binary.BigEndian.PutUint32(frame[:4], 256)
	copy(frame[4:], body)
	reader := bytes.NewReader(frame)

	b.ReportAllocs()
	b.SetBytes(int64(len(body)))
	for range b.N {
		reader.Reset(frame)
		got, err := ReadBytes(reader, len(body))
		if err != nil {
			b.Fatalf("ReadBytes() error = %v", err)
		}
		if len(got) != len(body) {
			b.Fatalf("ReadBytes() length = %d", len(got))
		}
	}
}
