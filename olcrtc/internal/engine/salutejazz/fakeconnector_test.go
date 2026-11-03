package salutejazz

import (
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"slices"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/gorilla/websocket"
	"github.com/livekit/protocol/livekit"
	"github.com/pion/webrtc/v4"
	"google.golang.org/protobuf/proto"
)

// The in-process connector fake. It speaks the frames the capture of the
// official web client recorded, so the engine can be exercised without the
// live service: a join answered with join-response, media-out rtc:config,
// the subscriber offer the SFU itself makes, the answer to the client's
// publisher offer, trickled ICE for both, and rtc:pong.
//
// Test surface, all safe to call from a test goroutine:
//
//	newFakeConnector(t) -> (url, *fakeSFU)  the ws:// URL to join
//	(*fakeSFU).sawAnswer()                  the client's subscriber answer arrived
//	(*fakeSFU).sawPublisherOffer()          the client's publisher offer arrived
//	(*fakeSFU).joins()                      every join seen: its payload and group
//	(*fakeSFU).clientICE()                  every candidate a client trickled
//	(*fakeSFU).holdJoins() -> release       stall the join answer, for a race
//	(*fakeSFU).holdDials() -> release       stall the upgrade, before any frame
//	(*fakeSFU).holdForward() -> release     stall the relay, so a writer's own
//	                                        queue fills behind it
//	(*fakeSFU).holdPongs() -> release       stall the answer to every ping
//	(*fakeSFU).pingsSeen()                  how many pings have arrived
//	(*fakeSFU).refuseJoins(code, message)   answer every join with an error
//	(*fakeSFU).dialsSeen()                  how many clients have reached it
//	(*fakeSFU).liveSockets()                sockets still open on its side
//	(*fakeSFU).dropAll()                    close every fake-side socket
//	(*fakeSFU).sendError(code, message)     a server error frame to every peer
//	(*fakeSFU).lastError()                  what the fake itself tripped over,
//	                                        including a client frame whose
//	                                        group id does not match the capture
//	(*fakeSFU).slowLeg(identity, rate)      queue what is relayed to one
//	                                        participant and drain it at rate
//	                                        bytes a second (0 holds it), as
//	                                        LiveKit 1.5.3 does; see fakeLeg
//	(*fakeSFU).queuedTo(identity)           what that leg holds now, and
//	(*fakeSFU).peakQueuedTo(identity)       the most it has held
//	(*fakeSFU).swallow(topic)               relay nothing under topic, as if
//	                                        no peer in the room spoke it
//	(*fakeSFU).framesFrom(identity, topic)  how many packets identity has
//	                                        published under topic
//
// What it deliberately does not do: no preconnect (that is the auth
// provider's HTTP call), no participant bookkeeping beyond the identities a
// room's peers need to address each other.

var (
	errGroupOnJoin = errors.New("join carried a group id")
	errWrongGroup  = errors.New("frame carried the wrong group id")
	errNoUfrag     = errors.New("candidate carried no usernameFragment")
)

const (
	// fakeConnectorPath is the connector endpoint, as on the real service.
	fakeConnectorPath = "/connector"
	// fakeSTUNURL is the single ICE server the fake advertises in
	// rtc:config. It needs no credentials and the fake's own candidates are
	// host candidates, so nothing in a test depends on reaching it.
	fakeSTUNURL = "stun:stun.l.google.com:19302"
)

// seenJoin is one join frame as it arrived: the payload the client built and
// the envelope group id it carried (the capture has none on a join).
type seenJoin struct {
	payload string
	group   string
}

// seenCandidate is one client candidate as it arrived, projected to what a
// test asks about it: which peer connection it belongs to, and the ICE
// username fragment it was gathered under.
type seenCandidate struct {
	target string
	ufrag  string
}

type fakeSFU struct {
	mu       sync.Mutex
	peers    []*fakePeer
	rooms    map[string][]*fakePeer
	seq      int
	answered bool
	pubOffer bool
	failure  string
	seen     []seenJoin
	trickled []seenCandidate
	dials    int
	live     int
	// joinGate, while non-nil, stalls every join answer, dialGate the
	// upgrade itself, forwardGate the relay of what a client writes and
	// pongGate the answer to a ping.
	joinGate    chan struct{}
	dialGate    chan struct{}
	forwardGate chan struct{}
	pongGate    chan struct{}
	pings       int
	// refusal, while non-nil, is the error every join is answered with.
	refusal *serverError
	// swallowed is the topics the relay drops, and published counts the
	// packets each sender published under each topic, swallowed or not.
	swallowed map[string]bool
	published map[publishedKey]int
}

// publishedKey is one sender and one topic.
type publishedKey struct {
	from  string
	topic string
}

