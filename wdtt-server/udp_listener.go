// SPDX-FileCopyrightText: 2023 The Pion community <https://pion.ly>
// SPDX-FileCopyrightText: 2026 The WDTT Plus contributors
// SPDX-License-Identifier: MIT

package main

import (
	"context"
	"errors"
	"io"
	"net"
	"runtime"
	"sync"
	"sync/atomic"
	"time"

	"github.com/pion/transport/v4/deadline"
	"github.com/pion/transport/v4/packetio"
	"golang.org/x/net/ipv4"
)

const (
	opportunisticUDPReceiveMTU            = 8192
	opportunisticUDPDefaultListenBacklog  = 128
	opportunisticUDPDefaultReadBatchSize  = 64
	opportunisticUDPDefaultWriteBatchSize = 32
	opportunisticUDPDefaultWriteQueueSize = 4096
)

var errOpportunisticUDPListenerClosed = errors.New("udp: listener closed")

type opportunisticUDPListenConfig struct {
	Backlog         int
	AcceptFilter    func([]byte) bool
	ReadBufferSize  int
	WriteBufferSize int
	ReadBatchSize   int
	WriteBatchSize  int
	WriteQueueSize  int
}

type udpBatchReader interface {
	ReadBatch([]ipv4.Message, int) (int, error)
}

type udpBatchWriter interface {
	WriteBatch([]ipv4.Message, int) (int, error)
}

type udpPacketWriter interface {
	WriteTo([]byte, net.Addr) (int, error)
}

type opportunisticUDPWriteResult struct {
	n   int
	err error
}

type opportunisticUDPWriteRequest struct {
	payload []byte
	addr    net.Addr
	result  chan opportunisticUDPWriteResult
	state   atomic.Uint32
}

const (
	opportunisticUDPWriteQueued uint32 = iota
	opportunisticUDPWriteExecuting
	opportunisticUDPWriteCanceled
)

type opportunisticUDPWriteStats struct {
	singleWrites       atomic.Uint64
	batchCalls         atomic.Uint64
	batchMessages      atomic.Uint64
	partialBatchWrites atomic.Uint64
	writeErrors        atomic.Uint64
	queueHighWater     atomic.Uint64
}

type opportunisticUDPWriteStatsSnapshot struct {
	SingleWrites       uint64
	BatchCalls         uint64
	BatchMessages      uint64
	PartialBatchWrites uint64
	WriteErrors        uint64
	QueueHighWater     uint64
}

func (s *opportunisticUDPWriteStats) snapshot() opportunisticUDPWriteStatsSnapshot {
	if s == nil {
		return opportunisticUDPWriteStatsSnapshot{}
	}
	return opportunisticUDPWriteStatsSnapshot{
		SingleWrites:       s.singleWrites.Load(),
		BatchCalls:         s.batchCalls.Load(),
		BatchMessages:      s.batchMessages.Load(),
		PartialBatchWrites: s.partialBatchWrites.Load(),
		WriteErrors:        s.writeErrors.Load(),
		QueueHighWater:     s.queueHighWater.Load(),
	}
}

func (s *opportunisticUDPWriteStats) observeQueueDepth(depth int) {
	if s == nil || depth <= 0 {
		return
	}
	next := uint64(depth)
	for {
		current := s.queueHighWater.Load()
		if next <= current || s.queueHighWater.CompareAndSwap(current, next) {
			return
		}
	}
}

var activeOpportunisticUDPWriteStats atomic.Pointer[opportunisticUDPWriteStats]

func currentOpportunisticUDPWriteStats() opportunisticUDPWriteStatsSnapshot {
	return activeOpportunisticUDPWriteStats.Load().snapshot()
}

type opportunisticUDPWriter struct {
	direct       udpPacketWriter
	batch        udpBatchWriter
	maxBatchSize int

	queueMu    sync.Mutex
	queueCond  *sync.Cond
	queueSpace chan struct{}
	closedCh   chan struct{}
	queue      []*opportunisticUDPWriteRequest
	queueHead  int
	queueLen   int
	leader     bool
	closed     bool
	closeOnce  sync.Once

	requestBatch []*opportunisticUDPWriteRequest
	messages     []ipv4.Message
	buffers      [][]byte
	requestsPool sync.Pool

	stats opportunisticUDPWriteStats
}

