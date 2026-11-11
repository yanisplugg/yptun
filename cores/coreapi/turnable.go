package coreapi

import (
	"fmt"
	"sync"

	turnableconfig "github.com/theairblow/turnable/pkg/config"
	turnableengine "github.com/theairblow/turnable/pkg/engine"
)

var turnableRuntime struct {
	sync.Mutex
	client *turnableengine.TurnableClient
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
