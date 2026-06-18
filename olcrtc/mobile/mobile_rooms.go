package mobile

// Multi-room support: several INDEPENDENT olcRTC room instances running at once, each with its own
// SOCKS5 listener, so the client can fan connections across them (per-connection round-robin) and
// aggregate bandwidth. Unlike Start/Stop (a package-level singleton), these are handle-based and use
// their own context — mirroring the isolated runtime of Check/Ping. Each room's SOCKS listener stays
// on loopback AND is password-protected (SOCKSUser/SOCKSPass), so no other local process can ride it.

import (
	"context"
	"sync"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/client"
	"github.com/openlibrecommunity/olcrtc/internal/transport/vp8channel"
)

//nolint:gochecknoglobals // handle registry for the multi-room instances (parallels the singleton state).
var (
	roomsMu    sync.Mutex
	rooms      = map[int]*roomInst{}
	roomNextID int
)

type roomInst struct {
	cancel context.CancelFunc
	done   chan struct{}
	errRun error
}

// StartRoom launches ONE independent olcRTC room instance and returns a handle for StopRoom. It blocks
// until the room's SOCKS5 listener is ready (or readyTimeoutMillis elapses, default 20s). socksUser/
// socksPass SHOULD be non-empty: the listener is loopback-only, but password auth keeps any other local
// app off the tunnel. Several rooms may run concurrently (different room/provider per call).
func StartRoom(
	carrierName, transportName, roomID, clientID, keyHex string,
	socksPort int,
	socksUser, socksPass string,
	readyTimeoutMillis int,
) (int, error) {
	registerDefaults()
	mu.Lock()
	ensureDefaultConfigLocked()
	cfg := defaults
	mu.Unlock()

	carrierName = normalizeCarrier(carrierName)
	transportName = normalizeTransport(transportName)
	if transportName == "" {
		transportName = cfg.transport
	}
	if err := validateStartArgs(carrierName, roomID, clientID, keyHex); err != nil {
		return 0, err
	}
	if readyTimeoutMillis <= 0 {
		readyTimeoutMillis = 20000
	}

	ctx, cancelFunc := context.WithCancel(context.Background())
	inst := &roomInst{cancel: cancelFunc, done: make(chan struct{})}
	readyCh := make(chan struct{})
	var readyOnce sync.Once

	go func() {
		defer cancelFunc()
		err := runClientWithReady(
			ctx,
			client.Config{
				Transport: transportName,
				Carrier:   carrierName,
				RoomURL:   buildRoomURL(carrierName, roomID),
				KeyHex:    keyHex,
				DeviceID:  clientID,
				LocalAddr: socksListenAddr(cfg.socksListenHost, socksPort),
				DNSServer: cfg.dnsServer,
				SOCKSUser: socksUser,
				SOCKSPass: socksPass,
				TransportOptions: vp8channel.Options{
					FPS:       cfg.vp8FPS,
					BatchSize: cfg.vp8BatchSize,
				},
				Liveness: livenessConfig(cfg),
			},
			func() { readyOnce.Do(func() { close(readyCh) }) },
		)
		roomsMu.Lock()
		inst.errRun = err
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
		return 0, errStoppedBeforeReady
	case <-timer.C:
		cancelFunc()
		<-inst.done
		return 0, errStartTimedOut
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
