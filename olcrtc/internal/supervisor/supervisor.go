// Package supervisor runs ordered session profiles with failover.
//
// The profile list is static (Config.Profiles) or dynamic: with Config.Reload
// set it is re-read at every failover advance, so a room added while a session
// was live is used the moment that session ends - without a restart, and
// without touching the live session, since the reload only happens after the
// current profile exits.
package supervisor

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/app/session"
)

// DefaultRetryDelay is used between profile attempts when Config.RetryDelay is unset.
const DefaultRetryDelay = 2 * time.Second

// DefaultHistoryLimit bounds emitted status history when Config.HistoryLimit is unset.
const DefaultHistoryLimit = 20

// maxTrackedProfiles bounds the per-profile counters a rolling list can
// accumulate; see statusTracker.forgetOldest.
//
// ai-generated: the constant (review of olcrtc#39).
const maxTrackedProfiles = 64

// profileHeldFor is how long a profile has to run to count as one that
// worked; maxRetryDelay and maxRetryBackoff bound the wait between passes
// that did not. See profilePace. A profile that cannot connect ends in the
// time its provider takes to give up, and one that gives up on an empty room
// in about a handshake; a profile serving traffic runs for hours.
//
// ai-generated: the three (review of olcrtc#39).
const (
	profileHeldFor  = time.Minute
	maxRetryDelay   = 5 * time.Minute
	maxRetryBackoff = 8
)

const (
	// EventProfileStart marks a profile attempt starting.
	EventProfileStart = "profile_start"
	// EventProfileEnd marks a profile attempt ending.
	EventProfileEnd = "profile_end"
)

var (
	// ErrNoProfiles is returned when the supervisor is started without profiles.
	ErrNoProfiles = errors.New("supervisor: no profiles configured")
	// ErrMaxCyclesExceeded is returned after MaxCycles complete profile-list passes.
	ErrMaxCyclesExceeded = errors.New("supervisor: max failover cycles exceeded")
	errProfileCleanEnd   = errors.New("profile ended")
)

// Profile is one runnable session configuration in an ordered failover list.
type Profile struct {
	Name   string
	Config session.Config
}

// ProfileStatus summarizes one profile's failover history.
type ProfileStatus struct {
	Name        string
	Starts      int
	Failures    int
	CleanEnds   int
	LastStarted time.Time
	LastEnded   time.Time
	LastError   string
}

// Event is one bounded failover history entry.
type Event struct {
	Time    time.Time
	Type    string
	Profile string
	Cycle   int
	Error   string
}

// Status is a point-in-time view of the supervisor.
//
// ActiveProfileIndex is the active profile's place in the list being walked,
// which with Config.Reload is not its place in Profiles: that one is ordered
// by when each profile was first seen and holds names the list has since
// dropped. ActiveProfile names it unambiguously.
//
// ai-generated: the note on ActiveProfileIndex (review of olcrtc#39).
type Status struct {
	Cycle              int
	ActiveProfile      string
	ActiveProfileIndex int
	Profiles           []ProfileStatus
	History            []Event
	LastError          string
}

// Runner starts one session profile and blocks until it ends or fails.
type Runner func(ctx context.Context, cfg session.Config) error

// Config controls ordered failover behavior.
type Config struct {
	// Profiles is the initial ordered list. With Reload unset it is the only list.
	Profiles []Profile

	// Reload, when set, returns the current ordered profile list. It is called
	// at every failover advance, never during a live session, so a room added
	// while a session was running is used the moment that session ends. A nil,
	// empty or errored result keeps the last known list.
	//
	// ai-generated: the dynamic profile list (this field, profileList,
	// nextStep and the name-keyed status tracker below).
	Reload func() ([]Profile, error)

	RetryDelay time.Duration
	MaxCycles  int

	OnProfileStart func(profile Profile, cycle int)
	OnProfileEnd   func(profile Profile, cycle int, err error)
	OnStatus       func(status Status)
	HistoryLimit   int
}

