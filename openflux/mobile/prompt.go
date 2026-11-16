package mobile

import (
	"encoding/json"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/p1neappleXpress/OpenFlux/transport"
	"github.com/p1neappleXpress/OpenFlux/transport/manager"
	"github.com/p1neappleXpress/OpenFlux/transport/script"
)

// Android side of the core's out-of-band transport prompt flow. Desktop and
// route it over transport/ipc; gomobile calls straight in instead: the app
// polls PendingPromptURL, lets the user pass the check in a WebView, and
// hands the resulting cookies to SubmitPromptCookies.
var prompt struct {
	mu    sync.Mutex
	store *transport.CookieStore
	key   string
	url   string
	// html is a page the script built itself (template_html.html's
	// contract), shown instead of url when set - a script transport's
	// own setup/login page rather than a real site.
	html string
	// own marks a page that is the script's own (html, or an address on its
	// own loopback server) rather than a real site: the app shows it as it
	// is, offers window.openfluxSubmit and collects no cookies. The core
	// decides (script.IsOwnPage); the app must not guess from the URL.
	own bool
	// name is the transport that asked, for the dialog's title.
	name   string
	reason string
	// apply hands cookies to the live transport that asked for them; nil
	// once that transport stopped or failed to start.
	apply func(map[string]string) error
	// remoteApply delivers cookies to the exit's transport by name; set by
	// attachSessionPrompt so SubmitPromptData can forward the node half
	// of a {client, node} submission to the exit.
	remoteApply func(string, map[string]string) error

	// nodeParams holds the "node" half of a {client, node} setup page
	// submission. It is set by SubmitPromptData and read by
	// PendingNodeParams so the wizard can provision the node with them.
	nodeParams     map[string]string
	nodeParamsName string

	// proxy is set when the check belongs to the exit (remoteName is its
	// transport): the page must be opened through this HTTP proxy so it
	// is passed from the exit's address, and the cookies go to the exit.
	proxy      string
	remoteName string
	// snooze holds off repeated exit checks the user cancelled.
	snooze map[string]time.Time
}

// remoteSnooze is how long a cancelled exit check stays quiet; the exit
// repeats its report every 20-30s while stuck.
const remoteSnooze = 10 * time.Minute

// SetCookieStorePath enables persisting solved-prompt cookies across app
// restarts. Call once before Start/StartProxy; returns "" or an error.
func SetCookieStorePath(path string) string {
	store, err := transport.NewCookieStore(path)
	if err != nil {
		return err.Error()
	}
	prompt.mu.Lock()
	prompt.store = store
	prompt.mu.Unlock()
	return ""
}

// attachPrompt wires the raw (unwrapped, not yet started) transport into
// the flow and replays cookies saved from an earlier solve.
func attachPrompt(transportType, documentURL string, raw transport.Transport) {
	prompt.mu.Lock()
	defer prompt.mu.Unlock()
	prompt.key = transportType + " " + documentURL
	prompt.url = ""
	prompt.html = ""
	prompt.own = false
	prompt.name = ""
	prompt.reason = ""
	prompt.proxy, prompt.remoteName = "", ""
	prompt.apply = nil
	exchanger, _ := raw.(transport.CookieExchanger)
	if exchanger != nil {
		prompt.apply = exchanger.ApplyCookies
	}
	if notifier, ok := raw.(transport.ErrorNotifier); ok {
		notifier.SetErrorNotifier(func(err error, name, url, html, reason string) {
			appendLog(fmt.Sprintf("[ANDROID] %s: нужна проверка в браузере (%s)", name, reason))
			prompt.mu.Lock()
			prompt.url = url
			prompt.html = html
			prompt.own = script.IsOwnPage(url, html)
			prompt.name = name
			prompt.reason = reason
			prompt.mu.Unlock()
		})
	}
	if prompt.store != nil && exchanger != nil {
		if saved := prompt.store.Load(prompt.key); len(saved) > 0 {
			_ = exchanger.ApplyCookies(saved)
		}
	}
}

