package script

import (
	"crypto/ed25519"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/dop251/goja"

	"github.com/p1neappleXpress/OpenFlux/transport"
)

// A script's setup wizard (Transport.setup) is a page of its own that needs the
// real host: sockets, http and an httpserver of its own, to sign in to a
// service and check what it was given. Transport.settings(values) cannot be
// that: it runs in the inspector, with no network. An app runs the wizard
// before any connection exists (a node is being made, no transport is running
// yet) with StartSetup: the script runs in a ScriptTransport that never
// connects, raise("needsSetup", {url}) names the page, and the app shows it
// like any own setup page. What the page hands to window.openfluxSubmit is the
// app's to read ({client, node}: FlattenSubmissionScoped); the session only has
// to live as long as the page does, and Close ends it, httpserver included.
//
// The contract a script follows:
//
//	Transport.setup(values)  // values: what is known so far (strings), may be {}
//	  httpserver.listen(...); raise("needsSetup", {url: "http://" + srv.addr + "/", reason: "..."})
//
// Inspect reports it as Info.SetupWizard, the apps as the script's setupPage.

// runSetup is Transport.setup(values) inside the transport's own loop, the way
// open() runs. A promise it returns is watched: a rejection ends the session
// as dead, with the reason, rather than leaving the app waiting for a page.
func (t *ScriptTransport) runSetup(vm *goja.Runtime, obj *goja.Object) error {
	fn, ok := goja.AssertFunction(obj.Get("setup"))
	if !ok {
		return fmt.Errorf("Transport.setup is not a function")
	}
	arg := vm.NewObject()
	for k, v := range t.setupValues {
		_ = arg.Set(k, v)
	}
	res, err := fn(obj, arg)
	if err != nil {
		return fmt.Errorf("Transport.setup(): %w", err)
	}
	if o, ok := res.(*goja.Object); ok && o != nil {
		if then, ok := goja.AssertFunction(o.Get("then")); ok {
			onRejected := vm.ToValue(func(call goja.FunctionCall) goja.Value {
				t.setLastState("dead", "Transport.setup(): "+call.Argument(0).String())
				return goja.Undefined()
			})
			_, _ = then(o, goja.Undefined(), onRejected)
		}
	}
	return nil
}

// SetupPage is a page the wizard asked the app to show: one of URL (the
// script's own server on 127.0.0.1) or HTML (an inline page), with the
// dialog's title.
type SetupPage struct {
	URL    string `json:"url,omitempty"`
	HTML   string `json:"html,omitempty"`
	Reason string `json:"reason,omitempty"`
}

// SetupSession is a script's setup wizard while it runs.
type SetupSession struct {
	t     *ScriptTransport
	pages chan SetupPage
	once  sync.Once
}

// StartSetup runs the setup wizard of the signed script (or .flux) at
// scriptPath, which must verify against pubKey like any transport, and returns
// at once; Page waits for the page it raises. values go to Transport.setup.
func StartSetup(scriptPath string, pubKey ed25519.PublicKey, values map[string]string) (*SetupSession, error) {
	t, err := New("setup", scriptPath, pubKey, "", nil, transport.DefaultConfig())
	if err != nil {
		return nil, err
	}
	if values == nil {
		values = map[string]string{}
	}
	t.setupValues = values
	s := &SetupSession{t: t, pages: make(chan SetupPage, 4)}
	t.SetEventHandler(func(kind string, payload map[string]interface{}) {
		if kind != "needsSetup" {
			return
		}
		str := func(k string) string { v, _ := payload[k].(string); return v }
		select {
		case s.pages <- SetupPage{URL: str("url"), HTML: str("html"), Reason: str("reason")}:
		default:
		}
	})
	if err := t.Start(); err != nil {
		_ = t.Stop()
		return nil, err
	}
	return s, nil
}

// Page waits up to timeout for the wizard's page: the first
// raise("needsSetup", ...). It fails sooner when the script's setup() promise
// was rejected.
func (s *SetupSession) Page(timeout time.Duration) (SetupPage, error) {
	deadline := time.NewTimer(timeout)
	defer deadline.Stop()
	tick := time.NewTicker(50 * time.Millisecond)
	defer tick.Stop()
	for {
		select {
		case p := <-s.pages:
			return p, nil
		case <-deadline.C:
			return SetupPage{}, errors.New("мастер настройки не показал страницу")
		case <-tick.C:
			if state, msg := s.t.LastState(); state == "dead" && msg != "" {
				return SetupPage{}, errors.New(msg)
			}
		}
	}
}

// Close ends the session: the script's close() runs, its httpserver and
// sockets are closed. Safe to call more than once.
func (s *SetupSession) Close() {
	s.once.Do(func() { _ = s.t.Stop() })
}
