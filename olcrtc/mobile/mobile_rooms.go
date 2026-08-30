package mobile

// Multi-room support: several INDEPENDENT olcRTC room instances running at once, each with its own
// SOCKS5 listener, so the client can fan connections across them (per-connection round-robin) and
// aggregate bandwidth. Unlike Runtime (one owned lifecycle per instance), these are handle-based,
// package-level, and use their own context — mirroring the isolated runtime of Runtime.Check/Ping.
// Each room's SOCKS listener stays on loopback AND is password-protected (SOCKSUser/SOCKSPass), so
// no other local process can ride it.

import (
	"context"
	"fmt"
	"net"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/openlibrecommunity/olcrtc/pkg/olcrtc/client"
)

// roomReconnectBackoff is the pause before an independent room re-runs its client after an unexpected
// exit (connection lost). Keeps a dropped room from hot-looping while it self-heals.
const roomReconnectBackoff = 3 * time.Second

//nolint:gochecknoglobals // handle registry for the multi-room instances (parallels Runtime's own state).
var (
	roomsMu    sync.Mutex
	rooms      = map[int]*roomInst{}
	roomNextID int
)

type roomInst struct {
	cancel  context.CancelFunc
	done    chan struct{}
	errRun  error
	healthy atomic.Bool  // true while the transport has a live, recent liveness pong
	port    atomic.Int32 // actual bound SOCKS listener port (0 until the first onReady)
}

// StartRoom launches ONE independent olcRTC room instance and returns a handle for StopRoom. It blocks
// until the room's SOCKS5 listener is ready (or readyTimeoutMillis elapses, default 20s). socksUser/
// socksPass SHOULD be non-empty: the listener is loopback-only, but password auth keeps any other local
// app off the tunnel. Several rooms may run concurrently (different room/provider per call).
//
// socksPort <= 0 asks the OS for any free loopback port instead of a caller-chosen one — RoomPort
// reports back which one it got. This is deliberate: a caller-computed port (e.g. a fixed base + index)
// can collide with a same-numbered port a JUST-STOPPED room hasn't fully released yet, since Go's
// net.Listen fails outright on a busy port rather than picking another — a real race on rapid
// stop+restart (manual reconnect, or a watchdog-triggered full restart). Letting the OS assign the port
// makes that class of collision structurally impossible.
func StartRoom(
	providerName, transportName, roomID, deviceID, keyHex string,
	socksPort int,
	socksUser, socksPass string,
	readyTimeoutMillis int,
) (int, error) {
	client.RegisterDefaults()
	if err := validateRoomArgs(providerName, transportName, roomID, deviceID, keyHex); err != nil {
		return 0, err
	}
	if readyTimeoutMillis <= 0 {
		readyTimeoutMillis = 20000
	}
	portArg := "0"
	if socksPort > 0 {
		portArg = strconv.Itoa(socksPort)
	}

	cfg := client.Config{
		Transport:        transportName,
		Provider:         providerName,
		RoomURL:          roomID,
		KeyHex:           keyHex,
		DeviceID:         deviceID,
		LocalAddr:        net.JoinHostPort(defaultSOCKSHost, portArg),
		DNSServer:        defaultDNSServer,
		SOCKSUser:        socksUser,
		SOCKSPass:        socksPass,
		TransportOptions: roomTransportOptions(transportName),
		Liveness: client.LivenessConfig{
			Interval: defaultLivenessInterval,
			Timeout:  defaultLivenessTimeout,
			Failures: defaultLivenessFailures,
		},
	}

	ctx, cancelFunc := context.WithCancel(context.Background())
	inst := &roomInst{cancel: cancelFunc, done: make(chan struct{})}
	// OnHealth is wired after inst exists so the closure can reference it.
	cfg.OnHealth = func(s client.HealthStatus) { inst.healthy.Store(s.MissedPongs == 0) }
	readyCh := make(chan struct{})
	var readyOnce sync.Once

	go func() {
		defer cancelFunc()
		// Self-healing: re-run the client whenever it returns (connection lost) until the room is
		// StopRoom'd. Without this an unexpected drop would leave a DEAD room in the registry that the
		// balancer keeps dialling → constant disconnects. Runtime's own generations get this via the
		// Kotlin watchdog restarting Start; an independent room reconnects ITSELF here. SOCKS listener
		// stays on the same port across reconnects (the OS won't reassign it mid-life), readyOnce keeps
		// the first-ready signal one-shot.
		var lastErr error
		for ctx.Err() == nil {
			lastErr = client.New(cfg).RunWithAddress(ctx, func(actualAddr string) {
				inst.healthy.Store(true)
				if _, portText, splitErr := net.SplitHostPort(actualAddr); splitErr == nil {
					if p, convErr := strconv.Atoi(portText); convErr == nil {
						inst.port.Store(int32(p))
					}
				}
				readyOnce.Do(func() { close(readyCh) })
			})
			// RunWithAddress returns only on a fatal drop (conference ended / reconnect exhausted);
			// mark unhealthy so the balancer stops routing here until the next re-run brings the
			// transport back up.
			inst.healthy.Store(false)
			if ctx.Err() != nil {
				break
			}
			select {
			case <-ctx.Done():
			case <-time.After(roomReconnectBackoff):
			}
		}
		roomsMu.Lock()
		inst.errRun = lastErr
		roomsMu.Unlock()
		close(inst.done)
	}()

	timer := time.NewTimer(time.Duration(readyTimeoutMillis) * time.Millisecond)
	defer timer.Stop()
	select {
	case <-readyCh:
		roomsMu.Lock()
		roomNextID++
		h := roomNextID
		rooms[h] = inst
		roomsMu.Unlock()
		return h, nil
	case <-inst.done:
		if inst.errRun != nil {
			return 0, inst.errRun
		}
		return 0, ErrStoppedBeforeReady
	case <-timer.C:
		cancelFunc()
		<-inst.done
		return 0, ErrReadyTimeout
	}
}

