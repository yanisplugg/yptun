package salutejazz

import (
	"context"
	"slices"
	"testing"
	"time"
)

// ai-generated: the whole file (PeerObserver bound to the confirmed server,
// olcrtc#49).

// peerSeen asks s what the client's handshake classifier asks its transport
// (transport.PeerObserver): whether the peer a handshake is for is in the
// room. A session that cannot say is taken as one that saw nobody, which is
// how the classifier takes it too.
func peerSeen(s *Session) bool {
	observer, ok := any(s).(interface{ PeerSeen() bool })
	return ok && observer.PeerSeen()
}

// TestPeerSeenIsTheServerTheHandshakeConfirmed follows one client through
// what its reconnects do to the binding. A hello the server has not answered
// yet - queued at the SFU behind a dead session's backlog - is a server that
// is there and silent, and the round retries it; read as an empty room it
// was one attempt and a 30 s pause. So PeerSeen names the server the last
// handshake confirmed, and keeps naming it through the two things that drop
// the binding before the next handshake: ResetPeer, and the rejoin a lost
// session asks for.
func TestPeerSeenIsTheServerTheHandshakeConfirmed(t *testing.T) {
	url, fake := newFakeConnector(t)
	server := connectSession(t, url, "server")
	client := connectSession(t, url, "client")
	stray := connectSession(t, url, "stray")
	waitForRoom(t, server, client, stray)
	serverID := server.localIdentity()

	if peerSeen(client) {
		t.Fatal("PeerSeen before any handshake: the room has participants, but none a handshake confirmed")
	}
	if err := client.ConfirmPeer(serverID); err != nil {
		t.Fatal(err)
	}
	if !peerSeen(client) {
		t.Fatal("PeerSeen = false with the confirmed server in the room")
	}
	client.ResetPeer()
	if !peerSeen(client) {
		t.Fatal("PeerSeen = false after ResetPeer: the server is still in the room")
	}

	if err := client.reconnect(context.Background()); err != nil {
		t.Fatalf("reconnect: %v (fake: %s)", err, fake.lastError())
	}
	waitFor(t, 10*time.Second, "the rejoined client to see the room again", func() bool {
		return slices.Contains(client.remoteIdentities(), serverID)
	})
	if !peerSeen(client) {
		t.Fatal("PeerSeen = false after a rejoin into a room the server is still in")
	}

	_ = server.Close()
	waitFor(t, 10*time.Second, "the client to see its server gone", func() bool {
		return !slices.Contains(client.remoteIdentities(), serverID)
	})
	if peerSeen(client) {
		t.Fatal("PeerSeen = true with the server gone and a stray client left in the room")
	}
}

// TestPeerSeenInAnEmptyRoom is the case the classifier exists for: nobody is
// there, and a client with other rooms moves on after one attempt.
func TestPeerSeenInAnEmptyRoom(t *testing.T) {
	url, _ := newFakeConnector(t)
	alone := connectSession(t, url, "alone")
	if peerSeen(alone) {
		t.Fatal("PeerSeen = true in an empty room")
	}
	// A server confirmed earlier and gone since is not in the room either.
	if err := alone.ConfirmPeer("gone"); err != nil {
		t.Fatal(err)
	}
	if peerSeen(alone) {
		t.Fatal("PeerSeen = true for a confirmed server the room does not name")
	}
}

// TestAStrayInARotatedRoomIsNotTheServer is the failover case. A room whose
// server has been retired can still hold a client - another device of the
// same user, one that has not moved yet - and a client that took that for a
// peer retried a dead room five times instead of failing over after one. Only
// the server the handshake confirmed counts: gone from this attempt's roster,
// or from the roster a rejoin brings, it is an empty room whoever else is in
// it.
func TestAStrayInARotatedRoomIsNotTheServer(t *testing.T) {
	client := newIdleSession(t)
	gen := newGeneration(nil)
	storeString(&gen.identity, "self")
	if !client.publishGeneration(gen) {
		t.Fatal("the session refused the attempt")
	}
	gen.applyParticipants([]participant{
		{SID: "PA_server", Identity: "server", State: "ACTIVE"},
		{SID: "PA_stray", Identity: "stray", State: "ACTIVE"},
	})
	if err := client.ConfirmPeer("server"); err != nil {
		t.Fatal(err)
	}
	if !peerSeen(client) {
		t.Fatal("PeerSeen = false with the confirmed server in the roster")
	}

	client.handleEnvelope(gen, envIn{Event: eventParticipantLeft, ParticipantID: "server"})
	if peerSeen(client) {
		t.Fatal("PeerSeen = true once the server left and a stray stayed")
	}

	// A rejoin starts a roster of its own. The server is not in it, the
	// stray is: still an empty room.
	rejoined := newGeneration(nil)
	storeString(&rejoined.identity, "self-2")
	if !client.publishGeneration(rejoined) {
		t.Fatal("the session refused the rejoin")
	}
	rejoined.applyParticipants([]participant{{SID: "PA_stray", Identity: "stray", State: "ACTIVE"}})
	if peerSeen(client) {
		t.Fatal("PeerSeen = true after a rejoin into a room holding only a stray")
	}

	// And a rejoin whose roster names the server finds it, although the
	// binding went with the attempt that made it.
	back := newGeneration(nil)
	storeString(&back.identity, "self-3")
	if !client.publishGeneration(back) {
		t.Fatal("the session refused the second rejoin")
	}
	back.applyParticipants([]participant{
		{SID: "PA_server", Identity: "server", State: "ACTIVE"},
		{SID: "PA_stray", Identity: "stray", State: "ACTIVE"},
	})
	if !peerSeen(client) {
		t.Fatal("PeerSeen = false after a rejoin whose roster names the confirmed server")
	}

	// An attempt that is gone answers for nobody.
	client.teardown(back)
	if peerSeen(client) {
		t.Fatal("PeerSeen = true on an attempt that has been torn down")
	}
}
