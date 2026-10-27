// Package salutejazz implements an engine.Session over the SaluteJazz
// connector, the signaling endpoint of the Sber video service. The connector
// is LiveKit wrapped in a JSON envelope: the client sends
// {roomId, payload, event, groupId, requestId} and the payload of a media
// frame carries a LiveKit signal method (rtc:config, rtc:offer, rtc:ice,
// rtc:ping) instead of protobuf.
//
// The service needs no account. What a guest must know is a room code and
// its password, which the auth package turns into the connector URL
// (engine.Config.URL) and the password (engine.Config.Token).
//
// Two peer connections are required. The SFU offers a subscriber PC and
// creates the _reliable and _lossy channels on it, but data written to those
// channels is dropped without an error: only the channels of a publisher PC
// the client itself offers are relayed to the room. This engine therefore
// answers the SFU's offer, then offers a publisher PC carrying both
// channels, and sends on the publisher while it receives on the subscriber.
package salutejazz

import (
	"context"
	"errors"
	"fmt"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
	"github.com/pion/webrtc/v4"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/protect"
	"github.com/openlibrecommunity/olcrtc/internal/relaywin"
)

const (
	// The envelope events this engine speaks. The service has more
	// (chat, transcription); an engine that carries bytes needs none of them.
	eventJoin         = "join"
	eventJoinResponse = "join-response"
	eventMediaIn      = "media-in"
	eventMediaOut     = "media-out"
	eventError        = "error"
	// eventParticipantLeft is what the connector tells the rest of the room
	// when a participant is gone, about one ping timeout after its socket
	// died.
	eventParticipantLeft = "participant-left"

	// The LiveKit signal methods a media payload carries.
	methodConfig = "rtc:config"
	methodOffer  = "rtc:offer"
	methodAnswer = "rtc:answer"
	methodICE    = "rtc:ice"
	methodPing   = "rtc:ping"
	methodPong   = "rtc:pong"
	// methodJoin carries the state of the room at the moment this
	// participant joined, and with it the only list of who was already here:
	// a rtc:participants:update describes this participant to itself.
	methodJoin = "rtc:join"
	// methodParticipants carries a roster update: who else is here, and
	// under which identity a data packet reaches them.
	methodParticipants = "rtc:participants:update"

	// Which peer connection a description or a candidate belongs to. The SFU
	// offers the subscriber; the client offers the publisher.
	targetSubscriber = "SUBSCRIBER"
	targetPublisher  = "PUBLISHER"

	// The data channel labels LiveKit uses on both peer connections.
	labelReliable = "_reliable"
	labelLossy    = "_lossy"

	// The two description types a session description carries.
	sdpTypeOffer  = "offer"
	sdpTypeAnswer = "answer"

	// credentialKeyRoomID is the Extra key the auth provider puts the room
	// code under.
	credentialKeyRoomID = "roomID"

	// codeNotFound is the one error code that says a room is gone without
	// naming what is gone. See roomIsGone.
	codeNotFound = "NOT_FOUND"

	// webOrigin and webUserAgent are what the official web client sends on
	// the connector handshake. The service sits behind a bot filter that
	// sees them.
	webOrigin    = "https://salutejazz.ru"
	webUserAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
		"(KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"

	wsHandshakeTimeout = 15 * time.Second
	wsWriteTimeout     = 15 * time.Second
	// wsReadTimeout is five ping intervals: the SFU answers every ping, so a
	// connector that says nothing for that long is gone.
	wsReadTimeout = 25 * time.Second
	// wsReadLimit caps one signaling frame. gorilla reads without a limit by
	// default, so the connector could otherwise name any size and have it
	// buffered and decoded in full.
	wsReadLimit = 8 << 20

	// pingInterval is the cadence the web client pings at, and pongTimeout
	// is what rtc:join announces as the server's own ping timeout.
	pingInterval = 5 * time.Second
	pongTimeout  = 15 * time.Second

	// answerTimeout bounds the wait for the SFU's answer to our publisher
	// offer. Measured at ~90 ms against the live service.
	answerTimeout = 20 * time.Second
	// joinTimeout bounds a whole join: socket, join-response, both peer
	// connections and the publisher channels. All media is relay-forced, so
	// this covers a full TURN allocation on a slow uplink.
	joinTimeout = 30 * time.Second
	// pcCloseTimeout bounds teardown of a peer connection. Closing one frees
	// its TURN allocation, and an unreachable relay makes pion retransmit
	// for tens of seconds; teardown must not wait that out.
	pcCloseTimeout = 2 * time.Second

	maxReconnects = 10
)

