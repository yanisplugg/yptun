package salutejazz

import (
	"slices"
	"strings"
	"testing"
	"time"
)

// ai-generated: the whole file (a client's unicast to the server it
// confirmed, olcrtc#49; the port of Jitsi's #25).

// roomSends is every way a client addresses the room: the byte stream's Send,
// a SendTo that names nobody, and a datagram.
var roomSends = []struct {
	name string
	send func(s *Session, payload []byte) error
}{
	{"Send", (*Session).Send},
	{"SendTo with no destination", func(s *Session, payload []byte) error { return s.SendTo("", payload) }},
	{"SendDatagram", (*Session).SendDatagram},
}

// sendToTheRoom sends one small payload to the room every way there is,
// tagged kind and the way's place in roomSends.
func sendToTheRoom(t *testing.T, client *Session, kind string) {
	t.Helper()
	for seq, way := range roomSends {
		if err := way.send(client, taggedPayload(kind, seq, 64)); err != nil {
			t.Fatalf("%s = %v", way.name, err)
		}
	}
}

// waitHeard waits until every one of got has what sendToTheRoom sent under
// kind, every way it was sent, and names the ways one of them has not heard
// if that takes over timeout.
func waitHeard(t *testing.T, timeout time.Duration, kind string, got ...*tally) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for {
		var unheard []string
		for _, one := range got {
			for seq, way := range roomSends {
				if _, ok := one.arrival(kind, seq); !ok && !slices.Contains(unheard, way.name) {
					unheard = append(unheard, way.name)
				}
			}
		}
		if len(unheard) == 0 {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("%s: what was sent by %s had not arrived after %s", kind, strings.Join(unheard, ", "), timeout)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

// heardAnyWay is the first way got has heard kind by, or "" for none.
func heardAnyWay(got *tally, kind string) string {
	for seq, way := range roomSends {
		if _, ok := got.arrival(kind, seq); ok {
			return way.name
		}
	}
	return ""
}

// loadTheRoom has client send several marks' worth to the room through Send
// and waits until got has all of it. What a mark would have followed has
// been forwarded by then, and slowLegSettle later the mark too.
func loadTheRoom(t *testing.T, client *Session, got *tally) {
	t.Helper()
	load := startBulk(client.Send, "bulk", 4*relayMarkEvery, slowLegRecord)
	if err := load.finish(t, 5*time.Second, "the load to the room"); err != nil {
		t.Fatalf("the load to the room = %v", err)
	}
	last := int(load.sent.Load()) - 1
	waitFor(t, 5*time.Second, "the load to the room", func() bool {
		_, ok := got.arrival("bulk", last)
		return ok
	})
	time.Sleep(slowLegSettle)
}

// TestAConfirmedClientSendsToItsServerAlone is a client's upload in a room
// with other participants in it. The SFU hands a packet addressed to the room
// to every participant, so a client that sent to the room had each upload -
// and whatever backlog a slow leg built of it - queued toward every other
// client too, a queue no window of the client's counts. Once the handshake
// has confirmed its server, everything the client sends to the room goes to
// that server alone.
func TestAConfirmedClientSendsToItsServerAlone(t *testing.T) {
	url, _ := newFakeConnector(t)
	server, serverGot := connectTally(t, url, "server")
	client, clientGot := connectTally(t, url, "client")
	other, otherGot := connectTally(t, url, "other")
	waitForRoom(t, server, client, other)
	pairWindow(t, server, serverGot, client, clientGot)

	sendToTheRoom(t, client, "up")
	waitHeard(t, 5*time.Second, "up", serverGot)
	// Sent to the room, each would have reached the other participant about
	// when it reached the server.
	time.Sleep(slowLegSettle)
	if way := heardAnyWay(otherGot, "up"); way != "" {
		t.Fatalf("what a confirmed client sent by %s reached another participant", way)
	}
}

// TestAClientWhoseServerLeftSendsToTheRoomAgain is the way back. Once the
// server a client confirmed has left the room - the connector says so, or a
// roster update reports it disconnected - what the client sends to the room
// goes to the room again, and counts against no window: nothing is marked
// toward a participant that is not there. A server that comes back does so
// under a new identity, and the client's next hello has to reach it before
// anything confirms it.
func TestAClientWhoseServerLeftSendsToTheRoomAgain(t *testing.T) {
	for _, tc := range []struct {
		name  string
		leave func(server, client *Session)
	}{
		{"the connector reports it gone", func(server, client *Session) {
			client.handleEnvelope(client.current(), envIn{
				Event: eventParticipantLeft, ParticipantID: server.localIdentity(),
			})
		}},
		{"the roster reports it disconnected", func(server, _ *Session) {
			_ = server.Close()
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			url, fake := newFakeConnector(t)
			server, serverGot := connectTally(t, url, "server")
			client, clientGot := connectTally(t, url, "client")
			other, otherGot := connectTally(t, url, "other")
			waitForRoom(t, server, client, other)
			pairWindow(t, server, serverGot, client, clientGot)
			serverID, clientID := server.localIdentity(), client.localIdentity()

			tc.leave(server, client)
			waitFor(t, 5*time.Second, "the client to see its server gone", func() bool {
				return !slices.Contains(client.remoteIdentities(), serverID)
			})
			marked := fake.framesFrom(clientID, windowTopic)
			sendToTheRoom(t, client, "again")
			waitHeard(t, 5*time.Second, "again", otherGot)
			loadTheRoom(t, client, otherGot)
			if extra := fake.framesFrom(clientID, windowTopic) - marked; extra != 0 {
				t.Fatalf("the client put %d window frames on the wire after its server left", extra)
			}
		})
	}
}

// TestTheWindowFollowsARebinding is a client that confirms another server:
// what it sends to the room goes to the new one and counts against the
// window toward it, so a stalled leg to the new server holds the client after
// a window, and the server it confirmed before hears none of it.
func TestTheWindowFollowsARebinding(t *testing.T) {
	url, fake := newFakeConnector(t)
	first, firstGot := connectTally(t, url, "first")
	second, secondGot := connectTally(t, url, "second")
	client, clientGot := connectTally(t, url, "client")
	waitForRoom(t, first, second, client)
	pairWindowNth(t, 0, first, firstGot, client, clientGot)
	pairWindowNth(t, 1, second, secondGot, client, clientGot)
	secondID := second.localIdentity()

	fake.slowLeg(secondID, 0)
	upload := startBulk(client.Send, "bulk", 4<<20, slowLegRecord)
	t.Cleanup(upload.halt)
	held := int(upload.waitHeld(t, "the client to hold on the window toward its new server"))
	if most := (relayWindow + slowLegRecord) / slowLegRecord; held > most {
		t.Fatalf("the client sent %d frames to the room before it was held, over the %d a window allows", held, most)
	}

	// The leg moves, the echoes come back, and the upload goes on - to the
	// new server.
	fake.slowLeg(secondID, 2_000_000)
	waitFor(t, 10*time.Second, "the upload to go on at the new server", func() bool {
		_, ok := secondGot.arrival("bulk", held)
		return ok
	})
	upload.halt()
	if err := upload.finish(t, 5*time.Second, "the upload once halted"); err != nil {
		t.Fatalf("the upload = %v", err)
	}
	time.Sleep(slowLegSettle)
	for seq := range int(upload.sent.Load()) {
		if _, ok := firstGot.arrival("bulk", seq); ok {
			t.Fatalf("frame %d of the upload reached the server the client had confirmed before", seq)
		}
	}
}

// TestTheHelloBeforeConfirmationReachesTheServer is the rule's other side.
// Before its handshake has confirmed a server a client does not know which
// participant that is, so what it sends to the room - the hello - goes to the
// room, the server with it, and counts against no window. A binding the
// handshake drops sends the next hello to the room the same way.
func TestTheHelloBeforeConfirmationReachesTheServer(t *testing.T) {
	url, fake := newFakeConnector(t)
	server, serverGot := connectTally(t, url, "server")
	client, clientGot := connectTally(t, url, "client")
	other, otherGot := connectTally(t, url, "other")
	waitForRoom(t, server, client, other)

	sendToTheRoom(t, client, "first")
	waitHeard(t, 5*time.Second, "first", serverGot, otherGot)
	loadTheRoom(t, client, serverGot)
	if marks := fake.framesFrom(client.localIdentity(), windowTopic); marks != 0 {
		t.Fatalf("the client put %d window frames on the wire before it confirmed a server", marks)
	}

	pairWindow(t, server, serverGot, client, clientGot)
	client.ResetPeer()
	sendToTheRoom(t, client, "retry")
	waitHeard(t, 5*time.Second, "retry", serverGot, otherGot)
}

// TestAServerThatLeftStaysGone is the way back on a slow leg. The SFU goes on
// delivering a server's last frames - a backlog of tens of seconds on the
// slowest legs, the server's marks among them - after the room has reported
// it gone. The SFU hands every connection fresh participant ids, so an
// identity that has left never comes back under the attempt: nothing that
// still arrives from it, and no roster that names it again, puts it back in
// the client's roster or has the client answer its marks, and what the client
// sends to the room goes to the room.
func TestAServerThatLeftStaysGone(t *testing.T) {
	for _, tc := range []struct {
		name  string
		leave func(server, client *Session)
	}{
		{"the connector reports it gone", func(server, client *Session) {
			client.handleEnvelope(client.current(), envIn{
				Event: eventParticipantLeft, ParticipantID: server.localIdentity(),
			})
		}},
		{"the roster reports it disconnected", func(server, client *Session) {
			client.current().applyParticipants([]participant{{
				SID: "PA_" + server.localIdentity(), Identity: server.localIdentity(), State: participantDisconnected,
			}})
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			url, fake := newFakeConnector(t)
			server, serverGot := connectTally(t, url, "server")
			client, clientGot := connectTally(t, url, "client")
			other, otherGot := connectTally(t, url, "other")
			waitForRoom(t, server, client, other)
			pairWindow(t, server, serverGot, client, clientGot)
			serverID, clientID := server.localIdentity(), client.localIdentity()

			tc.leave(server, client)
			// The reply the server was sending when it went: past a mark, and
			// a small frame behind the mark, so that once the frame is in the
			// client has handled the mark.
			marks, echoes := fake.framesFrom(serverID, windowTopic), fake.framesFrom(clientID, windowTopic)
			late := startBulk(func(p []byte) error { return server.SendTo(clientID, p) }, "late", relayMarkEvery+slowLegRecord, slowLegRecord)
			if err := late.finish(t, 5*time.Second, "the server's last reply"); err != nil {
				t.Fatalf("the server's last reply = %v", err)
			}
			if err := server.SendTo(clientID, taggedPayload("tail", 0, 64)); err != nil {
				t.Fatal(err)
			}
			waitFor(t, 5*time.Second, "the server's last frame at the client", func() bool {
				_, ok := clientGot.arrival("tail", 0)
				return ok
			})
			if fake.framesFrom(serverID, windowTopic) == marks {
				t.Fatal("the server's last reply carried no mark")
			}
			if slices.Contains(client.remoteIdentities(), serverID) {
				t.Fatal("a frame from a server that had left put it back in the client's roster")
			}
			// An echo would have gone out while the mark was handled, and been
			// relayed by now.
			time.Sleep(slowLegSettle)
			if extra := fake.framesFrom(clientID, windowTopic) - echoes; extra != 0 {
				t.Fatalf("the client answered a server that had left with %d window frames", extra)
			}

			client.current().applyParticipants([]participant{{
				SID: "PA_" + serverID, Identity: serverID, State: "ACTIVE",
			}})
			if slices.Contains(client.remoteIdentities(), serverID) {
				t.Fatal("a roster naming a server that had left put it back in the client's roster")
			}

			sendToTheRoom(t, client, "again")
			waitHeard(t, 5*time.Second, "again", otherGot)
		})
	}
}
