package jitsi

import (
	"context"
	"encoding/xml"
	"fmt"
	"strings"

	"github.com/pion/webrtc/v4"

	"github.com/openlibrecommunity/olcrtc/internal/logger"
)

// negotiator is the importable subset of j's internal peer.Negotiator.
type negotiator interface {
	HandleSourceAdd(stanza string) error
}

func (s *Session) trickleDrainLoop(
	ctx context.Context,
	pc *webrtc.PeerConnection,
	neg negotiator,
	stanzas <-chan string,
) {
	for {
		select {
		case <-ctx.Done():
			return
		case <-s.done:
			return
		case raw, ok := <-stanzas:
			if !ok {
				return
			}
			switch {
			case strings.Contains(raw, "transport-info"):
				if err := s.applyTrickleICE(pc, raw); err != nil {
					logger.Debugf("jitsi trickle ICE: %v", err)
				}
			case strings.Contains(raw, "source-add"):
				if err := neg.HandleSourceAdd(raw); err != nil {
					logger.Debugf("jitsi source-add: %v", err)
				}
			}
		}
	}
}

type xmlCandidate struct {
	Component  string `xml:"component,attr"`
	Foundation string `xml:"foundation,attr"`
	Generation string `xml:"generation,attr"`
	IP         string `xml:"ip,attr"`
	Port       string `xml:"port,attr"`
	Priority   string `xml:"priority,attr"`
	Protocol   string `xml:"protocol,attr"`
	Type       string `xml:"type,attr"`
	RelAddr    string `xml:"rel-addr,attr"` //nolint:tagliatelle // XMPP uses hyphenated attributes
	RelPort    string `xml:"rel-port,attr"` //nolint:tagliatelle // XMPP uses hyphenated attributes
}

type xmlTransportInfo struct {
	XMLName xml.Name `xml:"iq"`
	Jingle  struct {
		Action   string `xml:"action,attr"`
		Contents []struct {
			Name      string `xml:"name,attr"`
			Transport struct {
				Candidates []xmlCandidate `xml:"candidate"`
			} `xml:"transport"`
		} `xml:"content"`
	} `xml:"jingle"`
}

func (s *Session) applyTrickleICE(pc *webrtc.PeerConnection, raw string) error {
	var ti xmlTransportInfo
	if err := xml.Unmarshal([]byte(raw), &ti); err != nil {
		return fmt.Errorf("parse transport-info: %w", err)
	}
	for _, content := range ti.Jingle.Contents {
		mid := content.Name
		for _, candidate := range content.Transport.Candidates {
			sdpLine := buildSDPCandidate(candidate)
			if sdpLine == "" {
				continue
			}
			init := webrtc.ICECandidateInit{Candidate: sdpLine, SDPMid: &mid}
			if err := pc.AddICECandidate(init); err != nil {
				logger.Debugf("jitsi add ICE candidate (%s): %v", mid, err)
			}
		}
	}
	return nil
}

func buildSDPCandidate(candidate xmlCandidate) string {
	if candidate.IP == "" || candidate.Port == "" {
		return ""
	}
	component := candidate.Component
	if component == "" {
		component = "1"
	}
	protocol := strings.ToLower(candidate.Protocol)
	if protocol == "" {
		protocol = "udp"
	}
	priority := candidate.Priority
	if priority == "" {
		priority = "1"
	}
	candidateType := candidate.Type
	if candidateType == "" {
		candidateType = "host"
	}
	sdp := fmt.Sprintf("candidate:%s %s %s %s %s %s typ %s",
		candidate.Foundation, component, protocol, priority, candidate.IP, candidate.Port, candidateType)
	if candidate.RelAddr != "" && candidate.RelPort != "" {
		sdp += fmt.Sprintf(" raddr %s rport %s", candidate.RelAddr, candidate.RelPort)
	}
	if candidate.Generation != "" {
		sdp += " generation " + candidate.Generation
	}
	return sdp
}
