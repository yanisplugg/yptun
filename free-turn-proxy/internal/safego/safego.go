// Package safego превращает панику фоновой горутины в ошибку: ядро линкуется в процесс
// приложения (gomobile), где паника унесла бы весь UI и перехватить её снаружи нельзя.
package safego

import (
	"errors"
	"fmt"
	"runtime/debug"

	"github.com/samosvalishe/free-turn-proxy/internal/logx"
)

var ErrPanic = errors.New("safego: panic")

// Call выполняет fn; стек паники уходит в лог, а не в текст ошибки - тот показывается юзеру.
func Call(log logx.Logger, fn func() error) (err error) {
	defer func() {
		p := recover()
		if p == nil {
			return
		}
		logx.OrNop(log).Errorf("panic recovered: %v\n%s", p, debug.Stack())
		err = fmt.Errorf("%w: %v", ErrPanic, p)
	}()
	return fn()
}

func Run(log logx.Logger, fn func()) error {
	return Call(log, func() error { fn(); return nil })
}