var (
	// ErrURLRequired is returned when no connector URL was supplied.
	ErrURLRequired = errors.New("salutejazz connector URL required")
	// ErrRoomIDRequired is returned when no room code was supplied.
	ErrRoomIDRequired = errors.New("salutejazz room code required")
	// ErrPasswordRequired is returned when no room password was supplied.
	ErrPasswordRequired = errors.New("salutejazz room password required")
	// ErrWebSocketClosed is returned when a signaling write is attempted
	// while no connector socket is connected.
	ErrWebSocketClosed = errors.New("salutejazz signaling websocket closed")
	// ErrSessionClosed is returned when the session is closed mid-operation.
	ErrSessionClosed = errors.New("salutejazz session closed")
	// ErrJoinTimeout is returned when a join does not complete in time.
	ErrJoinTimeout = errors.New("salutejazz join timeout")
	// ErrPongTimeout is returned when the connector leaves a ping unanswered.
	ErrPongTimeout = errors.New("salutejazz ping was not answered")
	// ErrNoAnswer is returned when the SFU leaves a publisher offer
	// unanswered.
	ErrNoAnswer = errors.New("salutejazz publisher offer was not answered")
	// ErrNoDescription is returned when a description frame carries no SDP.
	ErrNoDescription = errors.New("salutejazz description missing")
	// ErrNoDataChannel is returned when no data channel can carry a payload.
	ErrNoDataChannel = errors.New("salutejazz data channel not ready")
	// ErrDestinationEnded is returned by a send held on its destination's
	// relay window when the session it belonged to ended while it waited:
	// the server retired the peer, the binding was dropped or moved, or the
	// participant left. Its frame was not sent.
	ErrDestinationEnded = errors.New("salutejazz destination's session ended while the send was held")
)

