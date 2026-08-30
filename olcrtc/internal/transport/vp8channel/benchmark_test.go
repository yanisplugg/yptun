package vp8channel

import (
	"encoding/binary"
	"hash/crc32"
	"testing"

	"github.com/pion/rtp"
)

func BenchmarkBatchSampleFrom12KiB(b *testing.B) {
	const packetSize = 900
	packetCount := (12*1024 + packetSize - 1) / packetSize
	frames := make([]*packetBuffer, packetCount)
	hdr := testEpochHdr(1)
	for i := range frames {
		frames[i] = &packetBuffer{data: make([]byte, epochHdrLen+packetSize)}
		copy(frames[i].data[:epochHdrLen], hdr[:])
	}
	src := make(chan *packetBuffer, packetCount-1)
	tr := &streamTransport{batchSize: packetCount}
	var scratch []byte

	b.ReportAllocs()
	b.SetBytes(12 * 1024)
	for range b.N {
		for _, frame := range frames[1:] {
			src <- frame
		}
		sample, pending := tr.batchSampleFrom(src, frames[0], scratch[:0])
		if len(sample) == 0 {
			b.Fatal("batchSampleFrom() returned an empty sample")
		}
		if pending != nil {
			b.Fatal("batchSampleFrom() unexpectedly left a pending packet")
		}
		scratch = sample[:0]
	}
}

func BenchmarkKCPConnWriteTo(b *testing.B) {
	payload := make([]byte, kcpMTU)
	out := make(chan *packetBuffer, 1)
	conn := newKCPConn(out, 1, testEpochHdr(1))

	b.ReportAllocs()
	b.SetBytes(int64(len(payload)))
	for range b.N {
		if _, err := conn.WriteTo(payload, nil); err != nil {
			b.Fatalf("WriteTo() error = %v", err)
		}
		packet := <-out
		if len(packet.data) != epochHdrLen+len(payload)+wireCRCLen {
			b.Fatal("WriteTo() returned an unexpected wire size")
		}
		packet.release()
	}
}

func BenchmarkKCPConnDeliverReadFrom(b *testing.B) {
	body := make([]byte, kcpMTU)
	wire := make([]byte, len(body)+wireCRCLen)
	copy(wire, body)
	binary.BigEndian.PutUint32(wire[len(body):], crc32.Checksum(body, crcTable))
	conn := newKCPConn(make(chan *packetBuffer, 1), 1, testEpochHdr(1))
	dst := make([]byte, len(body))

	b.ReportAllocs()
	b.SetBytes(int64(len(body)))
	for range b.N {
		conn.deliver(wire)
		n, _, err := conn.ReadFrom(dst)
		if err != nil {
			b.Fatalf("ReadFrom() error = %v", err)
		}
		if n != len(body) {
			b.Fatalf("ReadFrom() length = %d", n)
		}
	}
}

func BenchmarkRTPReorderFrameAssembly12KiB(b *testing.B) {
	const rtpPayloadSize = 1200
	frame := make([]byte, 12*1024)
	packetCount := (len(frame) + rtpPayloadSize - 1) / rtpPayloadSize
	packets := make([]rtp.Packet, packetCount)
	for i := range packets {
		start := i * rtpPayloadSize
		end := min(start+rtpPayloadSize, len(frame))
		packets[i].Payload = make([]byte, 1+end-start)
		copy(packets[i].Payload[1:], frame[start:end])
		if i == 0 {
			packets[i].Payload[0] = 0x10
		}
		packets[i].Marker = i == packetCount-1
	}
	order := benchmarkReorderOrder(packetCount)
	reorder := newReorderBuffer()
	var state vp8FrameState
	var base uint16

	b.ReportAllocs()
	b.SetBytes(int64(len(frame)))
	b.ReportMetric(float64(packetCount), "rtp_packets/op")
	for range b.N {
		for i := range packets {
			packets[i].SequenceNumber = base + uint16(i)
		}
		var assembled []byte
		for _, idx := range order {
			reorder.push(&packets[idx], func(ordered *rtp.Packet) {
				if complete := state.processRTPPacket(ordered); complete != nil {
					assembled = complete
				}
			})
		}
		if len(assembled) != len(frame) {
			b.Fatalf("assembled frame length = %d", len(assembled))
		}
		base += uint16(packetCount) //nolint:gosec // RTP sequence wrap is intentional
	}
}

func benchmarkReorderOrder(packetCount int) []int {
	order := make([]int, 0, packetCount)
	for i := 0; i < packetCount; {
		if i+2 < packetCount {
			order = append(order, i, i+2, i+1)
			i += 3
			continue
		}
		order = append(order, i)
		i++
	}
	return order
}
