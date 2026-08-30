package telemost

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// newTelemostProvider returns a Provider pointed at a test server.
func newTelemostProvider(t *testing.T, h http.Handler) Provider {
	t.Helper()
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)
	return Provider{apiBase: srv.URL}
}

func TestGetConnectionInfo(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /conferences/{id...}", func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasPrefix(r.URL.Path, "/conferences/room/id/connection") {
			t.Fatalf("path = %q", r.URL.Path)
		}
		if r.URL.Query().Get("display_name") != "peer" {
			t.Fatalf("display_name query = %q", r.URL.Query().Get("display_name"))
		}
		_ = json.NewEncoder(w).Encode(ConnectionInfo{
			RoomID:      "room",
			PeerID:      "peer-id",
			Credentials: "creds",
		})
	})

	p := newTelemostProvider(t, mux)

	info, err := p.connectionInfo(context.Background(), http.DefaultClient, "room/id", "peer")
	if err != nil {
		t.Fatalf("connectionInfo() error = %v", err)
	}
	if info.RoomID != "room" || info.PeerID != "peer-id" || info.Credentials != "creds" {
		t.Fatalf("connectionInfo() = %+v", info)
	}
}

func TestGetConnectionInfoErrors(t *testing.T) {
	p := newTelemostProvider(t, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "bad", http.StatusForbidden)
	}))
	if _, err := p.connectionInfo(context.Background(), http.DefaultClient, "room", "peer"); !errors.Is(err, ErrAPI) {
		t.Fatalf("connectionInfo() error = %v, want %v", err, ErrAPI)
	}

	p = newTelemostProvider(t, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("{"))
	}))
	if _, err := p.connectionInfo(context.Background(), http.DefaultClient, "room", "peer"); err == nil {
		t.Fatal("connectionInfo() unexpectedly accepted bad json")
	}
}