func newOpportunisticUDPWriter(
	direct udpPacketWriter,
	batch udpBatchWriter,
	maxBatchSize int,
	queueSize int,
) *opportunisticUDPWriter {
	if maxBatchSize < 2 {
		maxBatchSize = opportunisticUDPDefaultWriteBatchSize
	}
	if queueSize < maxBatchSize {
		queueSize = maxBatchSize
	}
	writer := &opportunisticUDPWriter{
		direct:       direct,
		batch:        batch,
		maxBatchSize: maxBatchSize,
		queueSpace:   make(chan struct{}),
		closedCh:     make(chan struct{}),
		queue:        make([]*opportunisticUDPWriteRequest, queueSize),
		requestBatch: make([]*opportunisticUDPWriteRequest, maxBatchSize),
		messages:     make([]ipv4.Message, maxBatchSize),
		buffers:      make([][]byte, maxBatchSize),
	}
	writer.queueCond = sync.NewCond(&writer.queueMu)
	writer.requestsPool.New = func() any {
		return &opportunisticUDPWriteRequest{
			result: make(chan opportunisticUDPWriteResult, 1),
		}
	}
	return writer
}

func (w *opportunisticUDPWriter) WriteTo(payload []byte, addr net.Addr) (int, error) {
	return w.writeTo(payload, addr, nil, nil)
}

func (w *opportunisticUDPWriter) writeTo(
	payload []byte,
	addr net.Addr,
	connectionClosed <-chan struct{},
	writeDeadline <-chan struct{},
) (int, error) {
	if w == nil || w.direct == nil {
		return 0, net.ErrClosed
	}

	for {
		if err := opportunisticUDPWriteCanceledError(connectionClosed, writeDeadline); err != nil {
			return 0, err
		}

		w.queueMu.Lock()
		if w.closed {
			w.queueMu.Unlock()
			return 0, net.ErrClosed
		}
		if !w.leader {
			w.leader = true
			w.queueMu.Unlock()

			if err := opportunisticUDPWriteCanceledError(connectionClosed, writeDeadline); err != nil {
				w.finishDirectWrite()
				return 0, err
			}
			result := w.writeSingleResult(payload, addr)
			w.finishDirectWrite()
			return result.n, result.err
		}
		if w.queueLen < len(w.queue) {
			request := w.requestsPool.Get().(*opportunisticUDPWriteRequest)
			request.payload = append(request.payload[:0], payload...)
			request.addr = addr
			request.state.Store(opportunisticUDPWriteQueued)

			tail := (w.queueHead + w.queueLen) % len(w.queue)
			w.queue[tail] = request
			w.queueLen++
			w.stats.observeQueueDepth(w.queueLen)
			w.queueMu.Unlock()

			return w.waitForWriteResult(request, connectionClosed, writeDeadline)
		}
		queueSpace := w.queueSpace
		w.queueMu.Unlock()

		select {
		case <-queueSpace:
		case <-w.closedCh:
			return 0, net.ErrClosed
		case <-connectionClosed:
			return 0, net.ErrClosed
		case <-writeDeadline:
			return 0, context.DeadlineExceeded
		}
	}
}

func opportunisticUDPWriteCanceledError(
	connectionClosed <-chan struct{},
	writeDeadline <-chan struct{},
) error {
	select {
	case <-connectionClosed:
		return net.ErrClosed
	default:
	}
	select {
	case <-writeDeadline:
		return context.DeadlineExceeded
	default:
		return nil
	}
}

func (w *opportunisticUDPWriter) waitForWriteResult(
	request *opportunisticUDPWriteRequest,
	connectionClosed <-chan struct{},
	writeDeadline <-chan struct{},
) (int, error) {
	select {
	case result := <-request.result:
		w.recycleRequest(request)
		return result.n, result.err
	case <-w.closedCh:
		return w.cancelQueuedWriteOrWait(request, net.ErrClosed)
	case <-connectionClosed:
		return w.cancelQueuedWriteOrWait(request, net.ErrClosed)
	case <-writeDeadline:
		return w.cancelQueuedWriteOrWait(request, context.DeadlineExceeded)
	}
}

