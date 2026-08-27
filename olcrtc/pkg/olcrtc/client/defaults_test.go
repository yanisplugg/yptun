package client_test

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"slices"
	"sync"
	"testing"

	"github.com/openlibrecommunity/olcrtc/internal/auth"
	"github.com/openlibrecommunity/olcrtc/internal/engine"
	enginebuiltin "github.com/openlibrecommunity/olcrtc/internal/engine/builtin"
	"github.com/openlibrecommunity/olcrtc/internal/transport"
	clientpkg "github.com/openlibrecommunity/olcrtc/pkg/olcrtc/client"
	"github.com/openlibrecommunity/olcrtc/pkg/olcrtc/engineconn"
	"github.com/openlibrecommunity/olcrtc/pkg/olcrtc/tunnel"
)

const defaultsHelperEnv = "OLCRTC_DEFAULTS_HELPER"

func TestPublicNewRegistersDefaults(t *testing.T) {
	if constructor := os.Getenv(defaultsHelperEnv); constructor != "" {
		runDefaultsHelper(t, constructor)
		return
	}

	for _, constructor := range []string{"client", "tunnel", "engineconn"} {
		t.Run(constructor, func(t *testing.T) {
			// #nosec G204,G702 -- the test only re-executes its own binary with a fixed argument.
			cmd := exec.Command(os.Args[0], "-test.run=^TestPublicNewRegistersDefaults$")
			cmd.Env = append(os.Environ(), defaultsHelperEnv+"="+constructor)
			if output, err := cmd.CombinedOutput(); err != nil {
				t.Fatalf("defaults helper failed: %v\n%s", err, output)
			}
		})
	}
}

func runDefaultsHelper(t *testing.T, constructor string) {
	t.Helper()
	requireRegistryNames(t, "auth providers", auth.Available(), nil)
	requireRegistryNames(t, "provider factories", enginebuiltin.Available(), nil)
	requireRegistryNames(t, "engines", engine.Available(), nil)
	requireRegistryNames(t, "transports", transport.Available(), nil)

	var wg sync.WaitGroup
	for range 16 {
		wg.Go(func() { callPublicNew(constructor) })
	}
	wg.Wait()

	requireRegistryNames(t, "auth providers", auth.Available(), []string{"jitsi", "telemost", "wbstream"})
	requireRegistryNames(t, "provider factories", enginebuiltin.Available(), []string{"jitsi", "none", "telemost", "wbstream"})
	requireRegistryNames(t, "engines", engine.Available(), []string{"goolom", "jitsi", "livekit"})
	if constructor == "engineconn" {
		requireRegistryNames(t, "transports", transport.Available(), nil)
		return
	}
	requireRegistryNames(t, "transports", transport.Available(),
		[]string{"datachannel", "seichannel", "videochannel", "vp8channel"})
}

func callPublicNew(constructor string) {
	switch constructor {
	case "client":
		clientpkg.New(clientpkg.Config{})
	case "tunnel":
		tunnel.New(tunnel.Config{})
	case "engineconn":
		_, _ = engineconn.New(context.Background(), engineconn.Config{})
	default:
		panic(fmt.Sprintf("unknown constructor %q", constructor))
	}
}

func requireRegistryNames(t *testing.T, label string, got, want []string) {
	t.Helper()
	if len(got) != len(want) {
		t.Fatalf("%s = %v, want %v", label, got, want)
	}
	for _, name := range want {
		if !slices.Contains(got, name) {
			t.Fatalf("%s = %v, missing %q", label, got, name)
		}
	}
}
