package salutejazz

import (
	"encoding/json"
	"testing"
	"time"

	"github.com/livekit/protocol/livekit"
)

// TestRTCJoinSeedsTheRoster pins where a joiner learns who is already in the
// room: rtc:join carries otherParticipants, and that is the only frame that
// names them. The roster updates the connector sends afterwards describe this
// participant to itself, so a session that read only those would sit in a
// full room believing it was alone.
func TestRTCJoinSeedsTheRoster(t *testing.T) {
	sess := newIdleSession(t)
	gen := newGeneration(nil)
	storeString(&gen.identity, "self")

	sess.handleMediaOut(gen, mediaOutJSON(t, map[string]any{
		"method": methodJoin,
		"join": map[string]any{"otherParticipants": []any{
			map[string]any{"sid": "PA_first", "identity": "first", "state": "ACTIVE"},
			map[string]any{"sid": "PA_second", "identity": "second", "state": "ACTIVE"},
		}},
	}))
	if peers := gen.remoteIdentities(); len(peers) != 2 || peers[0] != "first" || peers[1] != "second" {
		t.Fatalf("roster after rtc:join = %v, want both participants it listed", peers)
	}

	// The update frames describe this participant, and this session is never
	// its own peer.
	sess.handleMediaOut(gen, mediaOutJSON(t, map[string]any{
		"method": methodParticipants,
		"update": map[string]any{"participants": []any{
			map[string]any{"sid": "PA_self", "identity": "self", "state": "ACTIVE"},
		}},
	}))
	if peers := gen.remoteIdentities(); len(peers) != 2 {
		t.Fatalf("roster after an update about this participant = %v", peers)
	}

	// An empty room is the other half: a joiner that is first in is told so,
	// and takes nobody from it.
	fresh := newGeneration(nil)
	sess.handleMediaOut(fresh, mediaOutJSON(t, map[string]any{
		"method": methodJoin,
		"join":   map[string]any{"otherParticipants": []any{}},
	}))
	if peers := fresh.remoteIdentities(); len(peers) != 0 {
		t.Fatalf("roster after joining an empty room = %v", peers)
	}
}

// TestParticipantLeftClearsTheGhost covers the event the connector sends the
// rest of the room when a participant's socket has been gone for its ping
// timeout. Nothing else is known to report it, and a participant left in the
// roster keeps this session believing there is someone there to talk to.
func TestParticipantLeftClearsTheGhost(t *testing.T) {
	for _, tc := range []struct {
		name  string
		frame envIn
	}{
		{"named by the envelope", envIn{
			Event: eventParticipantLeft, ParticipantID: "ghost",
		}},
		{"named by the payload", envIn{
			Event: eventParticipantLeft, Payload: json.RawMessage(`{"participantId":"ghost"}`),
		}},
		{"named by the payload participant", envIn{
			Event: eventParticipantLeft, Payload: json.RawMessage(`{"participant":{"participantId":"ghost"}}`),
		}},
	} {
		sess := newIdleSession(t)
		gen := newGeneration(nil)
		if !sess.publishGeneration(gen) {
			t.Fatal("the session refused the attempt")
		}
		gen.applyParticipants([]participant{
			{SID: "PA_ghost", Identity: "ghost", State: "ACTIVE"},
			{SID: "PA_other", Identity: "other", State: "ACTIVE"},
		})
		if !sess.hasPeer() {
			t.Fatalf("%s: the roster named nobody to lose", tc.name)
		}

		sess.handleEnvelope(gen, tc.frame)

		if peers := gen.remoteIdentities(); len(peers) != 1 || peers[0] != "other" {
			t.Fatalf("%s: roster = %v, want the participant that is still here", tc.name, peers)
		}
	}

	// The last one out leaves a session with no peer at all: that is the
	// state WaitForPeer is about.
	sess := newIdleSession(t)
	gen := newGeneration(nil)
	if !sess.publishGeneration(gen) {
		t.Fatal("the session refused the attempt")
	}
	gen.applyParticipants([]participant{{SID: "PA_ghost", Identity: "ghost", State: "ACTIVE"}})
	sess.handleEnvelope(gen, envIn{Event: eventParticipantLeft, ParticipantID: "ghost"})
	if sess.hasPeer() {
		t.Fatal("a participant the connector reported gone still counts as a peer")
	}
}