func (w *opportunisticUDPWriter) cancelQueuedWriteOrWait(
	request *opportunisticUDPWriteRequest,
	err error,
) (int, error) {
	if request.state.CompareAndSwap(
		opportunisticUDPWriteQueued,
		opportunisticUDPWriteCanceled,
	) {
		return 0, err
	}
	result := <-request.result
	w.recycleRequest(request)
	return result.n, result.err
}

func (w *opportunisticUDPWriter) finishDirectWrite() {
	w.queueMu.Lock()
	if w.queueLen == 0 {
		w.leader = false
		w.queueCond.Broadcast()
		w.queueMu.Unlock()
		return
	}
	w.queueMu.Unlock()
	go w.drainQueue()
}

func (w *opportunisticUDPWriter) recycleRequest(request *opportunisticUDPWriteRequest) {
	select {
	case <-request.result:
	default:
	}
	if cap(request.payload) > opportunisticUDPReceiveMTU {
		request.payload = nil
	} else {
		request.payload = request.payload[:0]
	}
	request.addr = nil
	w.requestsPool.Put(request)
}

func (w *opportunisticUDPWriter) Close() {
	if w == nil {
		return
	}
	w.closeOnce.Do(func() {
		w.queueMu.Lock()
		w.closed = true
		close(w.closedCh)
		for w.leader {
			w.queueCond.Wait()
		}
		w.queueMu.Unlock()
	})
}

func (w *opportunisticUDPWriter) drainQueue() {
	for {
		w.queueMu.Lock()
		if w.closed {
			w.failQueuedLocked(net.ErrClosed)
			w.leader = false
			w.queueCond.Broadcast()
			w.queueMu.Unlock()
			return
		}
		if w.queueLen == 0 {
			w.leader = false
			w.queueCond.Broadcast()
			w.queueMu.Unlock()
			return
		}

		count := min(w.queueLen, w.maxBatchSize)
		for i := 0; i < count; i++ {
			index := (w.queueHead + i) % len(w.queue)
			w.requestBatch[i] = w.queue[index]
			w.queue[index] = nil
		}
		w.queueHead = (w.queueHead + count) % len(w.queue)
		w.queueLen -= count
		close(w.queueSpace)
		w.queueSpace = make(chan struct{})
		w.queueMu.Unlock()

		activeCount := 0
		for i := 0; i < count; i++ {
			request := w.requestBatch[i]
			if request.state.CompareAndSwap(
				opportunisticUDPWriteQueued,
				opportunisticUDPWriteExecuting,
			) {
				w.requestBatch[activeCount] = request
				activeCount++
			} else {
				w.recycleRequest(request)
			}
		}
		w.processWriteRequests(w.requestBatch[:activeCount], w.messages, w.buffers)
		for i := 0; i < count; i++ {
			w.requestBatch[i] = nil
		}
	}
}

func (w *opportunisticUDPWriter) failQueuedLocked(err error) {
	for w.queueLen > 0 {
		request := w.queue[w.queueHead]
		w.queue[w.queueHead] = nil
		w.queueHead = (w.queueHead + 1) % len(w.queue)
		w.queueLen--
		if request.state.CompareAndSwap(
			opportunisticUDPWriteQueued,
			opportunisticUDPWriteExecuting,
		) {
			w.stats.writeErrors.Add(1)
			request.result <- opportunisticUDPWriteResult{err: err}
		} else {
			w.recycleRequest(request)
		}
	}
}

func (w *opportunisticUDPWriter) queueDepth() int {
	w.queueMu.Lock()
	defer w.queueMu.Unlock()
	return w.queueLen
}

func (w *opportunisticUDPWriter) processWriteRequests(
	requests []*opportunisticUDPWriteRequest,
	messages []ipv4.Message,
	buffers [][]byte,
) {
	if len(requests) == 0 {
		return
	}
	if len(requests) == 1 || w.batch == nil {
		for _, request := range requests {
			w.writeSingle(request)
		}
		return
	}

	for i, request := range requests {
		buffers[i] = request.payload
		messages[i] = ipv4.Message{
			Buffers: buffers[i : i+1],
			Addr:    request.addr,
		}
	}
	w.writeBatch(requests, messages[:len(requests)])
	for i := range requests {
		buffers[i] = nil
		messages[i] = ipv4.Message{}
	}
}

