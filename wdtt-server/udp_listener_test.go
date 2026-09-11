package main

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/pion/dtls/v3"
	"github.com/pion/dtls/v3/pkg/crypto/selfsign"
	"golang.org/x/net/ipv4"
)

type testUDPPacketWriter struct {
	mu sync.Mutex

	calls   [][]byte
	entered chan struct{}
	release chan struct{}
	once    sync.Once
	err     error
	n       int
}

func (w *testUDPPacketWriter) WriteTo(payload []byte, _ net.Addr) (int, error) {
	w.mu.Lock()
	w.calls = append(w.calls, bytes.Clone(payload))
	w.mu.Unlock()

	if w.entered != nil {
		w.once.Do(func() { close(w.entered) })
	}
	if w.release != nil {
		<-w.release
	}
	if w.err != nil {
		return w.n, w.err
	}
	if w.n > 0 {
		return w.n, nil
	}
	return len(payload), nil
}

func (w *testUDPPacketWriter) callCount() int {
	w.mu.Lock()
	defer w.mu.Unlock()
	return len(w.calls)
}

type testUDPBatchResult struct {
	n   int
	err error
}

type testUDPBatchWriter struct {
	mu sync.Mutex

	calls   [][][]byte
	results []testUDPBatchResult
}

func (w *testUDPBatchWriter) WriteBatch(messages []ipv4.Message, _ int) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()

	call := make([][]byte, len(messages))
	for i := range messages {
		if len(messages[i].Buffers) > 0 {
			call[i] = bytes.Clone(messages[i].Buffers[0])
		}
	}
	w.calls = append(w.calls, call)
	if len(w.results) == 0 {
		return len(messages), nil
	}
	result := w.results[0]
	w.results = w.results[1:]
	return result.n, result.err
}

func (w *testUDPBatchWriter) snapshotCalls() [][][]byte {
	w.mu.Lock()
	defer w.mu.Unlock()
	result := make([][][]byte, len(w.calls))
	for i := range w.calls {
		result[i] = make([][]byte, len(w.calls[i]))
		for j := range w.calls[i] {
			result[i][j] = bytes.Clone(w.calls[i][j])
		}
	}
	return result
}

type testUDPWriteOutcome struct {
	n   int
	err error
}

func waitForUDPWriterQueue(t *testing.T, writer *opportunisticUDPWriter, want int) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for writer.queueDepth() < want {
		if time.Now().After(deadline) {
			t.Fatalf("write queue depth = %d, want at least %d", writer.queueDepth(), want)
		}
		time.Sleep(time.Millisecond)
	}
}

func enqueueBlockedUDPWrites(
	t *testing.T,
	writer *opportunisticUDPWriter,
	count int,
) []<-chan testUDPWriteOutcome {
	t.Helper()
	outcomes := make([]<-chan testUDPWriteOutcome, 0, count)
	for i := 0; i < count; i++ {
		outcome := make(chan testUDPWriteOutcome, 1)
		payload := []byte{byte(i + 1)}
		go func() {
			n, err := writer.WriteTo(payload, &net.UDPAddr{Port: 20000 + int(payload[0])})
			outcome <- testUDPWriteOutcome{n: n, err: err}
		}()
		waitForUDPWriterQueue(t, writer, i+1)
		outcomes = append(outcomes, outcome)
	}
	return outcomes
}

func receiveUDPWriteOutcome(t *testing.T, outcome <-chan testUDPWriteOutcome) testUDPWriteOutcome {
	t.Helper()
	select {
	case result := <-outcome:
		return result
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for UDP write result")
		return testUDPWriteOutcome{}
	}
}

func TestOpportunisticUDPWriterSendsSinglePacketImmediately(t *testing.T) {
	direct := &testUDPPacketWriter{}
	batch := &testUDPBatchWriter{}
	writer := newOpportunisticUDPWriter(direct, batch, 8, 16)
	defer writer.Close()

	payload := []byte("single")
	n, err := writer.WriteTo(payload, &net.UDPAddr{Port: 12345})
	if err != nil {
		t.Fatalf("WriteTo: %v", err)
	}
	if n != len(payload) {
		t.Fatalf("WriteTo n = %d, want %d", n, len(payload))
	}
	if got := direct.callCount(); got != 1 {
		t.Fatalf("direct writes = %d, want 1", got)
	}
	if calls := batch.snapshotCalls(); len(calls) != 0 {
		t.Fatalf("batch writes = %d, want 0", len(calls))
	}
	stats := writer.stats.snapshot()
	if stats.SingleWrites != 1 || stats.BatchCalls != 0 || stats.WriteErrors != 0 {
		t.Fatalf("unexpected stats: %+v", stats)
	}
}