// Run starts profiles in order. When a profile exits while ctx is still active,
// the supervisor waits RetryDelay, re-reads the list when Reload is set, and
// advances to the profile after the one that just ran, wrapping to the top;
// see nextStep for how it finds it. That keeps a client following a rolling
// window: once the room it just left is gone from the list, it lands on the
// new head, and no pass is counted for a list it never finished.
//
// MaxCycles counts completed passes over the profiles on offer, judged against
// the list as reloaded after each profile ends. A list that grows while a
// profile runs extends the current pass instead of waiting for the next one,
// which is what lets a host hand a running client its next room with
// MaxCycles set to 1: try everything I have been given, once. A pass in which
// no profile held is followed by a longer wait; see profilePace.
func Run(ctx context.Context, cfg Config, run Runner) error {
	// A negative delay parses fine from YAML and waitRetryDelay treats it as
	// "no wait", which turns failover into a busy loop against a profile that
	// fails immediately.
	if cfg.RetryDelay <= 0 {
		cfg.RetryDelay = DefaultRetryDelay
	}
	pace := &profilePace{}
	list := &profileList{current: append([]Profile(nil), cfg.Profiles...), reload: cfg.Reload}
	if len(list.refresh()) == 0 {
		return ErrNoProfiles
	}
	state := newStatusTracker(cfg.HistoryLimit, cfg.OnStatus)

	var last lastProfile
	cycle := 1
	for {
		if ctx.Err() != nil {
			return nil //nolint:nilerr // context cancellation is normal supervisor shutdown
		}
		profiles := list.refresh()
		if len(profiles) == 0 {
			if err := waitRetryDelay(ctx, cfg.RetryDelay); err != nil {
				return nil //nolint:nilerr // context cancellation during retry delay is normal shutdown
			}
			continue
		}
		idx, wrapped := nextStep(profiles, last)
		if wrapped {
			cycle++
		}
		profile := profiles[idx]
		last = lastProfile{name: profile.Name, index: idx, ran: true}

		state.start(profile.Name, cycle, idx)
		cfg.notifyStart(profile, cycle)
		started := time.Now()
		err := run(ctx, profile.Config)
		if ctx.Err() != nil {
			return nil //nolint:nilerr // context cancellation is normal supervisor shutdown
		}
		if time.Since(started) >= profileHeldFor {
			pace.held()
		}
		resultErr := profileResultError(profile.Name, err)
		state.end(profile.Name, cycle, err)
		cfg.notifyEnd(profile, cycle, err)

		// Judged against the list as it is now, not as it was when this
		// profile started. Checked before the retry delay, so a list with
		// nowhere left to go fails fast.
		_, done := nextStep(list.refresh(), last)
		if cfg.MaxCycles > 0 && cycle >= cfg.MaxCycles && done {
			return fmt.Errorf("%w after %d cycle(s): %w", ErrMaxCyclesExceeded, cycle, resultErr)
		}
		if err := waitRetryDelay(ctx, pace.wait(cfg.RetryDelay, done)); err != nil {
			return nil //nolint:nilerr // context cancellation during retry delay is normal shutdown
		}
	}
}

// profilePace spaces out passes that produce nothing. Every profile of a pass
// ending as fast as it can start is a client that is offline, or a server that
// has left every room it was in, and at RetryDelay apiece that is a join of
// the relay's room every few seconds for as long as olcrtc runs. A successful
// rejoin never counts against a provider's own reconnect budget, so nothing
// else bounds it - the same finding the client's recovery backoff came from
// (olcrtc#19, review of #20). A profile that held is one that worked, and
// clears the pacing, so a room retired after hours is followed at once.
//
// ai-generated: the whole type and its use (review of olcrtc#39).
type profilePace struct {
	deadCycles int
	heldACycle bool
}

func (p *profilePace) held() { p.heldACycle = true }

// wait is the pause before the next profile; passComplete says the pass just
// ended, which is when the pacing is reconsidered.
func (p *profilePace) wait(base time.Duration, passComplete bool) time.Duration {
	if passComplete {
		if p.heldACycle {
			p.deadCycles = 0
		} else {
			p.deadCycles++
		}
		p.heldACycle = false
	}
	if p.deadCycles <= 0 {
		return base
	}
	return min(base<<uint(min(p.deadCycles, maxRetryBackoff)), maxRetryDelay)
}

// lastProfile is where the walk is: the profile that ran last, by name and by
// position. ran tells a first run from one whose profile happened to be
// unnamed.
//
// ai-generated: the type (review of olcrtc#39). The port went by name alone,
// and an unnamed profile - what the "none" provider yields on mobile, where
// the room is the name - then looked like a walk that had not started, so the
// head ran for ever and nothing after it was ever reached.
type lastProfile struct {
	name  string
	index int
	ran   bool
}

// profileList is the rolling set of profiles the supervisor walks. With a
// Reload hook it is re-read on every access; a failed or empty reload keeps
// the last good list, so a transient error never empties the window.
type profileList struct {
	current []Profile
	reload  func() ([]Profile, error)
}

func (p *profileList) refresh() []Profile {
	if p.reload == nil {
		return p.current
	}
	if next, err := p.reload(); err == nil && len(next) > 0 {
		p.current = next
	}
	return p.current
}

