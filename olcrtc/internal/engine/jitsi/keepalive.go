package jitsi

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/pion/webrtc/v4"
	"github.com/pion/webrtc/v4/pkg/media"

	"github.com/openlibrecommunity/olcrtc/internal/logger"
)

const (
	xmppKeepaliveInterval = 25 * time.Second
	xmppKeepaliveTimeout  = 15 * time.Second
)

// vp8Keepalive is a minimal valid VP8 keyframe used to refresh JVB's
// lastRtpReceived timestamp on otherwise idle byte-stream sessions.
var vp8Keepalive = []byte{ //nolint:gochecknoglobals // wire protocol constant
	0x30, 0x01, 0x00, 0x9d, 0x01, 0x2a, 0x10, 0x00,
	0x10, 0x00, 0x00, 0x47, 0x08, 0x85, 0x85, 0x88,
	0x99, 0x84, 0x88, 0xfc,
}

// rtpKeepalive sends genuine RTP because empty RTCP reports do not refresh
// JVB endpoint activity on TURN/SCTP paths.
func (s *Session) rtpKeepalive(pcCtx context.Context, track *webrtc.TrackLocalStaticSample) {
	const interval = time.Second
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	sample := media.Sample{Data: vp8Keepalive, Duration: interval}
	for {
		select {
		case <-s.done:
			return
		case <-pcCtx.Done():
			return
		case <-ticker.C:
			if pcCtx.Err() != nil {
				return
			}
			if err := track.WriteSample(sample); err != nil {
				if s.closed.Load() || pcCtx.Err() != nil {
					return
				}
				// Sender binding can lag behind negotiation while DTLS starts.
				logger.Debugf("jitsi: rtp keepalive write: %v", err)
			}
		}
	}
}

// bridgeKeepalive sends an empty epoch frame over either colibri-ws or SCTP.
func (s *Session) bridgeKeepalive() {
	const interval = 10 * time.Second
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-s.done:
			return
		case <-ticker.C:
			if !s.bridgeReady.Load() {
				continue
			}
			jSess := s.jSess.Load()
			if jSess == nil {
				continue
			}
			frame, err := s.encodeBridgeFrame(nil, "")
			if err != nil {
				continue
			}
			if err := jSess.BridgeSendRaw("", frame); err != nil {
				logger.Debugf("jitsi: bridge keepalive send: %v", err)
			}
		}
	}
}

// xmppKeepalive keeps BOSH and websocket sessions alive while waiting for a
// peer. It survives session replacement and targets the bound XMPP domain.
func (s *Session) xmppKeepalive() {
	ticker := time.NewTicker(xmppKeepaliveInterval)
	defer ticker.Stop()
	var lastReconnectRequestErr string
	for {
		select {
		case <-s.done:
			return
		case <-ticker.C:
			jSess := s.jSess.Load()
			if jSess == nil {
				continue
			}
			conn := jSess.LowLevel()
			if conn == nil {
				continue
			}
			id := conn.NextID()
			ping := fmt.Sprintf(
				`<iq type="get" to=%q id=%q xmlns="jabber:client"><ping xmlns="urn:xmpp:ping"/></iq>`,
				xmppDomain(conn.JID(), conn.Host()), id,
			)
			if _, err := conn.SendIQWait(ping, id, xmppKeepaliveTimeout); err != nil {
				if s.closed.Load() {
					return
				}
				logger.Debugf("jitsi: xmpp keepalive: %v", err)
				if reason := err.Error(); reason != lastReconnectRequestErr {
					s.requestReconnect("xmpp keepalive: " + reason)
					lastReconnectRequestErr = reason
				}
				continue
			}
			lastReconnectRequestErr = ""
		}
	}
}

func xmppDomain(jid, fallback string) string {
	_, rest, ok := strings.Cut(jid, "@")
	if !ok || rest == "" {
		return fallback
	}
	if domain, _, found := strings.Cut(rest, "/"); found {
		rest = domain
	}
	if rest == "" {
		return fallback
	}
	return rest
}
