package salutejazz_test

// ai-generated: the whole file (olcrtc#49).

import (
	"github.com/openlibrecommunity/olcrtc/internal/engine/salutejazz"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
)

// Datachannel finds a session's PeerSeen through a type assertion to
// transport.PeerObserver, which a renamed method or a changed interface would
// fail without a word: every SaluteJazz hello timeout would read as an empty
// room again. salutejazz cannot import transport, which imports the engines,
// so the check lives in this external test package.
var _ transport.PeerObserver = (*salutejazz.Session)(nil)
