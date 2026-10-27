package jitsi

import (
	"cmp"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"github.com/zarazaex69/j"
)

// ai-generated: whole file, cover for config.js discovery and the
// docker-jitsi-meet fallback (ghostlane#22).

const (
	testPause       = time.Millisecond
	contentTypeJS   = "application/javascript; charset=utf8"
	contentTypeHTML = "text/html; charset=UTF-8"
	htmlPage        = "<!DOCTYPE html>\n<html><head><title>Error</title></head><body>denied</body></html>\n"
	dockerConfig    = "var config = {};\n" +
		"config.hosts = {};\n" +
		"config.hosts.domain = 'meet.jitsi';\n" +
		"var subdomain = '';\n" +
		"config.hosts.muc = 'muc.' + subdomain + 'meet.jitsi';\n"
	webHost = "<web host>"
	// wantAttempts is spelled out rather than taken from configJSAttempts, so
	// a changed constant fails here.
	wantAttempts = 3
	// xmppConfig declares a domain of its own, not the web host's.
	xmppConfig = "var config = {\n    hosts: {\n        domain: 'xmpp.example',\n    },\n};\n"
)

// testLimits keeps the pauses short and every other limit at its constant.
var testLimits = configJSLimits{pause: testPause}

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(req *http.Request) (*http.Response, error) { return f(req) }

func newTestRequest(ctx context.Context, t *testing.T, method, url string) *http.Request {
	t.Helper()
	req, err := http.NewRequestWithContext(ctx, method, url, http.NoBody)
	if err != nil {
		t.Fatalf("new request: %v", err)
	}
	return req
}

// libraryRead fetches url the way the j library reads config.js: one Read of
// the body into a 64 KiB buffer.
func libraryRead(t *testing.T, client *http.Client, url string) (int, string) {
	t.Helper()
	resp, err := client.Do(newTestRequest(t.Context(), t, http.MethodGet, url))
	if err != nil {
		t.Fatalf("get %s: %v", url, err)
	}
	defer func() { _ = resp.Body.Close() }()
	buf := make([]byte, 64<<10)
	n, _ := resp.Body.Read(buf)
	return resp.StatusCode, string(buf[:n])
}

func writeBody(w http.ResponseWriter, contentType string, status int, body string) {
	w.Header().Set("Content-Type", contentType)
	w.WriteHeader(status)
	_, _ = io.WriteString(w, body)
}

// serveConfig answers config.js with body.
func serveConfig(body string) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		writeBody(w, contentTypeJS, http.StatusOK, body)
	}
}

// stallAfter answers config.js with head, out of a body it says is 80 KB,
// and then sends nothing until the client gives up.
func stallAfter(head string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", contentTypeJS)
		w.Header().Set("Content-Length", "80000")
		w.WriteHeader(http.StatusOK)
		_, _ = io.WriteString(w, head)
		w.(http.Flusher).Flush()
		<-r.Context().Done()
	}
}

// noAnswer sends nothing, not even headers, until the client gives up.
func noAnswer(_ http.ResponseWriter, r *http.Request) {
	<-r.Context().Done()
}

func htmlResponse(req *http.Request, status int) *http.Response {
	return &http.Response{
		StatusCode: status,
		Header:     http.Header{"Content-Type": {contentTypeHTML}},
		Body:       io.NopCloser(strings.NewReader(htmlPage)),
		Request:    req,
	}
}

func TestConfigJSHandsTheLibraryTheWholeSlowBody(t *testing.T) {
	body := "var config = {};\n// " + strings.Repeat("x", 20<<10) + "\n" + dockerConfig
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", contentTypeJS)
		flusher := w.(http.Flusher)
		for chunk := range slices.Chunk([]byte(body), 1024) {
			_, _ = w.Write(chunk)
			flusher.Flush()
			time.Sleep(time.Millisecond)
		}
	}))
	defer srv.Close()

	// Control: without the wrapper the first Read ends long before the line.
	if _, first := libraryRead(t, srv.Client(), srv.URL+configJSPath); strings.Contains(first, "hosts.domain") {
		t.Fatalf("control: the bare first Read already holds hosts.domain (%d bytes)", len(first))
	}

	c := newConfigJS(srv.Client().Transport, false, testLimits)
	status, first := libraryRead(t, &http.Client{Transport: c}, srv.URL+configJSPath)
	if status != http.StatusOK || first != body {
		t.Fatalf("first Read = %d bytes (HTTP %d), want the whole %d-byte file", len(first), status, len(body))
	}
	if got := c.domain(); got != "meet.jitsi" {
		t.Fatalf("domain() = %q, want meet.jitsi", got)
	}
	if got := c.failure(); got != "" {
		t.Fatalf("failure() = %q, want none", got)
	}
}