// generation is one connection attempt: the peer connections, their
// channels, the ICE state and the signals that drive the handshake. A
// reconnect replaces it wholesale, and every pion callback holds the
// generation it was created for, so a callback from a superseded attempt
// cannot signal the live one.
type generation struct {
	api *webrtc.API

	done     chan struct{}
	subReady chan struct{}
	pubReady chan struct{}
	answer   chan string

	// closeOnce makes teardown idempotent. Close and a Connect that is
	// giving up both hold this generation, and a signal channel closed by
	// two of them at once panics.
	closeOnce sync.Once

	// wsMu owns ws: it serialises the writes (gorilla allows a single
	// concurrent writer) and guards the pointer. The socket belongs to the
	// generation that dialled it, so a teardown can only close its own.
	wsMu sync.Mutex
	ws   *websocket.Conn

	// identity is this participant's id and group the LiveKit room name
	// behind the room code. Both come from this generation's join-response:
	// a rejoin has neither until the connector answers it again.
	identity atomic.Pointer[string]
	group    atomic.Pointer[string]

	// joinReqID is the requestId this attempt's join went out under. The
	// connector answers a frame under the id that frame carried, so this is
	// what tells the answer to our own join - a join-response, or an error
	// refusing it - from a reply to anything else.
	joinReqID atomic.Pointer[string]

	subPC, pubPC     atomic.Pointer[webrtc.PeerConnection]
	subRel, subLossy atomic.Pointer[webrtc.DataChannel]
	pubRel, pubLossy atomic.Pointer[webrtc.DataChannel]

	subConnected atomic.Bool
	pubConnected atomic.Bool

	// iceMu owns the ICE state: the advertised servers, and the queue of
	// candidates that arrived before the peer connection they belong to had
	// a remote description.
	iceMu   sync.Mutex
	servers []webrtc.ICEServer
	pending map[string][]webrtc.ICECandidateInit

	// peersMu owns peers, the identity -> sid map the room's frames
	// maintain for everyone else in it. It is a read-write lock because the
	// reads outnumber the writes by the packet: every relayed packet asks
	// whether it is from someone the roster already names (notePeer), and
	// almost every one of them is. left is every identity the room has
	// reported gone during this attempt: the SFU hands every connection
	// fresh participant ids, so one that has left never comes back under
	// this attempt, and what still arrives from it - a slow leg delivers a
	// participant's last frames long after the connector has reported it
	// gone - does not put it back in the roster.
	peersMu sync.RWMutex
	peers   map[string]string
	left    map[string]struct{}

	// confirmed is the one remote identity the tunnel handshake has
	// authenticated, and it belongs to this connection attempt: the SFU
	// hands every connection fresh participant ids, so a rejoin starts
	// without a binding. See Session.ConfirmPeer.
	confirmed atomic.Pointer[string]

	// pcMu serialises publishing a peer connection against taking both of
	// them away, the same discipline wsMu gives the socket.
	pcMu sync.Mutex

	// pongMu owns pongWaiters, one per ping this attempt has in flight, and
	// rtt is the round trip it last measured. Both belong to the attempt:
	// a pong proves the link the ping went out on, and a frame from an
	// attempt this session has moved on from proves nothing about the one
	// it is on now.
	pongMu      sync.Mutex
	pongWaiters map[int64]chan struct{}
	rtt         atomic.Int64

	// windowMu owns window, the channel a sender parked on the reliable
	// publisher lane waits on. It is closed and replaced every time the lane
	// drains back under its mark, which is how one drain releases all of
	// them. See Session.awaitSendWindow.
	windowMu sync.Mutex
	window   chan struct{}

	// lossyDrops counts the datagrams this attempt threw away because the
	// lossy lane, or their destination's relay window, was over its budget.
	lossyDrops atomic.Uint64

	// win is the relay window toward every destination this attempt sends
	// to, and relayMu owns relayPeers, what the window's wire knows of each
	// of them (relaySeq numbers them for the log). All of it belongs to the
	// attempt: the SFU hands every connection fresh participant ids, so a
	// window kept under the last attempt's names nobody under this one. See
	// window.go.
	win        *relaywin.Windows
	relayMu    sync.Mutex
	relayPeers map[string]*relayPeer
	relaySeq   int
}

func newGeneration(api *webrtc.API) *generation {
	return &generation{
		api:         api,
		done:        make(chan struct{}),
		subReady:    make(chan struct{}),
		pubReady:    make(chan struct{}),
		answer:      make(chan string, 1),
		pending:     make(map[string][]webrtc.ICECandidateInit),
		peers:       make(map[string]string),
		left:        make(map[string]struct{}),
		window:      make(chan struct{}),
		pongWaiters: make(map[int64]chan struct{}),
		win:         relaywin.New(defaultRelayTiming()),
		relayPeers:  make(map[string]*relayPeer),
	}
}

// publishPC hands a freshly built peer connection to the generation. A
// generation that has already been torn down owns nothing, so the peer
// connection is closed here instead of being left with an ICE agent and a
// TURN allocation and no owner. It reports whether the generation took it.
func (g *generation) publishPC(target string, pc *webrtc.PeerConnection) bool {
	g.pcMu.Lock()
	gone := g.isDone()
	if !gone {
		if target == targetPublisher {
			g.pubPC.Store(pc)
		} else {
			g.subPC.Store(pc)
		}
	}
	g.pcMu.Unlock()
	if gone {
		_ = pc.Close()
		return false
	}
	return true
}

