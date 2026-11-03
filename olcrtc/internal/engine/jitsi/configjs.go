package jitsi

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/zarazaex69/j"

	"github.com/openlibrecommunity/olcrtc/internal/logger"
)

// Jitsi config.js discovery (ghostlane#22).
//
// The j library opens the XMPP stream to the domain config.js declares in
// hosts.domain. It fetches https://<host>/config.js with a bare Go request,
// reads the answer with a single Read into a 64 KiB buffer, and on any
// failure opens the stream to the web host instead. A docker-jitsi-meet
// server serves meet.jitsi whatever its public name, so that guess ends in
// host-unknown. configJS sits under the library's http.Client: it makes the
// request look like the browser's, tries it up to three times within a
// budget that leaves the join most of its time, and hands the library the
// part it reads, the first 64 KiB, in one piece. It judges that part by the
// library's own parsing rules, so discovery fails exactly when the library
// falls back to the web host. When it does and the server refuses the web
// host, joinMUC tries once more on a configJS that answers with the
// docker-jitsi-meet defaults without touching the network.
//
// ai-generated: the whole file.

const (
	configJSPath     = "/config.js"
	configJSAttempts = 3
	// configJSMaxBody is all of config.js the library looks at: it reads
	// the answer with one Read into a 64 KiB buffer.
	configJSMaxBody = 64 << 10

	// configJSBackoff is the pause before the second attempt; the third
	// waits twice as long.
	configJSBackoff = time.Second
	// configJSAttemptTimeout bounds one attempt, from the request to the end
	// of the body.
	configJSAttemptTimeout = 5 * time.Second
	// configJSGrace is how long the rest of the body gets once the lines
	// read declare the domain. The lines the library reads after it (muc,
	// bosh, websocket) follow within a kilobyte or two, so a body that
	// stalls later (a mobile link, a DPI freeze mid-transfer) costs this,
	// not the attempt.
	configJSGrace = time.Second
	// configJSBudget bounds discovery, attempts and pauses together. A
	// rejoin has reconnectJoinTimeout for everything, and the websocket and
	// the XMPP login come after discovery.
	configJSBudget = 10 * time.Second

	fallbackXMPPDomain = "meet.jitsi"
	fallbackMUCDomain  = "muc." + fallbackXMPPDomain
	// fallbackConfig is what a docker-jitsi-meet config.js says about its
	// XMPP hosts, in the shape it says it.
	fallbackConfig = "var config = {};\n" +
		"config.hosts = {};\n" +
		"config.hosts.domain = '" + fallbackXMPPDomain + "';\n" +
		"config.hosts.muc = '" + fallbackMUCDomain + "';\n"

	// browserUserAgent is the one the library's websocket dial sends.
	browserUserAgent = "Mozilla/5.0 (X11; Linux x86_64; rv:150.0) Gecko/20100101 Firefox/150.0"
)

// errNoHostsDomain is a 200 that declares no domain: a WAF challenge, an
// error page, a file cut short.
var errNoHostsDomain = errors.New("no hosts.domain")

// statusError is a config.js answer other than 200: "HTTP 403 text/html".
type statusError struct {
	code      int
	mediaType string
}

func (e *statusError) Error() string {
	return fmt.Sprintf("HTTP %d %s", e.code, e.mediaType)
}

// final reports an answer the same request gets again, so that another
// attempt only waits for it: a redirect, or 404 or 410 from a host that has
// no config.js.
func (e *statusError) final() bool {
	redirect := e.code >= http.StatusMultipleChoices && e.code < http.StatusBadRequest
	return redirect || e.code == http.StatusNotFound || e.code == http.StatusGone
}

// configJSLimits is discovery's clock. A zero field takes its constant;
// tests shorten them.
type configJSLimits struct {
	pause   time.Duration // before the second attempt, twice that before the third
	attempt time.Duration // one attempt, request to end of body
	grace   time.Duration // the rest of the body once the domain is in
	budget  time.Duration // attempts and pauses together
}

