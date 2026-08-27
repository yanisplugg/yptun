package common

import (
	"hash/crc32"
	"testing"
	"time"
)

const (
	commonBenchmarkPayloadSize = 12 * 1024
	commonBenchmarkFragment    = 900
	commonBenchmarkFragTotal   = 14
)

func BenchmarkFrameEncodeDecode900B(b *testing.B) {
	payload := make([]byte, commonBenchmarkFragment)

	b.ReportAllocs()
	b.SetBytes(commonBenchmarkFragment)
	for range b.N {
		encoded := EncodeData(RoleClient, 0x12345678, 1, 0xdeadbeef,
			commonBenchmarkPayloadSize, 0, 14, payload)
		frame, err := DecodeFrame(encoded)
		if err != nil {
			b.Fatalf("DecodeFrame() error = %v", err)
		}
		if len(frame.Payload) != commonBenchmarkFragment {
			b.Fatalf("DecodeFrame() payload length = %d", len(frame.Payload))
		}
	}
}

func BenchmarkFragmentPayloadReassembler12KiB(b *testing.B) {
	payload := make([]byte, commonBenchmarkPayloadSize)
	crc := crc32.ChecksumIEEE(payload)
	reassembler := NewReassembler(256)

	b.ReportAllocs()
	b.SetBytes(commonBenchmarkPayloadSize)
	for i := range b.N {
		fragments := FragmentPayload(payload, commonBenchmarkFragment)
		for idx, fragment := range fragments {
			result, data := reassembler.Push(Fragment{
				Seq:       uint32(i + 1),
				CRC:       crc,
				TotalLen:  commonBenchmarkPayloadSize,
				FragIdx:   uint16(idx),
				FragTotal: commonBenchmarkFragTotal,
				Payload:   fragment,
			}.WithPayloadCRC())
			if idx == len(fragments)-1 && (result != ResultDelivered || len(data) != len(payload)) {
				b.Fatalf("final Push() = (%v, %d bytes)", result, len(data))
			}
		}
	}
}

func BenchmarkSenderBookkeeping12KiB(b *testing.B) {
	done := make(chan struct{})
	queue := NewOutboundQueue(done, ErrAckTimeout)
	sender := NewSender(SenderConfig{
		Role:          RoleClient,
		Binding:       0x12345678,
		FragmentSize:  commonBenchmarkFragment,
		MaxAttempts:   1,
		FrameInterval: time.Second / 30,
		BatchSize:     64,
		AckFloor:      time.Second,
	}, queue)
	payload := make([]byte, commonBenchmarkPayloadSize)
	fragmentCount := len(FragmentPayload(payload, commonBenchmarkFragment))
	requests := make(chan []byte)
	results := make(chan error)
	go func() {
		for data := range requests {
			results <- sender.Send(data)
		}
	}()
	b.Cleanup(func() { close(requests) })

	b.ReportAllocs()
	b.SetBytes(commonBenchmarkPayloadSize)
	for range b.N {
		requests <- payload
		for range fragmentCount {
			frame := <-queue.data
			decoded, err := DecodeFrame(frame)
			if err != nil {
				b.Fatalf("DecodeFrame() error = %v", err)
			}
			sender.Resolve(decoded.Seq, decoded.CRC, decoded.FragIdx)
		}
		if err := <-results; err != nil {
			b.Fatalf("Send() error = %v", err)
		}
	}
}
