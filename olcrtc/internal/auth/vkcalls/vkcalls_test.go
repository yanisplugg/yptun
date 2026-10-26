package vkcalls

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/openlibrecommunity/olcrtc/internal/auth"
)

const testRoom = "https://vk.ru/call/join/6LYH98MpSLrY358BBjSGWroHhb"

// fakeVK serves the five-step guest chain and calls.start. The recorded
// request log lets tests assert the wire contract.
type fakeVK struct {
	api   *httptest.Server
	fb    *httptest.Server
	steps []string
	apiFn func(method string, form map[string]string) any
	fbFn  func(method string, form map[string]string) any
}

func newFakeVK(t *testing.T) *fakeVK {
	t.Helper()
	f := &fakeVK{}
	f.api = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = r.ParseForm()
		method := strings.TrimPrefix(r.URL.Path, "/method/")
		f.steps = append(f.steps, method)
		if f.apiFn != nil {
			writeJSON(w, f.apiFn(method, formMap(r)))
			return
		}
		writeJSON(w, defaultAPIAnswer(method))
	}))
	f.fb = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = r.ParseForm()
		method := r.Form.Get("method")
		f.steps = append(f.steps, method)
		if f.fbFn != nil {
			writeJSON(w, f.fbFn(method, formMap(r)))
			return
		}
		writeJSON(w, defaultFBAnswer(method))
	}))
	t.Cleanup(f.api.Close)
	t.Cleanup(f.fb.Close)
	return f
}

func (f *fakeVK) provider() Provider { return Provider{APIBase: f.api.URL, FBBase: f.fb.URL} }

