package mobile

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

const setupDemoScript = `
var Transport = {
  info: function () { return { name: "setup-demo", version: "1.0.0" }; },
  setup: function (values) {
    var srv = httpserver.listen(function (req) {
      return { headers: { "Content-Type": "text/html" }, body: "<p>hello " + (values.who || "nobody") + "</p>" };
    });
    this._srv = srv;
    raise("needsSetup", { url: "http://" + srv.addr + "/", reason: "Мастер" });
  },
  write: function (bytes) {},
  close: function () { if (this._srv) this._srv.close(); },
};
`

func signedSetupScript(t *testing.T) (path, pubHex string) {
	t.Helper()
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	path = filepath.Join(t.TempDir(), "setup-demo.js")
	if err := os.WriteFile(path, []byte(setupDemoScript), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path+".sig", ed25519.Sign(priv, []byte(setupDemoScript)), 0o644); err != nil {
		t.Fatal(err)
	}
	return path, hex.EncodeToString(pub)
}

func setupReply(t *testing.T, raw string) scriptSetupReply {
	t.Helper()
	var r scriptSetupReply
	if err := json.Unmarshal([]byte(raw), &r); err != nil {
		t.Fatalf("reply %q is not JSON: %v", raw, err)
	}
	return r
}

func alive(url string) bool {
	c := &http.Client{Timeout: time.Second}
	r, err := c.Get(url)
	if err != nil {
		return false
	}
	r.Body.Close()
	return true
}

func TestScriptSetupStartShowsThePageAndStopEndsIt(t *testing.T) {
	path, pub := signedSetupScript(t)
	defer ScriptSetupStop()
	r := setupReply(t, ScriptSetupStart(path, pub, `{"who":"phone"}`))
	if !r.OK || r.Reason != "Мастер" || !strings.HasPrefix(r.URL, "http://127.0.0.1:") {
		t.Fatalf("reply = %+v", r)
	}
	resp, err := http.Get(r.URL)
	if err != nil {
		t.Fatalf("GET: %v", err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if !strings.Contains(string(body), "hello phone") {
		t.Fatalf("body = %q", body)
	}
	ScriptSetupStop()
	if alive(r.URL) {
		t.Fatal("the wizard's server still answers after ScriptSetupStop")
	}
	ScriptSetupStop() // none runs: harmless
}

func TestScriptSetupStartEndsThePreviousWizard(t *testing.T) {
	path, pub := signedSetupScript(t)
	defer ScriptSetupStop()
	first := setupReply(t, ScriptSetupStart(path, pub, ""))
	second := setupReply(t, ScriptSetupStart(path, pub, ""))
	if !first.OK || !second.OK || first.URL == second.URL {
		t.Fatalf("first %+v second %+v", first, second)
	}
	if alive(first.URL) {
		t.Fatal("the first wizard survived the second start")
	}
	if !alive(second.URL) {
		t.Fatal("the second wizard is not up")
	}
}

func TestScriptSetupStartRefusesWhatItCannotRun(t *testing.T) {
	path, pub := signedSetupScript(t)
	defer ScriptSetupStop()
	cases := map[string][3]string{
		"bad values": {path, pub, "[1,2]"},
		"bad key":    {path, "zz", ""},
		"wrong key":  {path, strings.Repeat("ab", 32), ""},
		"no file":    {filepath.Join(t.TempDir(), "gone.js"), pub, ""},
	}
	for name, c := range cases {
		r := setupReply(t, ScriptSetupStart(c[0], c[1], c[2]))
		if r.OK || r.Error == "" {
			t.Errorf("%s: reply = %+v, want an error", name, r)
		}
	}
}
