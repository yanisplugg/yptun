package mtslink

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/pion/interceptor"
	"github.com/pion/rtcp"
	"github.com/pion/rtp"
	"github.com/pion/rtp/codecs"
	"github.com/pion/webrtc/v4"
	"github.com/pion/webrtc/v4/pkg/media"

	"github.com/openlibrecommunity/olcrtc/internal/engine"
)

const (
	testSession = "20300400600"
	testPeer    = "31djU82nnvUp1kyQjpDpPhyWx6eeoD1xs1SrH6HGSrPpwQyR8IgX3REL7"
	testCookies = "access=eyJguest.access; refresh=6074c7a91c56; sessionId=529e1f7d"
	testConf    = "395296501"
)

// blackKeyframe is a real VP9 keyframe (libvpx, a black 320x180 picture): what
// the test publishes, so pion's own packetizer takes it.
var blackKeyframe = []byte{
	0x82, 0x49, 0x83, 0x42, 0x60, 0x13, 0xf0, 0x0b, 0x36, 0x66, 0x38, 0x24, 0x1c, 0x19, 0x92, 0x00,
	0x00, 0x50, 0x43, 0xe8, 0xbf, 0x40, 0x00, 0x67, 0x3f, 0xfe, 0x2f, 0x20, 0x00, 0x04, 0xee, 0x66,
	0xa7, 0x89, 0xd3, 0xfb, 0x17, 0xd3, 0x70, 0x4c, 0xc4, 0xe7, 0xd9, 0x1b, 0x5b, 0xe5, 0xac, 0x4c,
	0x31, 0x1d, 0x90, 0x67, 0xac, 0xb3, 0x74, 0x35, 0x3e, 0x92, 0xab, 0xc1, 0xec, 0x2d, 0xe8, 0x00,
	0x85, 0x59, 0x2c, 0xf0,
}

// fakeSFU stands in for MTS Link's SFU and, on the same server, for the one
// gateway call the engine makes. It answers a join with a real pion peer
// connection, so the engine's offer is negotiated, not just looked at.
type fakeSFU struct {
	t   *testing.T
	srv *httptest.Server

	mu sync.Mutex
	// what the join carried
	joins     int
	offer     string
	joinQuery url.Values
	joinHead  http.Header
	// refuse makes the next join answer this status and body instead.
	refuseStatus int
	refuseBody   string
	// pins and leaves are the calls after the join, "METHOD path | body | cookie".
	pins      []string
	leaves    []string
	pinStatus int

	pcs []*webrtc.PeerConnection
	// published is the payload of every video packet a publisher sent.
	published chan []byte
	publisher chan *webrtc.TrackRemote
	// toEngine is the track the relay sends into the engine.
	toEngine *webrtc.TrackLocalStaticRTP
}

func newFakeSFU(t *testing.T) *fakeSFU {
	t.Helper()
	f := &fakeSFU{t: t, published: make(chan []byte, 256), publisher: make(chan *webrtc.TrackRemote, 4), pinStatus: http.StatusOK}
	track, err := webrtc.NewTrackLocalStaticRTP(
		webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeVP9, ClockRate: 90000}, "relay-video", "relay")
	if err != nil {
		t.Fatal(err)
	}
	f.toEngine = track
	f.srv = httptest.NewServer(http.HandlerFunc(f.serve))
	t.Cleanup(func() {
		f.srv.Close()
		f.mu.Lock()
		defer f.mu.Unlock()
		for _, pc := range f.pcs {
			_ = pc.Close()
		}
	})
	return f
}

