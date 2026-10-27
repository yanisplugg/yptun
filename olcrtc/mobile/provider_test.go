package mobile

import (
	"errors"
	"slices"
	"testing"

	enginebuiltin "github.com/openlibrecommunity/olcrtc/internal/engine/builtin"
	"github.com/openlibrecommunity/olcrtc/pkg/olcrtc/client"
)

// TestEveryProviderThisBuildRegistersCanBeSelected pins the allowlist the
// phone side goes through. SetProvider is the only way an app names a
// provider, and its list was written out by hand: a provider registered in
// the binary but missing from it cannot be selected at all, however well the
// engine behind it works. This file carries no build tag, so it holds for the
// lean bind too - the tag gates the videochannel transport and no provider.
func TestEveryProviderThisBuildRegistersCanBeSelected(t *testing.T) {
	client.RegisterDefaults()
	available := enginebuiltin.Available()
	if !slices.Contains(available, "salutejazz") {
		t.Fatalf("this build registers %v, and salutejazz is not among them", available)
	}
	runtime := New()
	for _, provider := range available {
		if !supportedProvider(provider) {
			t.Errorf("supportedProvider(%q) = false for a provider this build registers", provider)
		}
		if err := runtime.SetProvider(provider); err != nil {
			t.Errorf("SetProvider(%q) error = %v", provider, err)
		}
	}
	if err := runtime.SetProvider("nosuchservice"); !errors.Is(err, ErrUnsupportedProvider) {
		t.Fatalf("SetProvider(nosuchservice) error = %v, want %v", err, ErrUnsupportedProvider)
	}
}
