package livekit

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	lksdk "github.com/owenewans/owenlivekit/v2"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
)

// ai-generated: whole file, cover for publishes the SDK holds while the
// publisher peer connection is not up (ghostlane#38).

// parkingRoom is a fakeRoom whose data publishes wait until unpark, the way
// the SDK holds one while the publisher peer connection is not up.
type parkingRoom struct {
	*fakeRoom
	parked      chan struct{}
	release     chan struct{}
	releaseOnce sync.Once
}

func newParkingRoom() *parkingRoom {
	return &parkingRoom{
		fakeRoom: newFakeRoom(),
		parked:   make(chan struct{}, 1),
		release:  make(chan struct{}),
	}
}

func (r *parkingRoom) publishData(data []byte) error {
	notify(r.parked)
	<-r.release
	return r.fakeRoom.publishData(data)
}

func (r *parkingRoom) unpark() {
	r.releaseOnce.Do(func() { close(r.release) })
}

func (r *parkingRoom) waitParked(t *testing.T) {
	t.Helper()
	select {
	case <-r.parked:
	case <-time.After(testGrace):
		t.Fatal("no publish reached the room")
	}
}

// newSessionOn returns a session whose joins land in rooms, one per join.
func newSessionOn(t *testing.T, rooms ...roomHandle) *Session {
	t.Helper()
	var mu sync.Mutex
	joins := 0
	s := &Session{
		url:   testOldURL,
		token: testOldToken,
		connectRoom: func(string, string, *lksdk.RoomCallback, ...lksdk.ConnectOption) (roomHandle, error) {
			mu.Lock()
			defer mu.Unlock()
			if joins == len(rooms) {
				return nil, errFakeConnect
			}
			joins++
			return rooms[joins-1], nil
		},
		closeCh:   make(chan struct{}),
		sendQueue: make(chan []byte, engine.DefaultSendQueueSize),
		done:      make(chan struct{}),
	}
	t.Cleanup(func() { _ = s.Close() })
	return s
}