func (w *opportunisticUDPWriter) writeSingle(request *opportunisticUDPWriteRequest) {
	request.result <- w.writeSingleResult(request.payload, request.addr)
}

func (w *opportunisticUDPWriter) writeSingleResult(
	payload []byte,
	addr net.Addr,
) opportunisticUDPWriteResult {
	w.stats.singleWrites.Add(1)
	n, err := w.direct.WriteTo(payload, addr)
	if err == nil && n != len(payload) {
		err = io.ErrShortWrite
	}
	if err != nil {
		w.stats.writeErrors.Add(1)
	}
	return opportunisticUDPWriteResult{n: n, err: err}
}

func (w *opportunisticUDPWriter) writeBatch(
	requests []*opportunisticUDPWriteRequest,
	messages []ipv4.Message,
) {
	sent := 0
	for sent < len(requests) {
		remaining := len(requests) - sent
		w.stats.batchCalls.Add(1)
		n, err := w.batch.WriteBatch(messages[sent:], 0)
		if n < 0 || n > remaining {
			n = 0
			err = errors.New("udp: invalid WriteBatch result")
		}
		if n > 0 {
			w.stats.batchMessages.Add(uint64(n))
			for i := sent; i < sent+n; i++ {
				requests[i].result <- opportunisticUDPWriteResult{
					n: len(requests[i].payload),
				}
			}
			sent += n
		}

		if n < remaining {
			w.stats.partialBatchWrites.Add(1)
		}
		if err != nil {
			unsent := len(requests) - sent
			w.stats.writeErrors.Add(uint64(unsent))
			for i := sent; i < len(requests); i++ {
				requests[i].result <- opportunisticUDPWriteResult{err: err}
			}
			return
		}
		if n == 0 {
			unsent := len(requests) - sent
			w.stats.writeErrors.Add(uint64(unsent))
			for i := sent; i < len(requests); i++ {
				requests[i].result <- opportunisticUDPWriteResult{err: io.ErrNoProgress}
			}
			return
		}
	}
}

type opportunisticUDPListener struct {
	packetConn *net.UDPConn
	batchRead  udpBatchReader
	writer     *opportunisticUDPWriter

	readBatchSize int
	acceptFilter  func([]byte) bool
	acceptCh      chan *opportunisticUDPConn
	doneCh        chan struct{}
	readDoneCh    chan struct{}

	mu        sync.Mutex
	accepting bool
	conns     map[string]*opportunisticUDPConn
	readErr   error
	closeErr  error

	closeOnce          sync.Once
	transportCloseOnce sync.Once
}

type opportunisticUDPConn struct {
	listener *opportunisticUDPListener
	remote   net.Addr
	buffer   *packetio.Buffer

	doneCh        chan struct{}
	closeOnce     sync.Once
	closed        atomic.Bool
	accepted      bool
	writeDeadline *deadline.Deadline
}

