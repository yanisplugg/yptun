package common

import (
	"errors"
	"fmt"

	"github.com/pion/webrtc/v4"
)

// ErrVideoTrackUnsupported is returned when a provider cannot expose video tracks.
var ErrVideoTrackUnsupported = errors.New("provider does not support video tracks")

// NewVideoTrack creates the local video track a transport publishes.
//
// Track and stream IDs get a random per-peer suffix because Jitsi/Jicofo keys
// participant sources by msid (track-id + stream-id) and rejects a
// session-accept whose msid collides with one already in the conference.
func NewVideoTrack(
	capability webrtc.RTPCodecCapability,
	name string,
) (*webrtc.TrackLocalStaticSample, error) {
	track, err := webrtc.NewTrackLocalStaticSample(
		capability,
		name+"-"+RandomID(),
		"olcrtc-"+RandomID(),
	)
	if err != nil {
		return nil, fmt.Errorf("create local video track: %w", err)
	}

	return track, nil
}
