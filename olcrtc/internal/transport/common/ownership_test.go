package common

import (
	"bytes"
	"hash/crc32"
	"testing"
	"time"
)

func TestSenderOwnsFramesBeforeSendReturns(t *testing.T) {
	done := make(chan struct{})
	queue := NewOutboundQueue(done, ErrAckTimeout)
	sender := NewSender(SenderConfig{
		Role:          RoleClient,
		FragmentSize:  4,
		MaxAttempts:   1,
		FrameInterval: time.Millisecond,
		BatchSize:     8,
		AckFloor:      time.Second,
	}, queue)
	payload := []byte("caller-buffer")
	want := bytes.Clone(payload)
	result := make(chan error, 1)
	go func() { result <- sender.Send(payload) }()

	frames := make([][]byte, 0, 4)
	for range 4 {
		frame := <-queue.data
		frames = append(frames, frame)
		decoded, err := DecodeFrame(frame)
		if err != nil {
			t.Fatalf("DecodeFrame() error = %v", err)
		}
		sender.Resolve(decoded.Seq, decoded.CRC, decoded.FragIdx)
	}
	if err := <-result; err != nil {
		t.Fatalf("Send() error = %v", err)
	}
	clear(payload)

	got := make([]byte, 0, len(want))
	for _, frame := range frames {
		decoded, err := DecodeFrame(frame)
		if err != nil {
			t.Fatalf("DecodeFrame() after reuse error = %v", err)
		}
		got = append(got, decoded.Payload...)
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("queued payload = %q, want %q", got, want)
	}
}

func TestReassemblerOwnsDecodedFragment(t *testing.T) {
	payload := []byte("abcdefgh")
	crc := crc32.ChecksumIEEE(payload)
	frames := [][]byte{
		EncodeData(RoleClient, 1, 1, crc, len(payload), 0, 2, payload[:4]),
		EncodeData(RoleClient, 1, 1, crc, len(payload), 1, 2, payload[4:]),
	}
	reassembler := NewReassembler(8)

	first, err := DecodeFrame(frames[0])
	if err != nil {
		t.Fatalf("DecodeFrame(first) error = %v", err)
	}
	if result, _ := reassembler.Push(frameFragment(first)); result != ResultPartial {
		t.Fatalf("Push(first) result = %v", result)
	}
	clear(frames[0])

	second, err := DecodeFrame(frames[1])
	if err != nil {
		t.Fatalf("DecodeFrame(second) error = %v", err)
	}
	result, got := reassembler.Push(frameFragment(second))
	if result != ResultDelivered || !bytes.Equal(got, payload) {
		t.Fatalf("Push(second) = (%v, %q), want delivered %q", result, got, payload)
	}
}

func frameFragment(frame Frame) Fragment {
	return Fragment{
		Seq:       frame.Seq,
		CRC:       frame.CRC,
		TotalLen:  frame.TotalLen,
		FragIdx:   frame.FragIdx,
		FragTotal: frame.FragTotal,
		FragCRC:   frame.FragCRC,
		Payload:   frame.Payload,
	}
}
