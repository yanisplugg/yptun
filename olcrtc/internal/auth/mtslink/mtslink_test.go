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

	"github.com/openlibrecommunity/olcrtc/internal/auth"
)

const (
	testEvent   = "20300400500"
	testSession = "20300400600"
	testOwner   = "100200300"
	testLink    = "https://my.mts-link.ru/j/" + testOwner + "/" + testEvent
)

// The gateway's own answers, as it gave them on 2026-10-11 (ids kept, tokens
// replaced). The tests below answer with these and nothing invented.
const (
	replyEvent = `{"id":20300400500,"access":1,"type":"meeting","status":"ACTIVE","isEndless":true,` +
		`"eventSessions":[{"id":20300400600,"access":1,"type":"meeting","status":"START"}]}`
	replyGuestLogin = `{"isAccepted":true,"participationId":1485424883,"user":{"sessionId":"529e1f7db49244b035d47e8c4eca75f4"},` +
		`"participation":{"id":1485424883,"name":"","nickname":"cap-a","isOnline":false,"isAccepted":true,"role":"GUEST",` +
		`"event":{"id":20300400500},"user":{"id":100200999,"type":"GUEST","isDeleted":false,"nickname":"cap-a"},` +
		`"eventSession":{"id":20300400600},"registerStatus":"INVITED"}}`
	replyConfiguration = `{"hlsServer":"events-delivery-files.webinar.ru","rtcServer":"sfu.mts-link.ru",` +
		`"mediaServer":"live-sd.webinar.ru","rtcUrl":"\/\/sfu.mts-link.ru\/event\/20300400600\/rtc",` +
		`"cometServer":"msg-edge-5.webinar.ru","rtcActiveSpeakerServer":"sfu.mts-link.ru"}`
	replyConference = `{"id":395296501,"isDeleted":false,"createAt":"2026-10-11T15:46:04+03:00",` +
		`"privateKey":"vcPRIVATE0000000000000000000000000","publicKey":"vcPUBLIC00000000000000000000000000",` +
		`"userId":100200999,"user":{"id":100200999,"nickname":"cap-a"},"status":"PUBLISH","size":"SMALL",` +
		`"hasStream":false,"hasVideo":true,"hasAudio":false,"typeMedia":"conference","participationId":1485424883}`
	replyJoinToken    = `{"data":{"joinToken":"vcJOIN000000000000000000000000000"}}`
	replyGone         = `{"error":{"code":406,"message":"Not Acceptable"}}`
	replyNoToken      = `{"error":{"code":401,"message":"A Token was not found in the TokenStorage."}}`
	replyAccessDenied = `{"error":{"code":403,"message":"Access denied"}}`
)

// gateway is the service behind one test server: it answers the measured
// shapes, sets the guest cookies at the login, and expects them back on every
// call that needs them. calls records what was asked, in order.
type gateway struct {
	t  *testing.T
	mu sync.Mutex
	// calls is "METHOD path" of every request, in order.
	calls []string
	// forms is the decoded body of every POST, by path.
	forms map[string]url.Values
	// answer overrides the reply of a path: status and body.
	answer map[string]func(w http.ResponseWriter)
	// rtcServer is what the configuration names; empty keeps the measured one.
	rtcServer string
}

func newGateway(t *testing.T) (*gateway, *httptest.Server) {
	t.Helper()
	g := &gateway{t: t, forms: map[string]url.Values{}, answer: map[string]func(http.ResponseWriter){}}
	srv := httptest.NewServer(g)
	t.Cleanup(srv.Close)
	return g, srv
}

