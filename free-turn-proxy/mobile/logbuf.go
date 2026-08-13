package mobile

import (
	"strings"
	"sync"
)

// logBufMax - глубина кольцевого буфера. Не настройка сессии: при живом
// EventSink строки уходят хосту сразу, а буфер нужен только для DumpLogs -
// дампа "поделиться логом" и строк, накопленных до регистрации приёмника.
const logBufMax = 500

type logBuffer struct {
	mu    sync.Mutex
	lines []string
}

func (b *logBuffer) append(line string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.lines = append(b.lines, line)
	if len(b.lines) > logBufMax {
		b.lines = b.lines[len(b.lines)-logBufMax:]
	}
}

func (b *logBuffer) get() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return strings.Join(b.lines, "\n")
}

func (b *logBuffer) clear() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.lines = b.lines[:0]
}

var sharedLogBuf = &logBuffer{}

// DumpLogs возвращает последние строки лога (до logBufMax).
func DumpLogs() string { return sharedLogBuf.get() }

// ClearLogs очищает буфер логов.
func ClearLogs() { sharedLogBuf.clear() }