func TestConfigJSRetriesAfterForbidden(t *testing.T) {
	var hits atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		if hits.Add(1) == 1 {
			writeBody(w, contentTypeHTML, http.StatusForbidden, htmlPage)
			return
		}
		writeBody(w, contentTypeJS, http.StatusOK, dockerConfig)
	}))
	defer srv.Close()

	c := newConfigJS(srv.Client().Transport, false, testLimits)
	status, first := libraryRead(t, &http.Client{Transport: c}, srv.URL+configJSPath)
	if status != http.StatusOK || first != dockerConfig {
		t.Fatalf("got HTTP %d %q, want HTTP 200 with the config", status, first)
	}
	if n := hits.Load(); n != 2 {
		t.Fatalf("config.js requested %d times, want 2", n)
	}
	if c.domain() != "meet.jitsi" || c.failure() != "" {
		t.Fatalf("domain() = %q, failure() = %q; want meet.jitsi and none", c.domain(), c.failure())
	}
}

func TestConfigJSGivesUpOnHTMLPage(t *testing.T) {
	var hits atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		hits.Add(1)
		writeBody(w, contentTypeHTML, http.StatusOK, htmlPage)
	}))
	defer srv.Close()

	c := newConfigJS(srv.Client().Transport, false, testLimits)
	resp, err := c.RoundTrip(newTestRequest(t.Context(), t, http.MethodGet, srv.URL+configJSPath))
	if resp != nil {
		_ = resp.Body.Close()
	}
	if !errors.Is(err, errNoHostsDomain) {
		t.Fatalf("RoundTrip error = %v, want errNoHostsDomain", err)
	}
	if n := hits.Load(); n != wantAttempts {
		t.Fatalf("config.js requested %d times, want %d", n, wantAttempts)
	}
	if got := c.failure(); !strings.Contains(got, "no hosts.domain") || !strings.Contains(got, "text/html") {
		t.Fatalf("failure() = %q, want it to name the missing hosts.domain and the text/html", got)
	}
	if got := c.domain(); got != "" {
		t.Fatalf("domain() = %q, want none", got)
	}
}

func TestConfigJSGivesUpAfterTransportErrors(t *testing.T) {
	const pause = 20 * time.Millisecond
	errDial := errors.New("dial tcp 203.0.113.7:443: i/o timeout")
	var calls []time.Time
	c := newConfigJS(roundTripFunc(func(*http.Request) (*http.Response, error) {
		calls = append(calls, time.Now())
		return nil, errDial
	}), false, configJSLimits{pause: pause})

	resp, err := c.RoundTrip(newTestRequest(t.Context(), t, http.MethodGet, "https://meet.example.com/config.js"))
	if resp != nil {
		_ = resp.Body.Close()
	}
	if !errors.Is(err, errDial) {
		t.Fatalf("RoundTrip error = %v, want the transport error", err)
	}
	if len(calls) != wantAttempts {
		t.Fatalf("transport called %d times, want %d", len(calls), wantAttempts)
	}
	// One pause before the second attempt, two before the third.
	if gap := calls[1].Sub(calls[0]); gap < pause {
		t.Errorf("second attempt %v after the first, want at least %v", gap, pause)
	}
	if gap := calls[2].Sub(calls[1]); gap < 2*pause {
		t.Errorf("third attempt %v after the second, want at least %v", gap, 2*pause)
	}
	if got := c.failure(); !strings.Contains(got, "i/o timeout") {
		t.Fatalf("failure() = %q, want the transport error", got)
	}
}