func (f *fakeSFU) serve(w http.ResponseWriter, r *http.Request) {
	body, _ := io.ReadAll(r.Body)
	switch {
	case r.Method == http.MethodPost && r.URL.Path == "/rtc/room/"+testSession+"/join":
		f.join(w, r, string(body))
	case r.Method == http.MethodPost && strings.HasPrefix(r.URL.Path, "/rtc/peer/"):
		f.mu.Lock()
		f.pins = append(f.pins, r.Method+" "+r.URL.Path+" | "+string(body)+" | "+r.Header.Get("Content-Type")+" | "+r.Header.Get("Cookie"))
		status := f.pinStatus
		f.mu.Unlock()
		w.WriteHeader(status)
	case r.Method == http.MethodDelete && strings.HasPrefix(r.URL.Path, "/api/conferences/"):
		f.mu.Lock()
		f.leaves = append(f.leaves, r.Method+" "+r.URL.Path+" | "+r.Header.Get("Cookie"))
		f.mu.Unlock()
		w.WriteHeader(http.StatusNoContent)
	default:
		f.t.Errorf("the engine asked for %s %s", r.Method, r.URL.Path)
		w.WriteHeader(http.StatusNotFound)
	}
}

func (f *fakeSFU) join(w http.ResponseWriter, r *http.Request, offer string) {
	f.mu.Lock()
	f.joins++
	f.offer, f.joinQuery, f.joinHead = offer, r.URL.Query(), r.Header.Clone()
	status, refusal := f.refuseStatus, f.refuseBody
	f.mu.Unlock()
	if status != 0 {
		w.WriteHeader(status)
		_, _ = io.WriteString(w, refusal)
		return
	}

	me := &webrtc.MediaEngine{}
	if err := me.RegisterDefaultCodecs(); err != nil {
		f.t.Error(err)
	}
	ir := &interceptor.Registry{}
	if err := webrtc.RegisterDefaultInterceptors(me, ir); err != nil {
		f.t.Error(err)
	}
	se := webrtc.SettingEngine{}
	se.SetNetworkTypes([]webrtc.NetworkType{webrtc.NetworkTypeUDP4})
	se.SetIncludeLoopbackCandidate(true)
	pc, err := webrtc.NewAPI(webrtc.WithMediaEngine(me), webrtc.WithInterceptorRegistry(ir), webrtc.WithSettingEngine(se)).
		NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		f.t.Error(err)
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	f.mu.Lock()
	f.pcs = append(f.pcs, pc)
	f.mu.Unlock()
	pc.OnTrack(func(track *webrtc.TrackRemote, _ *webrtc.RTPReceiver) {
		if track.Kind() != webrtc.RTPCodecTypeVideo {
			return
		}
		select {
		case f.publisher <- track:
		default:
		}
		for {
			pkt, _, readErr := track.ReadRTP()
			if readErr != nil {
				return
			}
			select {
			case f.published <- append([]byte(nil), pkt.Payload...):
			default:
			}
		}
	})
	if srdErr := pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeOffer, SDP: offer}); srdErr != nil {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = io.WriteString(w, "SDP validation error: "+srdErr.Error())
		return
	}
	// The relay sends into a slot the guest offered to receive on.
	for _, tr := range pc.GetTransceivers() {
		if tr.Kind() == webrtc.RTPCodecTypeVideo && tr.Direction() == webrtc.RTPTransceiverDirectionSendonly {
			sender, senderErr := webrtc.NewAPI(webrtc.WithMediaEngine(me)).NewRTPSender(f.toEngine, tr.Receiver().Transport())
			if senderErr == nil {
				_ = tr.SetSender(sender, f.toEngine)
			}
			break
		}
	}
	answer, err := pc.CreateAnswer(nil)
	if err != nil {
		f.t.Error(err)
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	gathered := webrtc.GatheringCompletePromise(pc)
	if sldErr := pc.SetLocalDescription(answer); sldErr != nil {
		f.t.Error(sldErr)
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	<-gathered
	// The SFU names the peer in the answer's s= line.
	sdp := strings.Replace(pc.LocalDescription().SDP, "\r\ns=-\r\n", "\r\ns="+testPeer+"\r\n", 1)
	w.Header().Set("Content-Type", "application/sdp")
	_, _ = io.WriteString(w, sdp)
}

// joinURL is the room join as the auth provider hands it over.
func (f *fakeSFU) joinURL() string {
	return f.srv.URL + "/rtc/room/" + testSession + "/join?userId=100200999&joinToken=vcJOIN&disableRecording=true" +
		"&userName=guest&publishToken=vcPRIVATE&did=33d131e5f5194d28&pid=3d131e5f5194d283"
}

func (f *fakeSFU) config() engine.Config {
	return engine.Config{
		URL: f.joinURL(), Token: testCookies,
		Extra: map[string]string{"gateway": f.srv.URL, "sessionId": testSession, "conferenceId": testConf},
	}
}

// newPublishingSession is a session with a VP9 track on it, the way the video
// transport leaves one before it connects.
func newPublishingSession(t *testing.T, cfg engine.Config) (*Session, *webrtc.TrackLocalStaticSample) {
	t.Helper()
	sess, err := New(context.Background(), cfg)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	s, ok := sess.(*Session)
	if !ok {
		t.Fatalf("New returned %T", sess)
	}
	t.Cleanup(func() { _ = s.Close() })
	track, err := webrtc.NewTrackLocalStaticSample(
		webrtc.RTPCodecCapability{MimeType: s.VideoCodec(), ClockRate: 90000, SDPFmtpLine: "profile-id=0"}, "video", "olcrtc")
	if err != nil {
		t.Fatal(err)
	}
	if err := s.AddVideoTrack(track); err != nil {
		t.Fatal(err)
	}
	return s, track
}

// mediaSections is the kind and direction of every m-line of an SDP, in order.
func mediaSections(sdp string) []string {
	sections := strings.Split(sdp, "\r\nm=")[1:]
	out := make([]string, 0, len(sections))
	for _, section := range sections {
		kind, _, _ := strings.Cut(section, " ")
		direction := "?"
		for _, d := range []string{"sendrecv", "sendonly", "recvonly", "inactive"} {
			if strings.Contains(section, "\r\na="+d) {
				direction = d
			}
		}
		out = append(out, kind+" "+direction)
	}
	return out
}

func within(t *testing.T, d time.Duration, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(d)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("not within %s: %s", d, what)
}

func TestNewNeedsTheRoomJoin(t *testing.T) {
	if _, err := New(context.Background(), engine.Config{}); !errors.Is(err, ErrNoEndpoint) {
		t.Fatalf("New without a URL: error = %v, want %v", err, ErrNoEndpoint)
	}
}

// The relay answers H264 and VP9 and no VP8 (measured), and asks for
// keyframes: the engine says so, and a video transport dresses for it.
func TestTheEngineNamesItsCodecAndReportsKeyframeRequests(t *testing.T) {
	sess, err := New(context.Background(), engine.Config{URL: "https://sfu.example/rtc/room/1/join?userId=1"})
	if err != nil {
		t.Fatal(err)
	}
	selector, ok := sess.(engine.VideoCodecSelector)
	if !ok || selector.VideoCodec() != webrtc.MimeTypeVP9 {
		t.Fatal("the engine must name VP9 as the codec a published track carries")
	}
	if _, ok := sess.(engine.KeyframeRequestSource); !ok {
		t.Fatal("the engine must report the relay's keyframe requests")
	}
	if _, ok := sess.(engine.VideoTrackCapable); !ok {
		t.Fatal("the engine must take a video track")
	}
	if err := sess.Send([]byte("x")); !errors.Is(err, ErrSendUnsupported) {
		t.Fatalf("Send error = %v, want %v: the room relays no data between guests", err, ErrSendUnsupported)
	}
}

func TestOfferURLAddsWhatDescribesTheOffer(t *testing.T) {
	got, err := offerURL("https://sfu.mts-link.ru/rtc/room/20300400600/join?userId=1&joinToken=vcJ&userName=%D0%9C", "2")
	if err != nil {
		t.Fatal(err)
	}
	u, err := url.Parse(got)
	if err != nil {
		t.Fatal(err)
	}
	q := u.Query()
	for key, want := range map[string]string{
		"userId": "1", "joinToken": "vcJ", "userName": "М",
		"twcc": "true", "originMid": "2", "preferredPublishCodec": "vp9",
	} {
		if q.Get(key) != want {
			t.Errorf("%s = %q, want %q", key, q.Get(key), want)
		}
	}
	if u.Host != "sfu.mts-link.ru" || u.Path != "/rtc/room/20300400600/join" {
		t.Errorf("the join moved: %s%s", u.Host, u.Path)
	}
	if _, err := offerURL("://nothing", "2"); err == nil {
		t.Error("a join URL that does not parse was taken")
	}
}

func TestPeerOfReadsTheSessionNameOfAnAnswer(t *testing.T) {
	answer := "v=0\r\no=odin 0 0 IN IP4 127.0.0.1\r\ns=" + testPeer + "\r\nc=IN IP4 127.0.0.1\r\nt=0 0\r\n"
	if got := peerOf(answer); got != testPeer {
		t.Fatalf("peerOf = %q", got)
	}
	for _, sdp := range []string{"v=0\r\no=odin 0 0\r\n", "v=0\r\ns=-\r\n", "v=0\r\ns=a/b\r\n", "v=0\r\ns=a b\r\n", ""} {
		if got := peerOf(sdp); got != "" {
			t.Errorf("peerOf(%q) = %q, want none: a peer id goes into a path", sdp, got)
		}
	}
}

// The whole join against a relay that negotiates for real.
func TestSessionJoinsPublishesAndHearsTheRelay(t *testing.T) {
	sfu := newFakeSFU(t)
	s, track := newPublishingSession(t, sfu.config())

	remote := make(chan *webrtc.TrackRemote, 4)
	s.SetVideoTrackHandler(func(tr *webrtc.TrackRemote, _ *webrtc.RTPReceiver) { remote <- tr })
	asked := make(chan struct{}, 16)
	s.SetKeyframeRequestHandler(func() {
		select {
		case asked <- struct{}{}:
		default:
		}
	})

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if err := s.Connect(ctx); err != nil {
		t.Fatalf("Connect: %v", err)
	}
	if !s.CanSend() || !s.SubscriberCanSend() {
		t.Fatal("a joined session cannot send")
	}

	// The join: the offer as the body, nothing of the guest's cookies - the
	// SFU reads none, and they are the gateway's - and the three things that
	// describe the offer added to what the provider gave.
	sfu.mu.Lock()
	head, query, offer, pins := sfu.joinHead, sfu.joinQuery, sfu.offer, append([]string(nil), sfu.pins...)
	sfu.mu.Unlock()
	if ct := head.Get("Content-Type"); ct != "application/sdp" {
		t.Errorf("join Content-Type = %q", ct)
	}
	if head.Get("Cookie") != "" {
		t.Error("the guest's cookies went to the SFU")
	}
	for key, want := range map[string]string{
		"userId": "100200999", "joinToken": "vcJOIN", "publishToken": "vcPRIVATE", "disableRecording": "true",
		"twcc": "true", "preferredPublishCodec": "vp9", "originMid": "2",
	} {
		if query.Get(key) != want {
			t.Errorf("join %s = %q, want %q", key, query.Get(key), want)
		}
	}

	// The offer: the published camera, a microphone line, the one audio
	// player the SFU allows, and nine video slots - the web client's layout
	// less the line it leaves inactive and the data channel, neither of which
	// the SFU asks for.
	want := make([]string, 0, 3+receiveSlots)
	want = append(want, "video sendrecv", "audio sendrecv", "audio recvonly")
	for range receiveSlots {
		want = append(want, "video recvonly")
	}
	if got := mediaSections(offer); strings.Join(got, ", ") != strings.Join(want, ", ") {
		t.Errorf("offer sections:\n %s\nwant:\n %s", strings.Join(got, ", "), strings.Join(want, ", "))
	}
	if !strings.Contains(offer, "\r\na=mid:2\r\na=") && !strings.Contains(offer, "a=mid:2\r\n") {
		t.Error("no mid 2 in the offer")
	}
	if strings.Contains(offer, "VP8") || strings.Contains(offer, "H264") {
		t.Error("the offer names a video codec other than the one it publishes")
	}

	// The pin the web client sends after every join: nothing pinned.
	if len(pins) != 1 || pins[0] != "POST /rtc/peer/"+testPeer+"/pin | {\"streams\":[\"\"]} | application/json | " {
		t.Errorf("pins = %q", pins)
	}

	// What is written to the track reaches the relay as VP9.
	stop := make(chan struct{})
	defer close(stop)
	go func() {
		for {
			select {
			case <-stop:
				return
			case <-time.After(20 * time.Millisecond):
				_ = track.WriteSample(media.Sample{Data: blackKeyframe, Duration: 20 * time.Millisecond})
			}
		}
	}()
	var published *webrtc.TrackRemote
	select {
	case published = <-sfu.publisher:
	case <-time.After(10 * time.Second):
		t.Fatal("the relay never saw the published track")
	}
	if !strings.EqualFold(published.Codec().MimeType, webrtc.MimeTypeVP9) {
		t.Fatalf("published as %s", published.Codec().MimeType)
	}
	select {
	case payload := <-sfu.published:
		var d codecs.VP9Packet
		if _, err := d.Unmarshal(payload); err != nil {
			t.Fatalf("the published packet is not VP9: %v", err)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("no packet reached the relay")
	}

	// The relay asks for a keyframe; the handler hears it.
	sfu.mu.Lock()
	relay := sfu.pcs[0]
	sfu.mu.Unlock()
	within(t, 10*time.Second, "a keyframe request reaching the handler", func() bool {
		_ = relay.WriteRTCP([]rtcp.Packet{&rtcp.PictureLossIndication{MediaSSRC: uint32(published.SSRC())}})
		select {
		case <-asked:
			return true
		default:
			return false
		}
	})

	// What the relay sends into a slot reaches the track handler.
	within(t, 10*time.Second, "the relay's video reaching the handler", func() bool {
		_ = sfu.toEngine.WriteRTP(&rtp.Packet{
			Header:  rtp.Header{Version: 2, PayloadType: 98, SequenceNumber: uint16(time.Now().UnixNano()), Timestamp: 1}, //nolint:gosec // any number
			Payload: append([]byte{0x9f, 0x80, 0x01, 0x10, 0x01, 0x40, 0x00, 0xb4}, blackKeyframe...),
		})
		select {
		case tr := <-remote:
			return strings.EqualFold(tr.Codec().MimeType, webrtc.MimeTypeVP9)
		default:
			return false
		}
	})

	// A guest that joined leaves nothing to take back: the service drops the
	// conference of a publisher that was on the SFU and left.
	if err := s.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
	if s.CanSend() {
		t.Error("a closed session can send")
	}
	sfu.mu.Lock()
	defer sfu.mu.Unlock()
	if len(sfu.leaves) != 0 {
		t.Errorf("leave calls after a join that succeeded: %q", sfu.leaves)
	}
}

// A join the SFU refuses says why - its own words, which name the rule the
// offer broke - and takes back the conference nothing will publish into.
func TestARefusedJoinSaysWhyAndTakesTheConferenceBack(t *testing.T) {
	sfu := newFakeSFU(t)
	sfu.refuseStatus = http.StatusBadRequest
	sfu.refuseBody = "SDP validation error: exceeded maximal audio players value 1"
	s, _ := newPublishingSession(t, sfu.config())

	err := s.Connect(context.Background())
	if !errors.Is(err, ErrJoinRefused) {
		t.Fatalf("Connect error = %v, want %v", err, ErrJoinRefused)
	}
	if msg := err.Error(); !strings.Contains(msg, "400") || !strings.Contains(msg, "exceeded maximal audio players") {
		t.Errorf("error text = %q, want the status and the SFU's reason", msg)
	}
	if strings.Contains(err.Error(), "vcJOIN") || strings.Contains(err.Error(), "vcPRIVATE") {
		t.Error("a token is in the error")
	}
	if s.CanSend() {
		t.Error("a session whose join was refused can send")
	}
	sfu.mu.Lock()
	defer sfu.mu.Unlock()
	if len(sfu.leaves) != 1 || sfu.leaves[0] != "DELETE /api/conferences/"+testConf+" | "+testCookies {
		t.Errorf("leaves = %q, want the conference taken back with the guest's cookies", sfu.leaves)
	}
}

func TestAnAnswerThatIsNotSDPIsRefused(t *testing.T) {
	sfu := newFakeSFU(t)
	sfu.refuseStatus = http.StatusOK
	sfu.refuseBody = `{"error":"no room"}`
	s, _ := newPublishingSession(t, sfu.config())
	if err := s.Connect(context.Background()); !errors.Is(err, ErrNoAnswer) {
		t.Fatalf("Connect error = %v, want %v", err, ErrNoAnswer)
	}
}

// The join works without the pin (measured), so a refused pin is not a
// refused join.
func TestARefusedPinDoesNotFailTheJoin(t *testing.T) {
	sfu := newFakeSFU(t)
	sfu.pinStatus = http.StatusForbidden
	s, _ := newPublishingSession(t, sfu.config())
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if err := s.Connect(ctx); err != nil {
		t.Fatalf("Connect with a refused pin: %v", err)
	}
}

func TestASessionWithNothingToPublishDoesNotJoin(t *testing.T) {
	sfu := newFakeSFU(t)
	sess, err := New(context.Background(), sfu.config())
	if err != nil {
		t.Fatal(err)
	}
	if err := sess.Connect(context.Background()); !errors.Is(err, ErrNoVideoTrack) {
		t.Fatalf("Connect error = %v, want %v", err, ErrNoVideoTrack)
	}
	sfu.mu.Lock()
	defer sfu.mu.Unlock()
	if sfu.joins != 0 {
		t.Error("a session with no track asked the SFU for a join")
	}
}

// A rejoin is a new guest: the provider's fresh credentials, a join from
// scratch, and the transport told so it can shake hands again.
func TestReconnectJoinsAgainWithFreshCredentials(t *testing.T) {
	sfu := newFakeSFU(t)
	cfg := sfu.config()
	refreshed := 0
	cfg.Refresh = func(context.Context) (engine.Credentials, error) {
		refreshed++
		return engine.Credentials{
			URL:   strings.Replace(sfu.joinURL(), "joinToken=vcJOIN", "joinToken=vcSECOND", 1),
			Token: testCookies, Extra: cfg.Extra,
		}, nil
	}
	s, _ := newPublishingSession(t, cfg)
	notified := 0
	s.SetReconnectCallback(func() { notified++ })

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := s.Connect(ctx); err != nil {
		t.Fatalf("Connect: %v", err)
	}
	if err := s.reconnect(ctx); err != nil {
		t.Fatalf("reconnect: %v", err)
	}
	sfu.mu.Lock()
	joins, token := sfu.joins, sfu.joinQuery.Get("joinToken")
	sfu.mu.Unlock()
	if joins != 2 || token != "vcSECOND" || refreshed != 1 {
		t.Fatalf("joins = %d with token %q after %d refresh(es), want a second join with the fresh token", joins, token, refreshed)
	}
	if notified != 1 || !s.CanSend() {
		t.Fatalf("reconnect notices = %d, can send = %v", notified, s.CanSend())
	}
}

func TestACloseSessionRefusesToConnect(t *testing.T) {
	sfu := newFakeSFU(t)
	s, _ := newPublishingSession(t, sfu.config())
	_ = s.Close()
	if err := s.Connect(context.Background()); !errors.Is(err, ErrSessionClosed) {
		t.Fatalf("Connect on a closed session: %v, want %v", err, ErrSessionClosed)
	}
}

// ai-generated: what each ICE mode asks of pion.
func TestICENetworkTypes(t *testing.T) {
	if iceNetworkTypes("") != nil || iceNetworkTypes("udp") != nil {
		t.Fatal("no hint and udp keep the shared UDP settings")
	}
	if got := iceNetworkTypes("tcp"); len(got) != 1 || got[0] != webrtc.NetworkTypeTCP4 {
		t.Fatalf("tcp = %v", got)
	}
	if got := iceNetworkTypes("both"); len(got) != 2 {
		t.Fatalf("both = %v", got)
	}
}
