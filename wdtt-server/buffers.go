package main

import "sync"

// ==================== Пул буферов ====================

var bufPool = sync.Pool{
	New: func() interface{} {
		b := make([]byte, 1600)
		return &b
	},
}

func getBuf() *[]byte  { return bufPool.Get().(*[]byte) }
func putBuf(b *[]byte) { bufPool.Put(b) }

// buf2048Pool — буферы под пакеты, читаемые из raw TUN в downlinkLoop.
// В отличие от bufPool (используется синхронно в пределах одного вызова),
// эти буферы переживают передачу через канал downlinkWorker.sendCh в другую
// горутину, поэтому это []byte, а не *[]byte — Get/Put работают с копией
// слайса, а не с общим указателем, что здесь безопаснее при передаче между
// горутинами (нет риска, что отправитель продолжит писать в уже
// отправленный в канал буфер).
var buf2048Pool = sync.Pool{
	New: func() interface{} {
		return make([]byte, 2048)
	},
}

func getBuf2048() []byte {
	return buf2048Pool.Get().([]byte)[:2048]
}

func putBuf2048(b []byte) {
	if cap(b) != 2048 {
		return
	}
	buf2048Pool.Put(b) //nolint:staticcheck // намеренно без указателя, см. комментарий выше
}