func TestNewConfigJSDefaults(t *testing.T) {
	c := newConfigJS(nil, false, configJSLimits{})
	want := configJSLimits{pause: time.Second, attempt: 5 * time.Second, grace: time.Second, budget: 10 * time.Second}
	if c.limits != want {
		t.Fatalf("default limits = %+v, want %+v", c.limits, want)
	}
	if c.base != http.DefaultTransport {
		t.Fatalf("nil base = %T, want http.DefaultTransport", c.base)
	}
	if got := newConfigJS(nil, false, configJSLimits{grace: testPause}).limits; got.grace != testPause || got.budget != want.budget {
		t.Fatalf("limits with only grace set = %+v, want that grace and the other constants", got)
	}
	// Discovery at its slowest must leave a rejoin two thirds of its time
	// for the websocket, the XMPP login and a fallback join.
	if left := reconnectJoinTimeout - c.limits.budget; left < 2*reconnectJoinTimeout/3 {
		t.Fatalf("discovery may take %v of a %v rejoin, leaving %v", c.limits.budget, reconnectJoinTimeout, left)
	}
}

func TestConfigJSReportsStatusAndMediaType(t *testing.T) {
	c := newConfigJS(roundTripFunc(func(r *http.Request) (*http.Response, error) {
		return htmlResponse(r, http.StatusForbidden), nil
	}), false, testLimits)

	resp, err := c.RoundTrip(newTestRequest(t.Context(), t, http.MethodGet, "https://meet.example.com/config.js"))
	if resp != nil {
		_ = resp.Body.Close()
	}
	var status *statusError
	if !errors.As(err, &status) || status.code != http.StatusForbidden {
		t.Fatalf("RoundTrip error = %v, want a statusError for 403", err)
	}
	if got := c.failure(); got != "HTTP 403 text/html" {
		t.Fatalf("failure() = %q, want %q", got, "HTTP 403 text/html")
	}
}

func TestConfigJSAddsBrowserHeadersOnlyWhenMissing(t *testing.T) {
	var mu sync.Mutex
	var got []http.Header
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		got = append(got, r.Header.Clone())
		mu.Unlock()
		writeBody(w, contentTypeJS, http.StatusOK, dockerConfig)
	}))
	defer srv.Close()

	// What the server must see for a bare request and for one that set its
	// own User-Agent and Referer.
	const accept, language = "*/*", "en-US,en;q=0.9"
	headers := []struct{ name, bare, own string }{
		{"User-Agent", browserUserAgent, "olcrtc-test/1"},
		{"Accept", accept, accept},
		{"Accept-Language", language, language},
		{"Referer", srv.URL + "/", "https://elsewhere.example/"},
	}
	bare := newTestRequest(t.Context(), t, http.MethodGet, srv.URL+configJSPath)
	own := newTestRequest(t.Context(), t, http.MethodGet, srv.URL+configJSPath)
	for _, h := range headers {
		if h.own != h.bare {
			own.Header.Set(h.name, h.own)
		}
	}

	c := newConfigJS(srv.Client().Transport, false, testLimits)
	for _, req := range []*http.Request{bare, own} {
		resp, err := c.RoundTrip(req)
		if err != nil {
			t.Fatalf("RoundTrip: %v", err)
		}
		_ = resp.Body.Close()
	}
	if len(bare.Header) != 0 {
		t.Fatalf("RoundTrip changed the caller's request headers: %v", bare.Header)
	}

	mu.Lock()
	defer mu.Unlock()
	if len(got) != 2 {
		t.Fatalf("server saw %d requests, want 2", len(got))
	}
	for _, h := range headers {
		if v := got[0].Get(h.name); v != h.bare {
			t.Errorf("bare request: %s = %q, want %q", h.name, v, h.bare)
		}
		if v := got[1].Get(h.name); v != h.own {
			t.Errorf("request with its own headers: %s = %q, want %q", h.name, v, h.own)
		}
	}
}

func TestConfigJSPassesOtherRequestsThrough(t *testing.T) {
	errBase := errors.New("base transport error")
	requests := []struct{ method, url string }{
		{http.MethodGet, "https://meet.example.com/xmpp-websocket?room=" + testRoom},
		{http.MethodGet, "https://meet.example.com/libs/app.bundle.min.js"},
		{http.MethodGet, "https://meet.example.com/sub/config.js"},
		{http.MethodPost, "https://meet.example.com/config.js"},
	}
	for _, fallback := range []bool{false, true} {
		for _, rq := range requests {
			var seen *http.Request
			want := &http.Response{StatusCode: http.StatusSwitchingProtocols, Body: http.NoBody}
			c := newConfigJS(roundTripFunc(func(r *http.Request) (*http.Response, error) {
				seen = r
				return want, errBase
			}), fallback, testLimits)

			req := newTestRequest(t.Context(), t, rq.method, rq.url)
			got, err := c.RoundTrip(req)
			if got != nil {
				_ = got.Body.Close()
			}
			if got != want || !errors.Is(err, errBase) || seen != req {
				t.Fatalf("fallback=%v %s %s: base saw %p (sent %p), returned %p/%v; want both untouched",
					fallback, rq.method, rq.url, seen, req, got, err)
			}
			if len(req.Header) != 0 {
				t.Fatalf("fallback=%v %s %s: headers added: %v", fallback, rq.method, rq.url, req.Header)
			}
		}
	}
}

