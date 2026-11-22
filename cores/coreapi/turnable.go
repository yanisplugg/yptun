package coreapi

import (
	"fmt"
	"strings"
	"sync"

	turnableconfig "github.com/theairblow/turnable/pkg/config"
	turnableengine "github.com/theairblow/turnable/pkg/engine"
)

var turnableRuntime struct {
	sync.Mutex
	client *turnableengine.TurnableClient
}

// TurnableEndpoints extracts carrier/gateway endpoints from the given turnable config or URI.
func TurnableEndpoints(configText string) string {
	trimmed := strings.TrimSpace(configText)
	var hosts []string
	// Standard VK carrier hosts
	hosts = append(hosts, "calls.vk.com", "api.vk.com", "ok.ru", "vk.me")

	if strings.HasPrefix(strings.ToLower(trimmed), "turnable://") {
		// e.g. turnable://user:call@vk.com/wireguard?type=relay&gateway=1.2.3.4:56000#TurnableNode
		raw := trimmed[len("turnable://"):]
		raw = strings.SplitN(raw, "#", 2)[0]
		parts := strings.SplitN(raw, "?", 2)
		beforeQuery := parts[0]
		authority := strings.SplitN(beforeQuery, "/", 2)[0]
		if at := strings.LastIndex(authority, "@"); at >= 0 {
			authority = authority[at+1:]
		}
		host := strings.SplitN(authority, ":", 2)[0]
		if host != "" {
			hosts = append(hosts, host)
		}
		if len(parts) > 1 {
			query := parts[1]
			for _, pair := range strings.Split(query, "&") {
				kv := strings.SplitN(pair, "=", 2)
				if len(kv) == 2 && strings.EqualFold(kv[0], "gateway") {
					gw := kv[1]
					gwHost := strings.SplitN(gw, ":", 2)[0]
					if gwHost != "" {
						hosts = append(hosts, gwHost)
					}
				}
			}
		}
	} else if strings.HasPrefix(trimmed, "{") {
		// Simple JSON extraction without pulling heavy dependencies
		for _, key := range []string{`"gateway"`, `"turn_host"`, `"peer"`} {
			idx := strings.Index(trimmed, key)
			if idx >= 0 {
				rest := trimmed[idx+len(key):]
				colon := strings.Index(rest, ":")
				if colon >= 0 {
					val := strings.TrimSpace(rest[colon+1:])
					if strings.HasPrefix(val, `"`) {
						val = val[1:]
						end := strings.Index(val, `"`)
						if end >= 0 {
							endpoint := val[:end]
							h := strings.SplitN(endpoint, ":", 2)[0]
							if h != "" {
								hosts = append(hosts, h)
							}
						}
					}
				}
			}
		}
	}
	return strings.Join(hosts, ",")
}

// TurnableStart runs the GPL client in-process inside the packet-tunnel extension. The app and the
// extension still communicate only through the App Group; this runtime owns one local route socket.
func TurnableStart(configText, listenAddr string) error {
	turnableRuntime.Lock()
	defer turnableRuntime.Unlock()
	if turnableRuntime.client != nil && turnableRuntime.client.IsRunning() {
		return fmt.Errorf("turnable is already running")
	}
	parsed, err := turnableconfig.ParseClientConfig([]byte(configText))
	if err != nil {
		return fmt.Errorf("parse turnable config: %w", err)
	}
	client := turnableengine.NewTurnableClient(parsed)
	if err := client.Start([]string{listenAddr}); err != nil {
		return fmt.Errorf("start turnable: %w", err)
	}
	turnableRuntime.client = client
	PushLog("turnable", "turnable client started on "+listenAddr)
	return nil
}

func TurnableStop() {
	turnableRuntime.Lock()
	defer turnableRuntime.Unlock()
	if turnableRuntime.client != nil && turnableRuntime.client.IsRunning() {
		if err := turnableRuntime.client.Stop(); err != nil {
			PushLog("turnable", "stop failed: "+err.Error())
		}
	}
	turnableRuntime.client = nil
}

func TurnableRunning() bool {
	turnableRuntime.Lock()
	defer turnableRuntime.Unlock()
	return turnableRuntime.client != nil && turnableRuntime.client.IsRunning()
}