func formMap(r *http.Request) map[string]string {
	out := make(map[string]string, len(r.PostForm))
	for k := range r.PostForm {
		out[k] = r.PostForm.Get(k)
	}
	return out
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	b, err := json.Marshal(v)
	if err != nil {
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	_, _ = w.Write(b)
}

func defaultAPIAnswer(method string) any {
	switch method {
	case "auth.getAnonymToken":
		return map[string]any{"response": map[string]any{"token": "anon-token", "expired_at": 1790619997}}
	case "messages.getCallPreview":
		return map[string]any{"response": map[string]any{"user_id": 579934934382, "secret": "s3cret"}}
	case "messages.getAnonymCallToken":
		return map[string]any{"response": map[string]any{"token": "sdk-anon-token"}}
	}
	return map[string]any{"error": map[string]any{"error_code": 10, "error_msg": "internal"}}
}

func defaultFBAnswer(method string) any {
	switch method {
	case "auth.anonymLogin":
		return map[string]any{"session_key": "sk", "session_secret_key": "ssk", "api_server": "https://calls.okcdn.ru"}
	case "vchat.joinConversationByLink":
		return map[string]any{
			"endpoint":      "wss://videowebrtc.okcdn.ru/ws2?conversationId=c&peerId=46584250478&token=join-token&userId=579934934382&entityType=USER",
			"wt_endpoint":   "wss://videowebrtc.okcdn.ru/wt",
			"token":         "join-token",
			"id":            "call-id",
			"device_idx":    7,
			"client_type":   "VK",
			"p2p_forbidden": false,
			"stun_server":   map[string]any{"urls": "stun:stun.example:3478"},
			"turn_server":   map[string]any{"urls": []string{"turn:91.231.135.155:19302", "turn:91.231.135.179:19302"}, "username": "u", "credential": "c"},
		}
	}
	return map[string]any{"error": map[string]any{"error_code": 10, "error_msg": "internal"}}
}

func TestIssueHappyPath(t *testing.T) {
	f := newFakeVK(t)
	creds, err := f.provider().Issue(context.Background(), auth.Config{RoomURL: testRoom, Name: "Test"})
	if err != nil {
		t.Fatal(err)
	}
	want := []string{"auth.getAnonymToken", "messages.getCallPreview", "messages.getAnonymCallToken",
		"auth.anonymLogin", "vchat.joinConversationByLink"}
	if strings.Join(f.steps, ",") != strings.Join(want, ",") {
		t.Fatalf("chain order %v, want %v", f.steps, want)
	}
	if creds.URL != "wss://videowebrtc.okcdn.ru/ws2?conversationId=c&peerId=46584250478&token=join-token&userId=579934934382&entityType=USER" {
		t.Fatalf("url: %s", creds.URL)
	}
	if creds.Token != "join-token" {
		t.Fatalf("token: %s", creds.Token)
	}
	checks := map[string]string{
		KeySchema:        "1",
		KeyRoomURL:       testRoom,
		KeyParticipantID: "579934934382",
		KeyPeerIDHint:    "46584250478",
		KeyDeviceIdx:     "7",
		KeyClientType:    "VK",
		KeyP2PForbidden:  "false",
	}
	for k, want := range checks {
		if creds.Extra[k] != want {
			t.Errorf("%s = %q, want %q", k, creds.Extra[k], want)
		}
	}
	var ice []map[string]any
	if err := json.Unmarshal([]byte(creds.Extra[KeyICEServers]), &ice); err != nil {
		t.Fatalf("ice json: %v", err)
	}
	if len(ice) != 2 {
		t.Fatalf("ice servers: %v", ice)
	}
	if urls, _ := ice[1]["urls"].([]any); len(urls) != 2 || urls[0] != "turn:91.231.135.155:19302" {
		t.Fatalf("turn urls: %v", ice[1])
	}
	if ice[1]["username"] != "u" || ice[1]["credential"] != "c" {
		t.Fatalf("turn creds: %v", ice[1])
	}
	// Bootstrap secrets must not leak into credentials.
	for _, v := range creds.Extra {
		if strings.Contains(v, "anon-token") || strings.Contains(v, "s3cret") || strings.Contains(v, "ssk") {
			t.Fatal("bootstrap secret leaked into Extra")
		}
	}
}

func TestIssueValidations(t *testing.T) {
	f := newFakeVK(t)
	if _, err := f.provider().Issue(context.Background(), auth.Config{}); err == nil {
		t.Fatal("missing room url accepted")
	}
	if _, err := f.provider().Issue(context.Background(), auth.Config{RoomURL: testRoom, Token: "acc"}); err == nil {
		t.Fatal("account token accepted for guest issue")
	}
	for _, bad := range []string{
		"https://evil.example/call/join/x", "https://vk.ru/call/join/x/extra",
		"https://user@vk.ru/call/join/x", "https://vk.ru:8443/call/join/x",
		"https://vk.ru/call/join/x?token=1", "https://vk.ru/other/path",
	} {
		if _, err := f.provider().Issue(context.Background(), auth.Config{RoomURL: bad}); err == nil {
			t.Fatalf("bad room url accepted: %s", bad)
		}
	}
	// Endpoint token mismatch.
	f.fbFn = func(method string, _ map[string]string) any {
		a := defaultFBAnswer(method).(map[string]any)
		if method == "vchat.joinConversationByLink" {
			a["token"] = "different-token"
		}
		return a
	}
	if _, err := f.provider().Issue(context.Background(), auth.Config{RoomURL: testRoom}); err == nil {
		t.Fatal("endpoint token mismatch accepted")
	}
	// p2p_forbidden absent -> conservative true.
	f.fbFn = func(method string, _ map[string]string) any {
		a := defaultFBAnswer(method).(map[string]any)
		delete(a, "p2p_forbidden")
		return a
	}
	creds, err := f.provider().Issue(context.Background(), auth.Config{RoomURL: testRoom})
	if err != nil {
		t.Fatal(err)
	}
	if creds.Extra[KeyP2PForbidden] != "true" {
		t.Fatalf("absent p2p_forbidden = %q, want true", creds.Extra[KeyP2PForbidden])
	}
}

func TestIssueAPIErrors(t *testing.T) {
	f := newFakeVK(t)
	f.apiFn = func(method string, _ map[string]string) any {
		if method == "messages.getCallPreview" {
			return map[string]any{"error": map[string]any{"error_code": 14, "error_msg": "Captcha needed"}}
		}
		return defaultAPIAnswer(method)
	}
	_, err := f.provider().Issue(context.Background(), auth.Config{RoomURL: testRoom})
	var apiErr *GuestAPIError
	if !errors.As(err, &apiErr) || apiErr.Code != 14 {
		t.Fatalf("captcha error: %v", err)
	}
	f.apiFn = func(_ string, _ map[string]string) any { return "<html>" }
	if _, err := f.provider().Issue(context.Background(), auth.Config{RoomURL: testRoom}); err == nil {
		t.Fatal("invalid json accepted")
	}
}

func TestCreateRoom(t *testing.T) {
	f := newFakeVK(t)
	if _, err := f.provider().CreateRoom(context.Background(), auth.Config{}); err == nil {
		t.Fatal("missing organizer token accepted")
	}
	f.apiFn = func(_ string, _ map[string]string) any {
		return map[string]any{"response": map[string]any{"join_link": "https://vk.ru/call/join/NEWROOM1", "ok_join_link": ""}}
	}
	link, err := f.provider().CreateRoom(context.Background(), auth.Config{Token: "org-token"})
	if err != nil || link != "https://vk.ru/call/join/NEWROOM1" {
		t.Fatalf("create: %q %v", link, err)
	}
	// Ambiguous loss: connection reset mid-answer.
	f.api.Close()
	_, err = f.provider().CreateRoom(context.Background(), auth.Config{Token: "org-token"})
	if !errors.Is(err, ErrCreationUnknown) {
		t.Fatalf("lost response must be creation_unknown, got %v", err)
	}
}
