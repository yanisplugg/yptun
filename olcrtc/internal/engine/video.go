package engine

import (
	"sync"

	"github.com/pion/webrtc/v4"
)

// VideoTrackState stores local tracks and the remote-track callback.
type VideoTrackState struct {
	mu      sync.RWMutex
	tracks  []webrtc.TrackLocal
	handler func(*webrtc.TrackRemote, *webrtc.RTPReceiver)
}

// StoreVideoTrack records a local video track for current and future connections.
func (s *VideoTrackState) StoreVideoTrack(track webrtc.TrackLocal) {
	s.mu.Lock()
	s.tracks = append(s.tracks, track)
	s.mu.Unlock()
}

// SetVideoTrackHandler registers the remote video-track callback.
func (s *VideoTrackState) SetVideoTrackHandler(cb func(*webrtc.TrackRemote, *webrtc.RTPReceiver)) {
	s.mu.Lock()
	s.handler = cb
	s.mu.Unlock()
}

// VideoTrackHandler returns the current remote video-track callback.
func (s *VideoTrackState) VideoTrackHandler() func(*webrtc.TrackRemote, *webrtc.RTPReceiver) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.handler
}

// HasVideoTracks reports whether at least one local track is registered.
func (s *VideoTrackState) HasVideoTracks() bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return len(s.tracks) > 0
}

// WantsVideo reports whether local or remote video is configured.
func (s *VideoTrackState) WantsVideo() bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return len(s.tracks) > 0 || s.handler != nil
}

// RangeVideoTracks calls fn for every local track while preventing mutation.
// The callback receives whether remote video is configured, and the return
// value reports whether any local tracks were present under the same lock.
func (s *VideoTrackState) RangeVideoTracks(fn func(webrtc.TrackLocal, bool)) bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	wantsRemote := s.handler != nil
	for _, track := range s.tracks {
		fn(track, wantsRemote)
	}
	return len(s.tracks) > 0
}
