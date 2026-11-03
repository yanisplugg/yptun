package jitsi

// ai-generated: the whole file (who owns which video source, which the
// remote video latch needs twice over: to skip the bridge's own probes, and
// to follow the peer when it comes back with a new source).

import (
	"encoding/json"
	"encoding/xml"
	"strconv"
	"strings"
)

// bridgeOwner is the owner Jicofo files the bridge's own sources under: the
// owner attribute of an XML source's <ssrc-info>, and the key of the JSON
// source map.
const bridgeOwner = "jvb"

// initiateSources is what a session-initiate or a source-add says about
// sources: the <source> elements of each content, or the JSON map Jicofo
// sends in their place to an endpoint that reads it.
type initiateSources struct {
	Jingle struct {
		JSON     string `xml:"json-message"` //nolint:tagliatelle // Jingle element name
		Contents []struct {
			Sources []struct {
				SSRC  string `xml:"ssrc,attr"`
				Owner struct {
					Name string `xml:"owner,attr"`
				} `xml:"ssrc-info"` //nolint:tagliatelle // Jingle element name
			} `xml:"description>source"`
		} `xml:"content"`
	} `xml:"jingle"`
}

// sourceOwners returns the endpoint each source the stanza announces belongs
// to. JVB probes an endpoint's bandwidth with padding on its own video
// source, under whichever video payload type it picks, and a probe can reach
// the endpoint before the peer's first packet does; a peer that rejoins
// comes back under a new endpoint with a new source.
func sourceOwners(stanza string) map[uint32]string {
	var initiate initiateSources
	if err := xml.Unmarshal([]byte(stanza), &initiate); err != nil {
		return nil
	}
	owners := make(map[uint32]string)
	for _, content := range initiate.Jingle.Contents {
		for _, source := range content.Sources {
			ssrc, err := strconv.ParseUint(source.SSRC, 10, 32)
			if err == nil && ssrc != 0 && source.Owner.Name != "" {
				owners[uint32(ssrc)] = endpointName(source.Owner.Name)
			}
		}
	}
	addJSONSourceOwners(owners, initiate.Jingle.JSON)
	return owners
}

// addJSONSourceOwners adds the sources of Jicofo's JSON source map: per
// owner, lists of sources ({"s": ssrc, ...}) and of SSRC groups.
func addJSONSourceOwners(owners map[uint32]string, raw string) {
	var message struct {
		Sources map[string][]json.RawMessage `json:"sources"`
	}
	if raw == "" || json.Unmarshal([]byte(raw), &message) != nil {
		return
	}
	for owner, lists := range message.Sources {
		for _, list := range lists {
			var sources []struct {
				SSRC uint32 `json:"s"`
			}
			if json.Unmarshal(list, &sources) != nil {
				continue // a list of SSRC groups
			}
			for _, source := range sources {
				if source.SSRC != 0 {
					owners[source.SSRC] = endpointName(owner)
				}
			}
		}
	}
}

// endpointName is the endpoint an owner names: Jicofo writes it bare in the
// JSON map and as a MUC JID in the XML form.
func endpointName(owner string) string {
	if at := strings.LastIndex(owner, "/"); at >= 0 {
		return owner[at+1:]
	}
	return owner
}

// noteSources records what a stanza announces. A session-initiate describes
// the whole session and replaces what was known; a source-add adds to it.
func (s *Session) noteSources(stanza string, whole bool) {
	found := sourceOwners(stanza)
	if len(found) == 0 && !whole {
		return
	}
	merged := found
	if !whole {
		merged = make(map[uint32]string, len(found))
		if known := s.sourceOwners.Load(); known != nil {
			for ssrc, owner := range *known {
				merged[ssrc] = owner
			}
		}
		for ssrc, owner := range found {
			merged[ssrc] = owner
		}
	}
	s.sourceOwners.Store(&merged)
}

// ownerOf is the endpoint a source belongs to, empty when no stanza has
// named it.
func (s *Session) ownerOf(ssrc uint32) string {
	owners := s.sourceOwners.Load()
	if owners == nil {
		return ""
	}
	return (*owners)[ssrc]
}

// isBridgeSSRC reports whether ssrc is one the bridge sends on its own
// behalf rather than one it forwards from a participant.
func (s *Session) isBridgeSSRC(ssrc uint32) bool {
	return s.ownerOf(ssrc) == bridgeOwner
}

// latchPeerVideo binds the peer's video to ssrc and reports whether this
// side should read the track. The latch is what keeps a third participant's
// video out of the carrier, so it only moves when the source holding it is
// gone: its endpoint has left the room, or it is the same endpoint coming
// back with a new source after a rejoin. Without that a peer that rejoins
// while this side stays put is drained for the rest of the session, and the
// tunnel never hears it again (#9).
func (s *Session) latchPeerVideo(ssrc uint32) bool {
	for {
		held := s.peerVideoSSRC.Load()
		switch {
		case held == ssrc:
			return true
		case held != 0 && !s.videoSourceGone(held, ssrc):
			return false
		}
		if s.peerVideoSSRC.CompareAndSwap(held, ssrc) {
			return true
		}
	}
}

// videoSourceGone reports whether the source holding the latch has been
// replaced by next: the same endpoint announced it, or the endpoint that
// owns the latched one is no longer in the room. An owner no stanza named
// keeps the latch: nothing says it is gone.
func (s *Session) videoSourceGone(held, next uint32) bool {
	owner := s.ownerOf(held)
	if owner == "" || owner == bridgeOwner {
		return owner == bridgeOwner
	}
	if owner == s.ownerOf(next) {
		return true
	}
	return !s.endpointPresent(owner)
}

// endpointPresent reports whether name is still an occupant of the room.
func (s *Session) endpointPresent(name string) bool {
	jSess := s.jSess.Load()
	if jSess == nil || jSess.Conn == nil {
		return true // nothing to say it left
	}
	for _, endpoint := range jSess.Endpoints() {
		if endpointName(endpoint) == name {
			return true
		}
	}
	return false
}