// StopRoom cancels the instance for handle and waits (bounded) for it to unwind. No-op for unknown handles.
func StopRoom(handle int) {
	roomsMu.Lock()
	inst := rooms[handle]
	delete(rooms, handle)
	roomsMu.Unlock()
	if inst == nil {
		return
	}
	inst.cancel()
	select {
	case <-inst.done:
	case <-time.After(5 * time.Second):
	}
}

// StopAllRooms cancels every running room instance (call on disconnect).
func StopAllRooms() {
	roomsMu.Lock()
	insts := make([]*roomInst, 0, len(rooms))
	for h, inst := range rooms {
		insts = append(insts, inst)
		delete(rooms, h)
	}
	roomsMu.Unlock()
	for _, inst := range insts {
		inst.cancel()
	}
	for _, inst := range insts {
		select {
		case <-inst.done:
		case <-time.After(5 * time.Second):
		}
	}
}

// RoomsRunning reports how many independent room instances are currently up.
func RoomsRunning() int {
	roomsMu.Lock()
	defer roomsMu.Unlock()
	return len(rooms)
}

// RoomHealthy reports whether the room for handle currently has a live, healthy
// transport (recent liveness pong). It is false for unknown handles and for rooms
// that are mid-reconnect, letting the balancer route new connections around them
// instead of pinning a connection onto a dead link (the "frequent drop" cause).
func RoomHealthy(handle int) bool {
	roomsMu.Lock()
	inst := rooms[handle]
	roomsMu.Unlock()
	return inst != nil && inst.healthy.Load()
}

// RoomPort reports the actual SOCKS listener port StartRoom bound for handle (0 for an unknown handle,
// or a handle whose first onReady hasn't fired yet — StartRoom already blocks until then, so this is 0
// only for a handle that never reached readyCh, which StartRoom would have reported as an error instead
// of returning a handle for).
func RoomPort(handle int) int {
	roomsMu.Lock()
	inst := rooms[handle]
	roomsMu.Unlock()
	if inst == nil {
		return 0
	}
	return int(inst.port.Load())
}

// roomTransportOptions selects the options struct matching transportName, mirroring
// runtimeConfig.transportOptions(). datachannel carries no options; seichannel previously and
// incorrectly reused vp8channel.Options (harmless for vp8 fields the SEI transport ignores, but it
// meant SEI always ran with defaults instead of its own FPS/BatchSize/FragmentSize/AckTimeoutMS).
func roomTransportOptions(transportName string) client.TransportOptions {
	switch transportName {
	case transportVP8:
		return client.VP8Options{FPS: defaultVP8FPS, BatchSize: defaultVP8BatchSize}
	case transportSEI:
		return client.SEIOptions{
			FPS: defaultSEIFPS, BatchSize: defaultSEIBatchSize,
			FragmentSize: defaultSEIFragmentSize, AckTimeoutMS: defaultSEIAckTimeoutMS,
		}
	default:
		return nil
	}
}

func validateRoomArgs(providerName, transportName, roomID, deviceID, keyHex string) error {
	if !supportedProvider(providerName) {
		return fmt.Errorf("%w: provider %q", ErrUnsupportedProvider, providerName)
	}
	if !supportedTransport(transportName) {
		return fmt.Errorf("%w: transport %q", ErrUnsupportedTransport, transportName)
	}
	if roomID == "" {
		return fmt.Errorf("%w: room is required", ErrInvalidConfig)
	}
	if deviceID == "" {
		return fmt.Errorf("%w: device ID is required", ErrInvalidConfig)
	}
	return validateKey(keyHex)
}
