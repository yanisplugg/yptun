package auth

import (
	"context"
	"fmt"
	"slices"
	"sync"
	"testing"
)

type registryProvider struct{}

func (registryProvider) Engine() string            { return "test" }
func (registryProvider) DefaultServiceURL() string { return "" }
func (registryProvider) Issue(context.Context, Config) (Credentials, error) {
	return Credentials{}, nil
}

func TestRegistryConcurrentAccess(t *testing.T) {
	const count = 64
	var wg sync.WaitGroup
	for i := range count {
		name := fmt.Sprintf("auth-registry-%02d", i)
		wg.Go(func() {
			Register(name, registryProvider{})
			if _, err := Get(name); err != nil {
				t.Errorf("Get(%q): %v", name, err)
			}
			_ = Available()
		})
	}
	wg.Wait()

	available := Available()
	if !slices.IsSorted(available) {
		t.Fatalf("Available() is not sorted: %v", available)
	}
}
