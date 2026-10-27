package maxcalls

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"github.com/openlibrecommunity/olcrtc/internal/auth"
)

// ai-generated: the whole file (the provider against a fake of the calls
// API).

const (
	testKey      = "-w-Uk0000testsessionkey"
	testEndpoint = "wss://videowebrtc.okcdn.ru/ws2?userId=1125900269589776&entityType=USER" +
		"&conversationId=165c1eae-0000-4000-8000-000000000000&token=jointoken&peerId=39335131408"
)

// fakeAPI answers fb.do: a login with a session, a join with the room's
// connection, and records what each call carried.
type fakeAPI struct {
	srv   *httptest.Server
	mu    sync.Mutex
	calls []map[string]string
	// refuse makes every call answer this error envelope.
	refuse map[string]any
}

func newFakeAPI(t *testing.T) *fakeAPI {
	t.Helper()
	f := &fakeAPI{}
	f.srv = httptest.NewServer(http.HandlerFunc(f.serve))
	t.Cleanup(f.srv.Close)
	return f
}

func (f *fakeAPI) serve(w http.ResponseWriter, r *http.Request) {
	if err := r.ParseForm(); err != nil || r.URL.Path != "/fb.do" {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	call := map[string]string{"origin": r.Header.Get("Origin")}
	for k := range r.PostForm {
		call[k] = r.PostForm.Get(k)
	}
	f.mu.Lock()
	f.calls = append(f.calls, call)
	refuse := f.refuse
	f.mu.Unlock()
	var answer any
	switch {
	case refuse != nil:
		answer = refuse
	case call["method"] == methodAnonymLogin:
		answer = map[string]any{"uid": 512002791292, "session_key": testKey, "session_secret_key": "s"}
	case call["method"] == methodJoinByLink:
		answer = map[string]any{
			"endpoint": testEndpoint, "token": "jointoken",
			"turn_server": map[string]any{
				"urls":     []string{"turn:199.159.0.1:3478?transport=udp", "turn:199.159.0.1:443?transport=tcp"},
				"username": "u", "credential": "c",
			},
			"stun_server": map[string]any{"urls": []string{"stun:199.159.0.1:3478"}},
		}
	default:
		answer = map[string]any{"error_code": 100, "error_msg": "PARAM : unknown method"}
	}
	body, err := json.Marshal(answer)
	if err != nil {
		panic(err)
	}
	_, _ = w.Write(body)
}

func (f *fakeAPI) methods() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	out := make([]string, 0, len(f.calls))
	for _, c := range f.calls {
		out = append(out, c["method"])
	}
	return out
}

func TestASessionKeyJoinsTheLinkAsItIs(t *testing.T) {
	f := newFakeAPI(t)
	creds, err := Provider{APIBase: f.srv.URL}.Issue(context.Background(), auth.Config{
		RoomURL: "https://max.ru/joincall/gJKH8SzaJWI37-o10lPsM_e_e0gRM-eALIvdbiNqWE4", Token: testKey,
	})
	if err != nil {
		t.Fatal(err)
	}
	if got := f.methods(); strings.Join(got, ",") != methodJoinByLink {
		t.Fatalf("calls = %v, want the join alone", got)
	}
	join := f.calls[0]
	for k, want := range map[string]string{
		fieldSessionKey: testKey, "joinLink": "gJKH8SzaJWI37-o10lPsM_e_e0gRM-eALIvdbiNqWE4",
		"isVideo": "true", "isAudio": "true", "application_key": applicationKey, "format": "JSON", "origin": webOrigin,
	} {
		if join[k] != want {
			t.Errorf("join %s = %q, want %q", k, join[k], want)
		}
	}
	if creds.URL != testEndpoint || creds.Token != "jointoken" {
		t.Fatalf("creds = %+v", creds)
	}
	for k, want := range map[string]string{
		"userId": "1125900269589776", "turnUsername": "u", "turnCredential": "c",
		"turnUrl": "turn:199.159.0.1:3478?transport=udp,turn:199.159.0.1:443?transport=tcp", "stunUrl": "stun:199.159.0.1:3478",
	} {
		if creds.Extra[k] != want {
			t.Errorf("extra %s = %q, want %q", k, creds.Extra[k], want)
		}
	}
	for k, v := range creds.Extra {
		if strings.Contains(v, testKey) {
			t.Fatalf("extra %s carries the session key", k)
		}
	}
}

