package mtslink

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"net/http"
	"net/url"

	"github.com/openlibrecommunity/olcrtc/internal/auth"
	"github.com/openlibrecommunity/olcrtc/internal/protect"
	"github.com/openlibrecommunity/olcrtc/internal/roomhint"
)

// defaultNickname is what a guest is called when the caller names none; the
// service refuses an empty one (400 ERROR_BAD_REQUEST).
const defaultNickname = "Гость"

// Provider produces mtslink engine credentials for the MTS Link service.
//
// gateway overrides the API the provider talks to; empty means the measured
// production one. Only tests set it.
type Provider struct {
	gateway string
}

// New returns an MTS Link auth provider against the production gateway.
func New() Provider { return Provider{} }

// Engine reports which engine consumes credentials from this auth provider.
func (Provider) Engine() string { return "mtslink" }

// DefaultServiceURL returns the MTS Link service URL.
func (Provider) DefaultServiceURL() string { return webOrigin }

func (Provider) newClient(cfg auth.Config) *http.Client {
	resolver := cfg.Resolver
	if resolver == nil {
		resolver = protect.NewResolver(cfg.DNSServer)
	}
	return protect.NewHTTPClient(resolver)
}

// Issue walks a guest into the room and returns what the mtslink engine joins
// with:
//
//	URL   - the SFU room join (the offer goes in, the answer comes out), with
//	        everything that names the guest; the engine adds what describes
//	        its offer.
//	Token - the guest's cookies, for the gateway only: the SFU reads none.
//	Extra - gateway, sessionId, userId and conferenceId, for the engine to
//	        take its conference back with after a join that failed.
//
// cfg.RoomURL is the event join link the platform stores. Every call makes a
// new guest: the cookies of one are of no use to the next process, and a
// guest that is gone leaves nothing behind in the room.
func (p Provider) Issue(ctx context.Context, cfg auth.Config) (auth.Credentials, error) {
	room, ice := roomhint.Split(cfg.RoomURL)
	if ice == roomhint.ICEDefault {
		// ai-generated: a link that says nothing takes what its side asks for; the
		// client side asks for TCP, which holds where UDP to the SFU goes dark (#60).
		ice = roomhint.Mode(cfg.DefaultICE)
	}
	event, session, err := parseEventLink(room)
	if err != nil {
		return auth.Credentials{}, err
	}
	gateway := p.gateway
	if gateway == "" {
		gateway = defaultGateway
	}
	client := p.newClient(cfg)
	defer client.CloseIdleConnections()

	if session == "" {
		if session, err = eventSession(ctx, client, gateway, event); err != nil {
			return auth.Credentials{}, err
		}
	}
	nickname := cfg.Name
	if nickname == "" {
		nickname = defaultNickname
	}
	g, err := guestLogin(ctx, client, gateway, session, nickname)
	if err != nil {
		return auth.Credentials{}, err
	}
	server, err := mediaServer(ctx, client, gateway, session, g)
	if err != nil {
		return auth.Credentials{}, err
	}
	conf, err := newConference(ctx, client, gateway, session, g)
	if err != nil {
		return auth.Credentials{}, err
	}
	token, err := joinToken(ctx, client, gateway, session, g)
	if err != nil {
		// The conference is made and nothing will join it: take it back
		// rather than leave it in the room's list for good.
		_ = leave(ctx, client, gateway, conf.id, g.cookies)
		return auth.Credentials{}, err
	}

	extra := map[string]string{
		"gateway": gateway, "sessionId": session, "userId": g.userID, "conferenceId": conf.id,
	}
	if ice != roomhint.ICEDefault {
		// ai-generated: the link's ICE hint, for the engine (roomhint).
		extra["ice"] = ice
	}
	return auth.Credentials{
		URL:   roomJoinURL(server, session, g.userID, nickname, token, conf.publishToken),
		Token: g.cookies,
		Extra: extra,
	}, nil
}

// roomJoinURL is the SFU join the web client issues for a meeting, as far as
// it names the guest (measured 2026-10-11):
//
//	POST https://{rtcServer}/rtc/room/{sessionId}/join
//	     ?userId=&joinToken=&disableRecording=true&userName=&publishToken=&did=&pid=
//
// did and pid are the web client's device and page ids, sixteen hex digits
// each, the second the first turned by one digit. What describes the offer -
// twcc, originMid, preferredPublishCodec - is the engine's to add.
func roomJoinURL(server, session, userID, nickname, joinToken, publishToken string) string {
	var raw [8]byte
	_, _ = rand.Read(raw[:])
	did := hex.EncodeToString(raw[:])
	q := url.Values{}
	q.Set("userId", userID)
	q.Set("joinToken", joinToken)
	q.Set("disableRecording", yes)
	q.Set("userName", nickname)
	q.Set("publishToken", publishToken)
	q.Set("did", did)
	q.Set("pid", did[1:]+did[:1])
	return "https://" + server + "/rtc/room/" + session + "/join?" + q.Encode()
}
