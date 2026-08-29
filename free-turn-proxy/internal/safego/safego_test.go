package safego

import (
	"errors"
	"testing"
)

func TestCallTurnsPanicIntoError(t *testing.T) {
	t.Parallel()
	err := Call(nil, func() error { panic("boom") })
	if !errors.Is(err, ErrPanic) {
		t.Fatalf("err = %v, want ErrPanic", err)
	}
	// Стек едет в лог, а причина - в текст ошибки: по нему опознают баг в отчёте юзера.
	if got := err.Error(); got != "safego: panic: boom" {
		t.Fatalf("err = %q, want cause in message", got)
	}
}

func TestCallPassesResultThrough(t *testing.T) {
	t.Parallel()
	want := errors.New("plain")
	if err := Call(nil, func() error { return want }); !errors.Is(err, want) {
		t.Fatalf("err = %v, want %v", err, want)
	}
	if err := Call(nil, func() error { return nil }); err != nil {
		t.Fatalf("err = %v, want nil", err)
	}
}

func TestRunRecovers(t *testing.T) {
	t.Parallel()
	done := make(chan error, 1)
	go func() { done <- Run(nil, func() { panic(errors.New("boom")) }) }()
	if err := <-done; !errors.Is(err, ErrPanic) {
		t.Fatalf("err = %v, want ErrPanic", err)
	}
}
