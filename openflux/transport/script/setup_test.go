package script

import (
	"crypto/ed25519"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"
)

// wizardScript serves a page of its own from setup() (what values it was given
// are in the page) and raises it by address; open() must never run in a setup
// session.
const wizardScript = `
var Transport = {
  info: function () { return { name: "wizard-demo", version: "1.0.0" }; },
  setup: function (values) {
    var srv = httpserver.listen(function (req) {
      if (req.path === "/") {
        return { headers: { "Content-Type": "text/html" }, body: "<p>hello " + (values.who || "nobody") + "</p>" };
      }
      return { status: 404, body: "no route" };
    });
    this._srv = srv;
    raise("needsSetup", { url: "http://" + srv.addr + "/", reason: "Мастер" });
  },
  open: function (cfg) { raise("openRan", {}); },
  write: function (bytes) {},
  close: function () { if (this._srv) this._srv.close(); },
};
`

func TestStartSetupServesTheWizardPageAndCloseEndsIt(t *testing.T) {
	path, pub := mustSignedScript(t, wizardScript)
	s, err := StartSetup(path, pub, map[string]string{"who": "world"})
	if err != nil {
		t.Fatalf("StartSetup: %v", err)
	}
	defer s.Close()
	page, err := s.Page(3 * time.Second)
	if err != nil {
		t.Fatalf("Page: %v", err)
	}
	if page.Reason != "Мастер" || !strings.HasPrefix(page.URL, "http://127.0.0.1:") {
		t.Fatalf("page = %+v", page)
	}
	resp, err := http.Get(page.URL)
	if err != nil {
		t.Fatalf("GET %s: %v", page.URL, err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if !strings.Contains(string(body), "hello world") {
		t.Fatalf("page body = %q, setup() did not get its values", body)
	}

	s.Close()
	s.Close() // twice is fine
	client := &http.Client{Timeout: time.Second}
	if r, err := client.Get(page.URL); err == nil {
		r.Body.Close()
		t.Fatal("the wizard's server still answers after Close")
	}
}

func TestStartSetupDoesNotRunOpen(t *testing.T) {
	path, pub := mustSignedScript(t, wizardScript)
	s, err := StartSetup(path, pub, nil)
	if err != nil {
		t.Fatalf("StartSetup: %v", err)
	}
	defer s.Close()
	opened := make(chan struct{}, 1)
	s.t.SetEventHandler(func(kind string, payload map[string]interface{}) {
		if kind == "openRan" {
			opened <- struct{}{}
		}
	})
	if _, err := s.Page(3 * time.Second); err != nil {
		t.Fatalf("Page: %v", err)
	}
	select {
	case <-opened:
		t.Fatal("open() ran in a setup session")
	case <-time.After(200 * time.Millisecond):
	}
}

func TestStartSetupNeedsASetupFunction(t *testing.T) {
	path, pub := mustSignedScript(t, echoScript)
	if s, err := StartSetup(path, pub, nil); err == nil {
		s.Close()
		t.Fatal("a script with no setup() started a setup session")
	} else if !strings.Contains(err.Error(), "setup") {
		t.Fatalf("error = %v, want one that names setup", err)
	}
}

func TestStartSetupKeepsTheSignatureGate(t *testing.T) {
	path, _ := mustSignedScript(t, wizardScript)
	other, _, _ := ed25519.GenerateKey(nil)
	if s, err := StartSetup(path, other, nil); err == nil {
		s.Close()
		t.Fatal("a setup session started under a key that did not sign the script")
	}
}

func TestStartSetupReportsASetupThatThrows(t *testing.T) {
	path, pub := mustSignedScript(t, `
var Transport = {
  info: function () { return { name: "throws", version: "1.0.0" }; },
  setup: function () { throw new Error("boom"); },
  write: function () {}, close: function () {},
};`)
	s, err := StartSetup(path, pub, nil)
	if err == nil {
		s.Close()
		t.Fatal("a setup() that throws started a session")
	}
	if !strings.Contains(err.Error(), "boom") {
		t.Fatalf("error = %v, want the script's own message", err)
	}
}

func TestSetupPageFailsFastWhenAnAsyncSetupRejects(t *testing.T) {
	path, pub := mustSignedScript(t, `
var Transport = {
  info: function () { return { name: "async-fail", version: "1.0.0" }; },
  setup: function () { return new Promise(function (resolve, reject) { reject(new Error("no network")); }); },
  write: function () {}, close: function () {},
};`)
	s, err := StartSetup(path, pub, nil)
	if err != nil {
		t.Fatalf("StartSetup: %v", err)
	}
	defer s.Close()
	start := time.Now()
	_, err = s.Page(5 * time.Second)
	if err == nil || !strings.Contains(err.Error(), "no network") {
		t.Fatalf("Page error = %v, want the rejection's reason", err)
	}
	if time.Since(start) > 2*time.Second {
		t.Fatalf("Page took %v: it waited out the timeout instead of failing fast", time.Since(start))
	}
}

func TestSetupPageTimesOutWhenTheWizardNeverRaisesOne(t *testing.T) {
	path, pub := mustSignedScript(t, `
var Transport = {
  info: function () { return { name: "silent", version: "1.0.0" }; },
  setup: function () {},
  write: function () {}, close: function () {},
};`)
	s, err := StartSetup(path, pub, nil)
	if err != nil {
		t.Fatalf("StartSetup: %v", err)
	}
	defer s.Close()
	if _, err := s.Page(300 * time.Millisecond); err == nil {
		t.Fatal("Page returned a page the wizard never raised")
	}
}

func TestInspectSeesASetupWizard(t *testing.T) {
	info, err := Inspect([]byte(wizardScript))
	if err != nil {
		t.Fatalf("Inspect: %v", err)
	}
	if !info.SetupWizard {
		t.Error("a script with Transport.setup is not reported as a setup wizard")
	}
	plain, err := Inspect([]byte(echoScript))
	if err != nil {
		t.Fatalf("Inspect: %v", err)
	}
	if plain.SetupWizard {
		t.Error("a script with no Transport.setup is reported as a setup wizard")
	}
	rep := InspectTrust([]byte(wizardScript), nil, "", "")
	if !rep.OK || !rep.SetupPage {
		t.Errorf("trust report = %+v, want setupPage", rep)
	}
}
