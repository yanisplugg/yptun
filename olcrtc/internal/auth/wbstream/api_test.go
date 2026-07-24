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
	testAccessToken = "access"
	testRoomID      = "room"
	testToken       = "token"
	testPeerName    = "peer"
)

func withWBAPIServer(t *testing.T, h http.Handler) {
	t.Helper()
	old := apiBase
	srv := httptest.NewServer(h)
	t.Cleanup(func() {
		apiBase = old
		srv.Close()
	})
	apiBase = srv.URL
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

	withWBAPIServer(t, mux)

	access, err := registerGuest(context.Background(), testPeerName)
	if err != nil {
		t.Fatalf("registerGuest() error = %v", err)
	}
	if access != testAccessToken {
		t.Fatalf("registerGuest() = %q", access)
	}

	if err := joinRoom(context.Background(), access, testRoomID); err != nil {
		t.Fatalf("joinRoom() error = %v", err)
	}
	tok, err := getToken(context.Background(), access, testRoomID, testPeerName)
	if err != nil {
		t.Fatalf("getToken() error = %v", err)
	}
	if tok.RoomToken != testToken {
		t.Fatalf("getToken() = %q", tok.RoomToken)
	}
}

func TestWBStreamAPIErrors(t *testing.T) {
	withWBAPIServer(t, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "bad", http.StatusBadGateway)
	}))

	if _, err := registerGuest(context.Background(), testPeerName); !errors.Is(err, errGuestRegister) {
		t.Fatalf("registerGuest() error = %v, want %v", err, errGuestRegister)
	}
	if err := joinRoom(context.Background(), testAccessToken, testRoomID); !errors.Is(err, errJoinRoom) {
		t.Fatalf("joinRoom() error = %v, want %v", err, errJoinRoom)
	}
	if _, err := getToken(context.Background(), testAccessToken, testRoomID, testPeerName); !errors.Is(err, errGetToken) {
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

	withWBAPIServer(t, mux)

	p := Provider{}
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

	withWBAPIServer(t, mux)

	creds, err := Provider{}.Issue(context.Background(), auth.Config{
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

func TestWBStreamIssueSurfacesGuestToken(t *testing.T) {
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

	withWBAPIServer(t, mux)

	var buf bytes.Buffer
	old := log.Writer()
	log.SetOutput(&buf)
	logger.SetVerbose(false)
	t.Cleanup(func() { log.SetOutput(old) })

	_, err := Provider{}.Issue(context.Background(), auth.Config{
		RoomURL: testRoomID,
		Name:    testPeerName,
	})
	if err != nil {
		t.Fatalf("Issue() error = %v", err)
	}
	if !strings.Contains(buf.String(), testAccessToken) {
		t.Fatalf("guest access token not surfaced in logs: %q", buf.String())
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