func TestConfigJSStopsWhenTheContextEndsDuringBackoff(t *testing.T) {
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	var calls atomic.Int32
	c := newConfigJS(roundTripFunc(func(r *http.Request) (*http.Response, error) {
		if calls.Add(1) == 1 {
			time.AfterFunc(20*time.Millisecond, cancel)
		}
		return htmlResponse(r, http.StatusForbidden), nil
	}), false, configJSLimits{pause: time.Minute})

	req := newTestRequest(ctx, t, http.MethodGet, "https://meet.example.com/config.js")
	done := make(chan error, 1)
	go func() {
		resp, err := c.RoundTrip(req)
		if resp != nil {
			_ = resp.Body.Close()
		}
		done <- err
	}()
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("RoundTrip error = %v, want context.Canceled", err)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("RoundTrip still waiting after its context was cancelled")
	}
	if n := calls.Load(); n != 1 {
		t.Fatalf("transport called %d times, want 1", n)
	}
	if got := c.failure(); !strings.Contains(got, "context canceled") || !strings.Contains(got, "HTTP 403") {
		t.Fatalf("failure() = %q, want the cancellation and the last answer", got)
	}
}

func TestConfigJSHandsOverWhatArrivedBeforeAStall(t *testing.T) {
	var hits atomic.Int32
	// The hosts lines, half of the next line, and then nothing: a mobile
	// link or a DPI freeze mid-transfer.
	stall := stallAfter(dockerConfig + "config.websocket = 'wss://meet.exa")
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		stall(w, r)
	}))
	defer srv.Close()

	const grace = 50 * time.Millisecond
	c := newConfigJS(srv.Client().Transport, false, configJSLimits{pause: testPause, grace: grace})
	start := time.Now()
	status, first := libraryRead(t, &http.Client{Transport: c}, srv.URL+configJSPath)
	if took := time.Since(start); took >= configJSAttemptTimeout {
		t.Fatalf("discovery took %v; the %v grace, not the %v attempt, should end the read",
			took, grace, configJSAttemptTimeout)
	}
	if status != http.StatusOK || first != dockerConfig {
		t.Fatalf("first Read = %q (HTTP %d), want the whole lines that came before the stall", first, status)
	}
	if c.domain() != fallbackXMPPDomain || c.failure() != "" {
		t.Fatalf("domain() = %q, failure() = %q; want %s and none", c.domain(), c.failure(), fallbackXMPPDomain)
	}
	if n := hits.Load(); n != 1 {
		t.Fatalf("config.js requested %d times, want 1", n)
	}
}

func TestConfigJSGivesUpOnASilentServerInTime(t *testing.T) {
	tests := []struct {
		name     string
		limits   configJSLimits
		wantHits int32
	}{
		{"each attempt ends at its deadline", configJSLimits{pause: testPause, attempt: 200 * time.Millisecond}, wantAttempts},
		{"the budget ends them all", configJSLimits{pause: testPause, attempt: 10 * time.Second, budget: 200 * time.Millisecond}, 1},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			var hits atomic.Int32
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				hits.Add(1)
				noAnswer(w, r)
			}))
			defer srv.Close()

			c := newConfigJS(srv.Client().Transport, false, tc.limits)
			start := time.Now()
			resp, err := c.RoundTrip(newTestRequest(t.Context(), t, http.MethodGet, srv.URL+configJSPath))
			took := time.Since(start)
			if resp != nil {
				_ = resp.Body.Close()
			}
			if !errors.Is(err, context.DeadlineExceeded) {
				t.Fatalf("RoundTrip error = %v, want a deadline", err)
			}
			// Nothing else bounds this client: no header timeout, no
			// client Timeout.
			if took > 5*time.Second {
				t.Fatalf("RoundTrip took %v against a server that never answers", took)
			}
			if n := hits.Load(); n != tc.wantHits {
				t.Fatalf("config.js requested %d times, want %d", n, tc.wantHits)
			}
		})
	}
}

