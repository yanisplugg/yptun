package muxconn

import (
	"context"
	"io"
	"testing"

	cryptopkg "github.com/openlibrecommunity/olcrtc/internal/crypto"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
)

const muxBenchmarkPayloadSize = 12 * 1024

type synchronousTransport struct {
	onSend func([]byte)
}

func (t *synchronousTransport) Connect(context.Context) error   { return nil }
func (t *synchronousTransport) Close() error                    { return nil }
func (t *synchronousTransport) SetReconnectCallback(func())     {}
func (t *synchronousTransport) SetShouldReconnect(func() bool)  {}
func (t *synchronousTransport) SetEndedCallback(func(string))   {}
func (t *synchronousTransport) WatchConnection(context.Context) {}
func (t *synchronousTransport) CanSend() bool                   { return true }
func (t *synchronousTransport) Features() transport.Features    { return transport.Features{} }
func (t *synchronousTransport) Reconnect(string)                {}
func (t *synchronousTransport) Send(data []byte) error {
	if t.onSend != nil {
		t.onSend(data)
	}
	return nil
}

func benchmarkKeyPair(b *testing.B) (*cryptopkg.KeySet, *cryptopkg.KeySet) {
	b.Helper()
	client, err := cryptopkg.NewKeySet([]byte("01234567890123456789012345678901"), cryptopkg.Client)
	if err != nil {
		b.Fatalf("NewKeySet(client) error = %v", err)
	}
	server, err := cryptopkg.NewKeySet([]byte("01234567890123456789012345678901"), cryptopkg.Server)
	if err != nil {
		b.Fatalf("NewKeySet(server) error = %v", err)
	}
	return client, server
}

func BenchmarkConnPushRead12KiB(b *testing.B) {
	const recordBatch = 64
	clientKeys, serverKeys := benchmarkKeyPair(b)
	conn := New(&synchronousTransport{}, serverKeys)
	payload := make([]byte, muxBenchmarkPayloadSize)
	records := make([][]byte, recordBatch)
	for i := range records {
		records[i] = make([]byte, 0, muxBenchmarkPayloadSize+cryptopkg.WireOverhead)
	}
	readBuf := make([]byte, muxBenchmarkPayloadSize)
	aad := []byte(dataRecordAAD)

	b.ReportAllocs()
	b.SetBytes(muxBenchmarkPayloadSize)
	b.ResetTimer()
	for completed := 0; completed < b.N; {
		count := min(recordBatch, b.N-completed)
		b.StopTimer()
		for i := range count {
			var err error
			records[i], err = clientKeys.SealInto(records[i][:0], payload, aad)
			if err != nil {
				b.Fatalf("SealInto() error = %v", err)
			}
		}
		b.StartTimer()
		for i := range count {
			conn.Push(records[i])
			if _, err := io.ReadFull(conn, readBuf); err != nil {
				b.Fatalf("ReadFull() error = %v", err)
			}
		}
		completed += count
	}
}

func BenchmarkConnWriteRead12KiB(b *testing.B) {
	clientKeys, serverKeys := benchmarkKeyPair(b)
	clientLink := &synchronousTransport{}
	server := New(&synchronousTransport{}, serverKeys)
	clientLink.onSend = server.Push
	client := New(clientLink, clientKeys)
	payload := make([]byte, muxBenchmarkPayloadSize)
	readBuf := make([]byte, muxBenchmarkPayloadSize)

	b.ReportAllocs()
	b.SetBytes(muxBenchmarkPayloadSize)
	for range b.N {
		if _, err := client.Write(payload); err != nil {
			b.Fatalf("Write() error = %v", err)
		}
		if _, err := io.ReadFull(server, readBuf); err != nil {
			b.Fatalf("ReadFull() error = %v", err)
		}
	}
}