// waitPublished waits for room to have published n payloads and returns them.
func waitPublished(t *testing.T, room *fakeRoom, n int, what string) []string {
	t.Helper()
	deadline := time.Now().Add(testGrace)
	for {
		room.mu.Lock()
		got := make([]string, 0, len(room.published))
		for _, payload := range room.published {
			got = append(got, string(payload))
		}
		room.mu.Unlock()
		if len(got) >= n {
			return got
		}
		if time.Now().After(deadline) {
			t.Fatalf("%s: %q published after %s", what, got, testGrace)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func TestPublishGivesUpOnAPublishTheSDKHolds(t *testing.T) {
	t.Parallel()
	tests := []struct {
		name    string
		giveUp  func(s *Session, room *parkingRoom)
		wantErr error
	}{
		{
			name:    "session closed",
			giveUp:  func(s *Session, _ *parkingRoom) { close(s.done) },
			wantErr: ErrSessionClosed,
		},
		{
			name:    "room left",
			giveUp:  func(_ *Session, room *parkingRoom) { room.disconnect() },
			wantErr: ErrRoomNotConnected,
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			t.Parallel()
			room := newParkingRoom()
			t.Cleanup(room.unpark)
			s := &Session{done: make(chan struct{})}
			published := make(chan error, 1)
			go func() { published <- s.publish(room, []byte("hello")) }()
			room.waitParked(t)
			tt.giveUp(s, room)
			select {
			case err := <-published:
				if !errors.Is(err, tt.wantErr) {
					t.Fatalf("publish() error = %v, want %v", err, tt.wantErr)
				}
			case <-time.After(testGrace):
				t.Fatal("publish() kept waiting on a publish the SDK holds")
			}
		})
	}
}

func TestSDKRoomIsLeftOnceDisconnected(t *testing.T) {
	t.Parallel()
	room := newSDKRoom(nil)
	select {
	case <-room.left():
		t.Fatal("left() closed before disconnect")
	default:
	}
	room.disconnect()
	room.disconnect() // a second disconnect must not close it again
	select {
	case <-room.left():
	default:
		t.Fatal("left() still open after disconnect")
	}
	if room.publisherReady() {
		t.Fatal("publisherReady() = true without a room")
	}
}

func TestCloseDoesNotWaitForAPublishTheSDKHolds(t *testing.T) {
	t.Parallel()
	room := newParkingRoom()
	s := newSessionOn(t, room)
	// Cleanups run last in first out: this one lets the publish go before
	// the Close cleanup, so a Close that waits on it fails the test instead
	// of hanging it.
	t.Cleanup(room.unpark)
	if err := s.Connect(context.Background()); err != nil {
		t.Fatalf("Connect() error = %v", err)
	}
	if err := s.Send([]byte("hello")); err != nil {
		t.Fatalf("Send() error = %v", err)
	}
	room.waitParked(t)

	closed := make(chan struct{})
	start := time.Now()
	go func() {
		_ = s.Close()
		close(closed)
	}()
	select {
	case <-closed:
	case <-time.After(testGrace):
		t.Fatalf("Close() still waiting %s on a publish the SDK holds", testGrace)
	}
	t.Logf("Close() returned after %s", time.Since(start).Round(time.Microsecond))

	// The publish left behind ends when the SDK lets it go.
	room.unpark()
	waitPublished(t, room.fakeRoom, 1, "the publish left behind never ended")
}

func TestReconnectDoesNotWaitForAPublishOnTheRoomItLeft(t *testing.T) {
	t.Parallel()
	leftRoom, joinedRoom := newParkingRoom(), newFakeRoom()
	s := newSessionOn(t, leftRoom, joinedRoom)
	t.Cleanup(leftRoom.unpark)
	ctx := context.Background()
	if err := s.Connect(ctx); err != nil {
		t.Fatalf("Connect() error = %v", err)
	}
	if err := s.Send([]byte("lost")); err != nil {
		t.Fatalf("Send() error = %v", err)
	}
	leftRoom.waitParked(t)

	if err := s.reconnect(ctx); err != nil {
		t.Fatalf("reconnect() error = %v", err)
	}
	if err := s.Send([]byte("hello")); err != nil {
		t.Fatalf("Send() error = %v", err)
	}
	got := waitPublished(t, joinedRoom, 1,
		"the room reconnect joined got nothing while a publish waited on the room it left")
	if len(got) != 1 || got[0] != "hello" {
		t.Fatalf("published on the room reconnect joined = %q, want [hello]", got)
	}
}

func TestDatagramRefusedUntilThePublisherIsUp(t *testing.T) {
	t.Parallel()
	room := newFakeRoom()
	room.setPublisherDown(true)
	s := newSessionOn(t, room)
	if err := s.Connect(context.Background()); err != nil {
		t.Fatalf("Connect() error = %v", err)
	}
	if !s.CanSend() {
		t.Fatal("CanSend() = false while the publisher is down, but only a publish brings it up")
	}
	if s.DatagramCanSend() {
		t.Fatal("DatagramCanSend() = true while the publisher is down")
	}
	if err := s.SendDatagram([]byte("early")); !errors.Is(err, ErrRoomNotConnected) {
		t.Fatalf("SendDatagram() error = %v, want %v", err, ErrRoomNotConnected)
	}

	room.setPublisherDown(false)
	if !s.DatagramCanSend() {
		t.Fatal("DatagramCanSend() = false with the publisher up")
	}
	if err := s.SendDatagramTo("peer-a", []byte("udp")); err != nil {
		t.Fatalf("SendDatagramTo() error = %v", err)
	}
	room.mu.Lock()
	defer room.mu.Unlock()
	if len(room.datagrams) != 1 || string(room.datagrams[0]) != "udp" {
		t.Fatalf("datagrams = %q, want only the one sent with the publisher up", room.datagrams)
	}
}
