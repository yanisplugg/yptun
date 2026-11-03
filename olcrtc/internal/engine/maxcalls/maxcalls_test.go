package maxcalls

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"github.com/pion/webrtc/v4"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
)

// ai-generated: the whole file (the engine's relay negotiation against a
// fake of the SFU's signaling socket, and a pair of engines through it).

const testWait = 20 * time.Second

// fakeSFU stands in for the SFU's signaling: one socket per participant,
// the connection frame, the seat, and the relay between participants. A
// participant is told apart by the userId of its endpoint's query, as the
// real endpoint carries it.
type fakeSFU struct {
	t        *testing.T
	srv      *httptest.Server
	topology string
	// beforeSeat runs on a new socket after its accept-call and before the
	// response to it, with the socket's uid: what the SFU delivers first.
	beforeSeat func(uid int64, to *fakeSocket)

	mu      sync.Mutex
	sockets map[int64]*fakeSocket
	nextID  int64
	// relayed is every transmit-data frame a participant sent, as sent.
	relayed chan relayedFrame
}

type relayedFrame struct {
	from int64
	to   int64
	data map[string]any
}

// fakeSocket is one participant's socket. gorilla allows one writer at a
// time, so every write goes through the mutex.
type fakeSocket struct {
	uid    int64
	peerID int64
	conn   *websocket.Conn
	mu     sync.Mutex
}

func (c *fakeSocket) write(frame map[string]any) {
	c.mu.Lock()
	defer c.mu.Unlock()
	_ = c.conn.WriteJSON(frame) // a write after the engine hung up is no failure
}

func newFakeSFU(t *testing.T) *fakeSFU {
	t.Helper()
	f := &fakeSFU{t: t, topology: topologyDirect, sockets: map[int64]*fakeSocket{}, relayed: make(chan relayedFrame, 256)}
	f.srv = httptest.NewServer(http.HandlerFunc(f.serve))
	t.Cleanup(f.srv.Close)
	return f
}

// endpoint is the join endpoint of a participant, as the provider's
// credentials carry it.
func (f *fakeSFU) endpoint(uid int64) string {
	return "ws" + strings.TrimPrefix(f.srv.URL, "http") + "/ws2?userId=" + strconv.FormatInt(uid, 10)
}

func (f *fakeSFU) serve(w http.ResponseWriter, r *http.Request) {
	uid, _ := strconv.ParseInt(r.URL.Query().Get("userId"), 10, 64)
	if r.URL.Query().Get("appVersion") == "" {
		f.t.Errorf("a join without the client's query: %s", r.URL.RawQuery)
	}
	upgrader := websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		f.t.Errorf("upgrade: %v", err)
		return
	}
	defer func() { _ = conn.Close() }()
	f.mu.Lock()
	f.nextID++
	sock := &fakeSocket{uid: uid, peerID: 1000 + f.nextID, conn: conn}
	f.sockets[uid] = sock
	f.mu.Unlock()
	defer func() {
		f.mu.Lock()
		if f.sockets[uid] == sock {
			delete(f.sockets, uid)
		}
		f.mu.Unlock()
	}()
	sock.write(map[string]any{
		"type": "notification", "notification": "connection",
		"peerId":       map[string]any{"id": sock.peerID},
		"conversation": map[string]any{"id": "165c1eae-0000-4000-8000-000000000000", "topology": f.topology},
	})
	for {
		_, data, err := conn.ReadMessage()
		if err != nil {
			return
		}
		if string(data) == "pong" {
			continue
		}
		var cmd map[string]any
		if json.Unmarshal(data, &cmd) != nil {
			continue
		}
		f.command(sock, cmd)
	}
}

func (f *fakeSFU) command(sock *fakeSocket, cmd map[string]any) {
	name, _ := cmd["command"].(string)
	ack := map[string]any{"type": "response", "response": name, "sequence": cmd["sequence"]}
	switch name {
	case "accept-call":
		if f.beforeSeat != nil {
			f.beforeSeat(sock.uid, sock)
		}
		others := []int64{}
		f.mu.Lock()
		for uid, other := range f.sockets {
			if uid == sock.uid {
				continue
			}
			others = append(others, uid)
			other.write(map[string]any{
				"type": "notification", "notification": "registered-peer",
				"participantId": sock.uid, "participantType": "USER", "peerId": map[string]any{"id": sock.peerID},
			})
		}
		f.mu.Unlock()
		ack["participantIds"] = others
		sock.write(ack)
	case "transmit-data":
		to, _ := cmd["participantId"].(float64)
		payload, _ := cmd["data"].(map[string]any)
		f.relayed <- relayedFrame{from: sock.uid, to: int64(to), data: payload}
		f.mu.Lock()
		target := f.sockets[int64(to)]
		f.mu.Unlock()
		if target != nil {
			target.write(map[string]any{
				"type": "notification", "notification": "transmitted-data",
				"participantId": sock.uid, "participantType": "USER",
				"peerId": map[string]any{"id": sock.peerID}, "data": payload,
			})
		}
		sock.write(ack)
	default:
		sock.write(ack)
	}
}