// configJS is the transport of one join's http.Client. It handles GET
// /config.js and passes every other request to base untouched.
type configJS struct {
	base     http.RoundTripper
	fallback bool // answer config.js with fallbackConfig, offline
	limits   configJSLimits

	mu     sync.Mutex
	found  string // the domain the library takes from the config.js it was handed
	reason string // why discovery failed, "" when it did not
}

// newConfigJS wraps base, nil meaning http.DefaultTransport. The zero fields
// of limits take their constants.
func newConfigJS(base http.RoundTripper, fallback bool, limits configJSLimits) *configJS {
	if base == nil {
		base = http.DefaultTransport
	}
	c := &configJS{base: base, fallback: fallback, limits: limits}
	if c.limits.pause <= 0 {
		c.limits.pause = configJSBackoff
	}
	if c.limits.attempt <= 0 {
		c.limits.attempt = configJSAttemptTimeout
	}
	if c.limits.grace <= 0 {
		c.limits.grace = configJSGrace
	}
	if c.limits.budget <= 0 {
		c.limits.budget = configJSBudget
	}
	return c
}

// RoundTrip implements http.RoundTripper.
func (c *configJS) RoundTrip(req *http.Request) (*http.Response, error) {
	if req.Method != http.MethodGet || req.URL.Path != configJSPath {
		return c.base.RoundTrip(req) //nolint:wrapcheck // pass-through, the caller gets what base said
	}
	if c.fallback {
		if req.Body != nil {
			_ = req.Body.Close()
		}
		return fallbackResponse(req), nil
	}
	resp, domain, err := c.fetch(req)
	c.mu.Lock()
	c.found, c.reason = domain, ""
	if err != nil {
		c.reason = err.Error()
	}
	c.mu.Unlock()
	return resp, err
}

// domain is the domain the library takes from the config.js it was handed.
func (c *configJS) domain() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.found
}

// failure is why discovery failed, "" when it did not or never ran.
func (c *configJS) failure() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.reason
}

// fetch tries config.js up to configJSAttempts times within the budget, one
// and then two pauses apart, and returns the first answer that declares the
// domain. An answer the same request would get again ends it at once.
func (c *configJS) fetch(req *http.Request) (*http.Response, string, error) {
	ctx, cancel := context.WithTimeout(req.Context(), c.limits.budget)
	defer cancel()
	for attempt := 1; ; attempt++ {
		resp, domain, err := c.try(ctx, req)
		if err == nil {
			return resp, domain, nil
		}
		logger.Debugf("jitsi: config.js attempt %d/%d: %v", attempt, configJSAttempts, err)
		var status *statusError
		if attempt == configJSAttempts || ctx.Err() != nil || (errors.As(err, &status) && status.final()) {
			return nil, "", err
		}
		if waitErr := sleepContext(ctx, time.Duration(attempt)*c.limits.pause); waitErr != nil {
			return nil, "", fmt.Errorf("%w, after %w", waitErr, err)
		}
	}
}

// try makes one attempt, within limits.attempt. Only a 200 whose first
// configJSMaxBody bytes declare the domain, by the library's rules, counts.
// Those bytes are handed back as a buffer, so the library's single Read gets
// all of them; a body that ended early loses the line it was cut in.
func (c *configJS) try(ctx context.Context, req *http.Request) (*http.Response, string, error) {
	attemptCtx, cancel := context.WithTimeout(ctx, c.limits.attempt)
	defer cancel()
	resp, err := c.base.RoundTrip(browserRequest(attemptCtx, req))
	if err != nil {
		return nil, "", fmt.Errorf("get config.js: %w", err)
	}
	if resp.StatusCode != http.StatusOK {
		_ = resp.Body.Close()
		return nil, "", &statusError{code: resp.StatusCode, mediaType: mediaType(resp.Header)}
	}
	body, err := readConfig(resp.Body, c.limits.grace, cancel)
	_ = resp.Body.Close()
	read := len(body)
	if err != nil || read == configJSMaxBody {
		body = wholeLines(body)
	}
	domain := libraryDomain(body)
	switch {
	case domain == "" && err != nil:
		return nil, "", err
	case domain == "":
		return nil, "", fmt.Errorf("%w in %d bytes of %s", errNoHostsDomain, read, mediaType(resp.Header))
	case err != nil:
		logger.Debugf("jitsi: config.js cut after %d bytes (%v); its domain came before", read, err)
	}
	resp.Body = io.NopCloser(bytes.NewReader(body))
	resp.ContentLength = int64(len(body))
	resp.Request = req
	return resp, domain, nil
}

