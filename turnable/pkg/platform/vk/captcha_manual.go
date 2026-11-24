package vk

import (
	"bytes"
	"compress/gzip"
	"context"
	_ "embed"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	nethttp "net/http"
	"net/http/httputil"
	neturl "net/url"
	"os"
	"os/exec"
	"regexp"
	"runtime"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/theairblow/turnable/pkg/common"
	"github.com/theairblow/turnable/pkg/config"
)

//go:embed captcha_manual.user.js
var vkCaptchaUserScript []byte

//go:embed inject.js
var injectJS string

const (
	manualCaptchaTimeout = 10 * time.Minute // how long the user has to solve the captcha
	manualCaptchaPort    = "1984"           // local port of the captcha page
)

var (
	htmlURLAttrDoubleRe = regexp.MustCompile(`(?i)((?:src|href|action)\s*=\s*)"((?:https?:)?//[^"]+)"`)
	htmlURLAttrSingleRe = regexp.MustCompile(`(?i)((?:src|href|action)\s*=\s*)'((?:https?:)?//[^']+)'`)
	htmlScriptContentRe = regexp.MustCompile(`(?is)(<script[^>]*>)(.*?)(</script>)`)
	htmlStyleContentRe  = regexp.MustCompile(`(?is)(<style[^>]*>)(.*?)(</style>)`)

	blockedProxyHosts = []string{
		"ads.vk.com", "ads.vk.ru",
		"top-fwz1.mail.ru", "r0.mradx.net",
		"sdk-api.apptracer.ru", "stats.vk-portal.net",
	}
)

func isAllowedProxyHost(hostname string) bool {
	for _, blocked := range blockedProxyHosts {
		if strings.EqualFold(hostname, blocked) {
			return false
		}
	}
	allowed := []string{
		".vk.com", ".vk.ru", ".vkontakte.ru",
		".userapi.com", ".okcdn.ru", ".mycdn.me",
		".api.vk.com", ".api.vk.ru",
	}
	for _, suffix := range allowed {
		if strings.HasSuffix(hostname, suffix) || hostname == suffix[1:] {
			return true
		}
	}
	return false
}

func isLocalHost(host, localHost string) bool {
	candidates := []string{
		localHost,
		"localhost:" + strings.TrimPrefix(localHost, "127.0.0.1:"),
		"127.0.0.1:" + strings.TrimPrefix(localHost, "127.0.0.1:"),
	}
	for _, c := range candidates {
		if strings.EqualFold(host, c) {
			return true
		}
	}
	return false
}

func rewriteProxyRedirectLocation(raw string, targetURL *neturl.URL, localOrigin string) (string, bool) {
	if raw != "" && raw[0] == '/' && (len(raw) == 1 || (raw[1] != '/' && raw[1] != '\\')) {
		return raw, true
	}
	parsed, err := neturl.Parse(raw)
	if err != nil {
		return "", false
	}
	if !strings.EqualFold(parsed.Scheme, targetURL.Scheme) || !strings.EqualFold(parsed.Host, targetURL.Host) {
		return "", false
	}
	return localOrigin + parsed.RequestURI(), true
}

func rewriteProxyCookies(header nethttp.Header) {
	cookies := (&nethttp.Response{Header: header}).Cookies()
	if len(cookies) == 0 {
		return
	}
	header.Del("Set-Cookie")
	for _, cookie := range cookies {
		cookie.Domain = ""
		cookie.Secure = false
		cookie.Partitioned = false
		if cookie.SameSite == nethttp.SameSiteNoneMode || cookie.SameSite == nethttp.SameSiteStrictMode {
			cookie.SameSite = nethttp.SameSiteLaxMode
		}
		header.Add("Set-Cookie", cookie.String())
	}
}