// nextStep says which profile runs next and whether getting there went past
// the end of the list - one completed pass, which is what MaxCycles counts.
//
// It finds the profile that just ran by name, which is what lets the list roll
// underneath the walk, and starts the search where that profile ran, so a list
// whose names repeat - or has none, as the "none" provider's rooms do - walks
// all of them instead of pinning the first match. A profile the list no longer
// holds means the window rolled past it: the head is next and no pass ended,
// since nothing on offer now has been run yet. That is the difference between
// a list that wrapped and one that was rewritten, and going by "the next index
// is 0" alone could not tell them apart.
//
// ai-generated: the whole function (review of olcrtc#39); the name lookup is
// from the port. It replaces the port's set of names started this cycle, which
// could not tell two unnamed profiles apart and ended the pass at the first.
func nextStep(profiles []Profile, last lastProfile) (int, bool) {
	if !last.ran || len(profiles) == 0 {
		return 0, false
	}
	for offset := range profiles {
		i := (last.index + offset) % len(profiles)
		if profiles[i].Name == last.name {
			return (i + 1) % len(profiles), i+1 == len(profiles)
		}
	}
	return 0, false
}

func (c Config) notifyStart(profile Profile, cycle int) {
	if c.OnProfileStart != nil {
		c.OnProfileStart(profile, cycle)
	}
}

func (c Config) notifyEnd(profile Profile, cycle int, err error) {
	if c.OnProfileEnd != nil {
		c.OnProfileEnd(profile, cycle, err)
	}
}

func profileResultError(name string, err error) error {
	if err != nil {
		return fmt.Errorf("profile %q: %w", name, err)
	}
	return fmt.Errorf("profile %q: %w", name, errProfileCleanEnd)
}

// statusTracker keeps per-profile counters by name, in the order profiles were
// first seen: with a dynamic list there is no fixed index to key them on.
type statusTracker struct {
	status       Status
	byName       map[string]*ProfileStatus
	order        []string
	notify       func(Status)
	historyLimit int
}

func newStatusTracker(historyLimit int, notify func(Status)) *statusTracker {
	if historyLimit == 0 {
		historyLimit = DefaultHistoryLimit
	}
	return &statusTracker{
		status:       Status{ActiveProfileIndex: -1},
		byName:       make(map[string]*ProfileStatus),
		notify:       notify,
		historyLimit: historyLimit,
	}
}

func (t *statusTracker) profile(name string) *ProfileStatus {
	profile := t.byName[name]
	if profile == nil {
		profile = &ProfileStatus{Name: name}
		t.byName[name] = profile
		t.order = append(t.order, name)
		t.forgetOldest()
	}
	return profile
}

// forgetOldest keeps the tracked profiles bounded. A static list is smaller
// than the cap and nothing is ever dropped; a rolling one hands out a new name
// every few hours, and without this the tracker, every status it emits and
// every history entry it copies grew for as long as the client ran. The
// oldest names go first, which on a rolling list are the rooms long retired.
//
// ai-generated: the whole function (review of olcrtc#39).
func (t *statusTracker) forgetOldest() {
	for len(t.order) > maxTrackedProfiles {
		delete(t.byName, t.order[0])
		t.order = t.order[1:]
	}
}

func (t *statusTracker) start(name string, cycle, idx int) {
	now := time.Now()
	profile := t.profile(name)
	profile.Starts++
	profile.LastStarted = now
	t.status.Cycle = cycle
	t.status.ActiveProfile = name
	t.status.ActiveProfileIndex = idx
	t.appendHistory(Event{
		Time:    now,
		Type:    EventProfileStart,
		Profile: name,
		Cycle:   cycle,
	})
	t.emit()
}

func (t *statusTracker) end(name string, cycle int, err error) {
	now := time.Now()
	profile := t.profile(name)
	profile.LastEnded = now
	event := Event{
		Time:    now,
		Type:    EventProfileEnd,
		Profile: name,
		Cycle:   cycle,
	}
	if err != nil {
		profile.Failures++
		profile.LastError = err.Error()
		t.status.LastError = fmt.Sprintf("profile %q: %v", name, err)
		event.Error = err.Error()
	} else {
		profile.CleanEnds++
		profile.LastError = ""
		t.status.LastError = fmt.Sprintf("profile %q ended", name)
	}
	t.status.ActiveProfile = ""
	t.status.ActiveProfileIndex = -1
	t.appendHistory(event)
	t.emit()
}

func (t *statusTracker) appendHistory(event Event) {
	if t.historyLimit < 0 {
		return
	}
	t.status.History = append(t.status.History, event)
	if len(t.status.History) > t.historyLimit {
		t.status.History = t.status.History[len(t.status.History)-t.historyLimit:]
	}
}

func (t *statusTracker) emit() {
	if t.notify == nil {
		return
	}
	t.notify(t.snapshot())
}

// snapshot copies the status so a receiver cannot reach back into the tracker.
func (t *statusTracker) snapshot() Status {
	status := t.status
	status.Profiles = make([]ProfileStatus, 0, len(t.order))
	for _, name := range t.order {
		status.Profiles = append(status.Profiles, *t.byName[name])
	}
	status.History = append([]Event(nil), t.status.History...)
	return status
}

func waitRetryDelay(ctx context.Context, delay time.Duration) error {
	if delay <= 0 {
		return nil
	}
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return fmt.Errorf("retry delay canceled: %w", ctx.Err())
	case <-timer.C:
		return nil
	}
}