// takePCs removes both peer connections from the generation, so exactly one
// caller ever closes them.
func (g *generation) takePCs() []*webrtc.PeerConnection {
	g.pcMu.Lock()
	defer g.pcMu.Unlock()
	return []*webrtc.PeerConnection{g.subPC.Swap(nil), g.pubPC.Swap(nil)}
}

// isDone reports whether this generation has been torn down.
func (g *generation) isDone() bool {
	select {
	case <-g.done:
		return true
	default:
		return false
	}
}

func (g *generation) groupID() string { return loadString(&g.group) }

func (g *generation) joinRequestID() string { return loadString(&g.joinReqID) }

func (g *generation) localIdentity() string { return loadString(&g.identity) }

// remoteIdentities is everyone else the room has reported, sorted so a
// caller sees a stable list.
func (g *generation) remoteIdentities() []string {
	g.peersMu.RLock()
	defer g.peersMu.RUnlock()
	out := make([]string, 0, len(g.peers))
	for identity := range g.peers {
		out = append(out, identity)
	}
	slices.Sort(out)
	return out
}

// applyParticipants folds one roster - rtc:join's otherParticipants, or an
// rtc:participants:update - into the identity map: a participant that has
// left is dropped, and this session is never its own peer. A participant that
// has left takes its relay window with it, as participant-left does, and a
// roster that names it again later is stale: it stays gone.
func (g *generation) applyParticipants(list []participant) {
	local := g.localIdentity()
	var gone []string
	g.peersMu.Lock()
	for _, peer := range list {
		if peer.Identity == "" || peer.Identity == local {
			continue
		}
		if peer.State == participantDisconnected {
			g.departLocked(peer.Identity)
			gone = append(gone, peer.Identity)
			continue
		}
		if _, departed := g.left[peer.Identity]; departed {
			continue
		}
		g.peers[peer.Identity] = peer.SID
	}
	g.peersMu.Unlock()
	for _, identity := range gone {
		g.forgetRelayPeer(identity)
	}
}

// Session is the SaluteJazz engine handle.
type Session struct {
	engine.Reconnector

	name        string
	resolver    protect.Lookup
	dtlsProfile engine.DTLSProfile
	refresh     func(ctx context.Context) (engine.Credentials, error)

	onData         func([]byte)
	onPeerData     func(peerID string, data []byte)
	onDatagram     func([]byte)
	onPeerDatagram func(peerID string, data []byte)

	// credMu guards the credentials a reconnect replaces under a live
	// session.
	credMu       sync.RWMutex
	connectorURL string
	room         string
	pass         string

	cur atomic.Pointer[generation]

	// server is the identity the last handshake this session completed
	// confirmed. It is kept here and not on the attempt, where the binding
	// is: the binding is dropped before every handshake the client retries
	// (ResetPeer) and does not survive a rejoin, and PeerSeen asks about the
	// server across both. See PeerSeen.
	server atomic.Pointer[string]

	// joinTimeout and pingInterval are the two paces a test shortens: how
	// long a join may take, and how often the keepalive fires.
	joinTimeout  time.Duration
	pingInterval time.Duration

	// lifecycleMu is held across the two steps that decide who owns a
	// connection attempt: Connect reads the terminated flag and publishes
	// its attempt under it, Close raises the flag and takes the attempt
	// away under it. One of the two always sees the other.
	lifecycleMu sync.Mutex

	closeCh      chan struct{}
	closeOnce    sync.Once
	closed       atomic.Bool
	terminated   atomic.Bool
	reconnecting atomic.Bool
	// ended latches the one verdict the caller is told about: a session that
	// is over is over for one reason, and the attempts that unwind behind it
	// must not report a second.
	ended atomic.Bool

	goMu     sync.Mutex
	goClosed bool
	wg       sync.WaitGroup

	// onJoinPayload observes the frames that drive the join handshake:
	// join-response, rtc:config and rtc:offer, in that order. Tests set it
	// before Connect.
	onJoinPayload func(event string)

	// beforePublish runs in the instant before a connection attempt is
	// published on the session, which is where Close and Connect race for
	// ownership of it. Tests set it before Connect to land a Close in that
	// window every time instead of hoping for it.
	beforePublish func()

	// relayTiming paces the relay window of every attempt this session
	// makes. Tests shorten it before Connect.
	relayTiming relaywin.Timing
}

