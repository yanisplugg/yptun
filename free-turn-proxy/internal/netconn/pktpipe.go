package netconn

import (
	"errors"
	"net"
	"os"
	"sync"
	"time"
)

// ErrPacketTooLarge - датаграмма длиннее MTU пары; аналог EMSGSIZE на сокете.
var ErrPacketTooLarge = errors.New("netconn: packet exceeds pipe MTU")

// Дефолты пары в памяти.
const (
	// defaultPipeMTU с запасом покрывает WireGuard-датаграмму поверх TURN.
	defaultPipeMTU = 2048
	// defaultPipeQueue - глубина очереди в каждую сторону. Аналог SO_RCVBUF:
	// сглаживает всплески, дальше пакеты отбрасываются.
	defaultPipeQueue = 256
)

// pipeAddr - адрес конца пары. Адресация внутри пары не нужна (ровно два
// участника), но net.PacketConn требует net.Addr.
type pipeAddr string

func (pipeAddr) Network() string  { return "pipe" }
func (a pipeAddr) String() string { return string(a) }

// Возвращает пару связанных net.PacketConn в памяти без loopback-хопа.
func PacketPipe(mtu, queue int) (net.PacketConn, net.PacketConn) {
	if mtu <= 0 {
		mtu = defaultPipeMTU
	}
	if queue <= 0 {
		queue = defaultPipeQueue
	}

	// По каналу ходит указатель на слайс: sync.Pool с []byte заставлял бы
	// оборачивать каждый пакет в новый *[]byte, съедая смысл пула.
	pool := &sync.Pool{New: func() any { b := make([]byte, mtu); return &b }}
	toA := make(chan *[]byte, queue)
	toB := make(chan *[]byte, queue)

	a := &packetPipe{local: "pipe:a", remote: "pipe:b", rx: toA, tx: toB, mtu: mtu, pool: pool, done: make(chan struct{})}
	b := &packetPipe{local: "pipe:b", remote: "pipe:a", rx: toB, tx: toA, mtu: mtu, pool: pool, done: make(chan struct{})}
	a.peer, b.peer = b, a
	return a, b
}

type packetPipe struct {
	local  pipeAddr
	remote pipeAddr
	rx     <-chan *[]byte
	tx     chan<- *[]byte
	peer   *packetPipe
	mtu    int
	pool   *sync.Pool

	// deadline охраняет и время, и канал-сигнал его смены: читатель, уже
	// стоящий в select, обязан проснуться, когда дедлайн переставили.
	deadline struct {
		mu      sync.Mutex
		at      time.Time
		changed chan struct{}
	}

	closeOnce sync.Once
	done      chan struct{}
}

func (p *packetPipe) ReadFrom(b []byte) (int, net.Addr, error) {
	// Таймер живёт снаружи цикла: смена дедлайна начинает круг заново, и
	// per-iteration defer копил бы их до самого возврата.
	var timer *time.Timer
	defer func() {
		if timer != nil {
			timer.Stop()
		}
	}()

	for {
		at, changed := p.readDeadline()

		if timer != nil {
			timer.Stop()
			timer = nil
		}
		var timeout <-chan time.Time
		if !at.IsZero() {
			timer = time.NewTimer(time.Until(at))
			timeout = timer.C
		}

		select {
		case <-p.done:
			return 0, nil, net.ErrClosed
		case <-changed:
			// Дедлайн переставили - пересобрать таймер и ждать заново.
			continue
		case <-timeout:
			return 0, nil, os.ErrDeadlineExceeded
		case buf, ok := <-p.rx:
			if !ok {
				return 0, nil, net.ErrClosed
			}
			// Как UDP: не влезшее в буфер вызывающего отбрасывается.
			n := copy(b, *buf)
			p.pool.Put(buf)
			return n, p.remote, nil
		}
	}
}

func (p *packetPipe) readDeadline() (time.Time, <-chan struct{}) {
	p.deadline.mu.Lock()
	defer p.deadline.mu.Unlock()
	if p.deadline.changed == nil {
		p.deadline.changed = make(chan struct{})
	}
	return p.deadline.at, p.deadline.changed
}

func (p *packetPipe) WriteTo(b []byte, _ net.Addr) (int, error) {
	if len(b) > p.mtu {
		return 0, ErrPacketTooLarge
	}

	// Один select покрывает оба конца атомарно, исключая race между двумя
	// последовательными проверками.
	select {
	case <-p.done:
		return 0, net.ErrClosed
	case <-p.peer.done:
		return 0, net.ErrClosed
	default:
	}

	buf, _ := p.pool.Get().(*[]byte)
	*buf = (*buf)[:len(b)]
	copy(*buf, b)

	select {
	case p.tx <- buf:
	default:
		// Очередь полна - пакет теряется, как на переполненном UDP-сокете.
		p.pool.Put(buf)
	}
	return len(b), nil
}

func (p *packetPipe) Close() error {
	p.closeOnce.Do(func() { close(p.done) })
	return nil
}

func (p *packetPipe) LocalAddr() net.Addr { return p.local }

func (p *packetPipe) SetDeadline(t time.Time) error {
	return p.SetReadDeadline(t)
}

func (p *packetPipe) SetReadDeadline(t time.Time) error {
	p.deadline.mu.Lock()
	defer p.deadline.mu.Unlock()
	p.deadline.at = t
	// Закрытие будит читателя; следующий его круг возьмёт уже новый канал.
	if p.deadline.changed != nil {
		close(p.deadline.changed)
	}
	p.deadline.changed = make(chan struct{})
	return nil
}

// SetWriteDeadline - no-op: запись в пару никогда не блокируется (полная
// очередь роняет пакет), поэтому дедлайну нечего прерывать.
func (*packetPipe) SetWriteDeadline(time.Time) error { return nil }