func newFakeConnector(t *testing.T) (string, *fakeSFU) {
	t.Helper()
	requireHostInterface(t)
	fake := &fakeSFU{
		rooms:     make(map[string][]*fakePeer),
		swallowed: make(map[string]bool),
		published: make(map[publishedKey]int),
	}
	// The engine sends the browser's Origin, which gorilla's default check
	// refuses against a loopback host.
	upgrader := websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != fakeConnectorPath {
			http.NotFound(w, r)
			return
		}
		fake.arriveDial()
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		fake.serve(conn)
	}))
	t.Cleanup(func() {
		fake.shutdown()
		srv.Close()
	})
	return "ws" + strings.TrimPrefix(srv.URL, "http") + fakeConnectorPath, fake
}

// sawAnswer reports whether a client answered the subscriber offer.
func (f *fakeSFU) sawAnswer() bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.answered
}

// sawPublisherOffer reports whether a client offered a publisher PC.
func (f *fakeSFU) sawPublisherOffer() bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.pubOffer
}

// joins is every join frame the fake has read, in arrival order.
func (f *fakeSFU) joins() []seenJoin {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]seenJoin(nil), f.seen...)
}

// holdJoins stalls every join answer until the returned release is called,
// so a test can hold a Connect in flight. Releasing twice is safe.
func (f *fakeSFU) holdJoins() func() {
	gate := make(chan struct{})
	f.mu.Lock()
	f.joinGate = gate
	f.mu.Unlock()
	var once sync.Once
	return func() { once.Do(func() { close(gate) }) }
}

// holdDials stalls the upgrade itself, so a test can hold a client inside
// its dial, before it has written a single frame.
func (f *fakeSFU) holdDials() func() {
	gate := make(chan struct{})
	f.mu.Lock()
	f.dialGate = gate
	f.mu.Unlock()
	var once sync.Once
	return func() { once.Do(func() { close(gate) }) }
}

// holdForward stalls the relay of everything a client writes, inside the
// handler that reads its data channel. The reader stops, the receive window
// closes, and the writer's own SCTP queue fills - which is the only way a
// test can reach the high-water mark the lanes are bounded by.
// The release takes the gate off the fake before it closes it, so calling it
// twice - the test's own cleanup and the fake's shutdown - is safe.
func (f *fakeSFU) holdForward() func() {
	gate := make(chan struct{})
	f.mu.Lock()
	f.forwardGate = gate
	f.mu.Unlock()
	return f.releaseForward
}

// releaseForward lets the relay run again, once.
func (f *fakeSFU) releaseForward() {
	f.mu.Lock()
	gate := f.forwardGate
	f.forwardGate = nil
	f.mu.Unlock()
	if gate != nil {
		close(gate)
	}
}

// awaitForwardGate blocks while holdForward is in force.
func (f *fakeSFU) awaitForwardGate() {
	f.mu.Lock()
	gate := f.forwardGate
	f.mu.Unlock()
	if gate == nil {
		return
	}
	<-gate
}

// holdPongs stalls the answer to every ping, so a test can hold one in
// flight and watch what else might release it. The release takes the gate off
// the fake before it closes it, so calling it twice is safe.
func (f *fakeSFU) holdPongs() func() {
	gate := make(chan struct{})
	f.mu.Lock()
	f.pongGate = gate
	f.mu.Unlock()
	return f.releasePongs
}

// releasePongs answers the pings that are waiting, once.
func (f *fakeSFU) releasePongs() {
	f.mu.Lock()
	gate := f.pongGate
	f.pongGate = nil
	f.mu.Unlock()
	if gate != nil {
		close(gate)
	}
}

// notePing records a ping and blocks while holdPongs is in force.
func (f *fakeSFU) notePing() {
	f.mu.Lock()
	f.pings++
	gate := f.pongGate
	f.mu.Unlock()
	if gate != nil {
		<-gate
	}
}

// pingsSeen is how many pings have reached the connector.
func (f *fakeSFU) pingsSeen() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.pings
}

// refuseJoins answers every join with an error frame instead of a
// join-response, the way the connector refuses a room it will not admit a
// client to. The reply echoes that join's own requestId, as every server
// reply in the capture echoes the requestId of the frame it answers.
func (f *fakeSFU) refuseJoins(code, message string) {
	f.mu.Lock()
	f.refusal = &serverError{Code: code, Message: message}
	f.mu.Unlock()
}

func (f *fakeSFU) joinRefusal() *serverError {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.refusal
}

// dialsSeen is how many clients have reached the connector endpoint,
// counted before the upgrade so a held dial is visible.
func (f *fakeSFU) dialsSeen() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.dials
}

// liveSockets is how many sockets are still open on the fake's side. A
// client that leaks a connector socket leaves this above zero.
func (f *fakeSFU) liveSockets() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.live
}

// arriveDial records a client at the endpoint and blocks while holdDials is
// in force.
func (f *fakeSFU) arriveDial() {
	f.mu.Lock()
	f.dials++
	gate := f.dialGate
	f.mu.Unlock()
	if gate != nil {
		<-gate
	}
}