// New creates a SaluteJazz engine session.
//
// cfg.URL is the connector WebSocket URL, cfg.Token the room password and
// cfg.Extra["roomID"] the room code - the tuple the auth provider issues.
func New(_ context.Context, cfg engine.Config) (engine.Session, error) {
	if cfg.URL == "" {
		return nil, ErrURLRequired
	}
	if cfg.Token == "" {
		return nil, ErrPasswordRequired
	}
	room := cfg.Extra[credentialKeyRoomID]
	if room == "" {
		return nil, ErrRoomIDRequired
	}

	s := &Session{
		name:           cfg.Name,
		resolver:       cfg.Resolver,
		dtlsProfile:    cfg.DTLSProfile,
		refresh:        cfg.Refresh,
		onData:         cfg.OnData,
		onPeerData:     cfg.OnPeerData,
		onDatagram:     cfg.OnDatagram,
		onPeerDatagram: cfg.OnPeerDatagram,
		connectorURL:   cfg.URL,
		room:           room,
		pass:           cfg.Token,
		joinTimeout:    joinTimeout,
		pingInterval:   pingInterval,
		relayTiming:    defaultRelayTiming(),
		closeCh:        make(chan struct{}),
	}
	s.Configure(engine.ReconnectorConfig{
		MaxAttempts: maxReconnects,
		Reconnect:   s.reconnect,
		OnError: func(err error) {
			logger.Debugf("salutejazz: reconnect failed: %v", err)
		},
		OnLimit:     s.signalEnded,
		LimitReason: "reconnect limit reached",
	})
	return s, nil
}

// Connect joins the room and negotiates both peer connections. It returns
// once the publisher lane is open, which is when the session can carry
// bytes.
func (s *Session) Connect(ctx context.Context) error {
	api, err := newWebRTCAPI(s.resolver, s.dtlsProfile)
	if err != nil {
		return err
	}
	gen := newGeneration(api)
	gen.win = relaywin.New(s.relayTiming)
	if s.beforePublish != nil {
		s.beforePublish()
	}
	if !s.publishGeneration(gen) {
		return ErrSessionClosed
	}

	conn, err := s.dialWebSocket(gen)
	if err != nil {
		s.abandon(gen)
		return err
	}
	s.goLaunch(func() { s.readLoop(gen, conn) })
	s.goLaunch(func() { s.pingLoop(gen) })
	s.goLaunch(func() { s.negotiate(gen) })

	if err := s.sendJoin(gen); err != nil {
		s.abandon(gen)
		return err
	}
	if err := s.awaitLanes(ctx, gen); err != nil {
		s.abandon(gen)
		return err
	}
	return s.abortIfTerminated(gen)
}

// publishGeneration makes gen the session's live connection attempt, unless
// the session has been closed. It reports whether the session took it.
//
// Close raises the terminated flag and takes the live attempt away under the
// same lock, so the two cannot pass each other: either Close finds this
// attempt and ends it, or this publish is refused and the attempt is never
// dialled. Reading the flag and publishing as two steps left a Close that
// landed between them with nothing to end, and the attempt went on to dial
// and join behind a closed session - a participant the connector kept in the
// room until the join timed out.
//
// A session that has ended refuses here too: the verdict that ended it was
// about the room, and another join would only ask the same question again.
//
// An attempt that was still live is ended here, once the new one has taken
// its place. Overwriting the pointer left the old attempt with its socket,
// its two peer connections and their TURN allocations, and the SFU goes on
// relaying to a participant nobody reads any more: two of ours in one room.
// reconnect ends the old attempt itself, so it was a second Connect that
// paid for this.
func (s *Session) publishGeneration(gen *generation) bool {
	s.lifecycleMu.Lock()
	if s.terminated.Load() || s.ended.Load() {
		s.lifecycleMu.Unlock()
		return false
	}
	s.closed.Store(false)
	replaced := s.cur.Swap(gen)
	s.lifecycleMu.Unlock()

	// Outside the lock: a teardown waits on pion closing two peer
	// connections, and Close takes this lock to end the session.
	if replaced != nil && replaced != gen {
		s.teardown(replaced)
	}
	return true
}