func listenOpportunisticUDP(
	network string,
	addr *net.UDPAddr,
	config opportunisticUDPListenConfig,
) (*opportunisticUDPListener, error) {
	if config.Backlog <= 0 {
		config.Backlog = opportunisticUDPDefaultListenBacklog
	}
	if config.ReadBatchSize <= 0 {
		config.ReadBatchSize = opportunisticUDPDefaultReadBatchSize
	}
	if config.WriteBatchSize <= 0 {
		config.WriteBatchSize = opportunisticUDPDefaultWriteBatchSize
	}
	if config.WriteQueueSize <= 0 {
		config.WriteQueueSize = opportunisticUDPDefaultWriteQueueSize
	}

	packetConn, err := net.ListenUDP(network, addr)
	if err != nil {
		return nil, err
	}
	ok := false
	defer func() {
		if !ok {
			_ = packetConn.Close()
		}
	}()

	if config.ReadBufferSize > 0 {
		_ = packetConn.SetReadBuffer(config.ReadBufferSize)
	}
	if config.WriteBufferSize > 0 {
		_ = packetConn.SetWriteBuffer(config.WriteBufferSize)
	}

	var batchIO *ipv4.PacketConn
	if runtime.GOOS == "linux" && network != "udp6" &&
		(addr == nil || addr.IP == nil || addr.IP.To4() != nil) {
		batchIO = ipv4.NewPacketConn(packetConn)
	}

	var batchReader udpBatchReader
	var batchWriter udpBatchWriter
	if batchIO != nil {
		batchReader = batchIO
		batchWriter = batchIO
	}
	writer := newOpportunisticUDPWriter(
		packetConn,
		batchWriter,
		config.WriteBatchSize,
		config.WriteQueueSize,
	)
	listener := &opportunisticUDPListener{
		packetConn:    packetConn,
		batchRead:     batchReader,
		writer:        writer,
		readBatchSize: config.ReadBatchSize,
		acceptFilter:  config.AcceptFilter,
		acceptCh:      make(chan *opportunisticUDPConn, config.Backlog),
		doneCh:        make(chan struct{}),
		readDoneCh:    make(chan struct{}),
		accepting:     true,
		conns:         make(map[string]*opportunisticUDPConn),
	}
	activeOpportunisticUDPWriteStats.Store(&writer.stats)
	go listener.readLoop()
	ok = true
	return listener, nil
}

func (l *opportunisticUDPListener) Accept() (net.PacketConn, net.Addr, error) {
	for {
		select {
		case conn := <-l.acceptCh:
			l.mu.Lock()
			if !l.accepting || conn.closed.Load() {
				if current := l.conns[conn.remote.String()]; current == conn {
					delete(l.conns, conn.remote.String())
				}
				shouldClose := !l.accepting && len(l.conns) == 0
				l.mu.Unlock()
				_ = conn.closeLocal()
				if shouldClose {
					l.closeTransport()
				}
				if !l.isAccepting() {
					return nil, nil, errOpportunisticUDPListenerClosed
				}
				continue
			}
			conn.accepted = true
			l.mu.Unlock()
			return conn, conn.remote, nil

		case <-l.doneCh:
			return nil, nil, errOpportunisticUDPListenerClosed

		case <-l.readDoneCh:
			if err := l.readError(); err != nil {
				return nil, nil, err
			}
			return nil, nil, errOpportunisticUDPListenerClosed
		}
	}
}

func (l *opportunisticUDPListener) Close() error {
	if l == nil {
		return nil
	}
	var pending []*opportunisticUDPConn
	shouldClose := false
	l.closeOnce.Do(func() {
		l.mu.Lock()
		l.accepting = false
		close(l.doneCh)
		for key, conn := range l.conns {
			if !conn.accepted {
				delete(l.conns, key)
				pending = append(pending, conn)
			}
		}
		shouldClose = len(l.conns) == 0
		l.mu.Unlock()

		for _, conn := range pending {
			_ = conn.closeLocal()
		}
		if shouldClose {
			l.closeTransport()
		}
	})

	l.mu.Lock()
	err := l.closeErr
	l.mu.Unlock()
	return err
}

func (l *opportunisticUDPListener) Addr() net.Addr {
	if l == nil || l.packetConn == nil {
		return nil
	}
	return l.packetConn.LocalAddr()
}

func (l *opportunisticUDPListener) isAccepting() bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.accepting
}

func (l *opportunisticUDPListener) readError() error {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.readErr
}

func (l *opportunisticUDPListener) closeTransport() {
	l.transportCloseOnce.Do(func() {
		err := l.packetConn.Close()
		l.writer.Close()
		<-l.readDoneCh
		if err != nil && !errors.Is(err, net.ErrClosed) {
			l.mu.Lock()
			l.closeErr = err
			l.mu.Unlock()
		}
	})
}

func (l *opportunisticUDPListener) readLoop() {
	defer close(l.readDoneCh)
	var err error
	if l.batchRead != nil && l.readBatchSize > 1 {
		err = l.readBatchLoop()
	} else {
		err = l.readLoopSingle()
	}
	if err == nil || errors.Is(err, net.ErrClosed) {
		return
	}
	l.mu.Lock()
	l.readErr = err
	l.mu.Unlock()
}