func TestConfigJSDoesNotRetryAnswersThatStay(t *testing.T) {
	tests := []struct{ status, wantCalls int }{
		{http.StatusMovedPermanently, 1},
		{http.StatusNotFound, 1},
		{http.StatusGone, 1},
		{http.StatusForbidden, wantAttempts},
		{http.StatusBadGateway, wantAttempts},
	}
	for _, tc := range tests {
		t.Run(http.StatusText(tc.status), func(t *testing.T) {
			calls := 0
			c := newConfigJS(roundTripFunc(func(r *http.Request) (*http.Response, error) {
				calls++
				return htmlResponse(r, tc.status), nil
			}), false, testLimits)

			resp, err := c.RoundTrip(newTestRequest(t.Context(), t, http.MethodGet, "https://meet.example.com/config.js"))
			if resp != nil {
				_ = resp.Body.Close()
			}
			if err == nil || calls != tc.wantCalls {
				t.Fatalf("RoundTrip = %v after %d requests, want an error after %d", err, calls, tc.wantCalls)
			}
			if want := fmt.Sprintf("HTTP %d text/html", tc.status); c.failure() != want {
				t.Fatalf("failure() = %q, want %q", c.failure(), want)
			}
		})
	}
}

// TestConfigJSReadsTheLibraryWindow: the library reads 64 KiB, so a domain
// past them is no domain, and what lies past them is not waited for.
func TestConfigJSReadsTheLibraryWindow(t *testing.T) {
	filler := "// " + strings.Repeat("z", 70<<10) + "\n"
	t.Run("domain past the window", func(t *testing.T) {
		srv := httptest.NewServer(serveConfig("var config = {};\n" + filler + dockerConfig))
		defer srv.Close()

		c := newConfigJS(srv.Client().Transport, false, testLimits)
		resp, err := c.RoundTrip(newTestRequest(t.Context(), t, http.MethodGet, srv.URL+configJSPath))
		if resp != nil {
			_ = resp.Body.Close()
		}
		if !errors.Is(err, errNoHostsDomain) {
			t.Fatalf("RoundTrip error = %v, want errNoHostsDomain", err)
		}
		if want := fmt.Sprintf("in %d bytes", configJSMaxBody); !strings.Contains(c.failure(), want) {
			t.Fatalf("failure() = %q, want it to say %q", c.failure(), want)
		}
	})
	t.Run("domain in the window of a longer file", func(t *testing.T) {
		srv := httptest.NewServer(serveConfig(dockerConfig + filler))
		defer srv.Close()

		c := newConfigJS(srv.Client().Transport, false, testLimits)
		status, first := libraryRead(t, &http.Client{Transport: c}, srv.URL+configJSPath)
		if status != http.StatusOK || first != dockerConfig {
			t.Fatalf("first Read = %d bytes (HTTP %d), want the %d bytes of whole lines in the window",
				len(first), status, len(dockerConfig))
		}
	})
}

func TestConfigJSFallbackServesDockerDefaultsOffline(t *testing.T) {
	c := newConfigJS(roundTripFunc(func(*http.Request) (*http.Response, error) {
		t.Error("fallback mode touched the network")
		return nil, errors.New("offline")
	}), true, testLimits)

	status, first := libraryRead(t, &http.Client{Transport: c}, "https://meet.example.com/config.js")
	if status != http.StatusOK {
		t.Fatalf("fallback answered HTTP %d, want 200", status)
	}
	if got := libraryDomain([]byte(first)); got != fallbackXMPPDomain {
		t.Fatalf("fallback declares domain %q, want %q", got, fallbackXMPPDomain)
	}
	for _, line := range []string{
		"config.hosts.domain = 'meet.jitsi';",
		"config.hosts.muc = 'muc.meet.jitsi';",
	} {
		if !strings.Contains(first, line) {
			t.Errorf("fallback config.js lacks %q:\n%s", line, first)
		}
	}
	if got := c.failure(); got != "" {
		t.Fatalf("failure() = %q, want none", got)
	}
}