func TestOpportunisticUDPWriterBatchesConcurrentFollowersInOrder(t *testing.T) {
	direct := &testUDPPacketWriter{
		entered: make(chan struct{}),
		release: make(chan struct{}),
	}
	batch := &testUDPBatchWriter{}
	writer := newOpportunisticUDPWriter(direct, batch, 8, 16)
	defer writer.Close()

	first := make(chan testUDPWriteOutcome, 1)
	go func() {
		n, err := writer.WriteTo([]byte("leader"), &net.UDPAddr{Port: 10000})
		first <- testUDPWriteOutcome{n: n, err: err}
	}()
	select {
	case <-direct.entered:
	case <-time.After(2 * time.Second):
		t.Fatal("leader did not enter direct write")
	}

	followers := enqueueBlockedUDPWrites(t, writer, 6)
	close(direct.release)

	if result := receiveUDPWriteOutcome(t, first); result.err != nil || result.n != len("leader") {
		t.Fatalf("leader result = %+v", result)
	}
	for i, outcome := range followers {
		result := receiveUDPWriteOutcome(t, outcome)
		if result.err != nil || result.n != 1 {
			t.Fatalf("follower %d result = %+v", i, result)
		}
	}

	calls := batch.snapshotCalls()
	if len(calls) != 1 {
		t.Fatalf("batch calls = %d, want 1", len(calls))
	}
	if len(calls[0]) != len(followers) {
		t.Fatalf("batch size = %d, want %d", len(calls[0]), len(followers))
	}
	for i := range calls[0] {
		want := []byte{byte(i + 1)}
		if !bytes.Equal(calls[0][i], want) {
			t.Fatalf("batch payload %d = %v, want %v", i, calls[0][i], want)
		}
	}
	stats := writer.stats.snapshot()
	if stats.SingleWrites != 1 || stats.BatchCalls != 1 ||
		stats.BatchMessages != uint64(len(followers)) ||
		stats.QueueHighWater < uint64(len(followers)) {
		t.Fatalf("unexpected stats: %+v", stats)
	}
}

func TestOpportunisticUDPWriterReportsPartialBatchError(t *testing.T) {
	writeErr := errors.New("injected batch failure")
	direct := &testUDPPacketWriter{
		entered: make(chan struct{}),
		release: make(chan struct{}),
	}
	batch := &testUDPBatchWriter{
		results: []testUDPBatchResult{{n: 1, err: writeErr}},
	}
	writer := newOpportunisticUDPWriter(direct, batch, 8, 16)
	defer writer.Close()

	first := make(chan testUDPWriteOutcome, 1)
	go func() {
		n, err := writer.WriteTo([]byte("leader"), &net.UDPAddr{Port: 10000})
		first <- testUDPWriteOutcome{n: n, err: err}
	}()
	<-direct.entered
	followers := enqueueBlockedUDPWrites(t, writer, 3)
	close(direct.release)

	if result := receiveUDPWriteOutcome(t, first); result.err != nil {
		t.Fatalf("leader result = %+v", result)
	}
	if result := receiveUDPWriteOutcome(t, followers[0]); result.err != nil || result.n != 1 {
		t.Fatalf("sent follower result = %+v", result)
	}
	for i := 1; i < len(followers); i++ {
		result := receiveUDPWriteOutcome(t, followers[i])
		if !errors.Is(result.err, writeErr) || result.n != 0 {
			t.Fatalf("unsent follower %d result = %+v", i, result)
		}
	}
	stats := writer.stats.snapshot()
	if stats.PartialBatchWrites != 1 || stats.BatchMessages != 1 || stats.WriteErrors != 2 {
		t.Fatalf("unexpected stats: %+v", stats)
	}
}