// abandon ends a connection attempt Connect is giving up on and takes it off
// the session. Every accessor reads the live attempt through s.cur, so one
// that has been torn down must not be left answering for a session that has
// none. A later attempt that has already replaced it is left alone.
func (s *Session) abandon(gen *generation) {
	s.teardown(gen)
	s.cur.CompareAndSwap(gen, nil)
}

// awaitLanes waits for the publisher channel that carries the byte stream.
func (s *Session) awaitLanes(ctx context.Context, gen *generation) error {
	timer := time.NewTimer(s.joinTimeout)
	defer timer.Stop()
	select {
	case <-gen.pubReady:
		return nil
	case <-gen.done:
		return ErrSessionClosed
	case <-timer.C:
		return ErrJoinTimeout
	case <-ctx.Done():
		return fmt.Errorf("salutejazz connect cancelled: %w", ctx.Err())
	}
}

// abortIfTerminated tears down the generation Connect just built when Close
// ran while it was being built: Close cannot see resources that were not
// published yet.
func (s *Session) abortIfTerminated(gen *generation) error {
	if !s.terminated.Load() {
		return nil
	}
	s.abandon(gen)
	return ErrSessionClosed
}

// Close terminates the session and releases its resources. The connector
// has no leave frame: the SFU drops a participant on its own ping timeout.
func (s *Session) Close() error {
	gen := s.terminate()
	s.closeOnce.Do(func() { close(s.closeCh) })

	if gen != nil {
		s.teardown(gen)
	}
	s.stopLaunching()

	done := make(chan struct{})
	go func() {
		s.wg.Wait()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(pcCloseTimeout):
	}
	return nil
}

// terminate marks the session gone for good and takes the live connection
// attempt away, under the lock Connect publishes an attempt with.
func (s *Session) terminate() *generation {
	s.lifecycleMu.Lock()
	defer s.lifecycleMu.Unlock()
	s.terminated.Store(true)
	s.closed.Store(true)
	return s.cur.Swap(nil)
}

// teardown ends one generation: its goroutines stop, the socket it dialled
// is closed and both peer connections are released. It runs once however
// many callers hold the generation - Close and a Connect that is giving up
// both do.
//
// Every relay window of the generation goes with it, which wakes a sender
// held on one: it finds the generation gone and returns ErrSessionClosed. A
// new attempt starts with windows of its own.
func (s *Session) teardown(gen *generation) {
	gen.closeOnce.Do(func() {
		engine.CloseSignal(gen.done)
		gen.win.ResetAll()
		gen.closeSocket()
		closePeerConnections(gen)
	})
}

// closePeerConnections closes both peer connections without letting a stuck
// TURN deallocation block the caller.
func closePeerConnections(gen *generation) {
	var wg sync.WaitGroup
	for _, pc := range gen.takePCs() {
		if pc == nil {
			continue
		}
		wg.Add(1)
		go func(pc *webrtc.PeerConnection) {
			defer wg.Done()
			_ = pc.Close()
		}(pc)
	}
	done := make(chan struct{})
	go func() {
		wg.Wait()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(pcCloseTimeout):
		logger.Warnf("salutejazz: peer connection close timed out after %s", pcCloseTimeout)
	}
}

// WatchConnection services reconnect requests until ctx ends or the session
// closes.
func (s *Session) WatchConnection(ctx context.Context) {
	s.Watch(ctx, s.closeCh)
}

// Reconnect asks the session to rejoin the room. Upper layers call it when a
// liveness probe declares the path dead before the transport has noticed.
func (s *Session) Reconnect(reason string) {
	if s.closed.Load() {
		return
	}
	logger.Infof("salutejazz: reconnect requested: %s", reason)
	s.queueReconnect()
}