// awaitJoinGate blocks while holdJoins is in force.
func (f *fakeSFU) awaitJoinGate() {
	f.mu.Lock()
	gate := f.joinGate
	f.mu.Unlock()
	if gate == nil {
		return
	}
	<-gate
}

// lastError is what the fake itself failed on, for a test's failure message.
// The fake never calls t from its own goroutines: a websocket handler
// outlives the test that started it.
func (f *fakeSFU) lastError() string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.failure
}

// dropAll closes every fake-side socket without a close frame, the way a
// dead link ends a session.
func (f *fakeSFU) dropAll() {
	for _, peer := range f.snapshot() {
		peer.closeSocket()
	}
}

// sendError delivers a server error frame to every connected peer, under a
// requestId of the connector's own: an error that answers nothing the client
// sent.
func (f *fakeSFU) sendError(code, message string) {
	for _, peer := range f.snapshot() {
		_ = peer.write(eventError, map[string]any{"code": code, "message": message})
	}
}

// shutdown ends every peer: sockets first, then their peer connections. A
// relay a test left held is released, so no handler outlives the test blocked
// on a gate.
func (f *fakeSFU) shutdown() {
	f.releaseForward()
	f.releasePongs()
	f.dropAll()
	for _, peer := range f.snapshot() {
		peer.closePeerConnections()
	}
}

func (f *fakeSFU) snapshot() []*fakePeer {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]*fakePeer(nil), f.peers...)
}

func (f *fakeSFU) fail(what string, err error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.failure == "" {
		f.failure = what + ": " + err.Error()
	}
}

// join registers a peer in its room and hands it a distinct identity, the
// way the SFU hands every participant its own participantId.
func (f *fakeSFU) join(peer *fakePeer, room string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.seq++
	peer.id = "fakepart-" + strconv.Itoa(f.seq)
	peer.room = room
	peer.group = "fakegroup-" + room
	f.peers = append(f.peers, peer)
	f.rooms[room] = append(f.rooms[room], peer)
}

// noteJoin records a join frame as it arrived, admitted or refused.
func (f *fakeSFU) noteJoin(frame fakeIn) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.seen = append(f.seen, seenJoin{payload: string(frame.Payload), group: frame.GroupID})
}

// noteCandidate records one client candidate as it arrived.
func (f *fakeSFU) noteCandidate(cand fakeCandidate) {
	var ufrag string
	if cand.UsernameFragment != nil {
		ufrag = *cand.UsernameFragment
	}
	target := cand.Target
	if target == "" {
		target = targetSubscriber
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	f.trickled = append(f.trickled, seenCandidate{target: target, ufrag: ufrag})
}

// clientICE is every candidate a client has trickled, in arrival order.
func (f *fakeSFU) clientICE() []seenCandidate {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]seenCandidate(nil), f.trickled...)
}

func (f *fakeSFU) roomPeers(room string) []*fakePeer {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]*fakePeer(nil), f.rooms[room]...)
}

func (f *fakeSFU) markAnswer() {
	f.mu.Lock()
	f.answered = true
	f.mu.Unlock()
}

func (f *fakeSFU) markPublisherOffer() {
	f.mu.Lock()
	f.pubOffer = true
	f.mu.Unlock()
}

// forward relays one data packet from its sender to the room, stamped with
// the sender's identity and honouring DestinationIdentities, the way the SFU
// relays what a publisher writes. Receivers get it on the channels the fake
// created on their subscriber PC.
func (f *fakeSFU) forward(from *fakePeer, frame []byte) {
	f.awaitForwardGate()
	var packet livekit.DataPacket
	if err := proto.Unmarshal(frame, &packet); err != nil {
		f.fail("forward unmarshal", err)
		return
	}
	user := packet.GetUser()
	if user == nil {
		return
	}
	if !f.notePublished(from.id, user.GetTopic()) {
		return
	}
	// LiveKit 1.5.3, which the service runs, carries the sender and the
	// destinations on the user packet, not on the data packet around it.
	user.ParticipantIdentity = from.id      //nolint:staticcheck // 1.5.3 wire
	user.ParticipantSid = from.id           //nolint:staticcheck // 1.5.3 wire
	dest := user.GetDestinationIdentities() //nolint:staticcheck // 1.5.3 wire
	out, err := proto.Marshal(&packet)
	if err != nil {
		f.fail("forward marshal", err)
		return
	}
	lossy := packet.GetKind() == livekit.DataPacket_LOSSY //nolint:staticcheck // 1.5.3 wire
	for _, peer := range f.roomPeers(from.room) {
		if peer == from || !addressed(dest, peer.id) {
			continue
		}
		peer.relay(out, lossy)
	}
}

// swallow makes the relay drop every packet under topic, whoever sends it.
// A room whose peers are all of a build that does not know a topic behaves
// the same way at both ends: nothing under it is ever answered.
func (f *fakeSFU) swallow(topic string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.swallowed[topic] = true
}

