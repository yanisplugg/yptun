// Package mobile provides a gomobile-compatible olcRTC client API.
package mobile

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/app/session"
	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/supervisor"
	"github.com/openlibrecommunity/olcrtc/pkg/olcrtc/client"

	_ "golang.org/x/mobile/bind"                       // keep gomobile binding dependencies reachable
	_ "google.golang.org/genproto/protobuf/field_mask" // keep gomobile on post-split genproto modules
)

const (
	stateIdle     runtimeState = "idle"
	stateStarting runtimeState = "starting"
	stateRunning  runtimeState = "running"
	stateStopping runtimeState = "stopping"
	stateStopped  runtimeState = "stopped"
)

const (
	defaultReadyTimeout = 8 * time.Second
	defaultStopTimeout  = 5 * time.Second
	maxTimeoutMillis    = int64(9223372036854)
)

//nolint:gochecknoglobals,nolintlint // sentinel identities are immutable public API values
var (
	ErrAlreadyRunning       = errors.New("olcRTC runtime is already active")
	ErrNotRunning           = errors.New("olcRTC runtime has not been started")
	ErrStoppedBeforeReady   = errors.New("olcRTC runtime stopped before becoming ready")
	ErrReadyTimeout         = errors.New("olcRTC runtime readiness timed out")
	ErrStopTimeout          = errors.New("olcRTC runtime stop timed out")
	ErrInvalidConfig        = errors.New("invalid mobile runtime configuration")
	ErrUnsupportedProvider  = errors.New("unsupported provider")
	ErrUnsupportedTransport = errors.New("unsupported transport")
)

type runtimeState string

type clientRunner func(context.Context, client.Config, func(string)) error

type runGeneration struct {
	id            uint64
	cfg           client.Config
	cancel        context.CancelFunc
	ready         chan struct{}
	done          chan struct{}
	readyOnce     sync.Once
	doneOnce      sync.Once
	stopRequested bool
	err           error
}

// Runtime owns one independently configured mobile client lifecycle.
type Runtime struct {
	mu             sync.Mutex
	defaults       runtimeConfig
	state          runtimeState
	nextGeneration uint64
	current        *runGeneration
	runner         clientRunner
	// listener hears each session open; see SetSessionListener.
	listener SessionListener
}

// New returns an idle Runtime with documented mobile defaults.
func New() *Runtime {
	return newRuntime(runPublicClient)
}

func newRuntime(runner clientRunner) *Runtime {
	// Every host of this package is a phone: an iOS packet tunnel extension
	// with a ~50 MB ceiling, or the Android VPN service. Server-sized receive
	// windows get the extension killed mid-transfer, which the app can only
	// report as "the packet tunnel is down".
	client.UseConstrainedBuffers()
	client.RegisterDefaults()
	return &Runtime{
		defaults: defaultRuntimeConfig(),
		state:    stateIdle,
		runner:   runner,
	}
}

func runPublicClient(ctx context.Context, cfg client.Config, onReady func(string)) error {
	if err := client.New(cfg).RunWithAddress(ctx, onReady); err != nil {
		return fmt.Errorf("run public client: %w", err)
	}
	return nil
}

// Start launches a new generation from the current configuration snapshot.
func (r *Runtime) Start() error {
	r.mu.Lock()
	defer r.mu.Unlock()

	if r.isActiveLocked() {
		return ErrAlreadyRunning
	}
	if err := validateRuntimeConfig(r.defaults); err != nil {
		return err
	}
	cfg := r.defaults.clientConfig()

	ctx, cancel := context.WithCancel(context.Background())
	r.nextGeneration++
	gen := &runGeneration{
		id:     r.nextGeneration,
		cfg:    cfg,
		cancel: cancel,
		ready:  make(chan struct{}),
		done:   make(chan struct{}),
	}
	r.current = gen
	r.state = stateStarting
	go r.run(ctx, gen)
	return nil
}

// failoverMaxCycles is one forward pass over the room list, extended by the
// rooms the host appends meanwhile; then the generation ends with an error.
// The runtime deliberately does not retry forever on its own: the host app
// owns a retry loop and re-evaluates the network between attempts, which on a
// phone is the part that matters. A second, blind loop underneath it would
// only hide failures from the one that can act on them.
const failoverMaxCycles = 1