func TestOpportunisticUDPWriterRejectsNoProgressBatch(t *testing.T) {
	direct := &testUDPPacketWriter{
		entered: make(chan struct{}),
		release: make(chan struct{}),
	}
	batch := &testUDPBatchWriter{
		results: []testUDPBatchResult{{n: 0}},
	}
	writer := newOpportunisticUDPWriter(direct, batch, 8, 16)
	defer writer.Close()

	first := make(chan testUDPWriteOutcome, 1)
	go func() {
		n, err := writer.WriteTo([]byte("leader"), &net.UDPAddr{Port: 10000})
		first <- testUDPWriteOutcome{n: n, err: err}
	}()
	<-direct.entered
	followers := enqueueBlockedUDPWrites(t, writer, 2)
	close(direct.release)
	_ = receiveUDPWriteOutcome(t, first)

	for i, outcome := range followers {
		result := receiveUDPWriteOutcome(t, outcome)
		if !errors.Is(result.err, io.ErrNoProgress) || result.n != 0 {
			t.Fatalf("follower %d result = %+v", i, result)
		}
	}
	stats := writer.stats.snapshot()
	if stats.PartialBatchWrites != 1 || stats.WriteErrors != 2 {
		t.Fatalf("unexpected stats: %+v", stats)
	}
}

func TestOpportunisticUDPWriterContinuesAfterPartialBatchWithoutError(t *testing.T) {
	direct := &testUDPPacketWriter{
		entered: make(chan struct{}),
		release: make(chan struct{}),
	}
	batch := &testUDPBatchWriter{
		results: []testUDPBatchResult{
			{n: 1},
			{n: 2},
		},
	}
	writer := newOpportunisticUDPWriter(direct, batch, 8, 16)
	defer writer.Close()

	first := make(chan testUDPWriteOutcome, 1)
	go func() {
		n, err := writer.WriteTo([]byte("leader"), &net.UDPAddr{Port: 10000})
		first <- testUDPWriteOutcome{n: n, err: err}
	}()
	<-direct.entered
	followers := enqueueBlockedUDPWrites(t, writer, 3)
	close(direct.release)
	_ = receiveUDPWriteOutcome(t, first)

	for i, outcome := range followers {
		result := receiveUDPWriteOutcome(t, outcome)
		if result.err != nil || result.n != 1 {
			t.Fatalf("follower %d result = %+v", i, result)
		}
	}
	stats := writer.stats.snapshot()
	if stats.BatchCalls != 2 || stats.BatchMessages != 3 ||
		stats.PartialBatchWrites != 1 || stats.WriteErrors != 0 {
		t.Fatalf("unexpected stats: %+v", stats)
	}
}

func TestOpportunisticUDPWriterReportsShortDirectWrite(t *testing.T) {
	direct := &testUDPPacketWriter{n: 2}
	writer := newOpportunisticUDPWriter(direct, &testUDPBatchWriter{}, 8, 16)
	defer writer.Close()

	n, err := writer.WriteTo([]byte("short"), &net.UDPAddr{Port: 12345})
	if n != 2 || !errors.Is(err, io.ErrShortWrite) {
		t.Fatalf("WriteTo = (%d, %v), want (2, io.ErrShortWrite)", n, err)
	}
	stats := writer.stats.snapshot()
	if stats.SingleWrites != 1 || stats.WriteErrors != 1 {
		t.Fatalf("unexpected stats: %+v", stats)
	}
}

func TestOpportunisticUDPWriterAppliesBackpressureAtQueueLimit(t *testing.T) {
	direct := &testUDPPacketWriter{
		entered: make(chan struct{}),
		release: make(chan struct{}),
	}
	writer := newOpportunisticUDPWriter(direct, &testUDPBatchWriter{}, 2, 2)
	defer writer.Close()

	first := make(chan testUDPWriteOutcome, 1)
	go func() {
		n, err := writer.WriteTo([]byte("leader"), &net.UDPAddr{Port: 10000})
		first <- testUDPWriteOutcome{n: n, err: err}
	}()
	<-direct.entered
	followers := enqueueBlockedUDPWrites(t, writer, 2)

	blocked := make(chan testUDPWriteOutcome, 1)
	go func() {
		n, err := writer.WriteTo([]byte("blocked"), &net.UDPAddr{Port: 10003})
		blocked <- testUDPWriteOutcome{n: n, err: err}
	}()
	select {
	case result := <-blocked:
		t.Fatalf("write bypassed full queue: %+v", result)
	case <-time.After(30 * time.Millisecond):
	}

	close(direct.release)
	if result := receiveUDPWriteOutcome(t, first); result.err != nil {
		t.Fatalf("leader result = %+v", result)
	}
	for i, outcome := range followers {
		if result := receiveUDPWriteOutcome(t, outcome); result.err != nil {
			t.Fatalf("follower %d result = %+v", i, result)
		}
	}
	if result := receiveUDPWriteOutcome(t, blocked); result.err != nil {
		t.Fatalf("blocked result = %+v", result)
	}
	if stats := writer.stats.snapshot(); stats.QueueHighWater != 2 {
		t.Fatalf("queue high-water = %d, want 2", stats.QueueHighWater)
	}
}