// framesFrom is how many packets identity has published under topic.
func (f *fakeSFU) framesFrom(identity, topic string) int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.published[publishedKey{from: identity, topic: topic}]
}

// notePublished counts one packet and reports whether the relay passes it
// on.
func (f *fakeSFU) notePublished(from, topic string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.published[publishedKey{from: from, topic: topic}]++
	return !f.swallowed[topic]
}

// slowLeg puts the leg toward one participant behind a queue that drains at
// rate bytes a second, and holds it while rate is 0. Calling it again changes
// the rate of the same queue, so a held leg is released by giving it one.
// Unknown identities are ignored: the leg belongs to a peer the fake has
// admitted.
func (f *fakeSFU) slowLeg(identity string, rate int) {
	if peer := f.peer(identity); peer != nil {
		peer.slowLeg(rate)
	}
}

// queuedTo is what the leg toward identity holds right now, in relayed
// packet bytes: 0 for a participant with no slow leg.
func (f *fakeSFU) queuedTo(identity string) int {
	if leg := f.leg(identity); leg != nil {
		queued, _ := leg.gauge()
		return queued
	}
	return 0
}

// peakQueuedTo is the most the leg toward identity has held at once.
func (f *fakeSFU) peakQueuedTo(identity string) int {
	if leg := f.leg(identity); leg != nil {
		_, peak := leg.gauge()
		return peak
	}
	return 0
}

func (f *fakeSFU) leg(identity string) *fakeLeg {
	peer := f.peer(identity)
	if peer == nil {
		return nil
	}
	peer.mu.Lock()
	defer peer.mu.Unlock()
	return peer.leg
}

// peer is the admitted participant under identity, or nil.
func (f *fakeSFU) peer(identity string) *fakePeer {
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, peer := range f.peers {
		if peer.id == identity {
			return peer
		}
	}
	return nil
}

// fakeLeg is the SFU's queue toward one subscriber as LiveKit 1.5.3 keeps
// it, with the path to that subscriber as its only brake.
//
// LiveKit reads a publisher's channel as fast as packets arrive - its SCTP
// acknowledges at once, which is what the fake's publisher side does too - and
// hands every packet to each subscriber's channel there and then.
// PCTransport.SendDataPacket drops only when DataChannelMaxBufferedAmount is
// set, which it is not by default, and pion/sctp's pending queue has no bound,
// so what a slow subscriber cannot take piles up for it without a limit and
// leaves in order at the rate its path carries. Both lanes share the queue:
// LiveKit opens a subscriber's _lossy channel ordered, and pion keeps every
// ordered chunk of an association in one FIFO.
//
// The rate is the path's: a packet leaves once the leg has had the time to
// carry it and everything before it, and a leg that was idle has saved up no
// time. queued counts a packet until it has left, and peak is the most queued
// ever was.
type fakeLeg struct {
	mu     sync.Mutex
	frames []legFrame
	queued int
	peak   int
	rate   int
	// kick is closed and replaced whenever a packet arrives or the rate
	// changes, which is what a drain waiting on an empty or held leg waits
	// for.
	kick chan struct{}
	stop chan struct{}
	once sync.Once
}

// legFrame is one relayed packet and the lane it goes out on.
type legFrame struct {
	data  []byte
	lossy bool
}

func newFakeLeg() *fakeLeg {
	return &fakeLeg{kick: make(chan struct{}), stop: make(chan struct{})}
}

// push queues one packet. It never blocks: that is the point.
func (l *fakeLeg) push(frame []byte, lossy bool) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.frames = append(l.frames, legFrame{data: frame, lossy: lossy})
	l.queued += len(frame)
	l.peak = max(l.peak, l.queued)
	l.kickLocked()
}

func (l *fakeLeg) setRate(rate int) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.rate = rate
	l.kickLocked()
}

func (l *fakeLeg) gauge() (int, int) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.queued, l.peak
}

func (l *fakeLeg) kickLocked() {
	close(l.kick)
	l.kick = make(chan struct{})
}

// head waits for a packet at the front of a leg that is moving, and returns
// it with the rate it moves at. It reports false once the leg is stopped.
func (l *fakeLeg) head() (legFrame, int, bool) {
	for {
		l.mu.Lock()
		if len(l.frames) > 0 && l.rate > 0 {
			frame, rate := l.frames[0], l.rate
			l.mu.Unlock()
			return frame, rate, true
		}
		kick := l.kick
		l.mu.Unlock()
		select {
		case <-kick:
		case <-l.stop:
			return legFrame{}, 0, false
		}
	}
}

// pop takes the front packet off once it has left. Only the drain pops, so
// the front is still the packet head returned.
func (l *fakeLeg) pop() {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.queued -= len(l.frames[0].data)
	l.frames[0] = legFrame{}
	l.frames = l.frames[1:]
}