func rewriteProxyRequest(req *nethttp.Request, targetURL *neturl.URL, localHost string) {
	req.URL.Scheme = targetURL.Scheme
	req.URL.Host = targetURL.Host
	if req.URL.Path == "" {
		req.URL.Path = targetURL.Path
	}
	req.Host = targetURL.Host

	req.Header.Del("Accept-Encoding")
	req.Header.Del("TE")
	for _, h := range []string{
		"X-Requested-With",
		"X-Android-Package",
		"X-Android-Cert",
		"X-Client-Data",
		"Save-Data",
		"Purpose",
		"Sec-Purpose",
	} {
		req.Header.Del(h)
	}
	for _, headerName := range []string{"Origin", "Referer"} {
		ref := req.Header.Get(headerName)
		if ref == "" {
			continue
		}
		if p, err := neturl.Parse(ref); err == nil && isLocalHost(p.Host, localHost) {
			p.Scheme = targetURL.Scheme
			p.Host = targetURL.Host
			req.Header.Set(headerName, p.String())
		} else {
			req.Header.Del(headerName)
		}
	}
}

func injectScriptTag(localOrigin, upstreamOrigin, resultPath string) string {
	cfg, _ := json.Marshal(map[string]any{
		"local":      localOrigin,
		"upstream":   upstreamOrigin,
		"resultPath": resultPath,
	})
	return "\n<script>window.__ftpCaptcha=" + string(cfg) + ";</script>\n<script>\n" + injectJS + "</script>\n"
}

func rewriteCaptchaHTML(html string, targetURL *neturl.URL, localOrigin, resultPath string) string {
	upstreamOrigin := targetURL.Scheme + "://" + targetURL.Host

	html = strings.ReplaceAll(html, upstreamOrigin, localOrigin)
	html = rewriteHTMLAttrsServerSide(html, targetURL, localOrigin)

	script := injectScriptTag(localOrigin, upstreamOrigin, resultPath)

	switch {
	case strings.Contains(html, "<head>"):
		return strings.Replace(html, "<head>", "<head>"+script, 1)
	case strings.Contains(html, "</head>"):
		return strings.Replace(html, "</head>", script+"</head>", 1)
	case strings.Contains(html, "</body>"):
		return strings.Replace(html, "</body>", script+"</body>", 1)
	default:
		return html + script
	}
}

func rewriteHTMLAttrsServerSide(html string, targetURL *neturl.URL, localOrigin string) string {
	upstreamOrigin := targetURL.Scheme + "://" + targetURL.Host

	pathProxyURL := func(abs string) string {
		u, err := neturl.Parse(abs)
		if err != nil || u.Host == "" || u.Scheme != "https" {
			return "/generic_proxy?proxy_url=" + neturl.QueryEscape(abs)
		}
		p := "/_p/" + u.Host + u.EscapedPath()
		if u.RawQuery != "" {
			p += "?" + u.RawQuery
		}
		return p
	}

	rewriteURL := func(rawURL string) string {
		absURL := rawURL
		if strings.HasPrefix(rawURL, "//") {
			absURL = targetURL.Scheme + ":" + rawURL
		}
		if strings.HasPrefix(absURL, upstreamOrigin) {
			return localOrigin + absURL[len(upstreamOrigin):]
		}
		if strings.HasPrefix(absURL, localOrigin) {
			return rawURL
		}
		return pathProxyURL(absURL)
	}

	placeholders := make(map[string]string)

	html = htmlScriptContentRe.ReplaceAllStringFunc(html, func(match string) string {
		groups := htmlScriptContentRe.FindStringSubmatch(match)
		if len(groups) < 4 {
			return match
		}
		id := fmt.Sprintf("@@CONTENT_%d@@", len(placeholders))
		placeholders[id] = groups[2]
		return groups[1] + id + groups[3]
	})

	html = htmlStyleContentRe.ReplaceAllStringFunc(html, func(match string) string {
		groups := htmlStyleContentRe.FindStringSubmatch(match)
		if len(groups) < 4 {
			return match
		}
		id := fmt.Sprintf("@@CONTENT_%d@@", len(placeholders))
		placeholders[id] = groups[2]
		return groups[1] + id + groups[3]
	})

	html = htmlURLAttrDoubleRe.ReplaceAllStringFunc(html, func(match string) string {
		groups := htmlURLAttrDoubleRe.FindStringSubmatch(match)
		if len(groups) < 3 {
			return match
		}
		return groups[1] + `"` + rewriteURL(groups[2]) + `"`
	})

	html = htmlURLAttrSingleRe.ReplaceAllStringFunc(html, func(match string) string {
		groups := htmlURLAttrSingleRe.FindStringSubmatch(match)
		if len(groups) < 3 {
			return match
		}
		return groups[1] + `'` + rewriteURL(groups[2]) + `'`
	})

	for id, content := range placeholders {
		html = strings.Replace(html, id, content, 1)
	}

	return html
}

