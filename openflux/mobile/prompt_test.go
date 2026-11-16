package mobile

import (
	"errors"
	"path/filepath"
	"testing"
	"time"

	"github.com/p1neappleXpress/OpenFlux/transport"
)

type fakePromptTransport struct {
	transport.Transport
	notify  func(err error, name, url, html, reason string)
	applied chan map[string]string
}

func (f *fakePromptTransport) SetErrorNotifier(fn func(err error, name, url, html, reason string)) {
	f.notify = fn
}
func (f *fakePromptTransport) FetchCookies() (map[string]string, error) { return nil, nil }
func (f *fakePromptTransport) ApplyCookies(jar map[string]string) error {
	f.applied <- jar
	return nil
}

func TestPromptFlow(t *testing.T) {
	path := filepath.Join(t.TempDir(), "cookies.json")
	if msg := SetCookieStorePath(path); msg != "" {
		t.Fatal(msg)
	}
	raw := &fakePromptTransport{applied: make(chan map[string]string, 1)}
	attachPrompt("yandex", "https://docs.example/d", raw)

	raw.notify(errors.New("captcha"), "yandex", "https://docs.example/d", "", "smartcaptcha")
	if PendingPromptURL() != "https://docs.example/d" || PendingPromptReason() != "smartcaptcha" {
		t.Fatalf("pending = %q/%q", PendingPromptURL(), PendingPromptReason())
	}

	if msg := SubmitPromptCookies("spravka=abc; yandexuid=42;bad; =x"); msg != "" {
		t.Fatal(msg)
	}
	if PendingPromptURL() != "" {
		t.Fatal("pending not cleared after submit")
	}
	select {
	case jar := <-raw.applied:
		if len(jar) != 2 || jar["spravka"] != "abc" || jar["yandexuid"] != "42" {
			t.Fatalf("applied jar = %v", jar)
		}
	case <-time.After(time.Second):
		t.Fatal("cookies not applied to live transport")
	}

	// A fresh start with the same transport/URL replays the saved cookies.
	next := &fakePromptTransport{applied: make(chan map[string]string, 1)}
	SetCookieStorePath(path)
	attachPrompt("yandex", "https://docs.example/d", next)
	select {
	case jar := <-next.applied:
		if jar["spravka"] != "abc" {
			t.Fatalf("replayed jar = %v", jar)
		}
	default:
		t.Fatal("saved cookies not replayed on next start")
	}

	// After detach (stopped / failed start) cookies are only saved.
	detachPrompt()
	SubmitPromptCookies("spravka=new")
	select {
	case jar := <-next.applied:
		t.Fatalf("applied to detached transport: %v", jar)
	case <-time.After(50 * time.Millisecond):
	}
}

// The core, not the app, says which pages are a script's own.
func TestOwnPageFlag(t *testing.T) {
	raw := &fakePromptTransport{applied: make(chan map[string]string, 1)}
	attachPrompt("script", "local://x", raw)
	t.Cleanup(detachPrompt)

	for _, c := range []struct {
		url, html string
		own       bool
	}{
		{"https://docs.example/d", "", false},
		{"http://127.0.0.1:41234/", "", true},
		{"", "<p>setup</p>", true},
	} {
		raw.notify(errors.New("setup"), "script", c.url, c.html, "login")
		if PendingPromptOwn() != c.own {
			t.Errorf("url %q html %q: own = %v, want %v", c.url, c.html, PendingPromptOwn(), c.own)
		}
		if PendingPromptURL() != c.url || PendingPromptHTML() != c.html {
			t.Errorf("pending = %q / %q, want %q / %q", PendingPromptURL(), PendingPromptHTML(), c.url, c.html)
		}
		CancelPrompt()
		if PendingPromptOwn() {
			t.Errorf("own still set after CancelPrompt (url %q)", c.url)
		}
	}
}

func TestSubmitPromptDataReadsTheScopedPayload(t *testing.T) {
	raw := &fakePromptTransport{applied: make(chan map[string]string, 1)}
	attachPrompt("script", "local://x", raw)
	t.Cleanup(detachPrompt)
	raw.notify(errors.New("setup"), "script", "", "<p>setup</p>", "")

	if msg := SubmitPromptData(`{"client":{"token":"t","port":8080,"on":true,"x":{"y":1}},"node":{"n":"1"}}`); msg != "" {
		t.Fatal(msg)
	}
	select {
	case jar := <-raw.applied:
		if len(jar) != 3 || jar["token"] != "t" || jar["port"] != "8080" || jar["on"] != "true" {
			t.Fatalf("applied jar = %v", jar)
		}
	case <-time.After(time.Second):
		t.Fatal("data not applied to the live transport")
	}
	if PendingPromptHTML() != "" || PendingPromptOwn() {
		t.Fatal("pending page not cleared after submit")
	}
	if msg := SubmitPromptData(`{"o":{}}`); msg == "" {
		t.Fatal("a submission with no values must be refused")
	}
	if msg := SubmitPromptData(`oops`); msg == "" {
		t.Fatal("a submission that is not JSON must be refused")
	}
}
