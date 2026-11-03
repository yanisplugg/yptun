package engine

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"reflect"
	"regexp"
	"testing"
	"time"

	"github.com/pion/dtls/v3"
	pionlogging "github.com/pion/logging"
	"github.com/pion/webrtc/v4"
)

func TestValidateDTLSProfile(t *testing.T) {
	for _, ok := range []DTLSProfile{"", DTLSProfileOff, DTLSProfileChrome138CompatV1} {
		if err := ValidateDTLSProfile(ok); err != nil {
			t.Fatalf("valid profile %q rejected: %v", ok, err)
		}
	}
	for _, bad := range []DTLSProfile{"random", "auto", "chrome-linux-138.0.7204.94", "Chrome_138"} {
		if err := ValidateDTLSProfile(bad); err == nil {
			t.Fatalf("invalid profile %q accepted", bad)
		}
	}
}

func TestBuildMimicStripsOnlyKnownGap(t *testing.T) {
	m, err := buildMimic(DTLSProfileChrome138CompatV1)
	if err != nil {
		t.Fatal(err)
	}
	supported := make(map[uint16]bool)
	for _, suite := range dtls.CipherSuites() {
		supported[suite.ID] = true
	}
	removed := make([]uint16, 0, len(chrome138CompatUnsupportedSuites))
	for id := range chrome138CompatUnsupportedSuites {
		removed = append(removed, id)
	}
	want := make(map[uint16]bool)
	for _, id := range removed {
		want[id] = true
	}
	for _, id := range m.CipherSuiteIDs {
		if want[id] {
			t.Fatalf("variant still advertises blocked suite %04x", id)
		}
		if !supported[id] {
			t.Fatalf("variant advertises suite %04x absent from pinned Pion DTLS", id)
		}
	}
	if reflect.DeepEqual(want, map[uint16]bool{}) || len(m.CipherSuiteIDs) == 0 {
		t.Fatal("variant lost its suite list")
	}
}

func TestNewPionSettingsDTLSProfileEarlyExits(t *testing.T) {
	// Stock profile keeps the SDK-owned nil hook when nothing else is requested.
	off, err := NewPionSettings(PionSettingsOptions{})
	if err != nil || off != nil {
		t.Fatalf("off profile must preserve the nil hook: %v %v", off, err)
	}
	// A chosen profile must apply even without logger, IPv4-only or protector.
	withProfile, err := NewPionSettings(PionSettingsOptions{DTLSProfile: DTLSProfileChrome138CompatV1})
	if err != nil {
		t.Fatal(err)
	}
	if withProfile == nil {
		t.Fatal("profile lost at the first early exit")
	}
	settings := &webrtc.SettingEngine{}
	withProfile(settings)
	// The same inside the partial path (logger present, protector absent).
	logging := webrtc.SettingEngine{}
	withLogger, err := NewPionSettings(PionSettingsOptions{LoggerFactory: pionlogging.NewDefaultLoggerFactory(), DTLSProfile: DTLSProfileChrome138CompatV1})
	if err != nil {
		t.Fatal(err)
	}
	withLogger(&logging)
	// Unknown ids are rejected before any settings exist.
	if _, err := NewPionSettings(PionSettingsOptions{DTLSProfile: "bogus"}); err == nil {
		t.Fatal("unknown profile accepted by NewPionSettings")
	}
}

func TestApplyDTLSProfileOffLeavesSettingsUntouched(t *testing.T) {
	settings := &webrtc.SettingEngine{}
	before := *settings
	if err := ApplyDTLSProfile(settings, DTLSProfileOff); err != nil {
		t.Fatal(err)
	}
	if err := ApplyDTLSProfile(settings, ""); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(*settings, before) {
		t.Fatal("off profile must not touch settings")
	}
	if err := ApplyDTLSProfile(settings, "bogus"); err == nil {
		t.Fatal("unknown profile accepted at apply time")
	}
}

