package coreapi

import (
	"fmt"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/enfein/mieru/v3/pkg/appctl"
	"github.com/enfein/mieru/v3/pkg/appctl/appctlcommon"
	"github.com/enfein/mieru/v3/pkg/appctl/appctlpb"
	"github.com/enfein/mieru/v3/pkg/protocol"
	"github.com/enfein/mieru/v3/pkg/socks5"
)

var mieruRuntime struct {
	sync.Mutex
	mux     *protocol.Mux
	server  *socks5.Server
	running atomic.Bool
}

func mieruProfile(link string) (*appctlpb.ClientProfile, error) {
	trimmed := strings.TrimSpace(link)
	if strings.HasPrefix(strings.ToLower(trimmed), "mierus://") {
		return appctl.URLToClientProfile(trimmed)
	}
	if strings.HasPrefix(strings.ToLower(trimmed), "mieru://") {
		// A display fragment is not part of the upstream base64 protobuf payload.
		config, err := appctl.URLToClientConfig(strings.SplitN(trimmed, "#", 2)[0])
		if err != nil {
			return nil, err
		}
		return appctl.GetActiveProfileFromConfig(config, config.GetActiveProfile())
	}
	return nil, fmt.Errorf("expected mieru:// or mierus:// link")
}

// MieruStart exposes an imported Mieru profile as a loopback SOCKS5 listener without launching a
// subprocess (which is prohibited in an Apple packet-tunnel extension).
func MieruStart(link, listenAddr string) error {
	mieruRuntime.Lock()
	defer mieruRuntime.Unlock()
	if mieruRuntime.mux != nil || mieruRuntime.running.Load() {
		return fmt.Errorf("mieru is already running")
	}
	profile, err := mieruProfile(link)
	if err != nil {
		return fmt.Errorf("parse mieru profile: %w", err)
	}
	mux, err := appctlcommon.NewClientMuxFromProfile(profile, nil, nil, nil, nil)
	if err != nil {
		return fmt.Errorf("create mieru mux: %w", err)
	}
	server, err := socks5.New(&socks5.Config{
		UseProxy: true,
		AuthOpts: socks5.Auth{
			ClientSideAuthentication: true,
		},
		ProxyDialer:      mux,
		HandshakeTimeout: 10 * time.Second,
		HandshakeNoWait:  profile.GetHandshakeMode() == appctlpb.HandshakeMode_HANDSHAKE_NO_WAIT,
	})
	if err != nil {
		mux.Close()
		return fmt.Errorf("create mieru SOCKS5 server: %w", err)
	}
	listener, err := net.Listen("tcp", listenAddr)
	if err != nil {
		mux.Close()
		return fmt.Errorf("listen for mieru SOCKS5: %w", err)
	}
	mieruRuntime.mux = mux
	mieruRuntime.server = server
	mieruRuntime.running.Store(true)
	go func() {
		err := server.Serve(listener)
		mieruRuntime.Lock()
		isCurrent := mieruRuntime.server == server
		if isCurrent {
			mieruRuntime.running.Store(false)
		}
		mieruRuntime.Unlock()
		if err != nil && isCurrent {
			PushLog("mieru", "SOCKS5 server stopped: "+err.Error())
		}
	}()
	PushLog("mieru", "mieru client SOCKS5 ready on "+listenAddr)
	return nil
}

func MieruStop() {
	mieruRuntime.Lock()
	defer mieruRuntime.Unlock()
	mieruRuntime.running.Store(false)
	if mieruRuntime.server != nil {
		_ = mieruRuntime.server.Close()
	}
	if mieruRuntime.mux != nil {
		mieruRuntime.mux.Close()
	}
	mieruRuntime.server = nil
	mieruRuntime.mux = nil
}

func MieruRunning() bool {
	return mieruRuntime.running.Load()
}