// domainCases are config.js bodies and the domain the j library takes from
// each, "" for none. TestLibraryDomainAgreesWithTheLibrary checks the wants
// against the library itself.
var domainCases = []struct{ name, body, want string }{
	{
		name: "jitsi-meet config",
		body: "var config = {\n    // Connection\n    //\n\n    hosts: {\n        // XMPP domain.\n" +
			"        domain: 'jitsi-meet.example.com',\n\n" +
			"        muc: 'conference.' + subdomain + 'jitsi-meet.example.com',\n    },\n\n" +
			"    bosh: 'https://jitsi-meet.example.com/' + subdir + 'http-bind',\n};\n",
		want: "jitsi-meet.example.com",
	},
	{name: "docker-jitsi-meet config", body: dockerConfig, want: "meet.jitsi"},
	{
		name: "double quotes and a trailing comment",
		body: "config.hosts.domain = \"meet.example.org\"; // XMPP\n",
		want: "meet.example.org",
	},
	{
		name: "concatenated literals",
		body: "var config = {\n    hosts: {\n        domain: 'meet.' + subdomain + 'example.org',\n    },\n};\n",
		want: "meet.example.org",
	},
	{
		name: "hosts opened on the config line",
		body: "var config = { hosts: {\n        domain: 'xmpp.custom.example',\n    },\n};\n",
		want: "xmpp.custom.example",
	},
	{
		name: "hosts brace on a line of its own",
		body: "var config = {\n    hosts:\n    {\n        domain: 'xmpp.custom.example',\n    },\n};\n",
		want: "xmpp.custom.example",
	},
	{
		name: "the first domain key wins, in hosts or not",
		body: "var other = {\n    domain: 'other.example',\n};\n" +
			"var config = {\n    hosts: {\n        domain: 'meet.example.org',\n    },\n};\n",
		want: "other.example",
	},
	{
		name: "a key without a literal gives way to the next",
		body: "config.hosts.domain = someVariable;\nconfig.hosts.domain = 'meet.example.org';\n",
		want: "meet.example.org",
	},
	{name: "a semicolon in the literal cuts it", body: "config.hosts.domain = 'meet;example.org';\n"},
	{name: "html error page", body: htmlPage},
	{name: "file cut before hosts", body: "var config = {\n    // Connection\n"},
	{name: "commented out", body: "var config = {\n    hosts: {\n        // domain: 'x.example',\n    },\n};\n"},
	{name: "only anonymousdomain", body: "var config = {\n    hosts: {\n        anonymousdomain: 'guest.example',\n    },\n};\n"},
	{name: "longer key", body: "config.hosts.domainAlias = 'x.example';\n"},
	{name: "not a string", body: "config.hosts.domain = someVariable;\n"},
}

func TestLibraryDomain(t *testing.T) {
	for _, tc := range domainCases {
		t.Run(tc.name, func(t *testing.T) {
			if got := libraryDomain([]byte(tc.body)); got != tc.want {
				t.Fatalf("libraryDomain = %q, want %q", got, tc.want)
			}
		})
	}
}

// TestLibraryDomainAgreesWithTheLibrary serves each case to the j library on
// its own, without configJS, and reads the domain it opens the stream to.
func TestLibraryDomainAgreesWithTheLibrary(t *testing.T) {
	for _, tc := range domainCases {
		t.Run(tc.name, func(t *testing.T) {
			f := &fakeJitsi{config: serveConfig(tc.body)}
			srv := httptest.NewUnstartedServer(f)
			host := srv.Listener.Addr().String()
			srv.StartTLS()
			defer srv.Close()

			ctx, cancel := context.WithTimeout(t.Context(), 20*time.Second)
			defer cancel()
			cfg := j.Config{Host: host, Room: testRoom, Nick: defaultNick, HTTPClient: srv.Client()}
			if jSess, err := j.JoinMUC(ctx, cfg); err == nil {
				_ = jSess.Close()
			}
			f.mu.Lock()
			opens := slices.Clone(f.opens)
			f.mu.Unlock()
			if want := cmp.Or(tc.want, host); !slices.Equal(opens, []string{want}) {
				t.Fatalf("the library opened the stream to %q, want %q", opens, want)
			}
		})
	}
}

