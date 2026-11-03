package builtin

import (
	"context"
	"testing"

	"github.com/openlibrecommunity/olcrtc/internal/auth"
)

// seenProvider records the config an auth provider is asked to issue with.
type seenProvider struct{ got auth.Config }

func (p *seenProvider) Engine() string            { return "seen" }
func (p *seenProvider) DefaultServiceURL() string { return "" }
func (p *seenProvider) Issue(_ context.Context, cfg auth.Config) (auth.Credentials, error) {
	p.got = cfg
	return auth.Credentials{URL: "u"}, nil
}

// ai-generated: the ICE mode a side asks for reaches the auth provider, which
// is where a room link's hint is read and a link without one takes it.
func TestResolveCredentialsHandsTheProviderTheDefaultICE(t *testing.T) {
	p := &seenProvider{}
	if _, _, _, err := resolveCredentials(context.Background(), p, Config{RoomURL: "room", DefaultICE: "tcp"}); err != nil {
		t.Fatalf("resolveCredentials: %v", err)
	}
	if p.got.DefaultICE != "tcp" || p.got.RoomURL != "room" {
		t.Fatalf("auth.Config = %+v, want RoomURL room and DefaultICE tcp", p.got)
	}
}