// failoverRetryDelay is the pause between rooms.
var failoverRetryDelay = 2 * time.Second //nolint:gochecknoglobals // test hook

// run walks the room list under the supervisor, the way the CLI does: when
// the room a session is in ends - the server retired it, or it died - the next
// room is tried, and a room added while the session was live is seen at that
// moment. Failover happens inside one generation.
//
// ai-generated: the supervisor arrangement (this function and profilesSnapshot).
func (r *Runtime) run(ctx context.Context, gen *runGeneration) {
	onReady := func(string) { r.markReady(gen) }
	err := supervisor.Run(ctx, supervisor.Config{
		Profiles:   r.profilesSnapshot(),
		Reload:     func() ([]supervisor.Profile, error) { return r.profilesSnapshot(), nil },
		RetryDelay: failoverRetryDelay,
		MaxCycles:  failoverMaxCycles,
		OnProfileStart: func(profile supervisor.Profile, cycle int) {
			logger.Infof("failover cycle=%d starting room=%s", cycle, profile.Name)
		},
		OnProfileEnd: func(profile supervisor.Profile, cycle int, err error) {
			if err != nil {
				logger.Warnf("failover cycle=%d room=%s ended with error: %v", cycle, profile.Name, err)
				return
			}
			logger.Warnf("failover cycle=%d room=%s ended", cycle, profile.Name)
		},
	}, func(ctx context.Context, profile session.Config) error {
		// Only the room varies between profiles; the rest is the
		// generation's configuration snapshot, exactly as before failover.
		cfg := gen.cfg
		cfg.RoomURL = profile.RoomID
		cfg.OnSessionOpen = func(sessionID string) { r.notifySessionOpened(gen, profile.RoomID, sessionID) }
		// A room nobody is in ends this run so the next one is tried, but
		// only while there is a next one: with a single room, giving up
		// would end the generation and leave the host holding a tunnel it
		// did not ask to lose, which is what the runtime did before failover
		// and what a host without its own retry loop still expects. The
		// count is read here, at the start of this room's run, so a room the
		// host appends is in force from the next start on; a room whose peer
		// is merely silent is retried in place either way.
		//
		// ai-generated: EndOnEmptyRoom and the count (the port of olcrtc#39;
		// the count is from the review).
		cfg.EndOnEmptyRoom = r.roomCount() > 1
		return r.runner(ctx, cfg, onReady)
	})
	gen.cancel()
	r.finish(gen, err)
}

// roomCount is how many rooms the host is offering right now. It answers
// whether a room that turns out to be empty is worth giving up on: with one
// room there is nowhere to give up to.
//
// ai-generated: the whole function (review of olcrtc#39).
func (r *Runtime) roomCount() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.defaults.rooms())
}

// profilesSnapshot is the supervisor's view of the room list: the primary
// first, then the failover extras, each a profile carrying only its room. Read
// under the lock, so a host extending the list during a live session is seen
// at the next hop rather than the next Start.
func (r *Runtime) profilesSnapshot() []supervisor.Profile {
	r.mu.Lock()
	rooms := r.defaults.rooms()
	r.mu.Unlock()
	profiles := make([]supervisor.Profile, 0, len(rooms))
	for _, room := range rooms {
		profiles = append(profiles, supervisor.Profile{Name: room, Config: session.Config{RoomID: room}})
	}
	return profiles
}

func (r *Runtime) markReady(gen *runGeneration) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if !r.isCurrentGenerationLocked(gen) || r.state != stateStarting {
		return
	}
	gen.readyOnce.Do(func() {
		r.state = stateRunning
		close(gen.ready)
	})
}

func (r *Runtime) finish(gen *runGeneration, runErr error) {
	r.mu.Lock()
	if gen.stopRequested && errors.Is(runErr, context.Canceled) {
		runErr = nil
	}
	gen.err = runErr
	if r.isCurrentGenerationLocked(gen) {
		r.state = stateStopped
	}
	r.mu.Unlock()
	gen.doneOnce.Do(func() { close(gen.done) })
}