func (g *gateway) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	g.mu.Lock()
	g.calls = append(g.calls, r.Method+" "+r.URL.Path)
	if r.Method == http.MethodPost {
		body, _ := io.ReadAll(r.Body)
		form, _ := url.ParseQuery(string(body))
		g.forms[r.URL.Path] = form
	}
	override := g.answer[r.URL.Path]
	g.mu.Unlock()
	if override != nil {
		override(w)
		return
	}

	guest := func() bool {
		a, errA := r.Cookie("access")
		s, errS := r.Cookie("sessionId")
		return errA == nil && errS == nil && a.Value == "eyJguest.access" && s.Value == "529e1f7db49244b0"
	}
	switch r.Method + " " + r.URL.Path {
	case "GET /api/event/" + testEvent:
		_, _ = io.WriteString(w, replyEvent)
	case "POST /api/eventsessions/" + testSession + "/guestlogin":
		// As the service sets them: Secure, HttpOnly, SameSite=None.
		for _, pair := range []string{
			"access=eyJguest.access", "refresh=6074c7a91c56476840728cab1c1b42f5", "sessionId=529e1f7db49244b0",
		} {
			w.Header().Add("Set-Cookie", pair+"; Path=/; Domain=.mts-link.ru; Secure; HttpOnly; SameSite=None")
		}
		_, _ = io.WriteString(w, replyGuestLogin)
	case "GET /api/eventsession/" + testSession + "/configuration":
		if !guest() {
			w.WriteHeader(http.StatusForbidden)
			_, _ = io.WriteString(w, replyAccessDenied)
			return
		}
		reply := replyConfiguration
		if g.rtcServer != "" {
			reply = strings.ReplaceAll(reply, `"rtcServer":"sfu.mts-link.ru"`, `"rtcServer":"`+g.rtcServer+`"`)
		}
		_, _ = io.WriteString(w, reply)
	case "POST /api/eventsessions/" + testSession + "/conferences":
		if !guest() {
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = io.WriteString(w, replyNoToken)
			return
		}
		w.WriteHeader(http.StatusCreated)
		_, _ = io.WriteString(w, replyConference)
	case "GET /api/eventsessions/" + testSession + "/join-token":
		if !guest() {
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = io.WriteString(w, replyNoToken)
			return
		}
		_, _ = io.WriteString(w, replyJoinToken)
	case "DELETE /api/conferences/395296501":
		if !guest() {
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = io.WriteString(w, replyNoToken)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	default:
		g.t.Errorf("the provider asked for %s %s, which the web client never does", r.Method, r.URL.Path)
		w.WriteHeader(http.StatusNotAcceptable)
		_, _ = io.WriteString(w, replyGone)
	}
}

func (g *gateway) status(path string, code int, body string) {
	g.answer[path] = func(w http.ResponseWriter) {
		w.WriteHeader(code)
		_, _ = io.WriteString(w, body)
	}
}

func issue(t *testing.T, srv *httptest.Server, link, name string) (auth.Credentials, error) {
	t.Helper()
	return Provider{gateway: srv.URL}.Issue(context.Background(), auth.Config{RoomURL: link, Name: name})
}

func TestParseEventLink(t *testing.T) {
	for _, tc := range []struct {
		link, event, session string
		ok                   bool
	}{
		{testLink, testEvent, "", true},
		{testLink + "/session/" + testSession, testEvent, testSession, true},
		{testLink + "/stream-new/" + testSession, testEvent, "", true},
		{"  " + testLink + "\n", testEvent, "", true},
		{"https://my.mts-link.ru/j/abc/" + testEvent, "", "", false},
		{"https://my.mts-link.ru/j/" + testOwner, "", "", false},
		{"not a url", "", "", false},
		{"https://my.mts-link.ru/events", "", "", false},
		{"", "", "", false},
	} {
		event, session, err := parseEventLink(tc.link)
		if tc.ok && (err != nil || event != tc.event || session != tc.session) {
			t.Errorf("parseEventLink(%q) = %q, %q, %v; want %q, %q", tc.link, event, session, err, tc.event, tc.session)
		}
		if !tc.ok && !errors.Is(err, ErrRoomRef) {
			t.Errorf("parseEventLink(%q) error = %v, want %v", tc.link, err, ErrRoomRef)
		}
	}
}