// reconnect tears the current attempt down and joins again.
//
// The old attempt goes first and whole: its socket and both peer
// connections. The SFU does not drop a participant when its connector
// socket dies - it goes on relaying what the transport still carries - so a
// rejoin that left the old peer connections up would be two participants of
// ours in one room. Then the credentials are refreshed, and the rejoin is a
// join from scratch: fresh ICE servers, a fresh publisher offer, and no
// group id carried over from the attempt being replaced.
func (s *Session) reconnect(ctx context.Context) error {
	// A session that has ended is not reconnected. The verdict that ended
	// it was about the room, and the room is what a rejoin would ask for.
	if s.terminated.Load() || s.closed.Load() {
		return ErrSessionClosed
	}
	s.reconnecting.Store(true)
	defer s.reconnecting.Store(false)

	if gen := s.cur.Swap(nil); gen != nil {
		s.teardown(gen)
	}
	if err := s.refreshCredentials(ctx); err != nil {
		return err
	}
	if err := s.Connect(ctx); err != nil {
		return err
	}
	s.NotifyReconnect()
	return nil
}

// refreshCredentials asks the caller for the room credentials again, before
// a rejoin.
//
// The connector URL is what the preconnect call hands out, and the official
// client makes that call before every join: a rejoin on a stale URL can be
// pointed at a node that no longer serves the room. The room code and its
// password do not change, and a refresh that returns neither leaves them as
// they were. A session created without the hook rejoins on what it has.
func (s *Session) refreshCredentials(ctx context.Context) error {
	if s.refresh == nil {
		return nil
	}
	creds, err := s.refresh(ctx)
	if err != nil {
		return fmt.Errorf("salutejazz reconnect refresh: %w", err)
	}
	s.credMu.Lock()
	defer s.credMu.Unlock()
	engine.ApplyRefreshedCredentials(creds, &s.connectorURL, &s.pass,
		map[string]*string{credentialKeyRoomID: &s.room})
	return nil
}

// queueReconnect asks for a rejoin. The request is refused while the session
// is closed - which a session that has ended is - while one is already in
// flight, and whenever the caller's own policy says not to reconnect.
func (s *Session) queueReconnect() engine.ReconnectRequest {
	return s.Request(s.closed.Load(), s.reconnecting.Load())
}

// reconnectAttempt asks for a rejoin on behalf of one connection attempt.
//
// A failure raised by an attempt that has already been torn down says
// nothing about the session: that connection was given up on deliberately -
// by Close, by a Connect that gave up, or by the rejoin that replaced it -
// and everything of it fails on the way down. A request queued for one of
// those lands behind the attempt that replaced it and rejoins a room this
// session is already in. readLoop has always asked this before queueing;
// the keepalive, the publisher negotiation and the answer to the SFU's offer
// now ask it too.
func (s *Session) reconnectAttempt(gen *generation) {
	if s.closed.Load() || gen.isDone() {
		return
	}
	s.queueReconnect()
}

// signalEnded ends the session for good, once. A session that has ended
// asks for nothing more: the closed flag refuses every later reconnect
// request, and one that is already queued is dropped.
//
// It closes the session's done channel as well, which is what the reconnect
// supervisor watches. A request the supervisor had already taken off the
// queue when the verdict landed is not on the queue to be drained: it was
// inside an attempt, and it would have gone on failing and backing off until
// the attempt limit ran out - ten of them - for a room this session has been
// told is gone. The keepalive and everything else waiting on that channel
// stop with it, which is the same statement.
func (s *Session) signalEnded(reason string) {
	if s.ended.Swap(true) {
		return
	}
	s.closed.Store(true)
	s.Drain()
	s.closeOnce.Do(func() { close(s.closeCh) })
	logger.Warnf("salutejazz: session ended: %s", reason)
	s.SignalEnded(reason)
}