// drain carries the queue down the leg at its rate until the leg stops.
func (l *fakeLeg) drain(deliver func(frame []byte, lossy bool)) {
	var free time.Time
	for {
		frame, rate, ok := l.head()
		if !ok {
			return
		}
		now := time.Now()
		if free.Before(now) {
			free = now
		}
		free = free.Add(time.Duration(len(frame.data)) * time.Second / time.Duration(rate))
		timer := time.NewTimer(time.Until(free))
		select {
		case <-timer.C:
		case <-l.stop:
			timer.Stop()
			return
		}
		l.pop()
		deliver(frame.data, frame.lossy)
	}
}

func (l *fakeLeg) close() {
	l.once.Do(func() { close(l.stop) })
}

func addressed(dest []string, id string) bool {
	if len(dest) == 0 {
		return true
	}
	for _, want := range dest {
		if want == id {
			return true
		}
	}
	return false
}

// fakePeer is one connected client: its socket, the SFU side of its two peer
// connections and the channels the fake owns on each.
type fakePeer struct {
	fake *fakeSFU
	conn *websocket.Conn

	wsMu sync.Mutex

	id    string
	room  string
	group string

	mu               sync.Mutex
	subPC            *webrtc.PeerConnection
	pubPC            *webrtc.PeerConnection
	subRel, subLossy *webrtc.DataChannel
	pending          map[string][]webrtc.ICECandidateInit
	// leg, once a test has made this peer's leg slow, queues what is
	// relayed to it (see fakeLeg).
	leg *fakeLeg
}

func (f *fakeSFU) serve(conn *websocket.Conn) {
	peer := &fakePeer{fake: f, conn: conn, pending: make(map[string][]webrtc.ICECandidateInit)}
	f.mu.Lock()
	f.live++
	f.mu.Unlock()
	defer func() {
		peer.close()
		f.leave(peer)
		f.mu.Lock()
		f.live--
		f.mu.Unlock()
	}()
	for {
		var frame fakeIn
		if err := conn.ReadJSON(&frame); err != nil {
			return
		}
		if err := peer.handle(frame); err != nil {
			f.fail("handle "+frame.Event, err)
			return
		}
	}
}

// fakeIn is a client frame as it arrives on the wire.
type fakeIn struct {
	RoomID    string          `json:"roomId"` //nolint:tagliatelle // connector wire is camelCase
	Payload   json.RawMessage `json:"payload"`
	Event     string          `json:"event"`
	GroupID   string          `json:"groupId"`   //nolint:tagliatelle // connector wire is camelCase
	RequestID string          `json:"requestId"` //nolint:tagliatelle // connector wire is camelCase
}

// fakeMedia is the media-in payload the fake reads.
type fakeMedia struct {
	Method           string          `json:"method"`
	Description      *fakeDesc       `json:"description"`
	RTCIceCandidates []fakeCandidate `json:"rtcIceCandidates"` //nolint:tagliatelle // connector wire is camelCase
	PingReq          *fakePing       `json:"ping_req"`
}

type fakeDesc struct {
	SDP  string `json:"sdp"`
	Type string `json:"type"`
}

type fakeCandidate struct {
	Candidate        string  `json:"candidate"`
	SDPMid           *string `json:"sdpMid"`           //nolint:tagliatelle // connector wire is camelCase
	SDPMLineIndex    *uint16 `json:"sdpMLineIndex"`    //nolint:tagliatelle // connector wire is camelCase
	UsernameFragment *string `json:"usernameFragment"` //nolint:tagliatelle // connector wire is camelCase
	Target           string  `json:"target"`
}

type fakePing struct {
	Timestamp int64 `json:"timestamp"`
	RTT       int64 `json:"rtt"`
}

func (p *fakePeer) handle(frame fakeIn) error {
	switch frame.Event {
	case eventJoin:
		// The capture's join carries no group id: the connector names the
		// group in its answer to this very frame.
		if frame.GroupID != "" {
			p.fake.fail("join", errGroupOnJoin)
		}
		p.fake.noteJoin(frame)
		if refusal := p.fake.joinRefusal(); refusal != nil {
			p.room = frame.RoomID
			return p.reply(frame.RequestID, eventError,
				map[string]any{"code": refusal.Code, "message": refusal.Message})
		}
		p.fake.join(p, frame.RoomID)
		p.fake.awaitJoinGate()
		if err := p.write(eventJoinResponse, p.joinResponse()); err != nil {
			return err
		}
		if err := p.sendMedia(methodConfig, map[string]any{
			"configuration": map[string]any{"iceServers": []any{map[string]any{"urls": []string{fakeSTUNURL}}}},
		}); err != nil {
			return err
		}
		// Who is already here reaches the joiner in its own rtc:join, and
		// nowhere else: the roster updates the connector sends afterwards
		// describe this participant to itself. Between rtc:config and
		// rtc:offer is where the capture has it.
		if err := p.sendMedia(methodJoin, map[string]any{"join": p.roomState()}); err != nil {
			return err
		}
		if err := p.offerSubscriber(); err != nil {
			return err
		}
		p.fake.announceJoin(p.room, p)
		return nil
	case eventMediaIn:
		// Everything after the join echoes the group the join-response
		// named, as the capture does.
		if frame.GroupID != p.group {
			p.fake.fail("media-in", errWrongGroup)
		}
		var media fakeMedia
		if err := json.Unmarshal(frame.Payload, &media); err != nil {
			return err
		}
		return p.handleMedia(media)
	default:
		return nil
	}
}