// The whole guest flow, in the web client's order, with the web client's
// bodies: the event names its session, the login mints the guest, the
// configuration names the SFU, the conference is the publish ticket and the
// join token is the door.
func TestIssueWalksTheGuestFlowAndBuildsTheRoomJoin(t *testing.T) {
	g, srv := newGateway(t)
	creds, err := issue(t, srv, testLink, "Мария Ковалёва")
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}

	wantCalls := []string{
		"GET /api/event/" + testEvent,
		"POST /api/eventsessions/" + testSession + "/guestlogin",
		"GET /api/eventsession/" + testSession + "/configuration",
		"POST /api/eventsessions/" + testSession + "/conferences",
		"GET /api/eventsessions/" + testSession + "/join-token",
	}
	if strings.Join(g.calls, "\n") != strings.Join(wantCalls, "\n") {
		t.Fatalf("calls:\n%s\nwant:\n%s", strings.Join(g.calls, "\n"), strings.Join(wantCalls, "\n"))
	}
	if login := g.forms["/api/eventsessions/"+testSession+"/guestlogin"]; login.Get("nickname") != "Мария Ковалёва" {
		t.Errorf("guestlogin form = %v, want the nickname", login)
	}
	conf := g.forms["/api/eventsessions/"+testSession+"/conferences"]
	if conf.Get("hasVideo") != "true" || conf.Get("hasAudio") != "false" {
		t.Errorf("conference form = %v, want a camera and no microphone", conf)
	}

	u, err := url.Parse(creds.URL)
	if err != nil {
		t.Fatalf("join URL %q: %v", creds.URL, err)
	}
	if u.Scheme != "https" || u.Host != "sfu.mts-link.ru" || u.Path != "/rtc/room/"+testSession+"/join" {
		t.Errorf("join URL = %s://%s%s, want the configuration's SFU and the session's room", u.Scheme, u.Host, u.Path)
	}
	q := u.Query()
	for key, want := range map[string]string{
		"userId":           "100200999", // participation.user.id: the login reply's top-level user has no id
		"joinToken":        "vcJOIN000000000000000000000000000",
		"publishToken":     "vcPRIVATE0000000000000000000000000",
		"userName":         "Мария Ковалёва",
		"disableRecording": "true",
	} {
		if q.Get(key) != want {
			t.Errorf("join URL %s = %q, want %q", key, q.Get(key), want)
		}
	}
	if did, pid := q.Get("did"), q.Get("pid"); len(did) != 16 || len(pid) != 16 || did == pid {
		t.Errorf("did = %q, pid = %q; want two 16-hex ids", did, pid)
	}

	if creds.Extra["sessionId"] != testSession || creds.Extra["userId"] != "100200999" ||
		creds.Extra["conferenceId"] != "395296501" || creds.Extra["gateway"] != srv.URL {
		t.Errorf("Extra = %v", creds.Extra)
	}
	// The cookies are for the gateway (the engine takes its conference back
	// with them); every one the login set travels, not a pair picked by name.
	for _, cookie := range []string{"access=eyJguest.access", "refresh=6074c7a91c56476840728cab1c1b42f5", "sessionId=529e1f7db49244b0"} {
		if !strings.Contains(creds.Token, cookie) {
			t.Errorf("Token lacks %q", strings.SplitN(cookie, "=", 2)[0])
		}
	}
}

func TestIssueTakesTheSessionALinkNames(t *testing.T) {
	g, srv := newGateway(t)
	if _, err := issue(t, srv, testLink+"/session/"+testSession, "a"); err != nil {
		t.Fatalf("Issue: %v", err)
	}
	if g.calls[0] != "POST /api/eventsessions/"+testSession+"/guestlogin" {
		t.Fatalf("first call = %s: a link that names its session needs no event lookup", g.calls[0])
	}
}

func TestIssueJoinsUnderADefaultNameWhenNoneIsGiven(t *testing.T) {
	g, srv := newGateway(t)
	creds, err := issue(t, srv, testLink, "")
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}
	nick := g.forms["/api/eventsessions/"+testSession+"/guestlogin"].Get("nickname")
	if nick == "" {
		t.Fatal("an empty nickname is refused by the service (400 ERROR_BAD_REQUEST)")
	}
	if u, _ := url.Parse(creds.URL); u.Query().Get("userName") != nick {
		t.Fatal("the join names another user than the login")
	}
}

