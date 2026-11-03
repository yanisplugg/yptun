package transport

// PeerLifecycle is implemented by transports that keep per-peer state of
// their own under the server's peer sessions.
//
// RetirePeer tells the transport that the server's session on peerID has
// ended. The transport may release what it keeps for that peer, but must not
// refuse it afterwards: a client retries its handshake on the same peer ID,
// and the server answers with a fresh session on that peer's next frame.
//
// ai-generated: define explicit session-owned transport teardown; single-phase
// and without the fence the ported version had.
type PeerLifecycle interface {
	RetirePeer(peerID string)
}
