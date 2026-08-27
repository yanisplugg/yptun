package mobile

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/openlibrecommunity/olcrtc/pkg/olcrtc/client"
)

const (
	defaultCheckTimeout = 8 * time.Second
	defaultPingTimeout  = 10 * time.Second
	probeShutdownWait   = 2 * time.Second
)

type probeReadyAction func(context.Context, string) (int64, error)

// Check starts an isolated client and returns RTC startup latency in milliseconds.
func (r *Runtime) Check(
	providerName, transportName, roomID, deviceID, keyHex string,
	socksPort, timeoutMillis, vp8FPS, vp8BatchSize int,
) (int64, error) {
	cfg, err := r.probeConfig(
		providerName, transportName, roomID, deviceID, keyHex, socksPort, vp8FPS, vp8BatchSize,
	)
	if err != nil {
		return 0, err
	}
	return r.runProbe(cfg, timeoutFromMillis(timeoutMillis, defaultCheckTimeout), nil)
}

// Ping starts an isolated client and returns HTTP latency through its SOCKS tunnel.
func (r *Runtime) Ping(
	providerName, transportName, roomID, deviceID, keyHex string,
	socksPort, timeoutMillis int,
	pingURL string,
	vp8FPS, vp8BatchSize int,
) (int64, error) {
	cfg, err := r.probeConfig(
		providerName, transportName, roomID, deviceID, keyHex, socksPort, vp8FPS, vp8BatchSize,
	)
	if err != nil {
		return 0, err
	}
	action := func(ctx context.Context, address string) (int64, error) {
		return httpPingThroughSocks(ctx, address, pingURL)
	}
	return r.runProbe(cfg, timeoutFromMillis(timeoutMillis, defaultPingTimeout), action)
}

func (r *Runtime) probeConfig(
	providerName, transportName, roomID, deviceID, keyHex string,
	socksPort, vp8FPS, vp8BatchSize int,
) (client.Config, error) {
	r.mu.Lock()
	temporary := r.defaults
	r.mu.Unlock()

	temporary.provider = providerName
	temporary.transport = transportName
	temporary.roomURL = roomID
	temporary.deviceID = deviceID
	temporary.keyHex = keyHex
	temporary.socksHost = defaultSOCKSHost
	temporary.socksUser = ""
	temporary.socksPass = ""
	if vp8FPS > 0 {
		temporary.vp8.FPS = vp8FPS
	}
	if vp8BatchSize > 0 {
		temporary.vp8.BatchSize = vp8BatchSize
	}
	temporary.socksPort = socksPort
	if err := validateProbeConfig(temporary); err != nil {
		return client.Config{}, err
	}
	if err := validateProbeVP8(temporary.vp8); err != nil {
		return client.Config{}, err
	}
	return temporary.clientConfig(), nil
}

func validateProbeConfig(cfg runtimeConfig) error {
	if cfg.socksPort < 0 || cfg.socksPort > 65535 {
		return fmt.Errorf("%w: probe port must be between 0 and 65535", ErrInvalidConfig)
	}
	if cfg.socksPort == 0 {
		cfg.socksPort = 1
	}
	return validateRuntimeConfig(cfg)
}

func validateProbeVP8(options client.VP8Options) error {
	if options.FPS < 1 || options.FPS > 120 || options.BatchSize < 1 {
		return fmt.Errorf("%w: invalid VP8 probe options", ErrInvalidConfig)
	}
	return nil
}

func (r *Runtime) runProbe(
	cfg client.Config,
	timeout time.Duration,
	action probeReadyAction,
) (int64, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	ready := make(chan string, 1)
	done := make(chan error, 1)
	var readyOnce sync.Once
	started := time.Now()
	go func() {
		done <- r.runner(ctx, cfg, func(address string) {
			readyOnce.Do(func() { ready <- address })
		})
	}()

	select {
	case socksAddr := <-ready:
		return finishProbe(ctx, cancel, done, started, socksAddr, action)
	case err := <-done:
		if socksAddr, ok := probeAddress(ready); ok {
			return finishProbe(ctx, cancel, nil, started, socksAddr, action)
		}
		if err != nil {
			return 0, err
		}
		return 0, ErrStoppedBeforeReady
	case <-ctx.Done():
		waitProbeDone(done)
		return 0, ErrReadyTimeout
	}
}

func probeAddress(ready <-chan string) (string, bool) {
	select {
	case address := <-ready:
		return address, true
	default:
		return "", false
	}
}

func finishProbe(
	ctx context.Context,
	cancel context.CancelFunc,
	done <-chan error,
	started time.Time,
	socksAddr string,
	action probeReadyAction,
) (int64, error) {
	var latency int64
	var err error
	if action == nil {
		latency = time.Since(started).Milliseconds()
	} else {
		latency, err = action(ctx, socksAddr)
	}
	cancel()
	if done != nil {
		waitProbeDone(done)
	}
	return latency, err
}

func waitProbeDone(done <-chan error) {
	if done == nil {
		return
	}
	timer := time.NewTimer(probeShutdownWait)
	defer timer.Stop()
	select {
	case <-done:
	case <-timer.C:
	}
}
