package engine

import (
	"testing"

	"github.com/pion/interceptor"
	"github.com/pion/interceptor/pkg/nack"
	"github.com/pion/webrtc/v4"

	"github.com/openlibrecommunity/olcrtc/internal/hostprofile"
)

// The NACK responder keeps a copy of every packet it sent, in a full-MTU
// buffer each; pion's 1024 of them were 2 MB at the phone's upload peak. The
// constrained profile asks for a size nack accepts and a registry that still
// builds a peer connection.
func TestConstrainedProfileShrinksTheNackResponder(t *testing.T) {
	t.Cleanup(hostprofile.ResetForTest)
	hostprofile.ResetForTest()
	if opts := DefaultInterceptorOptions(); opts != nil {
		t.Fatalf("the server profile passed %d interceptor options, want pion's defaults", len(opts))
	}

	hostprofile.UseConstrainedBuffers()
	opts := DefaultInterceptorOptions()
	if len(opts) == 0 {
		t.Fatal("the constrained profile passed no interceptor options")
	}
	factory, err := nack.NewResponderInterceptor(nack.ResponderSize(constrainedNackResponderSize))
	if err != nil {
		t.Fatalf("NewResponderInterceptor: %v", err)
	}
	// The size is validated when the interceptor is built, not when the
	// option is made.
	if _, err = factory.NewInterceptor(""); err != nil {
		t.Fatalf("nack refuses a responder of %d packets: %v", constrainedNackResponderSize, err)
	}

	mediaEngine := &webrtc.MediaEngine{}
	if err = mediaEngine.RegisterDefaultCodecs(); err != nil {
		t.Fatalf("RegisterDefaultCodecs: %v", err)
	}
	registry := &interceptor.Registry{}
	if err = webrtc.RegisterDefaultInterceptorsWithOptions(mediaEngine, registry, opts...); err != nil {
		t.Fatalf("RegisterDefaultInterceptorsWithOptions: %v", err)
	}
	api := webrtc.NewAPI(webrtc.WithMediaEngine(mediaEngine), webrtc.WithInterceptorRegistry(registry))
	pc, err := api.NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		t.Fatalf("NewPeerConnection: %v", err)
	}
	if err := pc.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
}
