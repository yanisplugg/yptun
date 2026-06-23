package udprelay

import (
	"sync"
	"sync/atomic"
)

const (
	// dispatchChunkSize - сколько ПОДРЯД идущих пакетов уходит в один стрим
	// (один TURN relay) перед переключением на следующий.
	//
	// Зачем: WireGuard-over-VK - это ОДИН непрозрачный UDP-поток. Если размазывать
	// его по N TURN-путям с разным latency по одному пакету (round-robin, либо -
	// как было у нас раньше - общий inboundChan, который разбирают все стримы по
	// готовности), пакеты прилетают на сервер вперемешку. TCP ВНУТРИ WireGuard
	// читает reorder как потери → cwnd collapse → скорость single-flow падает в
	// пол, и добавление звонков/стримов её НЕ поднимает.
	//
	// С чанками: пачка из dispatchChunkSize пакетов (≈ одно TCP congestion window
	// при initial cwnd ~10) уходит через ОДИН relay → прилетает по порядку.
	// Reorder возможен только на границах чанков, что покрывается WG replay
	// window (2048 пакетов). Агрегатная полоса не теряется - стримы по-прежнему
	// нагружены равномерно, каждый получает 1/N трафика за время.
	//
	// Модель заимствована у WDTT (proxy-turn-vk-android, "Adaptive Chunking").
	dispatchChunkSize = 8

	// streamSendBuf - глубина очереди одного стрима. Достаточно, чтобы пережить
	// короткий DTLS-stall стрима без дропа всего чанка, но не настолько, чтобы
	// копить большой bufferbloat (это сам по себе вредит latency и reorder).
	streamSendBuf = 128
)

// streamSlot - живой DTLS-стрим, зарегистрированный в диспетчере. sendCh
// принадлежит стриму; пишет в него ТОЛЬКО dispatcher, читает ТОЛЬКО writer
// стрима. Канал никогда не закрывается (закрытие → паника при гонке отправки);
// отписка убирает слот из набора, а недослитые пакеты собирает GC.
type streamSlot struct {
	id     int
	sendCh chan *Packet
}

// dispatcher раздаёт входящие WG-пакеты по живым стримам с chunk-affinity.
// Набор стримов меняется на лету (стримы независимо поднимаются/падают), поэтому
// он держится в atomic-указателе на срез: register/unregister делают copy-on-write
// под mu, а горячий путь dispatch читает срез без блокировки. Поля rrIndex/rrCount
// трогает только одна горутина (runListener), поэтому им блокировка не нужна.
type dispatcher struct {
	mu      sync.Mutex
	streams atomic.Pointer[[]*streamSlot]
	rrIndex int
	rrCount int
}

func newDispatcher() *dispatcher {
	d := &dispatcher{}
	empty := make([]*streamSlot, 0)
	d.streams.Store(&empty)
	return d
}

func (d *dispatcher) register(s *streamSlot) {
	d.mu.Lock()
	defer d.mu.Unlock()
	old := *d.streams.Load()
	next := make([]*streamSlot, len(old)+1)
	copy(next, old)
	next[len(old)] = s
	d.streams.Store(&next)
}

func (d *dispatcher) unregister(s *streamSlot) {
	d.mu.Lock()
	defer d.mu.Unlock()
	old := *d.streams.Load()
	next := make([]*streamSlot, 0, len(old))
	for _, x := range old {
		if x != s {
			next = append(next, x)
		}
	}
	d.streams.Store(&next)
}

// liveStreams - число зарегистрированных стримов (для логов/тестов).
func (d *dispatcher) liveStreams() int { return len(*d.streams.Load()) }

// dispatch отправляет pkt живому стриму с chunk-affinity и без блокировок:
// держим dispatchChunkSize пакетов на текущем стриме, потом сдвигаемся.
// Если очередь текущего стрима полна - сразу ищем свободный и начинаем на нём
// новый чанк (стрим завис → не копим в нём bufferbloat). Если ВСЕ полны -
// сдвигаем указатель и возвращаем false: вызывающий освобождает pkt, WG сам
// ретранслирует (дроп дешевле блокировки на горячем ingest-пути).
//
// Вызывается строго из одной горутины (runListener); конкурентный вызов сломал
// бы rrIndex/rrCount.
func (d *dispatcher) dispatch(pkt *Packet) bool {
	streams := *d.streams.Load()
	n := len(streams)
	if n == 0 {
		return false
	}
	idx := d.rrIndex % n

	// Текущий стрим (chunk-affinity).
	select {
	case streams[idx].sendCh <- pkt:
		d.rrCount++
		if d.rrCount >= dispatchChunkSize {
			d.rrIndex = (idx + 1) % n
			d.rrCount = 0
		}
		return true
	default:
	}

	// Текущий перегружен - ищем свободный, начинаем новый чанк на нём.
	for i := 1; i < n; i++ {
		alt := (idx + i) % n
		select {
		case streams[alt].sendCh <- pkt:
			d.rrIndex = alt
			d.rrCount = 1 // первый пакет нового чанка уже отправлен
			return true
		default:
		}
	}

	// Все перегружены - сдвигаем и дропаем.
	d.rrIndex = (idx + 1) % n
	d.rrCount = 0
	return false
}