// awaitSDP waits for a relayed frame carrying an SDP of the given type.
func (f *fakeSFU) awaitSDP(t *testing.T, sdpType string) relayedFrame {
	t.Helper()
	timer := time.NewTimer(testWait)
	defer timer.Stop()
	for {
		select {
		case frame := <-f.relayed:
			sdp, _ := frame.data["sdp"].(map[string]any)
			if sdp["type"] == sdpType {
				return frame
			}
		case <-timer.C:
			t.Fatalf("no relayed %s", sdpType)
		}
	}
}

// carrierOffer is an offer of the carrier's own shape from a plain pion
// peer: recvonly video x2, recvonly audio, the "datagram" channel.
func carrierOffer(t *testing.T) string {
	t.Helper()
	pc, err := webrtc.NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = pc.Close() })
	for _, kind := range []webrtc.RTPCodecType{webrtc.RTPCodecTypeVideo, webrtc.RTPCodecTypeVideo, webrtc.RTPCodecTypeAudio} {
		if _, err = pc.AddTransceiverFromKind(kind,
			webrtc.RTPTransceiverInit{Direction: webrtc.RTPTransceiverDirectionRecvonly}); err != nil {
			t.Fatal(err)
		}
	}
	if _, err = pc.CreateDataChannel(channelLabel, nil); err != nil {
		t.Fatal(err)
	}
	offer, err := pc.CreateOffer(nil)
	if err != nil {
		t.Fatal(err)
	}
	return stripCandidates(offer.SDP)
}

func newSession(t *testing.T, f *fakeSFU, uid int64, onData func([]byte)) engine.Session {
	t.Helper()
	sess, err := New(context.Background(), engine.Config{
		URL: f.endpoint(uid), Extra: map[string]string{"userId": strconv.FormatInt(uid, 10)}, OnData: onData,
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = sess.Close() })
	return sess
}

func connect(t *testing.T, sess engine.Session) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), testWait)
	defer cancel()
	if err := sess.Connect(ctx); err != nil {
		t.Fatal(err)
	}
}

// TestTheAnswerGoesToTheOffersSender is the order the live SFU delivers a
// joiner's first frames in (measured 2026-10-18): the offer of the
// participant already in the room, and its candidates, land before the
// response to the joiner's own accept-call, the one frame that lists the
// room. The relayed frame names its sender, and the answer goes there: one
// addressed from the list went nowhere, and the pair never met.
func TestTheAnswerGoesToTheOffersSender(t *testing.T) {
	const offerer, answerer = int64(1001), int64(2002)
	offer := carrierOffer(t)
	f := newFakeSFU(t)
	f.beforeSeat = func(_ int64, to *fakeSocket) {
		to.write(map[string]any{
			"type": "notification", "notification": "transmitted-data",
			"participantId": offerer, "participantType": "USER", "peerId": map[string]any{"id": 4242},
			"data": map[string]any{"sdp": map[string]any{"type": "offer", "sdp": offer}, "animojiVersion": 1},
		})
		to.write(map[string]any{
			"type": "notification", "notification": "transmitted-data",
			"participantId": offerer, "participantType": "USER", "peerId": map[string]any{"id": 4242},
			"data": map[string]any{"candidate": map[string]any{
				"candidate": "candidate:1 1 udp 2130706431 192.0.2.10 43210 typ host", "sdpMid": "0", "sdpMLineIndex": 0,
			}},
		})
	}
	sess := newSession(t, f, answerer, nil)
	connect(t, sess)

	answer := f.awaitSDP(t, "answer")
	if answer.to != offerer {
		t.Fatalf("the answer went to %d, want the offer's sender %d", answer.to, offerer)
	}
	sdp, _ := answer.data["sdp"].(map[string]any)
	if s, _ := sdp["sdp"].(string); !strings.Contains(s, "a=setup:") || strings.Contains(s, "a=candidate:") {
		t.Fatalf("the relayed answer is not a candidate-free SDP answer:\n%s", s)
	}
}

