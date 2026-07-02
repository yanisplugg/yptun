package vkauth

import (
	"context"
	"fmt"
	"strings"

	"github.com/samosvalishe/free-turn-proxy/internal/provider/vk/internal/browserprofile"
	"github.com/samosvalishe/free-turn-proxy/internal/provider/vk/internal/captcha"

	tlsclient "github.com/bogdanfinn/tls-client"
)

func (c *Client) defaultAutoSolve(
	ctx context.Context,
	captchaErr *captcha.Error,
	streamID int,
	client tlsclient.HttpClient,
	profile browserprofile.Profile,
) (string, error) {
	log := captcha.Log
	log.Infof("[STREAM %d] [Captcha] Solving captcha...", streamID)

	if captchaErr.SessionToken == "" {
		return "", fmt.Errorf("no session_token in redirect_uri for auto-solve")
	}
	if captchaErr.RedirectURI == "" {
		return "", fmt.Errorf("no redirect_uri for auto-solve")
	}

	var savedProfile *browserprofile.Saved
	switch sp, err := browserprofile.Load(); {
	case err == nil && savedProfileUsable(*sp):
		savedFamily := browserprofile.Family(sp.Profile)
		log.Infof("[STREAM %d] [Captcha] Using captured browser profile (authority) family=%s mobile=%t ua=%q device=%t browser_fp=%t",
			streamID, savedFamily, browserprofile.IsMobile(sp.Profile), sp.UserAgent, sp.DeviceJSON != "", sp.BrowserFp != "")
		savedProfile = sp
		profile = sp.Profile
		if savedFamily != "" && normalizeFamily(savedFamily) != normalizeFamily(c.browser) {
			if rebuilt, rebuildErr := c.newTLSClientForKind(savedFamily, tlsclient.NewCookieJar()); rebuildErr == nil {
				client = rebuilt
				log.Infof("[STREAM %d] [Captcha] Rebuilt TLS client to match captured family=%s", streamID, savedFamily)
			} else {
				log.Warnf("[STREAM %d] [Captcha] Failed to rebuild TLS for captured family=%s: %v; keeping -browser TLS", streamID, savedFamily, rebuildErr)
			}
		}
	case err == nil:
		log.Warnf("[STREAM %d] [Captcha] Captured profile unusable (empty UA); using -browser fallback", streamID)
	default:
		log.Debugf("[STREAM %d] [Captcha] No captured browser profile: %v", streamID, err)
	}

	successToken, err := captcha.Solve(ctx, captchaErr, streamID, client, profile, savedProfile, log)
	if err != nil {
		return "", err
	}
	log.Infof("[STREAM %d] [Captcha] solver succeeded", streamID)
	return successToken, nil
}

func savedProfileUsable(saved browserprofile.Saved) bool {
	return strings.TrimSpace(saved.UserAgent) != ""
}

func normalizeFamily(k browserprofile.Kind) browserprofile.Kind {
	switch k {
	case browserprofile.Firefox, browserprofile.Safari:
		return k
	default:
		return browserprofile.Chrome
	}
}
