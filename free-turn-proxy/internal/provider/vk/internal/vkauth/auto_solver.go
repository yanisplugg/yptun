package vkauth

import (
	"context"
	"fmt"
	"net"
	"time"

	"github.com/samosvalishe/free-turn-proxy/internal/provider/vk/internal/browserprofile"
	"github.com/samosvalishe/free-turn-proxy/internal/provider/vk/internal/captcha"
	"github.com/samosvalishe/free-turn-proxy/internal/provider/vk/internal/captcha/manual"

	tlsclient "github.com/bogdanfinn/tls-client"
)

const browserPowCaptureTimeout = 30 * time.Second

func (c *Client) defaultAutoSolve(
	ctx context.Context,
	captchaErr *captcha.Error,
	streamID int,
	dialer net.Dialer,
	client tlsclient.HttpClient,
	profile browserprofile.Profile,
) (string, error) {
	log := captcha.Log()
	log.Infof("[STREAM %d] [Captcha] Solving captcha (platform=%s)...", streamID, profile.Platform)

	if captchaErr.SessionToken == "" {
		return "", fmt.Errorf("no session_token in redirect_uri for auto-solve")
	}
	if captchaErr.RedirectURI == "" {
		return "", fmt.Errorf("no redirect_uri for auto-solve")
	}

	if !c.advancedCaptcha {
		return c.solveLegacyCaptcha(ctx, captchaErr, streamID, client, profile)
	}

	log.Infof("[STREAM %d] [Captcha] Capturing current browser PoW envelope...", streamID)
	capturePow := c.powCapture
	if capturePow == nil {
		capturePow = manual.CapturePowViaProxy
	}
	captureCtx, cancelCapture := context.WithTimeout(ctx, browserPowCaptureTimeout)
	browserPow, err := capturePow(captureCtx, captchaErr.RedirectURI, dialer, profile)
	cancelCapture()
	if err != nil {
		log.Warnf("[STREAM %d] [Captcha] Browser PoW unavailable (%v); falling back to legacy automatic solver", streamID, err)
		return c.solveLegacyCaptcha(ctx, captchaErr, streamID, client, profile)
	}
	successToken, err := captcha.SolveWithBrowserPow(ctx, captchaErr, streamID, client, profile, log, browserPow)
	if err != nil {
		if ctx.Err() != nil {
			return "", ctx.Err()
		}
		log.Warnf("[STREAM %d] [Captcha] Browser PoW solve failed (%v); falling back to legacy automatic solver", streamID, err)
		return c.solveLegacyCaptcha(ctx, captchaErr, streamID, client, profile)
	}
	log.Infof("[STREAM %d] [Captcha] solver succeeded", streamID)
	return successToken, nil
}

func (*Client) solveLegacyCaptcha(
	ctx context.Context,
	captchaErr *captcha.Error,
	streamID int,
	client tlsclient.HttpClient,
	profile browserprofile.Profile,
) (string, error) {
	log := captcha.Log()
	log.Infof("[STREAM %d] [Captcha] Using legacy automatic solver", streamID)
	successToken, err := captcha.Solve(ctx, captchaErr, streamID, client, profile, log)
	if err != nil {
		return "", err
	}
	log.Infof("[STREAM %d] [Captcha] solver succeeded", streamID)
	return successToken, nil
}
