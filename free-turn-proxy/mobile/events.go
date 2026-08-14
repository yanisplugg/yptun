package mobile

import (
	"fmt"
	"sync/atomic"
	"time"

	"github.com/samosvalishe/free-turn-proxy/internal/session"
)

// Уровни, с которыми приходит EventSink.OnLog.
const (
	LevelDebug = "debug"
	LevelInfo  = "info"
	LevelWarn  = "warn"
	LevelError = "error"
)

// EventSink - приёмник событий сессии. Реализуется хостом (Java/ObjC-класс) и
// заменяет разбор текста логов: стадия, счётчик стримов и captcha приходят
// готовыми значениями.
//
// Контракт:
//   - OnState вызывается только на изменение и всегда из одной горутины;
//   - OnLog может прийти из любой горутины ядра, реализация обязана быть
//     потокобезопасной;
//   - ни один метод не должен блокировать: они стоят на пути сессии;
//   - хост обязан держать ссылку на объект живой (Go его не удерживает от GC).
type EventSink interface {
	OnState(state string, streams, total int, errMsg string)
	OnLog(level, msg string, unixMillis int64)
	// OnCaptcha показывает окно ручного решения captcha по url; пустой url -
	// закрыть окно.
	OnCaptcha(url string)
}

var sink atomic.Pointer[EventSink]

// SetEventSink регистрирует приёмник событий. nil отключает push-канал: хост
// остаётся с GetState. Ручная captcha без приёмника недоступна - показывать
// окно некому.
func SetEventSink(s EventSink) {
	if s == nil {
		sink.Store(nil)
		return
	}
	sink.Store(&s)
}

func currentSink() EventSink {
	if p := sink.Load(); p != nil {
		return *p
	}
	return nil
}

func emitCaptcha(url string) {
	if s := currentSink(); s != nil {
		s.OnCaptcha(url)
	}
}

// observer транслирует переходы сессии в EventSink. Приёмник читается на каждом
// событии: хост может подменить его между сессиями.
type observer struct{}

func (observer) OnPhase(phase session.Phase, streams, total int, errMsg string) {
	if s := currentSink(); s != nil {
		s.OnState(string(phase), streams, total, errMsg)
	}
}

// sinkLogger - logx.Logger, который пишет и в кольцевой буфер (для DumpLogs), и
// в EventSink. Уровень задаёт вызванный метод, поэтому хосту не нужно угадывать
// его по тексту.
type sinkLogger struct {
	debug bool
	buf   *logBuffer
}

func (l *sinkLogger) write(level, format string, v ...any) {
	msg := fmt.Sprintf(format, v...)
	now := time.Now()
	l.buf.append(now.Format("15:04:05") + " [" + level + "] " + msg)
	if s := currentSink(); s != nil {
		s.OnLog(level, msg, now.UnixMilli())
	}
}

func (l *sinkLogger) Debugf(format string, v ...any) {
	if l.debug {
		l.write(LevelDebug, format, v...)
	}
}
func (l *sinkLogger) Infof(format string, v ...any)  { l.write(LevelInfo, format, v...) }
func (l *sinkLogger) Warnf(format string, v ...any)  { l.write(LevelWarn, format, v...) }
func (l *sinkLogger) Errorf(format string, v ...any) { l.write(LevelError, format, v...) }
func (l *sinkLogger) DebugEnabled() bool             { return l.debug }
