package videochannel

import "testing"

func BenchmarkRenderVisualFrame(b *testing.B) {
	for _, codec := range []string{"qrcode", codecTile} {
		b.Run(codec, func(b *testing.B) {
			payload := make([]byte, 256)
			b.ReportAllocs()
			b.SetBytes(int64(len(payload)))
			for range b.N {
				frame, err := renderVisualFrame(payload, 1080, 1080, codec, "low", 4, 20)
				if err != nil {
					b.Fatalf("renderVisualFrame() error = %v", err)
				}
				if len(frame) != 1080*1080 {
					b.Fatalf("renderVisualFrame() length = %d", len(frame))
				}
			}
		})
	}
}

func BenchmarkExtractVisualPayload(b *testing.B) {
	for _, codec := range []string{"qrcode", codecTile} {
		b.Run(codec, func(b *testing.B) {
			payload := make([]byte, 256)
			frame, err := renderVisualFrame(payload, 1080, 1080, codec, "low", 4, 20)
			if err != nil {
				b.Fatalf("renderVisualFrame() error = %v", err)
			}

			b.ReportAllocs()
			b.SetBytes(int64(len(payload)))
			b.ResetTimer()
			for range b.N {
				extracted, extractErr := extractVisualPayload(frame, 1080, 1080, codec, 4, 20)
				if extractErr != nil {
					b.Fatalf("extractVisualPayload() error = %v", extractErr)
				}
				if len(extracted) != len(payload) {
					b.Fatalf("extractVisualPayload() length = %d", len(extracted))
				}
			}
		})
	}
}

func BenchmarkRenderVisualIdleFrame(b *testing.B) {
	b.ReportAllocs()
	for range b.N {
		frame, err := renderVisualFrame(nil, 1080, 1080, "qrcode", "low", 4, 20)
		if err != nil {
			b.Fatalf("renderVisualFrame() error = %v", err)
		}
		if len(frame) != 1080*1080 {
			b.Fatalf("renderVisualFrame() length = %d", len(frame))
		}
	}
}

func BenchmarkStreamTransportRenderFrame(b *testing.B) {
	for _, codec := range []string{"qrcode", codecTile} {
		b.Run(codec, func(b *testing.B) {
			tr := &streamTransport{
				videoW:          1080,
				videoH:          1080,
				videoQRRecovery: "low",
				videoCodec:      codec,
				videoTileModule: 4,
				videoTileRS:     20,
			}
			payload := make([]byte, 256)

			b.ReportAllocs()
			b.SetBytes(int64(len(payload)))
			for range b.N {
				frame, err := tr.renderFrame(payload)
				if err != nil {
					b.Fatalf("renderFrame() error = %v", err)
				}
				if len(frame) != 1080*1080 {
					b.Fatalf("renderFrame() length = %d", len(frame))
				}
			}
		})
	}
}

func BenchmarkStreamTransportRenderIdleFrame(b *testing.B) {
	tr := &streamTransport{
		videoW:          1080,
		videoH:          1080,
		videoQRRecovery: "low",
		videoCodec:      "qrcode",
		videoTileModule: 4,
		videoTileRS:     20,
	}

	b.ReportAllocs()
	for range b.N {
		frame, err := tr.renderFrame(nil)
		if err != nil {
			b.Fatalf("renderFrame() error = %v", err)
		}
		if len(frame) != 1080*1080 {
			b.Fatalf("renderFrame() length = %d", len(frame))
		}
	}
}

func BenchmarkStreamTransportExtractFrame(b *testing.B) {
	for _, codec := range []string{"qrcode", codecTile} {
		b.Run(codec, func(b *testing.B) {
			tr := &streamTransport{
				videoW:          1080,
				videoH:          1080,
				videoQRRecovery: "low",
				videoCodec:      codec,
				videoTileModule: 4,
				videoTileRS:     20,
			}
			payload := make([]byte, 256)
			frame, err := tr.renderFrame(payload)
			if err != nil {
				b.Fatalf("renderFrame() error = %v", err)
			}

			b.ReportAllocs()
			b.SetBytes(int64(len(payload)))
			b.ResetTimer()
			for range b.N {
				extracted, extractErr := tr.extractFrame(frame)
				if extractErr != nil {
					b.Fatalf("extractFrame() error = %v", extractErr)
				}
				if len(extracted) != len(payload) {
					b.Fatalf("extractFrame() length = %d", len(extracted))
				}
			}
		})
	}
}