// solveManualChallenge starts a local reverse-proxy for the VK captcha challenge and waits for the user to solve it
func (V *Handler) solveManualChallenge(ctx context.Context, apiErr vkAPIError) (string, error) {
	if apiErr.RedirectURI == "" {
		return "", errors.New("manual captcha: redirect_uri is empty")
	}

	targetURL, err := neturl.Parse(apiErr.RedirectURI)
	if err != nil || targetURL.Host == "" {
		return "", fmt.Errorf("manual captcha: invalid redirect URI %q: %w", apiErr.RedirectURI, err)
	}

	ln, err := net.Listen("tcp", "127.0.0.1:"+manualCaptchaPort)
	if err != nil {
		ln, err = net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			return "", fmt.Errorf("manual captcha listener failed: %w", err)
		}
	}
	defer ln.Close()

	localHost := ln.Addr().String()
	localOrigin := "http://" + localHost
	localCaptchaURL := localOrigin + targetURL.RequestURI()

	tokenCh := make(chan string, 1)
	resultPath := "/local-captcha-result/" + uuid.NewString()

	transport := &nethttp.Transport{
		DialContext:           common.ResolverDialContext(),
		TLSHandshakeTimeout:   10 * time.Second,
		ResponseHeaderTimeout: 30 * time.Second,
	}
	if proxyStr := os.Getenv("VK_PROXY"); proxyStr != "" {
		if pURL, err := neturl.Parse(proxyStr); err == nil {
			transport.Proxy = nethttp.ProxyURL(pURL)
		}
	}

	serveGeneric := func(w nethttp.ResponseWriter, r *nethttp.Request, targetParsed *neturl.URL) {
		if targetParsed.Host == "" {
			nethttp.Error(w, "Bad URL", nethttp.StatusBadRequest)
			return
		}
		if !isAllowedProxyHost(targetParsed.Hostname()) {
			nethttp.Error(w, "Forbidden host", nethttp.StatusForbidden)
			return
		}
		genericReverse := &httputil.ReverseProxy{
			Transport: transport,
			Rewrite: func(req *httputil.ProxyRequest) {
				req.Out.URL.Path = targetParsed.Path
				req.Out.URL.RawPath = targetParsed.RawPath
				req.Out.URL.RawQuery = targetParsed.RawQuery
				rewriteProxyRequest(req.Out, targetParsed, localHost)
			},
			ModifyResponse: func(res *nethttp.Response) error {
				for _, h := range []string{
					"Content-Security-Policy",
					"Content-Security-Policy-Report-Only",
					"X-Content-Security-Policy",
					"X-WebKit-CSP",
					"Cross-Origin-Opener-Policy",
					"Cross-Origin-Embedder-Policy",
					"Cross-Origin-Resource-Policy",
					"X-Frame-Options",
					"Strict-Transport-Security",
				} {
					res.Header.Del(h)
				}
				res.Header.Set("Access-Control-Allow-Origin", "*")
				if strings.Contains(res.Request.URL.Path, "captchaNotRobot.check") {
					bodyBytes, readErr := io.ReadAll(res.Body)
					if readErr == nil {
						_ = res.Body.Close()
						res.Body = io.NopCloser(bytes.NewReader(bodyBytes))
						res.ContentLength = int64(len(bodyBytes))
						res.Header.Set("Content-Length", fmt.Sprint(len(bodyBytes)))
						var payload struct {
							Response struct {
								SuccessToken string `json:"success_token"`
							} `json:"response"`
						}
						if err := json.Unmarshal(bodyBytes, &payload); err == nil && payload.Response.SuccessToken != "" {
							select {
							case tokenCh <- payload.Response.SuccessToken:
							default:
							}
						}
					}
				}
				return nil
			},
		}
		genericReverse.ServeHTTP(w, r)
	}

	proxy := &httputil.ReverseProxy{
		Transport: transport,
		Rewrite: func(req *httputil.ProxyRequest) {
			rewriteProxyRequest(req.Out, targetURL, localHost)
		},
		ErrorHandler: func(w nethttp.ResponseWriter, r *nethttp.Request, err error) {
			slog.Warn("manual captcha proxy error", "method", r.Method, "url", r.URL.String(), "error", err)
			nethttp.Error(w, "captcha proxy error", nethttp.StatusBadGateway)
		},
		ModifyResponse: func(res *nethttp.Response) error {
			rewriteProxyCookies(res.Header)

			if res.StatusCode >= 300 && res.StatusCode < 400 {
				if loc := res.Header.Get("Location"); loc != "" {
					if rewritten, ok := rewriteProxyRedirectLocation(loc, targetURL, localOrigin); ok {
						res.Header.Set("Location", rewritten)
					} else {
						res.Header.Del("Location")
					}
				}
			}

			contentType := res.Header.Get("Content-Type")
			shouldInspectBody := strings.Contains(contentType, "text/html") ||
				strings.Contains(contentType, "application/xhtml+xml") ||
				strings.Contains(res.Request.URL.Path, "captchaNotRobot.check")

			if !shouldInspectBody {
				return nil
			}

			reader := io.Reader(res.Body)
			if res.Header.Get("Content-Encoding") == "gzip" {
				gzReader, err := gzip.NewReader(res.Body)
				if err == nil {
					reader = gzReader
					defer gzReader.Close()
				}
			}

			bodyBytes, err := io.ReadAll(reader)
			if err != nil {
				return err
			}
			_ = res.Body.Close()

			if strings.Contains(res.Request.URL.Path, "captchaNotRobot.check") {
				var payload struct {
					Response struct {
						SuccessToken string `json:"success_token"`
					} `json:"response"`
				}
				if err := json.Unmarshal(bodyBytes, &payload); err == nil && payload.Response.SuccessToken != "" {
					select {
					case tokenCh <- payload.Response.SuccessToken:
					default:
					}
				}
			}

			if strings.Contains(contentType, "text/html") {
				for _, headerName := range []string{
					"Content-Security-Policy",
					"Content-Security-Policy-Report-Only",
					"X-Content-Security-Policy",
					"X-WebKit-CSP",
					"Cross-Origin-Opener-Policy",
					"Cross-Origin-Embedder-Policy",
					"Cross-Origin-Resource-Policy",
					"X-Frame-Options",
					"Strict-Transport-Security",
					"Alt-Svc",
				} {
					res.Header.Del(headerName)
				}

				bodyBytes = []byte(rewriteCaptchaHTML(string(bodyBytes), targetURL, localOrigin, resultPath))
			}

			res.Header.Del("Content-Encoding")
			res.Body = io.NopCloser(bytes.NewReader(bodyBytes))
			res.ContentLength = int64(len(bodyBytes))
			res.Header.Set("Content-Length", fmt.Sprint(len(bodyBytes)))
			return nil
		},
	}

	mux := nethttp.NewServeMux()
	mux.HandleFunc(resultPath, func(w nethttp.ResponseWriter, r *nethttp.Request) {
		_ = r.ParseForm()
		tok := r.FormValue("token")
		if tok != "" {
			select {
			case tokenCh <- tok:
			default:
			}
		}
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		_, _ = fmt.Fprint(w, "ok")
	})

	mux.HandleFunc("/_p/", func(w nethttp.ResponseWriter, r *nethttp.Request) {
		escaped := strings.TrimPrefix(r.URL.EscapedPath(), "/_p/")
		host, path, ok := strings.Cut(escaped, "/")
		if !ok || host == "" {
			nethttp.Error(w, "Bad URL", nethttp.StatusBadRequest)
			return
		}
		escapedURL := "https://" + host + "/" + path
		if r.URL.RawQuery != "" {
			escapedURL += "?" + r.URL.RawQuery
		}
		targetParsed, err := neturl.Parse(escapedURL)
		if err != nil {
			nethttp.Error(w, "Bad URL", nethttp.StatusBadRequest)
			return
		}
		serveGeneric(w, r, targetParsed)
	})

	mux.HandleFunc("/generic_proxy", func(w nethttp.ResponseWriter, r *nethttp.Request) {
		targetParsed, err := neturl.Parse(r.URL.Query().Get("proxy_url"))
		if err != nil || targetParsed.Host == "" {
			nethttp.Error(w, "Bad URL", nethttp.StatusBadRequest)
			return
		}
		serveGeneric(w, r, targetParsed)
	})

	mux.HandleFunc("/", func(w nethttp.ResponseWriter, r *nethttp.Request) {
		if r.URL.Path == "/" && targetURL.Path != "" && targetURL.Path != "/" && r.URL.RawQuery == "" {
			nethttp.Redirect(w, r, localCaptchaURL, nethttp.StatusTemporaryRedirect)
			return
		}
		proxy.ServeHTTP(w, r)
	})

	srv := &nethttp.Server{Handler: mux, ReadHeaderTimeout: 10 * time.Second}
	go func() {
		if err := srv.Serve(ln); err != nil && !errors.Is(err, nethttp.ErrServerClosed) {
			slog.Warn("manual captcha server error", "error", err)
		}
	}()

	slog.Info("manual captcha challenge ready", "url", localCaptchaURL)
	fmt.Printf("VK_CAPTCHA_URL|%s\n", localCaptchaURL)
	_ = os.Stdout.Sync()

	if config.Options.Interactive {
		manualCaptchaOpenBrowser(localCaptchaURL)
	}

	var (
		token    string
		solveErr error
	)
	solveCtx, solveCancel := context.WithTimeout(ctx, 5*time.Minute)
	defer solveCancel()

	select {
	case <-solveCtx.Done():
		solveErr = solveCtx.Err()
	case token = <-tokenCh:
	}

	shutCtx, shutCancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer shutCancel()
	_ = srv.Shutdown(shutCtx)

	fmt.Println("VK_CAPTCHA_DONE")
	_ = os.Stdout.Sync()
	slog.Info("manual captcha challenge finished", "has_token", token != "")

	if solveErr != nil {
		return "", solveErr
	}
	if token == "" {
		return "", errors.New("manual captcha returned empty token")
	}
	return token, nil
}

