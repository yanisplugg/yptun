package salutejazz

import (
	"context"
	"net/http"

	"github.com/openlibrecommunity/olcrtc/internal/auth"
	"github.com/openlibrecommunity/olcrtc/internal/protect"
)

// Provider produces salutejazz engine credentials for the SaluteJazz (Sber)
// service.
//
// apiBase overrides the REST endpoint the provider talks to. The zero value
// means defaultAPIBase; only tests set it.
type Provider struct {
	apiBase string
}

// New returns a SaluteJazz auth provider using the default API base.
func New() Provider { return Provider{} }

// Engine reports which engine consumes credentials from this auth provider.
func (Provider) Engine() string { return "salutejazz" }

// DefaultServiceURL returns the SaluteJazz REST API base URL.
func (Provider) DefaultServiceURL() string { return defaultAPIBase }

// newClient builds an HTTP client routed through the protected resolver for
// cfg, falling back to protect.NewResolver(cfg.DNSServer) when cfg.Resolver
// is unset - the same pattern wbstream.go's Issue uses (wbstream.go:35-41).
func (p Provider) newClient(cfg auth.Config) *http.Client {
	resolver := cfg.Resolver
	if resolver == nil {
		resolver = protect.NewResolver(cfg.DNSServer)
	}
	return protect.NewHTTPClient(resolver)
}

// Issue runs the SaluteJazz join flow for an existing room and returns
// salutejazz engine credentials.
//
// cfg.RoomURL is a "<code>:<password>" room reference, as produced by
// CreateRoom. Issue calls preconnect - the same call the official client
// makes before every join - to validate the password early and resolve the
// WS connector URL. The engine's Refresh hook re-runs Issue on reconnect, so
// whether SaluteJazz requires preconnect per join never matters here.
func (p Provider) Issue(ctx context.Context, cfg auth.Config) (auth.Credentials, error) {
	code, password, err := splitRoomRef(cfg.RoomURL)
	if err != nil {
		return auth.Credentials{}, err
	}

	client := p.newClient(cfg)

	connectorURL, err := p.preconnect(ctx, client, code, password)
	if err != nil {
		return auth.Credentials{}, err
	}
	if connectorURL == "" {
		connectorURL = defaultConnectorURL
	}

	return auth.Credentials{
		URL:   connectorURL,
		Token: password,
		Extra: map[string]string{"roomID": code},
	}, nil
}

// CreateRoom provisions a new SaluteJazz room via one anonymous HTTP call
// and returns it as a "<code>:<password>" room reference suitable for
// cfg.RoomURL on a later Issue call.
func (p Provider) CreateRoom(ctx context.Context, cfg auth.Config) (string, error) {
	client := p.newClient(cfg)

	roomID, password, err := p.createMeeting(ctx, client)
	if err != nil {
		return "", err
	}
	return roomID + ":" + password, nil
}