// TestTheLowerUIDOffersToAPeerAlreadyInTheRoom: a joiner whose accept-call
// list names a participant offers to it when its own uid is the lower, in
// the measured shape.
func TestTheLowerUIDOffersToAPeerAlreadyInTheRoom(t *testing.T) {
	f := newFakeSFU(t)
	present := newSession(t, f, 2002, nil)
	connect(t, present)
	joiner := newSession(t, f, 1001, nil)
	connect(t, joiner)

	offer := f.awaitSDP(t, "offer")
	if offer.from != 1001 || offer.to != 2002 {
		t.Fatalf("the offer went %d -> %d, want the lower uid 1001 -> 2002", offer.from, offer.to)
	}
	sdp, _ := offer.data["sdp"].(map[string]any)
	s, _ := sdp["sdp"].(string)
	for _, want := range []string{"m=video", "m=audio", "m=application", "a=recvonly"} {
		if !strings.Contains(s, want) {
			t.Errorf("the offer lacks %q:\n%s", want, s)
		}
	}
	if strings.Count(s, "m=video") != 2 || strings.Contains(s, "a=candidate:") || len(s) > relayCap {
		t.Fatalf("the offer is not the measured shape (%d bytes):\n%s", len(s), s)
	}
}

// TestAServerRoomIsRefused: a room of the SERVER topology (created with a
// camera) is refused at the join, with the remedy in the error.
func TestAServerRoomIsRefused(t *testing.T) {
	f := newFakeSFU(t)
	f.topology = "SERVER"
	sess := newSession(t, f, 1001, nil)
	ctx, cancel := context.WithTimeout(context.Background(), testWait)
	defer cancel()
	if err := sess.Connect(ctx); !errors.Is(err, ErrTopology) || !strings.Contains(err.Error(), "SERVER") {
		t.Fatalf("err = %v, want ErrTopology naming SERVER", err)
	}
}

// TestAPairMeetsThroughTheRelay runs two engines through the fake SFU: the
// lower uid offers, the other answers, the candidates trickle, and the data
// channel carries bytes both ways.
func TestAPairMeetsThroughTheRelay(t *testing.T) {
	f := newFakeSFU(t)
	fromA, fromB := make(chan []byte, 8), make(chan []byte, 8)
	a := newSession(t, f, 1001, func(b []byte) { fromB <- b })
	b := newSession(t, f, 2002, func(b []byte) { fromA <- b })
	connect(t, a)
	connect(t, b)

	deadline := time.Now().Add(testWait)
	for !a.CanSend() || !b.CanSend() {
		if time.Now().After(deadline) {
			t.Fatalf("the data channel did not open: a %v b %v", a.CanSend(), b.CanSend())
		}
		time.Sleep(20 * time.Millisecond)
	}
	if err := a.Send([]byte("from a")); err != nil {
		t.Fatal(err)
	}
	if err := b.Send([]byte("from b")); err != nil {
		t.Fatal(err)
	}
	for _, want := range []struct {
		ch   chan []byte
		text string
	}{{fromA, "from a"}, {fromB, "from b"}} {
		select {
		case got := <-want.ch:
			if string(got) != want.text {
				t.Fatalf("got %q, want %q", got, want.text)
			}
		case <-time.After(testWait):
			t.Fatalf("%q never arrived", want.text)
		}
	}
	if err := a.Close(); err != nil {
		t.Fatal(err)
	}
	if a.CanSend() || errors.Is(a.Send([]byte("x")), nil) {
		t.Fatal("a closed session still sends")
	}
}

// TestNewRefusesCredentialsWithoutAUserID: the pair rule needs our uid.
func TestNewRefusesCredentialsWithoutAUserID(t *testing.T) {
	if _, err := New(context.Background(), engine.Config{URL: "wss://example.invalid/ws2"}); !errors.Is(err, ErrNoUserID) {
		t.Fatalf("err = %v, want ErrNoUserID", err)
	}
	if _, err := New(context.Background(), engine.Config{Extra: map[string]string{"userId": "1"}}); !errors.Is(err, ErrNoEndpoint) {
		t.Fatalf("err = %v, want ErrNoEndpoint", err)
	}
}