func TestOpportunisticUDPWriterCancelsQueuedConnectionWrite(t *testing.T) {
	direct := &testUDPPacketWriter{
		entered: make(chan struct{}),
		release: make(chan struct{}),
	}
	batch := &testUDPBatchWriter{}
	writer := newOpportunisticUDPWriter(direct, batch, 8, 16)
	defer writer.Close()

	first := make(chan testUDPWriteOutcome, 1)
	go func() {
		n, err := writer.WriteTo([]byte("leader"), &net.UDPAddr{Port: 10000})
		first <- testUDPWriteOutcome{n: n, err: err}
	}()
	<-direct.entered

	connectionClosed := make(chan struct{})
	canceled := make(chan testUDPWriteOutcome, 1)
	go func() {
		n, err := writer.writeTo(
			[]byte("must-not-send"),
			&net.UDPAddr{Port: 10001},
			connectionClosed,
			nil,
		)
		canceled <- testUDPWriteOutcome{n: n, err: err}
	}()
	waitForUDPWriterQueue(t, writer, 1)
	close(connectionClosed)

	result := receiveUDPWriteOutcome(t, canceled)
	if result.n != 0 || !errors.Is(result.err, net.ErrClosed) {
		t.Fatalf("canceled write result = %+v, want net.ErrClosed", result)
	}
	close(direct.release)
	if result = receiveUDPWriteOutcome(t, first); result.err != nil {
		t.Fatalf("leader result = %+v", result)
	}
	if calls := batch.snapshotCalls(); len(calls) != 0 {
		t.Fatalf("canceled request reached WriteBatch: %v", calls)
	}
}

func TestOpportunisticUDPWriterHonorsDeadlineWhileQueueFull(t *testing.T) {
	direct := &testUDPPacketWriter{
		entered: make(chan struct{}),
		release: make(chan struct{}),
	}
	writer := newOpportunisticUDPWriter(direct, &testUDPBatchWriter{}, 2, 2)
	defer writer.Close()

	first := make(chan testUDPWriteOutcome, 1)
	go func() {
		n, err := writer.WriteTo([]byte("leader"), &net.UDPAddr{Port: 10000})
		first <- testUDPWriteOutcome{n: n, err: err}
	}()
	<-direct.entered
	followers := enqueueBlockedUDPWrites(t, writer, 2)

	deadline := make(chan struct{})
	expired := make(chan testUDPWriteOutcome, 1)
	go func() {
		n, err := writer.writeTo(
			[]byte("expired"),
			&net.UDPAddr{Port: 10003},
			nil,
			deadline,
		)
		expired <- testUDPWriteOutcome{n: n, err: err}
	}()
	close(deadline)

	result := receiveUDPWriteOutcome(t, expired)
	if result.n != 0 || !errors.Is(result.err, context.DeadlineExceeded) {
		t.Fatalf("expired write result = %+v, want context deadline exceeded", result)
	}
	if depth := writer.queueDepth(); depth != 2 {
		t.Fatalf("queue depth after expired write = %d, want 2", depth)
	}

	close(direct.release)
	if result = receiveUDPWriteOutcome(t, first); result.err != nil {
		t.Fatalf("leader result = %+v", result)
	}
	for i, outcome := range followers {
		if result = receiveUDPWriteOutcome(t, outcome); result.err != nil {
			t.Fatalf("follower %d result = %+v", i, result)
		}
	}
}

