package vkauth

import (
	"context"
	"fmt"
	neturl "net/url"

	"github.com/samosvalishe/free-turn-proxy/internal/provider/vk/internal/namegen"

	tlsclient "github.com/bogdanfinn/tls-client"
)

func (c *Client) getTokenChain(ctx context.Context, link string, streamID int, creds VKCredentials, jar tlsclient.CookieJar) (string, string, []string, error) {
	profile := c.currentPersona()

	httpClient, err := c.newTLSClient(profile, jar)
	if err != nil {
		return "", "", nil, fmt.Errorf("failed to initialize tls_client: %w", err)
	}

	name := namegen.Generate()
	escapedName := neturl.QueryEscape(name)

	c.log.Debugf("[STREAM %d] [VK Auth] Connecting Identity - Name: %s | User-Agent: %s", streamID, name, profile.UserAgent)

	if pageErr := c.openJoinPage(ctx, httpClient, profile, link); pageErr != nil && ctx.Err() == nil {
		c.log.Warnf("[STREAM %d] [VK Auth] join page warm-up failed: %v", streamID, pageErr)
	}

	if delayErr := vkDelayRandom(ctx, 300, 600); delayErr != nil {
		return "", "", nil, delayErr
	}

	token1, err := c.fetchAnonToken(ctx, httpClient, profile, creds)
	if err != nil {
		return "", "", nil, err
	}

	if delayErr := vkDelayRandom(ctx, 100, 150); delayErr != nil {
		return "", "", nil, delayErr
	}

	previewData := fmt.Sprintf("vk_join_link=https://vk.ru/call/join/%s&fields=photo_200&access_token=%s", link, token1)
	if _, prevErr := c.doRequest(ctx, httpClient, profile, previewData,
		"https://api.vk.ru/method/calls.getCallPreview?v="+APIVersion+"&client_id="+creds.ClientID); prevErr != nil {
		c.log.Warnf("[STREAM %d] [VK Auth] getCallPreview failed: %v", streamID, prevErr)
	}

	if delayErr := vkDelayRandom(ctx, 200, 400); delayErr != nil {
		return "", "", nil, delayErr
	}

	token2, err := c.fetchCallToken(ctx, httpClient, profile, streamID, link, escapedName, token1, creds)
	if err != nil {
		return "", "", nil, err
	}

	if delayErr := vkDelayRandom(ctx, 100, 150); delayErr != nil {
		return "", "", nil, delayErr
	}

	sessionKey, err := c.fetchOkRuSession(ctx, httpClient, profile)
	if err != nil {
		return "", "", nil, err
	}

	if delayErr := vkDelayRandom(ctx, 100, 150); delayErr != nil {
		return "", "", nil, delayErr
	}

	return c.fetchTurnCreds(ctx, httpClient, profile, streamID, link, token2, sessionKey)
}