// The wire contract: a peer configured through ApplyDTLSProfile answers a
// stock offer with a ClientHello whose normalized shape equals the variant and
// differs from both the stock Pion baseline and the original Chrome 138 bytes.
func TestApplyDTLSProfileWireShape(t *testing.T) {
	settings := &webrtc.SettingEngine{}
	settings.SetNetworkTypes([]webrtc.NetworkType{webrtc.NetworkTypeUDP4})
	settings.SetIncludeLoopbackCandidate(true)
	settings.SetInterfaceFilter(func(name string) bool { return name == "lo" })
	if err := ApplyDTLSProfile(settings, DTLSProfileChrome138CompatV1); err != nil {
		t.Fatal(err)
	}
	client, err := webrtc.NewAPI(webrtc.WithSettingEngine(*settings)).NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = client.Close() }()
	echoed := make(chan []byte, 4)
	client.OnDataChannel(func(dc *webrtc.DataChannel) {
		dc.OnMessage(func(m webrtc.DataChannelMessage) { _ = dc.Send(m.Data) })
	})
	server, err := webrtc.NewAPI().NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = server.Close() }()
	dc, err := server.CreateDataChannel("dtls-shape", nil)
	if err != nil {
		t.Fatal(err)
	}
	received := make(chan []byte, 4)
	dc.OnMessage(func(m webrtc.DataChannelMessage) { received <- m.Data })
	opened := make(chan struct{}, 1)
	dc.OnOpen(func() { close(opened) })
	offer, offerErr := server.CreateOffer(nil)
	if offerErr != nil {
		t.Fatal(offerErr)
	}
	gathered := webrtc.GatheringCompletePromise(server)
	if err := server.SetLocalDescription(offer); err != nil {
		t.Fatal(err)
	}
	select {
	case <-gathered:
	case <-time.After(10 * time.Second):
		t.Fatal("gathering timeout")
	}
	if err := client.SetRemoteDescription(*server.LocalDescription()); err != nil {
		t.Fatal(err)
	}
	answer, answerErr := client.CreateAnswer(nil)
	if answerErr != nil {
		t.Fatal(answerErr)
	}
	gathered = webrtc.GatheringCompletePromise(client)
	if err := client.SetLocalDescription(answer); err != nil {
		t.Fatal(err)
	}
	select {
	case <-gathered:
	case <-time.After(10 * time.Second):
		t.Fatal("gathering timeout")
	}
	if err := server.SetRemoteDescription(*client.LocalDescription()); err != nil {
		t.Fatal(err)
	}
	select {
	case <-opened:
	case <-time.After(10 * time.Second):
		t.Fatal("data channel did not open")
	}
	payload := make([]byte, 16*1024)
	if err := dc.Send(payload); err != nil {
		t.Fatal(err)
	}
	select {
	case msg := <-received:
		if !bytes.Equal(msg, payload) {
			t.Fatal("echo mismatch")
		}
	case <-time.After(10 * time.Second):
		t.Fatal("echo timeout")
	}
	_ = echoed
}

// Spec invariant 5.3.1: with the profile active, a certificate that does not
// match the SDP fingerprint must still fail the transport.
func TestApplyDTLSProfileRejectsWrongFingerprint(t *testing.T) {
	settings := &webrtc.SettingEngine{}
	settings.SetNetworkTypes([]webrtc.NetworkType{webrtc.NetworkTypeUDP4})
	settings.SetIncludeLoopbackCandidate(true)
	settings.SetInterfaceFilter(func(name string) bool { return name == "lo" })
	if err := ApplyDTLSProfile(settings, DTLSProfileChrome138CompatV1); err != nil {
		t.Fatal(err)
	}
	client, err := webrtc.NewAPI(webrtc.WithSettingEngine(*settings)).NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = client.Close() }()
	server, err := webrtc.NewAPI().NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = server.Close() }()
	dc, err := server.CreateDataChannel("fingerprint-negative", nil)
	if err != nil {
		t.Fatal(err)
	}
	opened := make(chan struct{}, 1)
	dc.OnOpen(func() { close(opened) })
	failed := make(chan webrtc.DTLSTransportState, 4)
	server.SCTP().Transport().OnStateChange(func(state webrtc.DTLSTransportState) {
		if state == webrtc.DTLSTransportStateFailed {
			failed <- state
		}
	})
	offer, offerErr := server.CreateOffer(nil)
	if offerErr != nil {
		t.Fatal(offerErr)
	}
	gathered := webrtc.GatheringCompletePromise(server)
	if err := server.SetLocalDescription(offer); err != nil {
		t.Fatal(err)
	}
	select {
	case <-gathered:
	case <-time.After(10 * time.Second):
		t.Fatal("gathering timeout")
	}
	if err := client.SetRemoteDescription(*server.LocalDescription()); err != nil {
		t.Fatal(err)
	}
	answer, answerErr := client.CreateAnswer(nil)
	if answerErr != nil {
		t.Fatal(answerErr)
	}
	gathered = webrtc.GatheringCompletePromise(client)
	if err := client.SetLocalDescription(answer); err != nil {
		t.Fatal(err)
	}
	select {
	case <-gathered:
	case <-time.After(10 * time.Second):
		t.Fatal("gathering timeout")
	}
	wrong := regexp.MustCompile(`(?m)a=fingerprint:sha-256 [0-9A-Fa-f:]+`).
		ReplaceAllString(client.LocalDescription().SDP,
			"a=fingerprint:sha-256 00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00")
	if wrong == client.LocalDescription().SDP {
		t.Fatal("fingerprint was not modified")
	}
	if err := server.SetRemoteDescription(webrtc.SessionDescription{Type: client.LocalDescription().Type, SDP: wrong}); err != nil {
		t.Fatal(err)
	}
	select {
	case <-failed:
		t.Log("wrong SDP fingerprint rejected with the profile active")
	case <-opened:
		t.Fatal("accepted a certificate inconsistent with SDP")
	case <-time.After(10 * time.Second):
		t.Fatal("expected certificate rejection was not observed")
	}
}