func TestShouldRetryWithFallback(t *testing.T) {
	hostUnknown := errors.New("xmpp dial: initial features: server error: <stream:error " +
		"xmlns='jabber:client'><host-unknown xmlns='urn:ietf:params:xml:ns:xmpp-streams'/>" +
		"<text>This server does not serve meet.example.com</text></stream:error>")
	other := errors.New("xmpp dial: failed to WebSocket dial: expected handshake response status code 101 but got 502")
	tests := []struct {
		name            string
		err             error
		discoveryFailed bool
		want            bool
	}{
		{"host-unknown after failed discovery", hostUnknown, true, true},
		{"host-unknown after good discovery", hostUnknown, false, false},
		{"other error after failed discovery", other, true, false},
		{"other error after good discovery", other, false, false},
		{"host-unknown named outside a stream error", errors.New("dial tcp: lookup host-unknown.example: no such host"), true, false},
		{"joined", nil, true, false},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if got := shouldRetryWithFallback(tc.err, tc.discoveryFailed); got != tc.want {
				t.Fatalf("shouldRetryWithFallback = %v, want %v", got, tc.want)
			}
		})
	}
}

// fakeJitsi is a Jitsi web host up to the stream open: config.js, and an
// XMPP websocket that answers the open with host-unknown unless the domain is
// the one it serves. For that one it offers no ANONYMOUS login, which ends
// the library's dial with an error of its own.
type fakeJitsi struct {
	config http.HandlerFunc // answers config.js; nil is a 403 page
	serves string

	mu      sync.Mutex
	configs int
	opens   []string
}

func (f *fakeJitsi) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	switch r.URL.Path {
	case configJSPath:
		f.mu.Lock()
		f.configs++
		f.mu.Unlock()
		if f.config == nil {
			writeBody(w, contentTypeHTML, http.StatusForbidden, htmlPage)
			return
		}
		f.config(w, r)
	case "/xmpp-websocket":
		f.stream(w, r)
	default:
		http.NotFound(w, r)
	}
}

func (f *fakeJitsi) stream(w http.ResponseWriter, r *http.Request) {
	up := websocket.Upgrader{Subprotocols: []string{"xmpp"}, CheckOrigin: func(*http.Request) bool { return true }}
	conn, err := up.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer func() { _ = conn.Close() }()
	_, open, err := conn.ReadMessage()
	if err != nil {
		return
	}
	_, rest, _ := strings.Cut(string(open), `to="`)
	to, _, _ := strings.Cut(rest, `"`)
	f.mu.Lock()
	f.opens = append(f.opens, to)
	f.mu.Unlock()

	reply := "<stream:error xmlns:stream='http://etherx.jabber.org/streams'>" +
		"<host-unknown xmlns='urn:ietf:params:xml:ns:xmpp-streams'/>" +
		"<text xmlns='urn:ietf:params:xml:ns:xmpp-streams'>This server does not serve " + to + "</text></stream:error>"
	if to == f.serves {
		reply = "<stream:features xmlns:stream='http://etherx.jabber.org/streams'>" +
			"<mechanisms xmlns='urn:ietf:params:xml:ns:xmpp-sasl'><mechanism>PLAIN</mechanism></mechanisms>" +
			"</stream:features>"
	}
	_ = conn.WriteMessage(websocket.TextMessage, []byte("<open xmlns='urn:ietf:params:xml:ns:xmpp-framing' version='1.0'/>"))
	_ = conn.WriteMessage(websocket.TextMessage, []byte(reply))
	for {
		if _, _, err := conn.ReadMessage(); err != nil {
			return
		}
	}
}

