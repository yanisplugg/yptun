package engine

import (
	"context"
	"errors"
	"fmt"
	"slices"
	"sync"
	"testing"
)

var errRegistryFactory = errors.New("registry factory")

func TestRegistryConcurrentAccess(t *testing.T) {
	const count = 64
	var wg sync.WaitGroup
	for i := range count {
		name := fmt.Sprintf("engine-registry-%02d", i)
		wg.Go(func() {
			Register(name, func(context.Context, Config) (Session, error) { return nil, errRegistryFactory })
			if _, err := New(context.Background(), name, Config{}); !errors.Is(err, errRegistryFactory) {
				t.Errorf("New(%q): %v", name, err)
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
