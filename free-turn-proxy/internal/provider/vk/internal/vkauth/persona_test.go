package vkauth

import (
	"context"
	"errors"
	"net"
	"strings"
	"testing"

	"github.com/samosvalishe/free-turn-proxy/internal/provider/vk/internal/browserprofile"
	"github.com/samosvalishe/free-turn-proxy/internal/provider/vk/internal/captcha"

	tlsclient "github.com/bogdanfinn/tls-client"
)

func testCaptchaErr() *captcha.Error {
	return &captcha.Error{SessionToken: "sess", RedirectURI: "https://vk.ru/captcha"}
}

func TestPersonaStableUntilBurned(t *testing.T) {
	c := New(Config{FingerprintSeed: "install-1"})
	first, again := c.currentPersona(), c.currentPersona()
	if first != again {
		t.Fatal("persona not stable")
	}
	same := New(Config{FingerprintSeed: "install-1"})
	if same.currentPersona() != c.currentPersona() {
		t.Fatal("same seed produced different persona")
	}
	other := New(Config{FingerprintSeed: "install-2"})
	if other.currentPersona().VisitorID == c.currentPersona().VisitorID {
		t.Fatal("different seeds share visitorId")
	}
}

func TestEmptySeedIsRandomPerProcess(t *testing.T) {
	a := New(Config{}).currentPersona()
	b := New(Config{}).currentPersona()
	if a.VisitorID == b.VisitorID {
		t.Fatal("empty seed shares visitorId across processes")
	}
}

func TestBurnPersonaAdvancesIdentity(t *testing.T) {
	c := New(Config{FingerprintSeed: "install-1"})
	before := c.currentPersona()
	c.burnPersona(1)
	if c.currentPersona().VisitorID == before.VisitorID {
		t.Fatal("burn kept the rejected visitorId")
	}
}

func TestAutoFailureBurnsPersonaAndRestartsChain(t *testing.T) {
	c := newTestClient(t, nil)
	c.autoSolver = func(context.Context, *captcha.Error, int, tlsclient.HttpClient, browserprofile.Profile) (string, error) {
		return "", errors.New("auto rejected")
	}
	var manualPersona browserprofile.Profile
	c.manualSolve = func(_ context.Context, _ *captcha.Error, _ net.Dialer, p browserprofile.Profile) (string, error) {
		manualPersona = p
		return "manual-token", nil
	}

	burned := c.currentPersona()
	_, err := c.solveCaptcha(context.Background(), nil, burned, 1, "lnk", "name", "t1", testCaptchaErr())
	if !errors.Is(err, ErrPersonaBurned) {
		t.Fatalf("auto failure err = %v, want ErrPersonaBurned", err)
	}
	fresh := c.currentPersona()
	if fresh.VisitorID == burned.VisitorID {
		t.Fatal("auto failure kept the rejected persona")
	}

	data, err := c.solveCaptcha(context.Background(), nil, fresh, 1, "lnk", "name", "t1", testCaptchaErr())
	if err != nil {
		t.Fatalf("manual attempt: %v", err)
	}
	if !strings.Contains(data, "success_token=manual-token") {
		t.Fatalf("retry data = %q", data)
	}
	if manualPersona.VisitorID != fresh.VisitorID {
		t.Fatal("manual ran with a persona other than the current one")
	}
}

func TestExhaustedModesBurnPersonaAndLock(t *testing.T) {
	c := newTestClient(t, nil)
	c.captchaAttempt = 2

	burned := c.currentPersona()
	_, err := c.solveCaptcha(context.Background(), nil, burned, 1, "lnk", "name", "t1", testCaptchaErr())
	if !errors.Is(err, ErrCaptchaWaitRequired) {
		t.Fatalf("err = %v, want ErrCaptchaWaitRequired", err)
	}
	if c.currentPersona().VisitorID == burned.VisitorID {
		t.Fatal("exhausted modes kept the rejected persona")
	}
	if c.LockoutUntilUnix() == 0 {
		t.Fatal("lockout not engaged")
	}
}

func TestFetchRestartsSameCredsWithFreshJar(t *testing.T) {
	var ids []string
	var jars []tlsclient.CookieJar
	calls := 0
	fake := func(_ context.Context, _ string, _ int, creds VKCredentials, jar tlsclient.CookieJar) (string, string, []string, error) {
		calls++
		ids = append(ids, creds.ClientID)
		jars = append(jars, jar)
		if calls == 1 {
			return "", "", nil, ErrPersonaBurned
		}
		return "u", "p", []string{"1.1.1.1:443"}, nil
	}
	c := newTestClient(t, fake)

	if _, _, _, err := c.fetch(context.Background(), "lnk", 1); err != nil {
		t.Fatalf("fetch: %v", err)
	}
	if calls != 2 {
		t.Fatalf("tokenChain called %d times, want 2", calls)
	}
	if ids[0] != ids[1] {
		t.Fatalf("restart switched client_id: %v", ids)
	}
	if jars[0] == jars[1] {
		t.Fatal("restart reused the cookie jar of the burned persona")
	}
}

func TestFetchBoundsPersonaRestarts(t *testing.T) {
	calls := 0
	fake := func(context.Context, string, int, VKCredentials, tlsclient.CookieJar) (string, string, []string, error) {
		calls++
		return "", "", nil, ErrPersonaBurned
	}
	c := newTestClient(t, fake)

	if _, _, _, err := c.fetch(context.Background(), "lnk", 1); err == nil {
		t.Fatal("fetch returned nil error on endless burns")
	}
	if want := len(c.credentials) + maxPersonaBurns; calls != want {
		t.Fatalf("tokenChain called %d times, want %d", calls, want)
	}
}