// attachSessionPrompt does the same for a Session: the Manager reports
// which transport needs the check, and keys maps each cookie-carrying
// transport to its store key. Saved cookies are replayed by the Manager.
func attachSessionPrompt(m *manager.Manager, keys map[string]string, proxy *authProxy) {
	prompt.mu.Lock()
	prompt.key, prompt.url, prompt.html, prompt.reason, prompt.apply = "", "", "", "", nil
	prompt.own = false
	prompt.name = ""
	prompt.proxy, prompt.remoteName = "", ""
	prompt.remoteApply = func(name string, jar map[string]string) error { return m.OfferCookies(name, jar) }
	store := prompt.store
	prompt.mu.Unlock()
	if store != nil {
		for name, key := range keys {
			if err := m.UseCookieStore(store, name, key); err != nil {
				appendLog(fmt.Sprintf("[ANDROID] %s: сохранённые cookies не применились: %v", name, err))
			}
		}
	}
	m.SetPromptNotifier(func(name, url, html, reason string) {
		appendLog(fmt.Sprintf("[ANDROID] %s: нужна проверка в браузере (%s)", name, reason))
		prompt.mu.Lock()
		prompt.url, prompt.html, prompt.reason, prompt.key = url, html, reason, keys[name]
		prompt.own = script.IsOwnPage(url, html)
		prompt.name = name
		prompt.proxy, prompt.remoteName = "", ""
		prompt.apply = func(jar map[string]string) error { return m.ApplyCookiesFor(name, jar) }
		prompt.mu.Unlock()
	})
	if proxy == nil {
		return
	}
	// A check the exit's own transport hit (AuthRequired): it has to be
	// passed from the exit's address, so the page goes through the tunnel,
	// and the cookies go back to the exit, which applies and keeps them.
	m.SetRemoteAuthNotifier(func(name, url, html, reason string) {
		if !script.AcceptRemoteCheck(url, html) {
			appendLog(fmt.Sprintf("[ANDROID] Нода: %s прислала адрес не https, игнорирую", name))
			return
		}
		prompt.mu.Lock()
		quiet := prompt.url != "" || prompt.html != "" || time.Now().Before(prompt.snooze[name])
		prompt.mu.Unlock()
		if quiet {
			return
		}
		addr, err := proxy.Addr()
		if err != nil || addr == "" {
			appendLog(fmt.Sprintf("[ERROR] Прокси для проверки ноды: %v", err))
			return
		}
		appendLog(fmt.Sprintf("[ANDROID] Нода: %s требует проверку в браузере (%s)", name, reason))
		prompt.mu.Lock()
		prompt.url, prompt.html, prompt.reason, prompt.key = url, html, reason, ""
		prompt.own = html != ""
		prompt.name = name
		prompt.proxy, prompt.remoteName = addr, name
		prompt.apply = func(jar map[string]string) error { return m.OfferCookies(name, jar) }
		prompt.mu.Unlock()
	})
}

// PendingPromptProxy returns the HTTP proxy (host:port) to open the
// pending page through when the check belongs to the exit, or "" when it
// is the phone's own.
func PendingPromptProxy() string {
	prompt.mu.Lock()
	defer prompt.mu.Unlock()
	return prompt.proxy
}

// detachPrompt drops the live transport reference, so cookies submitted
// afterwards are only saved for the next start, never applied to a
// transport that failed to start or was stopped.
func detachPrompt() {
	prompt.mu.Lock()
	prompt.apply = nil
	prompt.remoteApply = nil
	prompt.mu.Unlock()
}

// PendingPromptURL returns the page the user must open to pass a check
// or log in, or "" when nothing is pending. Mutually exclusive with
// PendingPromptHTML: a script transport's own page sets one or the other,
// never both. It is https (a real site), or, when PendingPromptOwn is true,
// an http://127.0.0.1:port address the script's own server answers on.
func PendingPromptURL() string {
	prompt.mu.Lock()
	defer prompt.mu.Unlock()
	return prompt.url
}

// PendingPromptHTML returns a script transport's own setup/login page
// (see transport/script/js/template_html.html) to load directly (e.g.
// WebView.loadDataWithBaseURL), or "" when the pending check is a real site
// (PendingPromptURL) or nothing is pending.
func PendingPromptHTML() string {
	prompt.mu.Lock()
	defer prompt.mu.Unlock()
	return prompt.html
}

// PendingPromptOwn reports that the pending page is the script's own: its
// inline PendingPromptHTML, or a PendingPromptURL on the script's own
// loopback server (http://127.0.0.1:port). The app shows it as it is, gives
// it window.openfluxSubmit (SubmitPromptData takes what it hands over) and
// does not look for cookies in it. False for a real site's check.
func PendingPromptOwn() bool {
	prompt.mu.Lock()
	defer prompt.mu.Unlock()
	return prompt.own
}

// PendingPromptTransport is the name of the transport that asked for the
// pending page, "" when nothing is pending.
func PendingPromptTransport() string {
	prompt.mu.Lock()
	defer prompt.mu.Unlock()
	return prompt.name
}