// manualCaptchaResult carries both tokens extracted from the VK join page flow
type manualCaptchaResult struct {
	messages string // token for the messages API
	calls    string // token for the calls API
}

// solveManualCaptcha starts a local token capture server and prompts the user to run a userscript
func (V *Handler) solveManualCaptcha(ctx context.Context, joinURL string) (string, string, error) {
	keyCh := make(chan manualCaptchaResult, 1)

	mux := nethttp.NewServeMux()
	mux.HandleFunc("/ping", func(w nethttp.ResponseWriter, r *nethttp.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		_, _ = fmt.Fprint(w, "ok")
	})
	mux.HandleFunc("/captcha_manual.user.js", func(w nethttp.ResponseWriter, r *nethttp.Request) {
		w.Header().Set("Content-Type", "application/javascript; charset=utf-8")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		_, _ = w.Write(vkCaptchaUserScript)
	})
	mux.HandleFunc("/", func(w nethttp.ResponseWriter, r *nethttp.Request) {
		nethttp.Redirect(w, r, "https://github.com/TheAirBlow/Turnable/blob/main/docs/VK.md", nethttp.StatusFound)
	})
	mux.HandleFunc("/done", func(w nethttp.ResponseWriter, r *nethttp.Request) {
		mToken := r.URL.Query().Get("messages")
		aToken := r.URL.Query().Get("calls")
		if aToken != "" {
			select {
			case keyCh <- manualCaptchaResult{messages: mToken, calls: aToken}:
			default:
			}
		}

		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = fmt.Fprint(w, `<!DOCTYPE html><html><head><title>Turnable</title>`+
			`<style>*{box-sizing:border-box}body{margin:0;display:flex;align-items:center;justify-content:center;`+
			`min-height:100vh;font-family:system-ui,sans-serif;background:#f0f4f8;color:#1a1a1a}`+
			`h2{margin:16px 0 8px;font-size:22px}p{margin:0;color:#666}</style></head>`+
			`<body><div style="text-align:center">`+
			`<div style="font-size:64px;line-height:1">✅</div>`+
			`<h2>Tokens captured</h2>`+
			`<p>You may close this tab.</p>`+
			`</div></body></html>`)
	})

	ln4, err := net.Listen("tcp", "127.0.0.1:"+manualCaptchaPort)
	if err != nil {
		return "", "", fmt.Errorf("manual captcha: port %s already in use (another instance running?): %w", manualCaptchaPort, err)
	}

	srv := &nethttp.Server{Handler: mux}
	go func() {
		if err := srv.Serve(ln4); err != nil && !errors.Is(err, nethttp.ErrServerClosed) {
			slog.Warn("manual captcha server error", "error", err)
		}
	}()
	if ln6, err := net.Listen("tcp", "[::1]:"+manualCaptchaPort); err == nil {
		go func() {
			if err := srv.Serve(ln6); err != nil && !errors.Is(err, nethttp.ErrServerClosed) {
				slog.Warn("manual captcha server error", "error", err)
			}
		}()
	}

	slog.Info("manual captcha solve required",
		"userscript", "http://localhost:"+manualCaptchaPort+"/captcha_manual.user.js",
		"guide", "http://localhost:"+manualCaptchaPort+"/",
		"url", joinURL,
		"timeout", manualCaptchaTimeout)

	if config.Options.Interactive {
		manualCaptchaOpenBrowser(joinURL)
	}

	var result manualCaptchaResult
	solveCtx, solveCancel := context.WithTimeout(ctx, manualCaptchaTimeout)
	defer solveCancel()

	select {
	case <-solveCtx.Done():
		if errors.Is(solveCtx.Err(), context.DeadlineExceeded) {
			err = fmt.Errorf("manual captcha timed out after %s", manualCaptchaTimeout)
		} else {
			err = solveCtx.Err()
		}
	case result = <-keyCh:
	}

	shutCtx, shutCancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer shutCancel()
	_ = srv.Shutdown(shutCtx)

	return result.messages, result.calls, err
}

// manualCaptchaOpenBrowser tries to open the URL in a browser appropriate for the current platform
func manualCaptchaOpenBrowser(url string) {
	var cmds []struct {
		name string
		args []string
	}

	switch runtime.GOOS {
	case "windows":
		cmds = []struct {
			name string
			args []string
		}{{"cmd", []string{"/c", "start", url}}}
	case "darwin", "ios":
		cmds = []struct {
			name string
			args []string
		}{{"open", []string{url}}}
	case "android":
		cmds = []struct {
			name string
			args []string
		}{
			{"sh", []string{"-c", "termux-open-url " + url}},
			{"sh", []string{"-c", "/system/bin/am start -a android.intent.action.VIEW -d " + url}},
		}
	default:
		cmds = []struct {
			name string
			args []string
		}{
			{"xdg-open", []string{url}},
			{"gio", []string{"open", url}},
			{"sensible-browser", []string{url}},
		}
	}

	for _, c := range cmds {
		if err := exec.Command(c.name, c.args...).Start(); err == nil {
			return
		}
	}
}
