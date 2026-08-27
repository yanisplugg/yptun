package wbstream

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/openlibrecommunity/olcrtc/internal/auth"
	"github.com/openlibrecommunity/olcrtc/internal/logger"
)

const (
	testAccessToken = "s3cret-guest-token"
	testRoomID      = "room"
	testToken       = "token"
	testPeerName    = "peer"
)

// newWBProvider returns a Provider pointed at a test server.
func newWBProvider(t *testing.T, h http.Handler) Provider {
	t.Helper()
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)
	return Provider{apiBase: srv.URL}
}

func TestWBStreamAPIHappyPath(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /auth/api/v1/auth/user/guest-register", func(w http.ResponseWriter, _ *http.Request) {
		_ = json.NewEncoder(w).Encode(guestRegisterResponse{AccessToken: testAccessToken}) //nolint:gosec
	})
	mux.HandleFunc("POST /api-room/api/v1/room/"+testRoomID+"/join", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	mux.HandleFunc("GET /api-room-manager/v2/room/"+testRoomID+"/connection-details",
		func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Query().Get("displayName") != testPeerName {
				t.Fatalf("displayName query = %q", r.URL.Query().Get("displayName"))
			}
			_ = json.NewEncoder(w).Encode(tokenResponse{RoomToken: testToken})
		})

	p := newWBProvider(t, mux)
	client := http.DefaultClient

	access, err := p.registerGuest(context.Background(), client, testPeerName)
	if err != nil {
		t.Fatalf("registerGuest() error = %v", err)
	}
	if access != testAccessToken {
		t.Fatalf("registerGuest() = %q", access)
	}

	if joinErr := p.joinRoom(context.Background(), client, access, testRoomID); joinErr != nil {
		t.Fatalf("joinRoom() error = %v", joinErr)
	}
	tok, err := p.getToken(context.Background(), client, access, testRoomID, testPeerName)
	if err != nil {
		t.Fatalf("getToken() error = %v", err)
	}
	if tok.RoomToken != testToken {
		t.Fatalf("getToken() = %q", tok.RoomToken)
	}
}

func TestWBStreamAPIErrors(t *testing.T) {
	p := newWBProvider(t, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "bad", http.StatusBadGateway)
	}))
	client := http.DefaultClient

	if _, err := p.registerGuest(context.Background(), client, testPeerName); !errors.Is(err, errGuestRegister) {
		t.Fatalf("registerGuest() error = %v, want %v", err, errGuestRegister)
	}
	if err := p.joinRoom(context.Background(), client, testAccessToken, testRoomID); !errors.Is(err, errJoinRoom) {
		t.Fatalf("joinRoom() error = %v, want %v", err, errJoinRoom)
	}
	_, err := p.getToken(context.Background(), client, testAccessToken, testRoomID, testPeerName)
	if !errors.Is(err, errGetToken) {
		t.Fatalf("getToken() error = %v, want %v", err, errGetToken)
	}
}

func TestWBStreamIssue(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /auth/api/v1/auth/user/guest-register", func(w http.ResponseWriter, _ *http.Request) {
		_ = json.NewEncoder(w).Encode(guestRegisterResponse{AccessToken: testAccessToken}) //nolint:gosec
	})
	mux.HandleFunc("POST /api-room/api/v1/room/{id}/join", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	mux.HandleFunc("GET /api-room-manager/v2/room/{id}/connection-details", func(w http.ResponseWriter, _ *http.Request) {
		_ = json.NewEncoder(w).Encode(tokenResponse{RoomToken: testToken})
	})

	p := newWBProvider(t, mux)
	creds, err := p.Issue(context.Background(), auth.Config{
		RoomURL: testRoomID,
		Name:    testPeerName,
	})
	if err != nil {
		t.Fatalf("Issue() error = %v", err)
	}
	if creds.Token != testToken {
		t.Fatalf("creds.Token = %q", creds.Token)
	}
	if creds.Extra["roomID"] != testRoomID {
		t.Fatalf("creds.Extra[roomID] = %q", creds.Extra["roomID"])
	}
}

func TestWBStreamIssueUsesSuppliedToken(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /auth/api/v1/auth/user/guest-register", func(_ http.ResponseWriter, _ *http.Request) {
		t.Error("guest-register must not be called when a token is supplied")
	})
	mux.HandleFunc("POST /api-room/api/v1/room/{id}/join", func(w http.ResponseWriter, r *http.Request) {
		if got := r.Header.Get("Authorization"); got != "Bearer "+testAccessToken {
			t.Fatalf("join Authorization = %q", got)
		}
		w.WriteHeader(http.StatusOK)
	})
	mux.HandleFunc("GET /api-room-manager/v2/room/{id}/connection-details",
		func(w http.ResponseWriter, r *http.Request) {
			if got := r.Header.Get("Authorization"); got != "Bearer "+testAccessToken {
				t.Fatalf("connection-details Authorization = %q", got)
			}
			_ = json.NewEncoder(w).Encode(tokenResponse{RoomToken: testToken})
		})

	creds, err := newWBProvider(t, mux).Issue(context.Background(), auth.Config{
		RoomURL: testRoomID,
		Name:    testPeerName,
		Token:   testAccessToken,
	})
	if err != nil {
		t.Fatalf("Issue() error = %v", err)
	}
	if creds.Token != testToken {
		t.Fatalf("creds.Token = %q", creds.Token)
	}
}

func TestWBStreamIssueNeverLogsGuestToken(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /auth/api/v1/auth/user/guest-register", func(w http.ResponseWriter, _ *http.Request) {
		_ = json.NewEncoder(w).Encode(guestRegisterResponse{AccessToken: testAccessToken}) //nolint:gosec
	})
	mux.HandleFunc("POST /api-room/api/v1/room/{id}/join", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	mux.HandleFunc("GET /api-room-manager/v2/room/{id}/connection-details", func(w http.ResponseWriter, _ *http.Request) {
		_ = json.NewEncoder(w).Encode(tokenResponse{RoomToken: testToken})
	})

	p := newWBProvider(t, mux)

	var buf bytes.Buffer
	old := log.Writer()
	log.SetOutput(&buf)
	logger.SetVerbose(false)
	t.Cleanup(func() { log.SetOutput(old) })

	_, err := p.Issue(context.Background(), auth.Config{
		RoomURL: testRoomID,
		Name:    testPeerName,
	})
	if err != nil {
		t.Fatalf("Issue() error = %v", err)
	}
	if strings.Contains(buf.String(), testAccessToken) {
		t.Fatalf("guest access token leaked into logs: %q", buf.String())
	}
	if !strings.Contains(buf.String(), "obtained guest access token") {
		t.Fatalf("guest token acquisition not reported: %q", buf.String())
	}
}

func TestWBStreamIssueRequiresRoom(t *testing.T) {
	p := Provider{}
	for _, roomURL := range []string{"", "any"} {
		_, err := p.Issue(context.Background(), auth.Config{RoomURL: roomURL, Name: testPeerName})
		if !errors.Is(err, auth.ErrRoomIDRequired) {
			t.Fatalf("Issue(RoomURL=%q) error = %v, want %v", roomURL, err, auth.ErrRoomIDRequired)
		}
	}
}