// The SFU is whatever the configuration names: webinar rooms answered on
// live-webrtc-ixcN-*.webinar.ru, meetings on sfu.mts-link.ru.
func TestIssueJoinsTheSFUTheConfigurationNames(t *testing.T) {
	g, srv := newGateway(t)
	g.rtcServer = "live-webrtc-ixc1-13.webinar.ru"
	creds, err := issue(t, srv, testLink, "a")
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}
	if !strings.HasPrefix(creds.URL, "https://live-webrtc-ixc1-13.webinar.ru/rtc/room/"+testSession+"/join?") {
		t.Fatalf("join URL = %q", creds.URL)
	}
}

func TestIssueRefusesAnSFUNameThatIsNotAHost(t *testing.T) {
	for _, name := range []string{"", "evil.example/path?x=", "user@host", "a b"} {
		g, srv := newGateway(t)
		g.status("/api/eventsession/"+testSession+"/configuration", http.StatusOK,
			strings.ReplaceAll(replyConfiguration, `"rtcServer":"sfu.mts-link.ru"`, `"rtcServer":"`+name+`"`))
		if _, err := issue(t, srv, testLink, "a"); !errors.Is(err, ErrIncompleteReply) {
			t.Errorf("rtcServer %q: error = %v, want %v", name, err, ErrIncompleteReply)
		}
	}
}

// A room the service no longer has answers 406 Not Acceptable, at the event
// lookup and at the login alike (measured). That is the room, not the guest.
func TestARoomTheServiceNoLongerHasIsGone(t *testing.T) {
	g, srv := newGateway(t)
	g.status("/api/event/"+testEvent, http.StatusNotAcceptable, replyGone)
	if _, err := issue(t, srv, testLink, "a"); !errors.Is(err, ErrRoomGone) {
		t.Fatalf("event lookup 406: error = %v, want %v", err, ErrRoomGone)
	}

	g, srv = newGateway(t)
	g.status("/api/eventsessions/"+testSession+"/guestlogin", http.StatusNotAcceptable, replyGone)
	if _, err := issue(t, srv, testLink, "a"); !errors.Is(err, ErrRoomGone) {
		t.Fatalf("guestlogin 406: error = %v, want %v", err, ErrRoomGone)
	}
}

func TestAnEventWithoutASessionIsNotARoom(t *testing.T) {
	g, srv := newGateway(t)
	g.status("/api/event/"+testEvent, http.StatusOK, `{"id":20300400500,"eventSessions":[]}`)
	if _, err := issue(t, srv, testLink, "a"); !errors.Is(err, ErrNoSession) {
		t.Fatalf("error = %v, want %v", err, ErrNoSession)
	}
}

func TestAGuestTheServiceTurnsAwayIsNotAccepted(t *testing.T) {
	g, srv := newGateway(t)
	g.status("/api/eventsessions/"+testSession+"/guestlogin", http.StatusOK,
		strings.Replace(replyGuestLogin, `"isAccepted":true`, `"isAccepted":false`, 1))
	if _, err := issue(t, srv, testLink, "a"); !errors.Is(err, ErrNotAccepted) {
		t.Fatalf("error = %v, want %v", err, ErrNotAccepted)
	}
}

func TestRepliesThatNameNothingAreIncomplete(t *testing.T) {
	for path, body := range map[string]string{
		"/api/eventsessions/" + testSession + "/guestlogin":  `{"isAccepted":true,"user":{"sessionId":"x"}}`,
		"/api/eventsessions/" + testSession + "/conferences": `{"id":395296501,"publicKey":"vcPUBLIC"}`,
		"/api/eventsessions/" + testSession + "/join-token":  `{"data":{}}`,
	} {
		g, srv := newGateway(t)
		code := http.StatusOK
		if strings.HasSuffix(path, "/conferences") {
			code = http.StatusCreated
		}
		g.status(path, code, body)
		if _, err := issue(t, srv, testLink, "a"); !errors.Is(err, ErrIncompleteReply) {
			t.Errorf("%s: error = %v, want %v", path, err, ErrIncompleteReply)
		}
	}
}

