// What the carrier session is doing, as /stats reports it.
//
// The /stats listener is bound before the server joins its room and answers
// for as long as the process lives, so "it answered" says only that the
// process is running. A server whose room is gone answers exactly like one a
// client could pair with, for the whole join attempt and again after every
// respawn - which is enough to keep an operator's probe flapping instead of
// settling on a verdict. The state below is the half that was missing.
//
// ai-generated: the whole file.
package server

// LinkState is what the server's carrier session is doing. It is reported as
// the "link" field of the /stats body.
type LinkState string

const (
	// LinkConnecting is the state from process start until the carrier
	// session is joined. A join that never succeeds never leaves it.
	LinkConnecting LinkState = "connecting"
	// LinkUp means the carrier session is joined and the server is serving
	// over it: a client could pair now.
	LinkUp LinkState = "up"
	// LinkDown means the carrier session is gone - it ended, or the server
	// declared it dead and the engine is rebuilding it.
	LinkDown LinkState = "down"
)

// LinkState reports what the carrier session is doing. A server that has not
// joined yet - including one built by hand in a test - reports
// LinkConnecting.
func (s *Server) LinkState() LinkState {
	if state := s.link.Load(); state != nil {
		return *state
	}
	return LinkConnecting
}

// setLinkState records a carrier-session transition.
func (s *Server) setLinkState(state LinkState) {
	s.link.Store(&state)
}