func TestOpportunisticUDPWriterCloseFailsQueuedFollowers(t *testing.T) {
	direct := &testUDPPacketWriter{
		entered: make(chan struct{}),
		release: make(chan struct{}),
	}
	writer := newOpportunisticUDPWriter(direct, &testUDPBatchWriter{}, 8, 16)

	first := make(chan testUDPWriteOutcome, 1)
	go func() {
		n, err := writer.WriteTo([]byte("leader"), &net.UDPAddr{Port: 10000})
		first <- testUDPWriteOutcome{n: n, err: err}
	}()
	<-direct.entered
	followers := enqueueBlockedUDPWrites(t, writer, 3)

	closed := make(chan struct{})
	go func() {
		writer.Close()
		close(closed)
	}()
	closeDeadline := time.Now().Add(2 * time.Second)
	for {
		writer.queueMu.Lock()
		isClosed := writer.closed
		writer.queueMu.Unlock()
		if isClosed {
			break
		}
		if time.Now().After(closeDeadline) {
			t.Fatal("writer Close did not begin")
		}
		time.Sleep(time.Millisecond)
	}
	close(direct.release)

	_ = receiveUDPWriteOutcome(t, first)
	for i, outcome := range followers {
		result := receiveUDPWriteOutcome(t, outcome)
		if !errors.Is(result.err, net.ErrClosed) {
			t.Fatalf("follower %d error = %v, want net.ErrClosed", i, result.err)
		}
	}
	select {
	case <-closed:
	case <-time.After(2 * time.Second):
		t.Fatal("writer Close did not finish")
	}

	if _, err := writer.WriteTo([]byte("late"), &net.UDPAddr{Port: 10001}); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("WriteTo after Close error = %v, want net.ErrClosed", err)
	}
}

func TestOpportunisticUDPListenerRoundTripAndFilter(t *testing.T) {
	listener, err := listenOpportunisticUDP(
		"udp4",
		&net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)},
		opportunisticUDPListenConfig{
			Backlog:        8,
			AcceptFilter:   func(payload []byte) bool { return bytes.HasPrefix(payload, []byte("ok:")) },
			ReadBatchSize:  4,
			WriteBatchSize: 4,
			WriteQueueSize: 8,
		},
	)
	if err != nil {
		t.Fatalf("listenOpportunisticUDP: %v", err)
	}
	defer listener.Close()
	if listener.batchRead == nil {
		t.Fatal("Linux UDP listener did not enable batch read")
	}

	client, err := net.DialUDP("udp4", nil, listener.Addr().(*net.UDPAddr))
	if err != nil {
		t.Fatalf("DialUDP: %v", err)
	}
	defer client.Close()

	accepted := make(chan net.PacketConn, 1)
	acceptErr := make(chan error, 1)
	go func() {
		conn, _, acceptError := listener.Accept()
		if acceptError != nil {
			acceptErr <- acceptError
			return
		}
		accepted <- conn
	}()

	if _, err = client.Write([]byte("reject")); err != nil {
		t.Fatalf("write rejected datagram: %v", err)
	}
	select {
	case err = <-acceptErr:
		t.Fatalf("Accept returned after rejected datagram: %v", err)
	case <-accepted:
		t.Fatal("Accept returned a connection for a rejected datagram")
	case <-time.After(30 * time.Millisecond):
	}

	request := []byte("ok:request")
	if _, err = client.Write(request); err != nil {
		t.Fatalf("write accepted datagram: %v", err)
	}

	var serverConn net.PacketConn
	select {
	case serverConn = <-accepted:
	case err = <-acceptErr:
		t.Fatalf("Accept: %v", err)
	case <-time.After(2 * time.Second):
		t.Fatal("Accept timed out")
	}
	defer serverConn.Close()

	if err = serverConn.SetReadDeadline(time.Now().Add(2 * time.Second)); err != nil {
		t.Fatalf("SetReadDeadline: %v", err)
	}
	buffer := make([]byte, 64)
	n, remote, err := serverConn.ReadFrom(buffer)
	if err != nil {
		t.Fatalf("server ReadFrom: %v", err)
	}
	if !bytes.Equal(buffer[:n], request) {
		t.Fatalf("server payload = %q, want %q", buffer[:n], request)
	}

	response := []byte("response")
	if n, err = serverConn.WriteTo(response, remote); err != nil || n != len(response) {
		t.Fatalf("server WriteTo = (%d, %v)", n, err)
	}
	if err = client.SetReadDeadline(time.Now().Add(2 * time.Second)); err != nil {
		t.Fatalf("client SetReadDeadline: %v", err)
	}
	n, err = client.Read(buffer)
	if err != nil {
		t.Fatalf("client Read: %v", err)
	}
	if !bytes.Equal(buffer[:n], response) {
		t.Fatalf("client payload = %q, want %q", buffer[:n], response)
	}
}