// What the service answers is never in an error: a body can carry a token.
func TestAnUnexpectedStatusNamesTheCallAndTheNumberOnly(t *testing.T) {
	g, srv := newGateway(t)
	g.status("/api/eventsessions/"+testSession+"/join-token", http.StatusInternalServerError,
		`{"error":"boom","token":"vcSECRET00000000"}`)
	_, err := issue(t, srv, testLink, "a")
	if !errors.Is(err, ErrBadStatus) {
		t.Fatalf("error = %v, want %v", err, ErrBadStatus)
	}
	if msg := err.Error(); !strings.Contains(msg, "join-token") || !strings.Contains(msg, "500") || strings.Contains(msg, "vcSECRET") {
		t.Fatalf("error text = %q", msg)
	}
}

// A conference stays in the room's list until its publisher has been on the
// SFU and left, or its guest takes it back (measured: one whose join never
// happened was still listed 45 minutes on). A flow that stops after making
// one takes it back.
func TestAConferenceNothingWillJoinIsTakenBack(t *testing.T) {
	g, srv := newGateway(t)
	g.status("/api/eventsessions/"+testSession+"/join-token", http.StatusInternalServerError, `{}`)
	if _, err := issue(t, srv, testLink, "a"); !errors.Is(err, ErrBadStatus) {
		t.Fatalf("error = %v, want %v", err, ErrBadStatus)
	}
	if last := g.calls[len(g.calls)-1]; last != "DELETE /api/conferences/395296501" {
		t.Fatalf("last call = %s, want the conference taken back", last)
	}
}

func TestLeaveTakesTheConferenceBack(t *testing.T) {
	var got, cookie string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got, cookie = r.Method+" "+r.URL.Path, r.Header.Get("Cookie")
		w.WriteHeader(http.StatusNoContent)
	}))
	defer srv.Close()

	err := leave(context.Background(), srv.Client(), srv.URL, "395296501", "access=a; sessionId=s")
	if err != nil {
		t.Fatalf("leave: %v", err)
	}
	if got != "DELETE /api/conferences/395296501" || cookie != "access=a; sessionId=s" {
		t.Fatalf("got %q with cookie %q", got, cookie)
	}
	if err := leave(context.Background(), srv.Client(), srv.URL, "39/../5", "c"); !errors.Is(err, ErrConferenceID) {
		t.Fatal("a conference id that is not a number went into a path")
	}
}

// ai-generated: the room link's ICE hint reaches the engine and nothing else
// .
func TestIssuePassesTheLinksICEHintToTheEngine(t *testing.T) {
	_, srv := newGateway(t)
	creds, err := issue(t, srv, testLink+"?ice=tcp", "a")
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}
	if creds.Extra["ice"] != "tcp" {
		t.Fatalf("Extra[ice] = %q, want tcp", creds.Extra["ice"])
	}
	plain, err := issue(t, srv, testLink, "a")
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}
	if _, ok := plain.Extra["ice"]; ok {
		t.Fatal("a plain link asks for no ICE mode")
	}
}

// ai-generated: a link without a hint takes the ICE mode its caller's side asks for
// (auth.Config.DefaultICE): the client side asks for TCP, a server for nothing. A
// hint on the link wins, and a default that names no mode is no default.
func TestALinkWithoutAHintTakesTheCallersDefaultICE(t *testing.T) {
	_, srv := newGateway(t)
	issueWith := func(link, def string) auth.Credentials {
		t.Helper()
		creds, err := Provider{gateway: srv.URL}.Issue(context.Background(),
			auth.Config{RoomURL: link, Name: "a", DefaultICE: def})
		if err != nil {
			t.Fatalf("Issue(%q, default %q): %v", link, def, err)
		}
		return creds
	}
	if got := issueWith(testLink, "tcp").Extra["ice"]; got != "tcp" {
		t.Fatalf("no hint, default tcp: Extra[ice] = %q, want tcp", got)
	}
	if got := issueWith(testLink+"?ice=udp", "tcp").Extra["ice"]; got != "udp" {
		t.Fatalf("the link's hint wins over the default: Extra[ice] = %q, want udp", got)
	}
	if _, ok := issueWith(testLink, "").Extra["ice"]; ok {
		t.Fatal("no hint and no default ask for no ICE mode")
	}
	if _, ok := issueWith(testLink, "sideways").Extra["ice"]; ok {
		t.Fatal("a default that names no mode is no default")
	}
}
