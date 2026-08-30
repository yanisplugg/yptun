package session

import (
	"context"
	"fmt"
	"slices"
	"strings"
	"time"

	"github.com/openlibrecommunity/olcrtc/internal/auth"
	enginebuiltin "github.com/openlibrecommunity/olcrtc/internal/engine/builtin"
	"github.com/openlibrecommunity/olcrtc/internal/names"
	"github.com/openlibrecommunity/olcrtc/internal/tunnelcore"
)

// ValidateGen validates that the config contains enough fields to run gen mode.
func ValidateGen(cfg Config) error {
	if cfg.Provider == "" {
		return ErrProviderRequired
	}
	if !slices.Contains(enginebuiltin.Available(), cfg.Provider) {
		return fmt.Errorf("%w: %s (available: %v)", ErrUnsupportedProvider, cfg.Provider, enginebuiltin.Available())
	}
	if cfg.DNSServer == "" && cfg.Resolver == nil {
		return ErrDNSServerRequired
	}
	if cfg.Amount < 1 {
		return ErrAmountRequired
	}
	provider, err := auth.Get(cfg.Provider)
	if err != nil {
		return fmt.Errorf("%w: %s", ErrUnsupportedProvider, cfg.Provider)
	}
	if _, ok := provider.(auth.RoomCreator); !ok {
		return errNoRoomCreation(cfg.Provider)
	}
	return nil
}

func errNoRoomCreation(name string) error {
	creators := auth.RoomCreators()
	if len(creators) == 0 {
		return fmt.Errorf(
			"%w: %s does not support room generation, and no registered provider does "+
				"(pass an existing room with -url instead of -mode gen)",
			ErrUnsupportedProvider, name)
	}
	return fmt.Errorf(
		"%w: %s does not support room generation (providers that do: %s)",
		ErrUnsupportedProvider, name, strings.Join(creators, ", "))
}

const (
	genMaxAttempts = 5
	genRetryDelay  = 2 * time.Second
)

func genRetry(ctx context.Context, fn func(context.Context) error) error {
	var lastErr error
	for attempt := range genMaxAttempts {
		lastErr = fn(ctx)
		if lastErr == nil {
			return nil
		}
		if attempt >= genMaxAttempts-1 {
			continue
		}
		select {
		case <-ctx.Done():
			return fmt.Errorf("context canceled: %w", ctx.Err())
		case <-time.After(genRetryDelay):
		}
	}
	return lastErr
}

// Gen creates cfg.Amount rooms and writes each room ID to out.
func Gen(ctx context.Context, cfg Config, out func(string)) error {
	cfg.Resolver = tunnelcore.Resolver(cfg.Resolver, cfg.DNSServer)
	provider, err := auth.Get(cfg.Provider)
	if err != nil {
		return fmt.Errorf("%w: %s", ErrUnsupportedProvider, cfg.Provider)
	}
	creator, ok := provider.(auth.RoomCreator)
	if !ok {
		return errNoRoomCreation(cfg.Provider)
	}
	for i := range cfg.Amount {
		var roomID string
		err := genRetry(ctx, func(ctx context.Context) error {
			var createErr error
			roomID, createErr = creator.CreateRoom(ctx, auth.Config{
				Name: names.Generate(), DNSServer: cfg.DNSServer, Resolver: cfg.Resolver,
			})
			if createErr != nil {
				return fmt.Errorf("CreateRoom: %w", createErr)
			}
			return nil
		})
		if err != nil {
			return fmt.Errorf("gen room %d: %w", i+1, err)
		}
		out(roomID)
	}
	return nil
}
