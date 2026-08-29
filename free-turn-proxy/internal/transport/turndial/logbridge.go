package turndial

import (
	"fmt"

	"github.com/pion/logging"

	"github.com/samosvalishe/free-turn-proxy/internal/logx"
)

type logxFactory struct {
	log    logx.Logger
	stream int
}

func (f *logxFactory) NewLogger(scope string) logging.LeveledLogger {
	return &logxLogger{
		log:    logx.OrNop(f.log),
		prefix: fmt.Sprintf("[STREAM %d] [%s]", f.stream, scope),
	}
}

type logxLogger struct {
	log    logx.Logger
	prefix string
}

// Trace pion сыплет на каждый пакет - в логе сессии он не нужен ни на каком уровне.
func (*logxLogger) Trace(string)          {}
func (*logxLogger) Tracef(string, ...any) {}

func (l *logxLogger) Debug(msg string) { l.log.Debugf("%s %s", l.prefix, msg) }
func (l *logxLogger) Warn(msg string)  { l.log.Warnf("%s %s", l.prefix, msg) }
func (l *logxLogger) Error(msg string) { l.log.Errorf("%s %s", l.prefix, msg) }

// Info у pion - шум уровня отладки (таймеры, состояния биндинга).
func (l *logxLogger) Info(msg string) { l.log.Debugf("%s %s", l.prefix, msg) }

func (l *logxLogger) Debugf(format string, args ...any) {
	l.log.Debugf(l.prefix+" "+format, args...)
}

func (l *logxLogger) Infof(format string, args ...any) {
	l.log.Debugf(l.prefix+" "+format, args...)
}

func (l *logxLogger) Warnf(format string, args ...any) {
	l.log.Warnf(l.prefix+" "+format, args...)
}

func (l *logxLogger) Errorf(format string, args ...any) {
	l.log.Errorf(l.prefix+" "+format, args...)
}
