package crypto

import (
	"fmt"
	"testing"
)

func BenchmarkKeySetSealInto(b *testing.B) {
	for _, size := range []int{1024, 12 * 1024, 60 * 1024} {
		b.Run(fmt.Sprintf("%dB", size), func(b *testing.B) {
			client, _ := newKeyPair(b)
			payload := make([]byte, size)
			dst := make([]byte, 0, size+WireOverhead)
			aad := []byte(testDataAAD)

			b.ReportAllocs()
			b.SetBytes(int64(size))
			for range b.N {
				sealed, err := client.SealInto(dst[:0], payload, aad)
				if err != nil {
					b.Fatalf("SealInto() error = %v", err)
				}
				if len(sealed) != size+WireOverhead {
					b.Fatalf("SealInto() length = %d", len(sealed))
				}
			}
		})
	}
}

func BenchmarkKeySetOpenInto(b *testing.B) {
	const recordBatch = 64

	for _, size := range []int{1024, 12 * 1024, 60 * 1024} {
		b.Run(fmt.Sprintf("%dB", size), func(b *testing.B) {
			client, server := newKeyPair(b)
			payload := make([]byte, size)
			aad := []byte(testDataAAD)
			records := make([][]byte, recordBatch)
			for i := range records {
				records[i] = make([]byte, 0, size+WireOverhead)
			}
			plaintext := make([]byte, 0, size)

			b.ReportAllocs()
			b.SetBytes(int64(size))
			b.ResetTimer()
			for completed := 0; completed < b.N; {
				count := min(recordBatch, b.N-completed)
				b.StopTimer()
				for i := range count {
					var err error
					records[i], err = client.SealInto(records[i][:0], payload, aad)
					if err != nil {
						b.Fatalf("SealInto() error = %v", err)
					}
				}
				b.StartTimer()
				for i := range count {
					opened, err := server.OpenInto(plaintext[:0], records[i], aad)
					if err != nil {
						b.Fatalf("OpenInto() error = %v", err)
					}
					if len(opened) != size {
						b.Fatalf("OpenInto() length = %d", len(opened))
					}
				}
				completed += count
			}
		})
	}
}