// TestAParticipantLeftThatNamesNobodyLeavesTheRosterAlone is the other side
// of a shape the capture does not carry: a frame this engine cannot read an
// identity out of takes nobody out of the room.
func TestAParticipantLeftThatNamesNobodyLeavesTheRosterAlone(t *testing.T) {
	sess := newIdleSession(t)
	gen := newGeneration(nil)
	gen.applyParticipants([]participant{{SID: "PA_other", Identity: "other", State: "ACTIVE"}})

	for _, payload := range []string{`{}`, `{"participantId":""}`, `not json`, ``} {
		sess.handleEnvelope(gen, envIn{Event: eventParticipantLeft, Payload: json.RawMessage(payload)})
		if peers := gen.remoteIdentities(); len(peers) != 1 || peers[0] != "other" {
			t.Fatalf("payload %q left the roster as %v", payload, peers)
		}
	}
}

// mediaOutJSON renders one media-out payload the way it arrives on the wire.
func mediaOutJSON(t *testing.T, payload map[string]any) json.RawMessage {
	t.Helper()
	raw, err := json.Marshal(payload)
	if err != nil {
		t.Fatal(err)
	}
	return raw
}

// TestNotePeerTakesNoExclusiveLockForAPeerItKnows pins the shape of the
// roster lock. Every relayed packet goes through notePeer, and almost every
// one of them is from a participant the roster already names: taking the
// exclusive lock to find that out put every packet of a session behind
// whoever was reading the roster.
func TestNotePeerTakesNoExclusiveLockForAPeerItKnows(t *testing.T) {
	gen := newGeneration(nil)
	gen.applyParticipants([]participant{{SID: "PA_known", Identity: "known", State: "ACTIVE"}})

	gen.peersMu.RLock()
	noted := make(chan struct{})
	go func() {
		gen.notePeer("known")
		close(noted)
	}()
	timeout := time.NewTimer(2 * time.Second)
	defer timeout.Stop()
	select {
	case <-noted:
		gen.peersMu.RUnlock()
	case <-timeout.C:
		gen.peersMu.RUnlock()
		t.Fatal("notePeer waited on the exclusive lock for a participant the roster already names")
	}

	// The fast path leaves the roster as it found it: the sid a roster
	// update recorded is not replaced by the empty one a packet carries.
	gen.peersMu.RLock()
	sid := gen.peers["known"]
	gen.peersMu.RUnlock()
	if sid != "PA_known" {
		t.Fatalf("the sid of a known participant is %q after a packet from them", sid)
	}

	// A sender the roster has not named yet still enters it, which is what
	// makes the first packet from a participant count as that participant
	// appearing.
	gen.notePeer("stranger")
	if peers := gen.remoteIdentities(); len(peers) != 2 || peers[1] != "stranger" {
		t.Fatalf("roster = %v, want the known participant and the one that just spoke", peers)
	}
}

// TestTheReceivePathDropsWhatItCannotUse covers the three ways a relayed
// frame is not a payload for the tunnel: it is not a data packet at all, it
// is one of the room's own updates rather than a user packet, or it belongs
// to a connection attempt this session has already replaced.
func TestTheReceivePathDropsWhatItCannotUse(t *testing.T) {
	sess, in := newCallbackSession(t)
	gen := newGeneration(nil)
	storeString(&gen.identity, "self")

	sess.handleDataPacket(gen, []byte("this is not a protobuf"))
	sess.handleDataPacket(gen, marshalPacket(t, &livekit.DataPacket{
		Value: &livekit.DataPacket_Speaker{Speaker: &livekit.ActiveSpeakerUpdate{}}, //nolint:staticcheck // 1.5.3 wire
	}))
	sess.handleDataPacket(gen, marshalPacket(t, &livekit.DataPacket{}))

	// An attempt that is gone belongs to a connection this session has moved
	// on from, whatever still arrives on its channels.
	sess.teardown(gen)
	sess.handleDataPacket(gen, userPacket(t, "other", "", []byte("after the teardown")))

	// And a closed session delivers nothing at all.
	live := newGeneration(nil)
	if err := sess.Close(); err != nil {
		t.Fatal(err)
	}
	sess.handleDataPacket(live, userPacket(t, "other", "", []byte("after the close")))

	select {
	case got := <-in:
		t.Fatalf("a frame the receive path cannot use was delivered as %q from %q", got.payload, got.sender)
	case <-time.After(100 * time.Millisecond):
	}
	for _, roster := range [][]string{gen.remoteIdentities(), live.remoteIdentities()} {
		if len(roster) != 0 {
			t.Fatalf("roster = %v, want nothing from a frame that was dropped", roster)
		}
	}
}