// roomState is the rtc:join payload this peer is given: who else was already
// in the room when it arrived. The real frame carries the room, this
// participant, the ICE servers and the server's ping timeouts too; the roster
// is the part an engine that carries bytes reads.
func (p *fakePeer) roomState() map[string]any {
	others := make([]any, 0)
	for _, peer := range p.fake.roomPeers(p.room) {
		if peer == p {
			continue
		}
		others = append(others, rosterEntry(peer, "ACTIVE"))
	}
	return map[string]any{"otherParticipants": others}
}

// announceJoin tells the room about a participant that has just arrived. The
// joiner is not among the recipients: it has been told who is here in its own
// rtc:join, and it is what the others are being told about.
func (f *fakeSFU) announceJoin(room string, joiner *fakePeer) {
	f.broadcastParticipants(room, nil, joiner)
}

// broadcastParticipants sends the room roster to everyone in it, the way the
// SFU does when the membership changes. gone, when set, is listed as
// DISCONNECTED: that is how a participant leaves a LiveKit roster. skip, when
// set, is left out of the recipients. A write that fails is dropped - one
// dead socket does not concern the others.
func (f *fakeSFU) broadcastParticipants(room string, gone, skip *fakePeer) {
	peers := f.roomPeers(room)
	roster := make([]any, 0, len(peers)+1)
	for _, peer := range peers {
		roster = append(roster, rosterEntry(peer, "ACTIVE"))
	}
	if gone != nil {
		roster = append(roster, rosterEntry(gone, participantDisconnected))
	}
	payload := map[string]any{"update": map[string]any{"participants": roster}}
	for _, peer := range peers {
		if peer == skip {
			continue
		}
		_ = peer.sendMedia(methodParticipants, payload)
	}
}

func rosterEntry(peer *fakePeer, state string) map[string]any {
	return map[string]any{"sid": "PA_" + peer.id, "identity": peer.id, "state": state, "name": peer.id}
}

// leave removes a peer whose socket is gone and tells the room, as the SFU
// does when a participant drops.
func (f *fakeSFU) leave(peer *fakePeer) {
	f.mu.Lock()
	f.peers = slices.DeleteFunc(f.peers, func(other *fakePeer) bool { return other == peer })
	if peer.room != "" {
		f.rooms[peer.room] = slices.DeleteFunc(f.rooms[peer.room],
			func(other *fakePeer) bool { return other == peer })
	}
	room := peer.room
	f.mu.Unlock()
	if room != "" {
		f.broadcastParticipants(room, peer, nil)
	}
}

func (p *fakePeer) joinResponse() map[string]any {
	return map[string]any{
		"roomId":    p.room,
		"meetingId": "fakemeeting",
		"participant": map[string]any{
			"sessionId":     "fakesession-" + p.id,
			"participantId": p.id,
		},
		"participantGroup": map[string]any{"groupId": p.group},
		"settings":         map[string]any{"maxParticipantCount": 100},
	}
}

func (p *fakePeer) handleMedia(media fakeMedia) error {
	switch media.Method {
	case methodAnswer:
		if media.Description == nil {
			return nil
		}
		p.fake.markAnswer()
		return p.acceptSubscriberAnswer(media.Description.SDP)
	case methodOffer:
		if media.Description == nil {
			return nil
		}
		p.fake.markPublisherOffer()
		return p.answerPublisher(media.Description.SDP)
	case methodICE:
		for _, cand := range media.RTCIceCandidates {
			// Every client rtc:ice in the capture carries the fragment its
			// candidates were gathered under, so one that does not is a
			// frame the service never sees from its own client.
			if cand.UsernameFragment == nil || *cand.UsernameFragment == "" {
				p.fake.fail("ice", errNoUfrag)
			}
			p.fake.noteCandidate(cand)
			p.addICE(cand)
		}
		return nil
	case methodPing:
		return p.pong(media.PingReq)
	default:
		return nil
	}
}

func (p *fakePeer) pong(ping *fakePing) error {
	p.fake.notePing()
	var last int64
	if ping != nil {
		last = ping.Timestamp
	}
	return p.sendMedia(methodPong, map[string]any{
		"pong_resp": map[string]any{
			"lastPingTimestamp": strconv.FormatInt(last, 10),
			"timestamp":         strconv.FormatInt(time.Now().UnixMilli(), 10),
		},
	})
}

