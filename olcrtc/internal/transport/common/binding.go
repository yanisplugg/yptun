package common

import (
	"hash/fnv"

	"github.com/openlibrecommunity/olcrtc/internal/roomhint"
)

// BindingToken derives the per-session token that keeps two olcrtc pairs
// sharing one SFU room from accepting each other's frames (concurrent e2e
// runs, real multi-tenant usage). channelID is unique per deployment when
// configured; falling back to roomURL preserves room-level isolation for
// deployments that do not set one.
//
// A client-side hint on the link (roomhint) is no part of the room: a client
// that reaches the relay over TCP binds to the same token as the server.
//
// The token is never zero, so a receiver can keep treating zero as "frame
// carries no binding" and accept it.
func BindingToken(channelID, roomURL string) uint32 {
	source := channelID
	if source == "" {
		source, _ = roomhint.Split(roomURL)
	}

	h := fnv.New32a()
	_, _ = h.Write([]byte(source))

	token := h.Sum32()
	if token == 0 {
		token = 1
	}

	return token
}