// PendingPromptReason is "smartcaptcha" or "login" while a check is pending.
func PendingPromptReason() string {
	prompt.mu.Lock()
	defer prompt.mu.Unlock()
	return prompt.reason
}

// CancelPrompt clears the pending check without cookies (user gave up).
// An exit check stays quiet for a while instead of popping up again.
func CancelPrompt() {
	prompt.mu.Lock()
	if prompt.remoteName != "" {
		if prompt.snooze == nil {
			prompt.snooze = make(map[string]time.Time)
		}
		prompt.snooze[prompt.remoteName] = time.Now().Add(remoteSnooze)
	}
	prompt.url = ""
	prompt.html = ""
	prompt.own = false
	prompt.name = ""
	prompt.reason = ""
	prompt.proxy, prompt.remoteName = "", ""
	prompt.mu.Unlock()
}

// SubmitPromptCookies takes a Cookie header ("a=1; b=2", the format of
// Android's CookieManager.getCookie), saves it for future starts and applies
// it to the running transport. Returns "" or a user-readable error.
func SubmitPromptCookies(cookieHeader string) string {
	jar := parseCookieHeader(cookieHeader)
	if len(jar) == 0 {
		return "Cookies не получены"
	}
	return submitPromptJar(jar)
}

// SubmitPromptData takes the JSON a script transport's own setup page
// passed to window.openfluxSubmit (script.FlattenSubmission says how it is
// read: flat or {client: {...}, node: {...}}) and delivers the client half
// the same way SubmitPromptCookies delivers real cookies. The node half
// (if present) is stored for PendingNodeParams so the wizard can provision
// the node with it.
func SubmitPromptData(json_ string) string {
	client, node, err := script.FlattenSubmissionScoped([]byte(json_))
	if err != nil {
		return "Страница настройки: " + err.Error()
	}
	prompt.mu.Lock()
	name := prompt.name
	remoteApply := prompt.remoteApply
	if node != nil {
		prompt.nodeParams = node
		prompt.nodeParamsName = name
	}
	prompt.mu.Unlock()
	result := submitPromptJar(client)
	if node != nil && remoteApply != nil {
		go func() {
			if err := remoteApply(name, node); err != nil {
				appendLog(fmt.Sprintf("[ANDROID] Настройка ноды для %s: %v", name, err))
			} else {
				appendLog(fmt.Sprintf("[ANDROID] Настройка ноды передана на выход для %s (%d значений)", name, len(node)))
			}
		}()
	}
	return result
}

// PendingNodeParams returns the node half of the last setup page
// submission as a JSON object {"transport":"name","params":{...}},
// or "" if the page passed a flat object or no node key. The wizard
// reads this during provisioning to set ChannelTransport.Params on
// the exit node. Consuming: the value is cleared after reading.
func PendingNodeParams() string {
	prompt.mu.Lock()
	defer prompt.mu.Unlock()
	if prompt.nodeParams == nil {
		return ""
	}
	out := map[string]interface{}{
		"transport": prompt.nodeParamsName,
		"params":    prompt.nodeParams,
	}
	b, _ := json.Marshal(out)
	prompt.nodeParams = nil
	prompt.nodeParamsName = ""
	return string(b)
}

// submitPromptJar is SubmitPromptCookies/SubmitPromptData's shared tail:
// persist for future starts, hand to the live transport, clear the pending
// check.
func submitPromptJar(jar map[string]string) string {
	prompt.mu.Lock()
	store, key, apply := prompt.store, prompt.key, prompt.apply
	prompt.url = ""
	prompt.html = ""
	prompt.own = false
	prompt.name = ""
	prompt.reason = ""
	prompt.proxy, prompt.remoteName = "", ""
	prompt.mu.Unlock()

	if store != nil && key != "" {
		if err := store.Save(key, jar); err != nil {
			appendLog(fmt.Sprintf("[ERROR] Не удалось сохранить cookies: %v", err))
		}
	}
	if apply != nil {
		// ApplyCookies may sleep through a reconnect backoff.
		go func() {
			if err := apply(jar); err != nil {
				appendLog(fmt.Sprintf("[ERROR] Применение cookies: %v", err))
			}
		}()
	}
	appendLog(fmt.Sprintf("[ANDROID] Получено значений: %d", len(jar)))
	return ""
}

func parseCookieHeader(header string) map[string]string {
	jar := make(map[string]string)
	for _, part := range strings.Split(header, ";") {
		name, value, ok := strings.Cut(strings.TrimSpace(part), "=")
		if ok && name != "" {
			jar[name] = value
		}
	}
	return jar
}