// readConfig reads body up to configJSMaxBody, until it ends, fails or fills
// the window. Once the whole lines read declare the domain, the rest gets
// grace to arrive, and then stop ends the read.
func readConfig(body io.Reader, grace time.Duration, stop func()) ([]byte, error) {
	buf := make([]byte, 0, configJSMaxBody)
	scanned := 0 // whole lines already searched for the domain
	var timer *time.Timer
	defer func() {
		if timer != nil {
			timer.Stop()
		}
	}()
	for len(buf) < cap(buf) {
		n, err := body.Read(buf[len(buf):cap(buf)])
		buf = buf[:len(buf)+n]
		if errors.Is(err, io.EOF) {
			return buf, nil
		}
		if err != nil {
			return buf, fmt.Errorf("read config.js: %w", err)
		}
		if timer == nil {
			whole := len(wholeLines(buf))
			if libraryDomain(buf[scanned:whole]) != "" {
				timer = time.AfterFunc(grace, stop)
			}
			scanned = whole
		}
	}
	return buf, nil
}

// wholeLines is b up to its last newline. A line cut mid-value would hand the
// library half a domain.
func wholeLines(b []byte) []byte {
	return b[:bytes.LastIndexByte(b, '\n')+1]
}

// browserRequest is a copy of req under ctx, with the headers a browser sends
// for the script where req has none of its own.
func browserRequest(ctx context.Context, req *http.Request) *http.Request {
	r := req.Clone(ctx)
	if r.Header == nil {
		r.Header = make(http.Header)
	}
	for _, h := range [...][2]string{
		{"User-Agent", browserUserAgent},
		{"Accept", "*/*"},
		{"Accept-Language", "en-US,en;q=0.9"},
		{"Referer", req.URL.Scheme + "://" + req.URL.Host + "/"},
	} {
		if r.Header.Get(h[0]) == "" {
			r.Header.Set(h[0], h[1])
		}
	}
	return r
}

// mediaType is the Content-Type of an answer without its parameters.
func mediaType(h http.Header) string {
	mt, _, _ := strings.Cut(h.Get("Content-Type"), ";")
	if mt = strings.TrimSpace(mt); mt != "" {
		return mt
	}
	return "no content-type"
}

// fallbackResponse answers config.js with fallbackConfig.
func fallbackResponse(req *http.Request) *http.Response {
	return &http.Response{
		Status:        "200 OK",
		StatusCode:    http.StatusOK,
		Proto:         "HTTP/1.1",
		ProtoMajor:    1,
		ProtoMinor:    1,
		Header:        http.Header{"Content-Type": {"application/javascript"}},
		Body:          io.NopCloser(strings.NewReader(fallbackConfig)),
		ContentLength: int64(len(fallbackConfig)),
		Request:       req,
	}
}

// sleepContext waits for d, or until ctx ends.
func sleepContext(ctx context.Context, d time.Duration) error {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-t.C:
		return nil
	case <-ctx.Done():
		return fmt.Errorf("wait for config.js retry: %w", ctx.Err())
	}
}