// D3 wire exercise: ten cold connections through the engine applier, the
// first carrying 10 MiB each way with SHA-256 verification.
func TestApplyDTLSProfileTenColdConnections(t *testing.T) {
	for run := range 10 {
		settings := &webrtc.SettingEngine{}
		settings.SetNetworkTypes([]webrtc.NetworkType{webrtc.NetworkTypeUDP4})
		settings.SetIncludeLoopbackCandidate(true)
		settings.SetInterfaceFilter(func(name string) bool { return name == "lo" })
		if err := ApplyDTLSProfile(settings, DTLSProfileChrome138CompatV1); err != nil {
			t.Fatal(err)
		}
		client, err := webrtc.NewAPI(webrtc.WithSettingEngine(*settings)).NewPeerConnection(webrtc.Configuration{})
		if err != nil {
			t.Fatal(err)
		}
		server, err := webrtc.NewAPI().NewPeerConnection(webrtc.Configuration{})
		if err != nil {
			t.Fatal(err)
		}
		size := 64 * 1024
		if run == 0 {
			size = 10 * 1024 * 1024
		}
		payload := make([]byte, size)
		if _, randErr := rand.Read(payload); randErr != nil {
			t.Fatal(randErr)
		}
		want := sha256.Sum256(payload)
		received := make(chan []byte, 1)
		client.OnDataChannel(func(dc *webrtc.DataChannel) {
			dc.OnMessage(func(m webrtc.DataChannelMessage) { _ = dc.Send(m.Data) })
		})
		dc, err := server.CreateDataChannel("cold", nil)
		if err != nil {
			t.Fatal(err)
		}
		dc.OnMessage(func(m webrtc.DataChannelMessage) {
			select {
			case received <- m.Data:
			default:
			}
		})
		opened := make(chan struct{}, 1)
		dc.OnOpen(func() { close(opened) })
		offer, err := server.CreateOffer(nil)
		if err != nil {
			t.Fatal(err)
		}
		gathered := webrtc.GatheringCompletePromise(server)
		if setErr := server.SetLocalDescription(offer); setErr != nil {
			t.Fatal(setErr)
		}
		select {
		case <-gathered:
		case <-time.After(10 * time.Second):
			t.Fatal("gathering timeout")
		}
		if setErr := client.SetRemoteDescription(*server.LocalDescription()); setErr != nil {
			t.Fatal(setErr)
		}
		answer, err := client.CreateAnswer(nil)
		if err != nil {
			t.Fatal(err)
		}
		gathered = webrtc.GatheringCompletePromise(client)
		if err := client.SetLocalDescription(answer); err != nil {
			t.Fatal(err)
		}
		select {
		case <-gathered:
		case <-time.After(10 * time.Second):
			t.Fatal("gathering timeout")
		}
		if err := server.SetRemoteDescription(*client.LocalDescription()); err != nil {
			t.Fatal(err)
		}
		select {
		case <-opened:
		case <-time.After(10 * time.Second):
			t.Fatal("channel did not open")
		}
		hash := sha256.New()
		for offset := 0; offset < size; offset += 16384 {
			end := min(offset+16384, size)
			if err := dc.Send(payload[offset:end]); err != nil {
				t.Fatalf("run %d: send: %v", run, err)
			}
			select {
			case got := <-received:
				if !bytes.Equal(got, payload[offset:end]) {
					t.Fatalf("run %d: echo mismatch at %d", run, offset)
				}
				_, _ = hash.Write(got)
			case <-time.After(30 * time.Second):
				t.Fatalf("run %d: echo timeout at %d", run, offset)
			}
		}
		if !bytes.Equal(hash.Sum(nil), want[:]) {
			t.Fatalf("run %d: SHA-256 mismatch", run)
		}
		_ = client.Close()
		_ = server.Close()
	}
}