// WaitReady waits for the generation current when the method is called.
func (r *Runtime) WaitReady(timeoutMillis int) error {
	r.mu.Lock()
	gen := r.current
	r.mu.Unlock()
	if gen == nil {
		return ErrNotRunning
	}
	return r.waitGenerationReady(gen, timeoutFromMillis(timeoutMillis, defaultReadyTimeout))
}

func (r *Runtime) waitGenerationReady(gen *runGeneration, timeout time.Duration) error {
	// A generation that has already exited is never ready, whatever its latch
	// says. The ready channel stays closed after Stop and the runtime keeps
	// the generation, so checking the latch alone reported a live tunnel for
	// a runtime whose State() already said stopped.
	if channelClosed(gen.done) {
		return r.generationError(gen)
	}
	if channelClosed(gen.ready) {
		return nil
	}
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case <-gen.ready:
		return nil
	case <-gen.done:
		return r.generationError(gen)
	case <-timer.C:
		return ErrReadyTimeout
	}
}

// generationError describes a generation that has finished: its own failure
// when it had one, otherwise whether it ever reached readiness.
func (r *Runtime) generationError(gen *runGeneration) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if gen.err != nil {
		return gen.err
	}
	if channelClosed(gen.ready) {
		return ErrNotRunning
	}
	return ErrStoppedBeforeReady
}

// Stop cancels the current generation and waits for its shutdown.
//
// It always leaves the runtime startable. The returned error says whether the
// generation shut down inside the deadline, not whether the runtime can be used
// again — those were the same thing once, and that is the bug this contract
// exists to prevent: a teardown that outran its deadline left the state at
// "stopping", which counts as active, so every later Start was refused with
// ErrAlreadyRunning. On a phone that is a tunnel which cannot be switched to
// another room until the app is force-stopped, and it is reached by the
// ordinary route of a wedged transport, whose writes take 30 s each to fail
// while Stop is given 5.
//
// A generation that misses the deadline is therefore detached rather than
// waited on. Its goroutines keep unwinding, and when they finish, `finish`
// finds it is no longer the current generation and leaves the live one alone.
// The socket a new generation needs is already free: the SOCKS listener is
// closed by the run function's own defer the moment the context is cancelled,
// long before the slow half of the teardown.
func (r *Runtime) Stop(timeoutMillis int) error {
	r.mu.Lock()
	if r.state == stateIdle || r.state == stateStopped || r.current == nil {
		r.mu.Unlock()
		return nil
	}
	gen := r.current
	if r.state != stateStopping {
		r.state = stateStopping
		gen.stopRequested = true
		gen.cancel()
	}
	r.mu.Unlock()

	timer := time.NewTimer(timeoutFromMillis(timeoutMillis, defaultStopTimeout))
	defer timer.Stop()
	select {
	case <-gen.done:
		return nil
	case <-timer.C:
		r.abandon(gen)
		return ErrStopTimeout
	}
}

// abandon detaches a generation whose shutdown outran its deadline, so that the
// runtime can be started again while the old one is still unwinding.
//
// Safe against the late finish: `finish` only writes state for the generation
// that is current, and this one no longer is.
func (r *Runtime) abandon(gen *runGeneration) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if !r.isCurrentGenerationLocked(gen) {
		return
	}
	r.current = nil
	r.state = stateStopped
}

// State returns idle, starting, running, stopping, or stopped.
func (r *Runtime) State() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return string(r.state)
}

// IsRunning reports whether a generation is starting, running, or stopping.
func (r *Runtime) IsRunning() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.isActiveLocked()
}

func (r *Runtime) isActiveLocked() bool {
	return r.state == stateStarting || r.state == stateRunning || r.state == stateStopping
}

func (r *Runtime) isCurrentGenerationLocked(gen *runGeneration) bool {
	return r.current == gen && r.current.id == gen.id
}

func timeoutFromMillis(value int, fallback time.Duration) time.Duration {
	if value <= 0 {
		return fallback
	}
	if int64(value) > maxTimeoutMillis {
		return time.Duration(1<<63 - 1)
	}
	return time.Duration(value) * time.Millisecond
}

func channelClosed(ch <-chan struct{}) bool {
	select {
	case <-ch:
		return true
	default:
		return false
	}
}
