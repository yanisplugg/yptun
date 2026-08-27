package goolom

import (
	"math/rand/v2"
	"time"

	"github.com/pion/webrtc/v4"

	"github.com/openlibrecommunity/olcrtc/internal/logger"
)

// processSendQueue drains the outbound queue onto dc. The data channel is
// passed in rather than read from the session on every iteration: the worker
// belongs to the channel it was started for, and a reconnect installs a new
// one while this worker unwinds.
func (s *Session) processSendQueue(dc *webrtc.DataChannel, workerID int, sessionCloseCh <-chan struct{}) {
	for {
		select {
		case <-sessionCloseCh:
			return
		case <-s.closeCh:
			return
		case data := <-s.sendQueue:
			if len(data) > s.trafficShape.MaxMessageSize {
				logger.Debugf("oversized message size=%d limit=%d", len(data), s.trafficShape.MaxMessageSize)
				continue
			}

			waited, err := s.waitBufferedAmount(dc, workerID, sessionCloseCh)
			if err != nil {
				return
			}
			if waited > 0 {
				logger.Verbosef("[WORKER-%d] Drained after %v", workerID, waited)
			}

			if err := dc.Send(data); err != nil {
				logger.Debugf("send error: %v", err)
				s.queueReconnect()
				return
			}

			if s.trafficShape.MinDelay > 0 {
				time.Sleep(s.calculateDelay())
			}
		}
	}
}

func (s *Session) waitBufferedAmount(
	dc *webrtc.DataChannel, workerID int, sessionCloseCh <-chan struct{},
) (time.Duration, error) {
	start := time.Now()
	for dc.BufferedAmount() > defaultBufferHighWaterMark {
		select {
		case <-sessionCloseCh:
			return 0, ErrSessionClosed
		case <-s.closeCh:
			return 0, ErrPeerClosed
		case <-time.After(10 * time.Millisecond):
			if time.Since(start) > 5*time.Second {
				logger.Debugf("buffer wait timeout worker=%d", workerID)
				return time.Since(start), nil
			}
		}
	}
	return time.Since(start), nil
}

func (s *Session) calculateDelay() time.Duration {
	minDelay := s.trafficShape.MinDelay
	maxDelay := s.trafficShape.MaxDelay
	if maxDelay <= minDelay {
		return minDelay
	}
	return minDelay + time.Duration(rand.Int64N(int64(maxDelay-minDelay))) //nolint:gosec,lll // G404: non-cryptographic shaping randomness
}
