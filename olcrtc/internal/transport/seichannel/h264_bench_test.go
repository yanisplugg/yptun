package seichannel

import "testing"

func BenchmarkBuildVideoAccessUnit900B(b *testing.B) {
	payload := make([]byte, 900)

	b.ReportAllocs()
	b.SetBytes(int64(len(payload)))
	for range b.N {
		accessUnit := buildVideoAccessUnit(payload)
		if len(accessUnit) <= len(payload) {
			b.Fatalf("buildVideoAccessUnit() length = %d", len(accessUnit))
		}
	}
}

func BenchmarkExtractVideoPayloads900B(b *testing.B) {
	payload := make([]byte, 900)
	accessUnit := buildVideoAccessUnit(payload)

	b.ReportAllocs()
	b.SetBytes(int64(len(payload)))
	for range b.N {
		payloads := extractVideoPayloads(accessUnit)
		if len(payloads) != 1 || len(payloads[0]) != len(payload) {
			b.Fatalf("extractVideoPayloads() returned %d payloads", len(payloads))
		}
	}
}