func TestACallsTokenIsExchangedForASessionFirst(t *testing.T) {
	f := newFakeAPI(t)
	creds, err := Provider{APIBase: f.srv.URL}.Issue(context.Background(), auth.Config{
		RoomURL: "https://max.ru/joincall/gJKH8SzaJWI37", Token: "An_calls_token", Name: "gate-cli",
	})
	if err != nil {
		t.Fatal(err)
	}
	if got := f.methods(); strings.Join(got, ",") != methodAnonymLogin+","+methodJoinByLink {
		t.Fatalf("calls = %v, want the login then the join", got)
	}
	var sessionData map[string]any
	if err := json.Unmarshal([]byte(f.calls[0]["session_data"]), &sessionData); err != nil {
		t.Fatal(err)
	}
	if sessionData["auth_token"] != "An_calls_token" || sessionData["device_id"] != "gate-cli" ||
		sessionData["client_version"] != clientVersion || sessionData["version"] != float64(3) {
		t.Fatalf("session_data = %v", sessionData)
	}
	if f.calls[1][fieldSessionKey] != testKey {
		t.Fatalf("the join used session key %q, want the login's", f.calls[1][fieldSessionKey])
	}
	if creds.Extra["userId"] != "512002791292" {
		t.Fatalf("userId = %q, want the login's uid", creds.Extra["userId"])
	}
}

func TestAnAPIErrorIsNamed(t *testing.T) {
	f := newFakeAPI(t)
	f.refuse = map[string]any{"error_code": 102, "error_msg": "PARAM_SESSION_EXPIRED : session expired"}
	_, err := Provider{APIBase: f.srv.URL}.Issue(context.Background(), auth.Config{
		RoomURL: "https://max.ru/joincall/abc", Token: testKey,
	})
	var apiErr *APIError
	if !errors.As(err, &apiErr) || apiErr.Code != 102 || apiErr.Method != methodJoinByLink {
		t.Fatalf("err = %v, want an APIError of the join with code 102", err)
	}
}

func TestIssueRefusesWhatItCannotJoinWith(t *testing.T) {
	f := newFakeAPI(t)
	p := Provider{APIBase: f.srv.URL}
	if _, err := p.Issue(context.Background(), auth.Config{RoomURL: "https://max.ru/joincall/abc"}); !errors.Is(err, ErrTokenRequired) {
		t.Fatalf("err = %v, want ErrTokenRequired", err)
	}
	for _, room := range []string{"", "https://max.ru/", "https://max.ru/joincall/", "https://max.ru/joincall/a b"} {
		if _, err := p.Issue(context.Background(), auth.Config{RoomURL: room, Token: testKey}); !errors.Is(err, ErrJoinLinkRequired) {
			t.Fatalf("room %q: err = %v, want ErrJoinLinkRequired", room, err)
		}
	}
	if len(f.methods()) != 0 {
		t.Fatalf("the API was called: %v", f.methods())
	}
}

func TestJoinHash(t *testing.T) {
	for _, c := range []struct{ room, want string }{
		{"https://max.ru/joincall/gJKH8Sza-_WE4", "gJKH8Sza-_WE4"},
		{" https://max.ru/joincall/gJKH8Sza/?x=1#f\n", "gJKH8Sza"},
		{"gJKH8Sza", "gJKH8Sza"},
		{"https://max.ru/call/gJKH8Sza", ""},
	} {
		if got := joinHash(c.room); got != c.want {
			t.Errorf("joinHash(%q) = %q, want %q", c.room, got, c.want)
		}
	}
}