// offerSubscriber plays the SFU's half of the subscriber PC: it creates both
// channels, offers, and trickles its candidates.
func (p *fakePeer) offerSubscriber() error {
	pc, err := newFakePC()
	if err != nil {
		return err
	}
	pc.OnICECandidate(func(cand *webrtc.ICECandidate) { p.trickle(targetSubscriber, cand) })
	ordered := true
	rel, err := pc.CreateDataChannel(labelReliable, &webrtc.DataChannelInit{Ordered: &ordered})
	if err != nil {
		return err
	}
	var noRetransmits uint16
	lossy, err := pc.CreateDataChannel(labelLossy,
		&webrtc.DataChannelInit{Ordered: &ordered, MaxRetransmits: &noRetransmits})
	if err != nil {
		return err
	}
	offer, err := pc.CreateOffer(nil)
	if err != nil {
		return err
	}
	if err := pc.SetLocalDescription(offer); err != nil {
		return err
	}
	p.mu.Lock()
	p.subPC, p.subRel, p.subLossy = pc, rel, lossy
	p.mu.Unlock()
	return p.sendMedia(methodOffer, map[string]any{
		"description": map[string]any{"type": sdpTypeOffer, "sdp": offer.SDP},
	})
}

func (p *fakePeer) acceptSubscriberAnswer(sdp string) error {
	pc := p.peerConnection(targetSubscriber)
	if pc == nil {
		return nil
	}
	if err := pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeAnswer, SDP: sdp}); err != nil {
		return err
	}
	p.drainICE(targetSubscriber)
	return nil
}

// answerPublisher accepts the PC the client offers. The channels it carries
// arrive through OnDataChannel; what the client writes on them is what the
// SFU relays to the room.
func (p *fakePeer) answerPublisher(sdp string) error {
	pc, err := newFakePC()
	if err != nil {
		return err
	}
	pc.OnICECandidate(func(cand *webrtc.ICECandidate) { p.trickle(targetPublisher, cand) })
	pc.OnDataChannel(func(dc *webrtc.DataChannel) {
		dc.OnMessage(func(msg webrtc.DataChannelMessage) { p.fake.forward(p, msg.Data) })
	})
	p.mu.Lock()
	p.pubPC = pc
	p.mu.Unlock()
	offer := webrtc.SessionDescription{Type: webrtc.SDPTypeOffer, SDP: sdp}
	if setErr := pc.SetRemoteDescription(offer); setErr != nil {
		return setErr
	}
	p.drainICE(targetPublisher)
	answer, err := pc.CreateAnswer(nil)
	if err != nil {
		return err
	}
	if err := pc.SetLocalDescription(answer); err != nil {
		return err
	}
	return p.sendMedia(methodAnswer, map[string]any{
		"description": map[string]any{"type": sdpTypeAnswer, "sdp": answer.SDP},
	})
}

// relay hands one relayed packet to this peer: down its slow leg when it has
// one, straight to its channel otherwise.
func (p *fakePeer) relay(frame []byte, lossy bool) {
	p.mu.Lock()
	leg := p.leg
	p.mu.Unlock()
	if leg != nil {
		leg.push(frame, lossy)
		return
	}
	p.deliver(frame, lossy)
}

// slowLeg gives this peer a leg of its own at rate, or changes its rate.
func (p *fakePeer) slowLeg(rate int) {
	p.mu.Lock()
	leg := p.leg
	if leg == nil {
		leg = newFakeLeg()
		p.leg = leg
		go leg.drain(p.deliver)
	}
	p.mu.Unlock()
	leg.setRate(rate)
}

// deliver writes one relayed packet to this peer on the channel its kind
// belongs to.
func (p *fakePeer) deliver(frame []byte, lossy bool) {
	p.mu.Lock()
	dc := p.subRel
	if lossy {
		dc = p.subLossy
	}
	p.mu.Unlock()
	if dc == nil || dc.ReadyState() != webrtc.DataChannelStateOpen {
		return
	}
	if err := dc.Send(frame); err != nil {
		p.fake.fail("deliver", err)
	}
}

func (p *fakePeer) trickle(target string, cand *webrtc.ICECandidate) {
	if cand == nil {
		return
	}
	init := cand.ToJSON()
	mid := "0"
	if init.SDPMid != nil && *init.SDPMid != "" {
		mid = *init.SDPMid
	}
	var index uint16
	if init.SDPMLineIndex != nil {
		index = *init.SDPMLineIndex
	}
	_ = p.sendMedia(methodICE, map[string]any{
		"rtcIceCandidates": []any{map[string]any{
			"candidate":     init.Candidate,
			"sdpMid":        mid,
			"sdpMLineIndex": index,
			"target":        target,
		}},
	})
}