func (l *opportunisticUDPListener) readBatchLoop() error {
	messages := make([]ipv4.Message, l.readBatchSize)
	for i := range messages {
		messages[i].Buffers = [][]byte{make([]byte, opportunisticUDPReceiveMTU)}
		messages[i].OOB = make([]byte, 40)
	}
	for {
		n, err := l.batchRead.ReadBatch(messages, 0)
		for i := 0; i < n; i++ {
			message := &messages[i]
			if message.N > 0 && message.Addr != nil {
				l.dispatchMessage(message.Addr, message.Buffers[0][:message.N])
			}
			message.N = 0
		}
		if err != nil {
			return err
		}
	}
}

func (l *opportunisticUDPListener) readLoopSingle() error {
	buffer := make([]byte, opportunisticUDPReceiveMTU)
	for {
		n, remote, err := l.packetConn.ReadFrom(buffer)
		if err != nil {
			return err
		}
		l.dispatchMessage(remote, buffer[:n])
	}
}

func (l *opportunisticUDPListener) dispatchMessage(remote net.Addr, payload []byte) {
	conn, ok := l.connectionFor(remote, payload)
	if ok {
		_, _ = conn.buffer.Write(payload)
	}
}

func (l *opportunisticUDPListener) connectionFor(
	remote net.Addr,
	payload []byte,
) (*opportunisticUDPConn, bool) {
	l.mu.Lock()
	defer l.mu.Unlock()

	key := remote.String()
	if conn, ok := l.conns[key]; ok && !conn.closed.Load() {
		return conn, true
	}
	if !l.accepting {
		return nil, false
	}
	if l.acceptFilter != nil && !l.acceptFilter(payload) {
		return nil, false
	}

	conn := &opportunisticUDPConn{
		listener:      l,
		remote:        remote,
		buffer:        packetio.NewBuffer(),
		doneCh:        make(chan struct{}),
		writeDeadline: deadline.New(),
	}
	select {
	case l.acceptCh <- conn:
		l.conns[key] = conn
		return conn, true
	default:
		_ = conn.closeLocal()
		return nil, false
	}
}

func (l *opportunisticUDPListener) removeConn(conn *opportunisticUDPConn) {
	l.mu.Lock()
	if current := l.conns[conn.remote.String()]; current == conn {
		delete(l.conns, conn.remote.String())
	}
	shouldClose := !l.accepting && len(l.conns) == 0
	l.mu.Unlock()
	if shouldClose {
		l.closeTransport()
	}
}

func (c *opportunisticUDPConn) ReadFrom(payload []byte) (int, net.Addr, error) {
	n, err := c.buffer.Read(payload)
	return n, c.remote, err
}

func (c *opportunisticUDPConn) WriteTo(payload []byte, _ net.Addr) (int, error) {
	select {
	case <-c.doneCh:
		return 0, net.ErrClosed
	case <-c.writeDeadline.Done():
		return 0, context.DeadlineExceeded
	default:
	}
	return c.listener.writer.writeTo(
		payload,
		c.remote,
		c.doneCh,
		c.writeDeadline.Done(),
	)
}

func (c *opportunisticUDPConn) Close() error {
	err := c.closeLocal()
	c.listener.removeConn(c)
	return err
}

func (c *opportunisticUDPConn) closeLocal() error {
	var err error
	c.closeOnce.Do(func() {
		c.closed.Store(true)
		close(c.doneCh)
		err = c.buffer.Close()
	})
	return err
}

func (c *opportunisticUDPConn) LocalAddr() net.Addr {
	return c.listener.Addr()
}

func (c *opportunisticUDPConn) SetDeadline(value time.Time) error {
	c.writeDeadline.Set(value)
	return c.SetReadDeadline(value)
}

func (c *opportunisticUDPConn) SetReadDeadline(value time.Time) error {
	return c.buffer.SetReadDeadline(value)
}

func (c *opportunisticUDPConn) SetWriteDeadline(value time.Time) error {
	c.writeDeadline.Set(value)
	return nil
}
