package vkcalls

import (
	"context"
	"fmt"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/logger"
)

// A fresh VK room keeps its first participants in DIRECT: two alone never
// switch, and a third simultaneous participant is what flips the SFU to
// SERVER - which then sticks for the room's lifetime (the spike's
// topology-flip runs, and the 1.0.444 fresh-link measurement: a server and
// its client sat in DIRECT to their connect timeouts with no
// topology-changed between them). While the engine waits in DIRECT it
// therefore recruits signaling-only guest joins of its own, and drops them
// the moment the switch arrives.
const (
	// maxRecruits is how many guests the engine adds besides itself: one
	// already makes three with the waiting peer, two cover the case the
	// peer has not joined yet.
	maxRecruits = 2
	// recruitFirstDelay lets the waiting peer join first, so its own
	// connection already counts toward the three.
	recruitFirstDelay = 2 * time.Second
	// recruitNextDelay spaces the joins: the flip lands on a join, and a
	// burst buys nothing.
	recruitNextDelay = 3 * time.Second
)

// recruitGuests joins signaling-only guests until done closes (the main
// socket saw the switch, or gave up), at most maxRecruits of them. Every
// guest is closed on the way out: they exist only to raise the room's
// simultaneous participant count.
func (s *Session) recruitGuests(ctx context.Context, done <-chan struct{}) {
	if s.cfg.Refresh == nil {
		// Without a credential issuer there is nothing to join with; the
		// wait stands on its own.
		return
	}
	var guests []*signalingClient
	defer func() {
		for _, guest := range guests {
			guest.close()
		}
	}()
	delay := recruitFirstDelay
	for range maxRecruits {
		if !recruitPause(ctx, done, delay) {
			return
		}
		delay = recruitNextDelay
		guest, err := s.recruitOne(ctx)
		if err != nil {
			logger.Debugf("vkcalls: guest recruit: %v", err)
			continue
		}
		guests = append(guests, guest)
		logger.Debugf("vkcalls: recruited a signaling-only guest to flip the fresh room to SERVER")
	}
	<-doneOrContext(ctx, done)
}

// recruitOne issues a fresh guest's credentials, joins its signaling socket
// and holds it open - answering keepalives and nothing else: no peer
// connection, no commands, the participant only counts.
func (s *Session) recruitOne(ctx context.Context) (*signalingClient, error) {
	creds, err := s.cfg.Refresh(ctx)
	if err != nil {
		return nil, fmt.Errorf("guest credentials: %w", err)
	}
	guest, err := dialSignaling(ctx, creds.URL, s.cfg.Resolver)
	if err != nil {
		return nil, err
	}
	if _, err = guest.awaitJoin(ctx); err != nil {
		guest.close()
		return nil, fmt.Errorf("guest join: %w", err)
	}
	go guest.drainNotifications()
	return guest, nil
}

// recruitPause waits d, or ends early when the wait is over.
func recruitPause(ctx context.Context, done <-chan struct{}, d time.Duration) bool {
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-timer.C:
		return true
	case <-done:
		return false
	case <-ctx.Done():
		return false
	}
}

// doneOrContext is what a recruiter out of guests blocks on.
func doneOrContext(ctx context.Context, done <-chan struct{}) <-chan struct{} {
	if ctxDone := ctx.Done(); ctxDone != nil {
		merged := make(chan struct{})
		go func() {
			select {
			case <-done:
			case <-ctxDone:
			}
			close(merged)
		}()
		return merged
	}
	return done
}