// endAttempt ends the session on a verdict one connection attempt reached,
// and takes that attempt down with it: a Connect still waiting on it must
// not sit out the join timeout for a room that is gone. An attempt a later
// one has already replaced ends alone - it speaks for a connection this
// session has moved on from.
func (s *Session) endAttempt(gen *generation, reason string) {
	if gen != s.current() {
		s.teardown(gen)
		return
	}
	s.signalEnded(reason)
	s.abandon(gen)
}

// roomIsGone reports whether an error code says the room this session joined
// is not there any more. Such a verdict is terminal: the room code is issued
// by the layer above, and the session it was issued for cannot get it back.
//
// The capture of the official client carries a single error frame, and that
// one is scoped to the request it answers, so the shapes below are what this
// engine treats as terminal rather than a vocabulary the service has
// confirmed. An unfamiliar code never ends a session by itself.
func roomIsGone(code string) bool {
	if code == codeNotFound {
		return true
	}
	for _, prefix := range []string{"ROOM_", "MEETING_"} {
		if rest, found := strings.CutPrefix(code, prefix); found && isGoneState(rest) {
			return true
		}
	}
	return false
}

// isGoneState is the tail of a ROOM_/MEETING_ code that says the room is
// over rather than merely unhappy.
func isGoneState(state string) bool {
	switch state {
	case codeNotFound, "CLOSED", "ENDED", "FINISHED", "DELETED", "EXPIRED":
		return true
	default:
		return false
	}
}

// endedReason is the reason a verdict is reported to the caller under. Only
// the code goes in: an error message from the connector names the
// participant it was raised for, and a reason travels into the caller's
// logs.
func endedReason(what, code string) string {
	if code == "" {
		code = "no code"
	}
	return "salutejazz: " + what + " (" + code + ")"
}

func (s *Session) current() *generation { return s.cur.Load() }

// localIdentity is this participant's id, as this generation's join-response
// named it.
func (s *Session) localIdentity() string {
	gen := s.current()
	if gen == nil {
		return ""
	}
	return gen.localIdentity()
}

// remoteIdentities is everyone else rtc:participants:update has reported in
// the room.
func (s *Session) remoteIdentities() []string {
	gen := s.current()
	if gen == nil {
		return nil
	}
	return gen.remoteIdentities()
}

func (s *Session) subscriberConnected() bool {
	gen := s.current()
	return gen != nil && gen.subConnected.Load()
}

func (s *Session) publisherConnected() bool {
	gen := s.current()
	return gen != nil && gen.pubConnected.Load()
}

// iceServers is what rtc:config advertised, normalised for pion.
func (s *Session) iceServers() []webrtc.ICEServer {
	gen := s.current()
	if gen == nil {
		return nil
	}
	return gen.iceServersSnapshot()
}

func (s *Session) roomID() string {
	s.credMu.RLock()
	defer s.credMu.RUnlock()
	return s.room
}

// password is the room password. It is never logged.
func (s *Session) password() string {
	s.credMu.RLock()
	defer s.credMu.RUnlock()
	return s.pass
}

func (s *Session) signalingURL() string {
	s.credMu.RLock()
	defer s.credMu.RUnlock()
	return s.connectorURL
}

func loadString(p *atomic.Pointer[string]) string {
	if v := p.Load(); v != nil {
		return *v
	}
	return ""
}

func storeString(p *atomic.Pointer[string], value string) {
	p.Store(&value)
}

// participantDisconnected is the state rtc:participants:update reports for
// someone who has left.
const participantDisconnected = "DISCONNECTED"

// goLaunch starts a tracked goroutine unless Close has started waiting.
func (s *Session) goLaunch(fn func()) {
	s.goMu.Lock()
	if s.goClosed {
		s.goMu.Unlock()
		return
	}
	s.wg.Add(1)
	s.goMu.Unlock()

	go func() {
		defer s.wg.Done()
		fn()
	}()
}

func (s *Session) stopLaunching() {
	s.goMu.Lock()
	s.goClosed = true
	s.goMu.Unlock()
}