func (p *fakePeer) addICE(cand fakeCandidate) {
	target := cand.Target
	if target == "" {
		target = targetSubscriber
	}
	mid := "0"
	if cand.SDPMid != nil && *cand.SDPMid != "" {
		mid = *cand.SDPMid
	}
	var index uint16
	if cand.SDPMLineIndex != nil {
		index = *cand.SDPMLineIndex
	}
	init := webrtc.ICECandidateInit{Candidate: cand.Candidate, SDPMid: &mid, SDPMLineIndex: &index}
	p.mu.Lock()
	defer p.mu.Unlock()
	pc := p.pcLocked(target)
	if pc == nil || pc.RemoteDescription() == nil {
		p.pending[target] = append(p.pending[target], init)
		return
	}
	if err := pc.AddICECandidate(init); err != nil {
		p.fake.fail("add ice", err)
	}
}

func (p *fakePeer) drainICE(target string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	pc := p.pcLocked(target)
	queued := p.pending[target]
	p.pending[target] = nil
	if pc == nil {
		return
	}
	for _, init := range queued {
		if err := pc.AddICECandidate(init); err != nil {
			p.fake.fail("drain ice", err)
		}
	}
}

func (p *fakePeer) peerConnection(target string) *webrtc.PeerConnection {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.pcLocked(target)
}

func (p *fakePeer) pcLocked(target string) *webrtc.PeerConnection {
	if target == targetPublisher {
		return p.pubPC
	}
	return p.subPC
}

// write sends one server frame under a requestId of its own, the way the
// connector sends what no client asked for.
func (p *fakePeer) write(event string, payload any) error {
	return p.reply(uuid.NewString(), event, payload)
}

// reply sends one server frame under the requestId it answers. The keys are
// the ones the capture shows for that event: a join-response carries none of
// the session ids, a media-out carries both, an error frame carries the
// group only.
func (p *fakePeer) reply(requestID, event string, payload any) error {
	frame := map[string]any{
		"event":     event,
		"requestId": requestID,
		"roomId":    p.room,
	}
	switch event {
	case eventMediaOut:
		frame["groupId"] = p.group
		frame["participantId"] = p.id
	case eventError:
		frame["groupId"] = p.group
	}
	if payload != nil {
		frame["payload"] = payload
	}
	p.wsMu.Lock()
	defer p.wsMu.Unlock()
	return p.conn.WriteJSON(frame)
}

func (p *fakePeer) sendMedia(method string, extra map[string]any) error {
	payload := map[string]any{"method": method}
	for key, value := range extra {
		payload[key] = value
	}
	return p.write(eventMediaOut, payload)
}

func (p *fakePeer) closeSocket() {
	p.wsMu.Lock()
	defer p.wsMu.Unlock()
	_ = p.conn.Close()
}

func (p *fakePeer) close() {
	p.closeSocket()
	p.closePeerConnections()
}

func (p *fakePeer) closePeerConnections() {
	p.mu.Lock()
	sub, pub := p.subPC, p.pubPC
	p.subPC, p.pubPC = nil, nil
	leg := p.leg
	p.mu.Unlock()
	if leg != nil {
		leg.close()
	}
	for _, pc := range []*webrtc.PeerConnection{sub, pub} {
		if pc != nil {
			_ = pc.Close()
		}
	}
}

// requireHostInterface skips a test that joins the fake SFU on a host with no
// up, non-loopback IPv4 interface. Both sides gather IPv4 host candidates only
// and pion leaves loopback out (the engine's settings are a real client's), so
// on such a host neither side has a candidate pair and the join waits out its
// own deadline, or the package's: a sandbox started with --network none ran
// this package into its ten-minute timeout.
//
// ai-generated: the function.
func requireHostInterface(t *testing.T) {
	t.Helper()
	ifaces, err := net.Interfaces()
	if err != nil {
		return // cannot tell: let the test run and say what it finds
	}
	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp != 0 && iface.Flags&net.FlagLoopback == 0 && hasIPv4(iface) {
			return
		}
	}
	t.Skip("needs an up, non-loopback IPv4 interface: pion gathers no ICE host candidate on lo")
}

// hasIPv4 reports whether iface holds an IPv4 address.
//
// ai-generated: the function.
func hasIPv4(iface net.Interface) bool {
	addrs, err := iface.Addrs()
	if err != nil {
		return false
	}
	for _, addr := range addrs {
		if ipnet, ok := addr.(*net.IPNet); ok && ipnet.IP.To4() != nil {
			return true
		}
	}
	return false
}

// newFakePC builds the SFU side of a peer connection: no ICE servers, IPv4
// host candidates only, so a test never leaves the machine.
func newFakePC() (*webrtc.PeerConnection, error) {
	settings := webrtc.SettingEngine{}
	settings.SetNetworkTypes([]webrtc.NetworkType{webrtc.NetworkTypeUDP4})
	return webrtc.NewAPI(webrtc.WithSettingEngine(settings)).NewPeerConnection(webrtc.Configuration{})
}