// libraryDomain is the XMPP domain the j library takes from a config.js
// body, by its own rules (extractStringField in its internal/xmpp): the first
// line that, less a leading "config.hosts." or "config.", starts with domain
// and then ':', '=' or a blank, and whose value, cut at the first ';' or ','
// and then at a // comment, holds string literals, joined. "" means the
// library opens the stream to the web host.
func libraryDomain(body []byte) string {
	for line := range bytes.Lines(body) {
		t := bytes.TrimSpace(line)
		t = bytes.TrimPrefix(t, []byte("config.hosts."))
		t = bytes.TrimPrefix(t, []byte("config."))
		rest, ok := bytes.CutPrefix(t, []byte("domain"))
		if !ok || len(rest) == 0 || strings.IndexByte(":= \t", rest[0]) < 0 {
			continue
		}
		expr := bytes.TrimLeft(rest, " \t:=")
		if i := bytes.IndexAny(expr, ";,"); i >= 0 {
			expr = expr[:i]
		}
		if i := commentStart(expr); i >= 0 {
			expr = expr[:i]
		}
		if v := joinLiterals(expr); v != "" {
			return v
		}
	}
	return ""
}

// commentStart is where a // comment starts in expr outside string literals,
// or -1.
func commentStart(expr []byte) int {
	var quote byte
	for i, ch := range expr {
		switch {
		case quote != 0:
			if ch == quote {
				quote = 0
			}
		case ch == '\'' || ch == '"':
			quote = ch
		case ch == '/' && i+1 < len(expr) && expr[i+1] == '/':
			return i
		}
	}
	return -1
}

// joinLiterals is the string literals of a JS expression, joined, up to one
// left open: `'conference.' + subdomain + 'example.org'` gives
// conference.example.org.
func joinLiterals(expr []byte) string {
	var out []byte
	for {
		start := bytes.IndexAny(expr, `'"`)
		if start < 0 {
			return string(out)
		}
		end := bytes.IndexByte(expr[start+1:], expr[start])
		if end < 0 {
			return string(out)
		}
		out = append(out, expr[start+1:start+1+end]...)
		expr = expr[start+2+end:]
	}
}

// shouldRetryWithFallback reports whether a failed join gets one more try on
// the docker-jitsi-meet defaults: the server refused the XMPP domain with a
// <host-unknown/> stream error, and that domain was the library's guess
// because config.js could not be read.
func shouldRetryWithFallback(joinErr error, discoveryFailed bool) bool {
	return joinErr != nil && discoveryFailed && strings.Contains(joinErr.Error(), "<host-unknown")
}

// joinMUC joins the room with config.js discovery under the library, and
// once more on the docker-jitsi-meet defaults when discovery failed and the
// server refused the web host the library fell back to.
func (s *Session) joinMUC(ctx context.Context) (*j.Session, error) {
	cfg, disc := s.joinConfig(false)
	jSess, err := j.JoinMUC(ctx, cfg)
	failure := disc.failure()
	fallback := shouldRetryWithFallback(err, failure != "")
	if fallback {
		logger.Infof("jitsi: server refused XMPP domain %s, config.js unavailable (%s); retrying with %s",
			s.host, failure, fallbackXMPPDomain)
		cfg, _ = s.joinConfig(true)
		jSess, err = j.JoinMUC(ctx, cfg)
	}
	if err != nil {
		if failure != "" {
			return nil, fmt.Errorf("jitsi join muc: %w (config.js unavailable: %s)", err, failure)
		}
		return nil, fmt.Errorf("jitsi join muc: %w", err)
	}
	switch domain := disc.domain(); {
	case fallback:
		logger.Infof("jitsi: XMPP domain %s (docker-jitsi-meet default)", fallbackXMPPDomain)
	case domain != "":
		logger.Infof("jitsi: XMPP domain %s (from config.js)", domain)
	default:
		logger.Infof("jitsi: XMPP domain %s (web host; config.js unavailable: %s)", s.host, failure)
	}
	return jSess, nil
}

// joinConfig is the library's config for one join: a copy of the session's
// http.Client with a configJS under it.
func (s *Session) joinConfig(fallback bool) (j.Config, *configJS) {
	client := &http.Client{}
	if s.httpClient != nil {
		*client = *s.httpClient
	}
	disc := newConfigJS(client.Transport, fallback, s.configJSLimits)
	client.Transport = disc
	return j.Config{
		Host:       s.host,
		Room:       s.room,
		Nick:       s.name,
		Debug:      logger.IsVerbose(),
		HTTPClient: client,
	}, disc
}