func TestOpportunisticUDPListenerKeepsAcceptedConnectionAfterClose(t *testing.T) {
	listener, err := listenOpportunisticUDP(
		"udp4",
		&net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)},
		opportunisticUDPListenConfig{},
	)
	if err != nil {
		t.Fatalf("listenOpportunisticUDP: %v", err)
	}

	client, err := net.DialUDP("udp4", nil, listener.Addr().(*net.UDPAddr))
	if err != nil {
		t.Fatalf("DialUDP: %v", err)
	}
	defer client.Close()
	if _, err = client.Write([]byte("hello")); err != nil {
		t.Fatalf("client Write: %v", err)
	}

	serverConn, remote, err := listener.Accept()
	if err != nil {
		t.Fatalf("Accept: %v", err)
	}
	if err = listener.Close(); err != nil {
		t.Fatalf("listener Close: %v", err)
	}
	if _, _, err = listener.Accept(); !errors.Is(err, errOpportunisticUDPListenerClosed) {
		t.Fatalf("Accept after Close error = %v", err)
	}

	response := []byte("still-alive")
	if n, writeErr := serverConn.WriteTo(response, remote); writeErr != nil || n != len(response) {
		t.Fatalf("server WriteTo after listener Close = (%d, %v)", n, writeErr)
	}
	if err = client.SetReadDeadline(time.Now().Add(2 * time.Second)); err != nil {
		t.Fatalf("SetReadDeadline: %v", err)
	}
	buffer := make([]byte, 64)
	n, err := client.Read(buffer)
	if err != nil {
		t.Fatalf("client Read: %v", err)
	}
	if !bytes.Equal(buffer[:n], response) {
		t.Fatalf("response = %q, want %q", buffer[:n], response)
	}
	if err = serverConn.Close(); err != nil {
		t.Fatalf("server connection Close: %v", err)
	}
}

