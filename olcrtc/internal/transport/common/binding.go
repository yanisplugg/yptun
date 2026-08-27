package common

import "hash/fnv"

// BindingToken derives the per-session token that keeps two olcrtc pairs
// sharing one SFU room from accepting each other's frames (concurrent e2e
// runs, real multi-tenant usage). channelID is unique per deployment when
// configured; falling back to roomURL preserves room-level isolation for
// deployments that do not set one.
//
// The token is never zero, so a receiver can keep treating zero as "frame
// carries no binding" and accept it.
func BindingToken(channelID, roomURL string) uint32 {
	source := channelID
	if source == "" {
		source = roomURL
	}

	h := fnv.New32a()
	_, _ = h.Write([]byte(source))

	token := h.Sum32()
	if token == 0 {
		token = 1
	}

	return token
}
