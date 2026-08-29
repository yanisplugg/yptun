// Package provider определяет интерфейс источника учетных данных TURN-серверов.
package provider

import (
	"context"
	"errors"
	"time"
)

// Credentials содержит реквизиты авторизации и адреса TURN-серверов.
type Credentials struct {
	User        string
	Pass        string
	ServerAddrs []string
	ExpiresAt   time.Time
}

// Provider предоставляет доступ к учетным записям TURN.
type Provider interface {
	GetCredentials(ctx context.Context, streamID int) (Credentials, error)
	IsAuthError(err error) bool
	HandleAuthError(streamID int) bool
	ResetErrors(streamID int)
	DropCredentials(streamID int)
	BackoffUntilUnix() int64
	Name() string
}

var (
	ErrBackoffActive  = errors.New("provider backoff active")
	ErrFatalNoStreams = errors.New("provider fatal: no streams alive")
)