func TestOpportunisticUDPListenerSupportsDTLSRoundTrip(t *testing.T) {
	transport, err := listenOpportunisticUDP(
		"udp4",
		&net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)},
		opportunisticUDPListenConfig{
			Backlog:        8,
			ReadBatchSize:  8,
			WriteBatchSize: 8,
			WriteQueueSize: 16,
		},
	)
	if err != nil {
		t.Fatalf("listenOpportunisticUDP: %v", err)
	}

	certificate, err := selfsign.GenerateSelfSigned()
	if err != nil {
		t.Fatalf("GenerateSelfSigned: %v", err)
	}
	server, err := dtls.NewListenerWithOptions(
		transport,
		dtls.WithCertificates(certificate),
		dtls.WithExtendedMasterSecret(dtls.RequireExtendedMasterSecret),
		dtls.WithCipherSuites(dtls.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256),
	)
	if err != nil {
		t.Fatalf("NewListenerWithOptions: %v", err)
	}
	defer server.Close()

	serverDone := make(chan error, 1)
	go func() {
		conn, acceptErr := server.Accept()
		if acceptErr != nil {
			serverDone <- acceptErr
			return
		}
		defer conn.Close()
		if deadlineErr := conn.SetDeadline(time.Now().Add(3 * time.Second)); deadlineErr != nil {
			serverDone <- deadlineErr
			return
		}
		buffer := make([]byte, 32)
		n, readErr := conn.Read(buffer)
		if readErr != nil {
			serverDone <- readErr
			return
		}
		if !bytes.Equal(buffer[:n], []byte("ping")) {
			serverDone <- errors.New("unexpected DTLS request")
			return
		}
		if _, writeErr := conn.Write([]byte("pong")); writeErr != nil {
			serverDone <- writeErr
			return
		}
		serverDone <- nil
	}()

	client, err := dtls.DialWithOptions(
		"udp4",
		server.Addr().(*net.UDPAddr),
		dtls.WithCertificates(certificate),
		dtls.WithInsecureSkipVerify(true),
		dtls.WithExtendedMasterSecret(dtls.RequireExtendedMasterSecret),
		dtls.WithCipherSuites(dtls.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256),
	)
	if err != nil {
		t.Fatalf("DialWithOptions: %v", err)
	}
	defer client.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if err = client.HandshakeContext(ctx); err != nil {
		t.Fatalf("HandshakeContext: %v", err)
	}
	if err = client.SetDeadline(time.Now().Add(3 * time.Second)); err != nil {
		t.Fatalf("client SetDeadline: %v", err)
	}
	if _, err = client.Write([]byte("ping")); err != nil {
		t.Fatalf("client Write: %v", err)
	}
	buffer := make([]byte, 32)
	n, err := client.Read(buffer)
	if err != nil {
		t.Fatalf("client Read: %v", err)
	}
	if !bytes.Equal(buffer[:n], []byte("pong")) {
		t.Fatalf("DTLS response = %q, want pong", buffer[:n])
	}
	select {
	case err = <-serverDone:
		if err != nil {
			t.Fatalf("DTLS server: %v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("DTLS server did not finish")
	}
}

func TestOpportunisticUDPListenerSupportsWrappedDTLSRoundTrip(t *testing.T) {
	keys := newWrapKeyStore()
	const password = "wrapped-dtls-test-password"
	if err := keys.SetPasswords(password, nil); err != nil {
		t.Fatalf("SetPasswords: %v", err)
	}
	packetListener, err := listenWrapped(
		&net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)},
		keys,
	)
	if err != nil {
		t.Fatalf("listenWrapped: %v", err)
	}

	certificate, err := selfsign.GenerateSelfSigned()
	if err != nil {
		t.Fatalf("GenerateSelfSigned: %v", err)
	}
	server, err := dtls.NewListenerWithOptions(
		packetListener,
		dtls.WithCertificates(certificate),
		dtls.WithExtendedMasterSecret(dtls.RequireExtendedMasterSecret),
		dtls.WithCipherSuites(dtls.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256),
	)
	if err != nil {
		t.Fatalf("NewListenerWithOptions: %v", err)
	}
	defer server.Close()

	serverDone := make(chan error, 1)
	go func() {
		conn, acceptErr := server.Accept()
		if acceptErr != nil {
			serverDone <- acceptErr
			return
		}
		defer conn.Close()
		if deadlineErr := conn.SetDeadline(time.Now().Add(3 * time.Second)); deadlineErr != nil {
			serverDone <- deadlineErr
			return
		}
		buffer := make([]byte, 32)
		n, readErr := conn.Read(buffer)
		if readErr != nil {
			serverDone <- readErr
			return
		}
		if !bytes.Equal(buffer[:n], []byte("wrapped-ping")) {
			serverDone <- errors.New("unexpected wrapped DTLS request")
			return
		}
		if _, writeErr := conn.Write([]byte("wrapped-pong")); writeErr != nil {
			serverDone <- writeErr
			return
		}
		serverDone <- nil
	}()

	clientUDP, err := net.ListenUDP("udp4", nil)
	if err != nil {
		t.Fatalf("client ListenUDP: %v", err)
	}
	key, err := deriveWrapKey(password)
	if err != nil {
		t.Fatalf("deriveWrapKey: %v", err)
	}
	wrappedClient := &wrapPacketConn{
		inner: clientUDP,
		key:   key,
	}
	atomic.StoreInt32(&wrappedClient.selected, 1)

	client, err := dtls.ClientWithOptions(
		wrappedClient,
		server.Addr(),
		dtls.WithCertificates(certificate),
		dtls.WithInsecureSkipVerify(true),
		dtls.WithExtendedMasterSecret(dtls.RequireExtendedMasterSecret),
		dtls.WithCipherSuites(dtls.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256),
	)
	if err != nil {
		t.Fatalf("ClientWithOptions: %v", err)
	}
	defer client.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if err = client.HandshakeContext(ctx); err != nil {
		t.Fatalf("HandshakeContext: %v", err)
	}
	if err = client.SetDeadline(time.Now().Add(3 * time.Second)); err != nil {
		t.Fatalf("client SetDeadline: %v", err)
	}
	if _, err = client.Write([]byte("wrapped-ping")); err != nil {
		t.Fatalf("client Write: %v", err)
	}
	buffer := make([]byte, 32)
	n, err := client.Read(buffer)
	if err != nil {
		t.Fatalf("client Read: %v", err)
	}
	if !bytes.Equal(buffer[:n], []byte("wrapped-pong")) {
		t.Fatalf("wrapped DTLS response = %q, want wrapped-pong", buffer[:n])
	}
	select {
	case err = <-serverDone:
		if err != nil {
			t.Fatalf("wrapped DTLS server: %v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("wrapped DTLS server did not finish")
	}
}