func TestJoinMUCRetriesOnceWithTheFallback(t *testing.T) {
	const unavailable = "(config.js unavailable: HTTP 403 text/html)"
	tests := []struct {
		name        string
		configOK    bool
		serves      string
		wantOpens   []string
		wantConfigs int
		wantErr     []string
		unwanted    string
	}{
		{
			name:        "discovery failed, host-unknown, then the fallback domain",
			serves:      fallbackXMPPDomain,
			wantOpens:   []string{webHost, fallbackXMPPDomain},
			wantConfigs: wantAttempts,
			wantErr:     []string{"anonymous", unavailable},
		},
		{
			name:        "discovery failed, host-unknown twice, no third try",
			wantOpens:   []string{webHost, fallbackXMPPDomain},
			wantConfigs: wantAttempts,
			wantErr:     []string{"does not serve meet.jitsi", unavailable},
		},
		{
			name:        "discovery ok, host-unknown, no retry",
			configOK:    true,
			wantOpens:   []string{"xmpp.example"},
			wantConfigs: 1,
			wantErr:     []string{"host-unknown"},
			unwanted:    "config.js unavailable",
		},
		{
			name:        "discovery failed, another error, no retry",
			serves:      webHost,
			wantOpens:   []string{webHost},
			wantConfigs: wantAttempts,
			wantErr:     []string{"anonymous", unavailable},
		},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			f := &fakeJitsi{}
			if tc.configOK {
				f.config = serveConfig(xmppConfig)
			}
			srv := httptest.NewUnstartedServer(f)
			host := srv.Listener.Addr().String()
			f.serves = strings.ReplaceAll(tc.serves, webHost, host)
			srv.StartTLS()
			defer srv.Close()

			s := &Session{host: host, room: testRoom, name: defaultNick, httpClient: srv.Client(), configJSLimits: testLimits}
			ctx, cancel := context.WithTimeout(t.Context(), 20*time.Second)
			defer cancel()
			jSess, err := s.joinMUC(ctx)
			if err == nil {
				_ = jSess.Close()
				t.Fatal("joinMUC succeeded against a server that cannot finish a login")
			}
			if !strings.HasPrefix(err.Error(), "jitsi join muc: ") {
				t.Errorf("error %q lacks the jitsi join muc prefix", err)
			}
			for _, want := range tc.wantErr {
				if !strings.Contains(err.Error(), want) {
					t.Errorf("error %q lacks %q", err, want)
				}
			}
			if tc.unwanted != "" && strings.Contains(err.Error(), tc.unwanted) {
				t.Errorf("error %q has %q", err, tc.unwanted)
			}

			wantOpens := make([]string, 0, len(tc.wantOpens))
			for _, o := range tc.wantOpens {
				wantOpens = append(wantOpens, strings.ReplaceAll(o, webHost, host))
			}
			f.mu.Lock()
			opens, configs := slices.Clone(f.opens), f.configs
			f.mu.Unlock()
			if !slices.Equal(opens, wantOpens) {
				t.Errorf("stream opened to %q, want %q", opens, wantOpens)
			}
			if configs != tc.wantConfigs {
				t.Errorf("config.js requested %d times, want %d", configs, tc.wantConfigs)
			}
		})
	}
}

func TestJoinMUCReachesTheStreamWhenConfigJSStalls(t *testing.T) {
	tests := []struct {
		name      string
		config    http.HandlerFunc
		limits    configJSLimits
		wantOpens []string
	}{
		{
			// The engine's own limits: the grace ends the read, and the
			// library gets the hosts lines that came before the stall.
			name:      "body stalls after the hosts lines",
			config:    stallAfter(dockerConfig),
			wantOpens: []string{fallbackXMPPDomain},
		},
		{
			name:      "no answer at all",
			config:    noAnswer,
			limits:    configJSLimits{pause: testPause, attempt: 100 * time.Millisecond, budget: 300 * time.Millisecond},
			wantOpens: []string{webHost, fallbackXMPPDomain},
		},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			f := &fakeJitsi{config: tc.config, serves: fallbackXMPPDomain}
			srv := httptest.NewUnstartedServer(f)
			host := srv.Listener.Addr().String()
			srv.StartTLS()
			defer srv.Close()

			// protect's client Timeout, in a rejoin's context.
			client := &http.Client{Transport: srv.Client().Transport, Timeout: 30 * time.Second}
			s := &Session{host: host, room: testRoom, name: defaultNick, httpClient: client, configJSLimits: tc.limits}
			ctx, cancel := context.WithTimeout(t.Context(), reconnectJoinTimeout)
			defer cancel()
			start := time.Now()
			jSess, err := s.joinMUC(ctx)
			took := time.Since(start)
			if err == nil {
				_ = jSess.Close()
				t.Fatal("joinMUC succeeded against a server that cannot finish a login")
			}
			// The fake answers a stream open to the domain it serves with a
			// login the library cannot use: reaching that is reaching the
			// stream in time.
			if !strings.Contains(err.Error(), "anonymous") {
				t.Fatalf("joinMUC error = %v, want the fake's login refusal", err)
			}
			if took >= configJSAttemptTimeout {
				t.Fatalf("joinMUC took %v, want the stream open well inside one config.js attempt", took)
			}
			wantOpens := make([]string, 0, len(tc.wantOpens))
			for _, o := range tc.wantOpens {
				wantOpens = append(wantOpens, strings.ReplaceAll(o, webHost, host))
			}
			f.mu.Lock()
			opens := slices.Clone(f.opens)
			f.mu.Unlock()
			if !slices.Equal(opens, wantOpens) {
				t.Fatalf("stream opened to %q, want %q", opens, wantOpens)
			}
		})
	}
}
