package wbstream

import (
	"context"

	"github.com/openlibrecommunity/olcrtc/internal/auth"
	"github.com/openlibrecommunity/olcrtc/internal/logger"
	"github.com/openlibrecommunity/olcrtc/internal/protect"
)

// Provider produces LiveKit credentials for the WB Stream service.
//
// apiBase overrides the REST endpoint the provider talks to. The zero value
// means defaultAPIURL; only tests set it.
type Provider struct {
	apiBase string
}

// Engine reports which engine consumes credentials from this auth provider.
func (Provider) Engine() string { return "livekit" }

// DefaultServiceURL returns the WB Stream service URL.
func (Provider) DefaultServiceURL() string { return defaultAPIURL }

// Issue runs the WB Stream auth flow and returns LiveKit credentials.
//
// When cfg.Token is set it is used as the WB account access token directly,
// skipping the anonymous guest-register step so the session joins as that
// account (with whatever publish rights the account holds). When cfg.Token is
// empty the provider registers a guest as before.
func (p Provider) Issue(ctx context.Context, cfg auth.Config) (auth.Credentials, error) {
	if cfg.RoomURL == "" || cfg.RoomURL == "any" {
		return auth.Credentials{}, auth.ErrRoomIDRequired
	}
	resolver := cfg.Resolver
	if resolver == nil {
		resolver = protect.NewResolver(cfg.DNSServer)
	}
	// One client for the whole flow: the three calls hit the same host, so
	// a client per request would throw away the connection every time.
	client := protect.NewHTTPClient(resolver)

	accessToken := cfg.Token
	if accessToken == "" {
		guest, err := p.registerGuest(ctx, client, cfg.Name)
		if err != nil {
			return auth.Credentials{}, err
		}
		accessToken = guest
		// Never log the token itself: it is a bearer credential for the
		// account and these logs routinely end up in bug reports.
		logger.Infof("wbstream: obtained guest access token (%d bytes), "+
			"reuse it via auth.token to keep this identity", len(accessToken))
	}

	roomID := cfg.RoomURL
	if err := p.joinRoom(ctx, client, accessToken, roomID); err != nil {
		return auth.Credentials{}, err
	}

	tok, err := p.getToken(ctx, client, accessToken, roomID, cfg.Name)
	if err != nil {
		return auth.Credentials{}, err
	}

	url := tok.ServerURL
	if url == "" {
		url = defaultWSURL
	}

	return auth.Credentials{
		URL:   url,
		Token: tok.RoomToken,
		Extra: map[string]string{"roomID": roomID},
	}, nil
}
