package wdtt

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	mathrand "math/rand"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	neturl "net/url"

	fhttp "github.com/bogdanfinn/fhttp"
	tlsclient "github.com/bogdanfinn/tls-client"
)

const (
	captchaV2APIVersion    = "5.131"
	captchaV2ScriptVersion = "1.1.1324"
	captchaV2DeviceInfo    = `{"screenWidth":1920,"screenHeight":1080,"screenAvailWidth":1920,"screenAvailHeight":1080,"innerWidth":1920,"innerHeight":951,"devicePixelRatio":1,"language":"en-US","languages":["en-US","en"],"webdriver":false,"hardwareConcurrency":8,"notificationsPermission":"denied"}`
)

var (
	reCaptchaV2PowInput   = regexp.MustCompile(`const\s+powInput\s*=\s*"([^"]+)"`)
	reCaptchaV2Difficulty = regexp.MustCompile(`const\s+difficulty\s*=\s*(\d+)`)
	reCaptchaV2WindowInit = regexp.MustCompile(`(?s)window\.init\s*=\s*(\{.*?})\s*;`)
	reCaptchaV2ScriptSrc  = regexp.MustCompile(`src="(https://[^"]+not_robot_captcha[^"]+)"`)
	reCaptchaV2DebugInfo  = regexp.MustCompile(`debug_info:(?:[^"]*\|\|)?"([a-fA-F0-9]{64})"`)
	reCaptchaV2Version    = regexp.MustCompile(`vkid/([0-9.]*)/not_robot_captcha\.js`)

	errCaptchaV2RateLimit      = errors.New("captcha session rate limit reached")
	errCaptchaV2Bot            = errors.New("captcha bot challenge")
	errCaptchaSessionExpired     = errors.New("captcha session expired, need fresh challenge")

	captchaV2MaxAttempts = 2
	captchaV2MaxSliderChecks = 2

	captchaV2DebugCache  sync.Map // scriptURL -> string
	captchaV2HeaderOrder = []string{
		"host",
		"content-length",
		"sec-ch-ua-platform",
		"accept-language",
		"sec-ch-ua",
		"content-type",
		"sec-ch-ua-mobile",
		"user-agent",
		"accept",
		"origin",
		"sec-fetch-site",
		"sec-fetch-mode",
		"sec-fetch-dest",
		"referer",
		"accept-encoding",
		"priority",
	}
	captchaV2PHeaderOrder = []string{":method", ":path", ":authority", ":scheme"}
)

type captchaV2Init struct {
	Data captchaV2InitData `json:"data"`
}

type captchaV2InitData struct {
	ShowCaptchaType string                 `json:"show_captcha_type"`
	CaptchaSettings []captchaV2InitSetting `json:"captcha_settings"`
}

type captchaV2InitSetting struct {
	Type     string `json:"type"`
	Settings string `json:"settings"`
}

type captchaV2Page struct {
	PowInput      string
	PowDifficulty int
	ScriptURL     string
	Init          *captchaV2Init
}

type captchaV2Check struct {
	Status       string
	SuccessToken string
	ShowType     string
}

type captchaV2ShowTypeError struct {
	ShowType string
}

func (e *captchaV2ShowTypeError) Error() string {
	return "captcha show type mismatch: " + e.ShowType
}

type captchaV2Session struct {
	ctx              context.Context
	client           tlsclient.HttpClient
	profile          Profile
	savedProfile     *SavedProfile
	variedDeviceJSON string
	domain           string
}

func solveVkCaptchaV2Attempts(
	ctx context.Context,
	captchaErr *VkCaptchaError,
	client tlsclient.HttpClient,
	profile Profile,
	savedProfile *SavedProfile,
	maxAttempts int,
) (string, error) {
	if captchaErr == nil || captchaErr.SessionToken == "" {
		return "", fmt.Errorf("no session_token in redirect_uri")
	}
	if maxAttempts < 1 {
		maxAttempts = 1
	}
	log.Printf("[КАПЧА] Решаю VK Smart Captcha автоматически (v2, попыток=%d)...", maxAttempts)

	s := &captchaV2Session{
		ctx:          ctx,
		client:       client,
		profile:      profile,
		savedProfile: savedProfile,
		domain:       captchaDomainFromRedirectURI(captchaErr.RedirectURI),
	}

	for attempt := 1; attempt <= maxAttempts; attempt++ {
		rotateCaptchaV2Identity(s, savedProfile)
		token, solveErr := s.solveOnce(captchaErr)
		if solveErr == nil {
			return token, nil
		}
		log.Printf("[КАПЧА] v2 попытка %d ошибка: %v", attempt, solveErr)
		if errors.Is(solveErr, errCaptchaV2RateLimit) {
			return "", solveErr
		}

		backoffSteps := attempt
		if backoffSteps > 10 {
			backoffSteps = 10
		}
		timer := time.NewTimer(time.Duration(backoffSteps) * 500 * time.Millisecond)
		select {
		case <-ctx.Done():
			timer.Stop()
			return "", ctx.Err()
		case <-timer.C:
		}
	}
	return "", fmt.Errorf("v2 captcha attempts exhausted (%d)", maxAttempts)
}

func (s *captchaV2Session) solveOnce(captchaErr *VkCaptchaError) (string, error) {
	html, err := s.fetchCaptchaHTML(captchaErr.RedirectURI)
	if err != nil {
		return "", err
	}

	page, err := parseCaptchaV2Page(html)
	if err != nil {
		return "", err
	}
	if page.PowInput == "" {
		return "", errors.New("failed to find PoW settings")
	}

	sliderSettings := ""
	if page.Init != nil {
		for _, setting := range page.Init.Data.CaptchaSettings {
			if setting.Type == "slider" {
				sliderSettings = setting.Settings
			}
		}
	}
	if page.Init != nil && page.Init.Data.ShowCaptchaType == "slider" && sliderSettings == "" {
		return "", errors.New("failed to find slider captcha settings")
	}

	log.Printf("[КАПЧА] v2 solving pow difficulty=%d", page.PowDifficulty)
	hash := solveCaptchaPoWV2(s.ctx, page.PowInput, page.PowDifficulty)
	if hash == "" {
		return "", errors.New("captcha pow failed")
	}
	log.Printf("[КАПЧА] v2 pow solved")

	base := captchaV2BaseValues(captchaErr.SessionToken, s.domain)
	if _, settingsErr := s.captchaRequest("captchaNotRobot.settings", base); settingsErr != nil {
		return "", fmt.Errorf("captcha settings failed: %w", settingsErr)
	}

	browserFP := ""
	if s.savedProfile != nil {
		browserFP = strings.TrimSpace(s.savedProfile.BrowserFp)
	}
	if browserFP == "" {
		var err error
		browserFP, err = captchaV2BrowserFP()
		if err != nil {
			return "", err
		}
	}

	if m := reCaptchaV2Version.FindStringSubmatch(page.ScriptURL); len(m) > 1 {
		if m[1] != captchaV2ScriptVersion {
			log.Printf("[КАПЧА] v2 script version drift: known=%s latest=%s", captchaV2ScriptVersion, m[1])
		}
	}

	debugInfo, err := s.fetchDebugInfo(page.ScriptURL)
	if err != nil {
		return "", fmt.Errorf("failed to fetch debug info: %w (script_version=%s)", err, captchaV2ScriptVersion)
	}

	showType := ""
	if page.Init != nil {
		showType = page.Init.Data.ShowCaptchaType
	}
	var token string
	for {
		log.Printf("[КАПЧА] v2 solving show_type=%s", showType)
		switch showType {
		case "slider":
			token, err = s.solveSliderCaptcha(captchaErr.SessionToken, browserFP, hash, sliderSettings, debugInfo)
		case "checkbox", "":
			token, err = s.solveCheckboxCaptcha(captchaErr.SessionToken, browserFP, hash, debugInfo)
		default:
			return "", fmt.Errorf("unsupported captcha type: %s", showType)
		}
		if err == nil {
			break
		}
		if errors.Is(err, errCaptchaV2Bot) && !strings.EqualFold(showType, "slider") && sliderSettings != "" {
			log.Printf("[КАПЧА] v2 checkbox returned BOT, trying slider")
			showType = "slider"
			continue
		}
		var stErr *captchaV2ShowTypeError
		if !errors.As(err, &stErr) || stErr.ShowType == "" {
			return "", err
		}
		showType = stErr.ShowType
	}

	if _, endErr := s.captchaRequest("captchaNotRobot.endSession", base); endErr != nil {
		log.Printf("[КАПЧА] v2 endSession failed: %v", endErr)
	}
	return token, nil
}

func captchaV2BaseValues(sessionToken, domain string) [][2]string {
	return [][2]string{
		{"session_token", sessionToken},
		{"domain", domain},
		{"adFp", ""},
		{"access_token", ""},
	}
}

func captchaDomainFromRedirectURI(redirectURI string) string {
	const fallback = "vk.com"
	parsed, err := url.Parse(redirectURI)
	if err != nil {
		return fallback
	}
	domain := strings.TrimSpace(parsed.Query().Get("domain"))
	if domain == "" {
		return fallback
	}
	return domain
}

func isCaptchaSessionDead(err error) bool {
	if err == nil {
		return false
	}
	msg := strings.ToLower(err.Error())
	return strings.Contains(msg, "getcontent status:") ||
		strings.Contains(msg, "error_limit")
}

func isCaptchaSessionExhausted(err error) bool {
	if err == nil {
		return false
	}
	if errors.Is(err, errCaptchaV2RateLimit) {
		return true
	}
	msg := strings.ToLower(err.Error())
	return isCaptchaSessionDead(err) ||
		strings.Contains(msg, "rate limit")
}

func captchaV2DeviceJSON(savedProfile *SavedProfile) string {
	if savedProfile != nil && strings.TrimSpace(savedProfile.DeviceJSON) != "" {
		return savedProfile.DeviceJSON
	}
	return captchaV2DeviceInfo
}

func (s *captchaV2Session) deviceJSON() string {
	if strings.TrimSpace(s.variedDeviceJSON) != "" {
		return s.variedDeviceJSON
	}
	return captchaV2DeviceJSON(s.savedProfile)
}

// rotateCaptchaV2Identity — новый browser_fp, UA и device_json на каждую попытку v2.
func rotateCaptchaV2Identity(s *captchaV2Session, base *SavedProfile) {
	s.profile = getRandomProfile()
	fp, err := captchaV2BrowserFP()
	if err != nil {
		log.Printf("[КАПЧА] v2 fp generate failed: %v", err)
		return
	}
	if s.savedProfile == nil {
		s.savedProfile = &SavedProfile{}
	}
	s.savedProfile.Profile = s.profile
	s.savedProfile.BrowserFp = fp
	s.variedDeviceJSON = captchaV2VariedDeviceJSON(captchaV2DeviceJSON(base))
	short := fp
	if len(short) > 8 {
		short = fp[:8]
	}
	log.Printf("[КАПЧА] v2 identity rotated fp=%s... ua=%s", short, truncateUA(s.profile.UserAgent))
}

func truncateUA(ua string) string {
	if len(ua) <= 48 {
		return ua
	}
	return ua[:48] + "..."
}

func captchaV2VariedDeviceJSON(base string) string {
	var m map[string]any
	if err := json.Unmarshal([]byte(base), &m); err != nil {
		return base
	}
	jitterInt := func(key string, delta int) {
		v, ok := m[key].(float64)
		if !ok {
			return
		}
		n := int(v) + mathrand.Intn(delta*2+1) - delta
		if n < 320 {
			n = 320
		}
		m[key] = n
	}
	jitterInt("screenWidth", 18)
	jitterInt("screenHeight", 24)
	jitterInt("screenAvailWidth", 18)
	jitterInt("screenAvailHeight", 24)
	jitterInt("innerWidth", 18)
	jitterInt("innerHeight", 20)
	if dpr, ok := m["devicePixelRatio"].(float64); ok && mathrand.Intn(3) == 0 {
		m["devicePixelRatio"] = dpr
	} else {
		opts := []float64{1, 1.25, 1.5, 2, 2.5, 3}
		m["devicePixelRatio"] = opts[mathrand.Intn(len(opts))]
	}
	m["hardwareConcurrency"] = 4 + mathrand.Intn(5)
	langs := [][]string{
		{"ru-RU", "ru", "en-US"},
		{"ru-RU", "ru"},
		{"en-US", "en", "ru-RU"},
	}
	pick := langs[mathrand.Intn(len(langs))]
	m["language"] = pick[0]
	langArr := make([]any, len(pick))
	for i, l := range pick {
		langArr[i] = l
	}
	m["languages"] = langArr
	perms := []string{"default", "denied", "granted"}
	m["notificationsPermission"] = perms[mathrand.Intn(len(perms))]
	out, err := json.Marshal(m)
	if err != nil {
		return base
	}
	return string(out)
}

func captchaV2AcceptLanguage(profile Profile) string {
	if strings.Contains(profile.SecChUaMobile, "?1") {
		return "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7"
	}
	return "en-US,en;q=0.9"
}

func captchaV2BrowserFP() (string, error) {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", fmt.Errorf("browser fp generate: %w", err)
	}
	return hex.EncodeToString(b), nil
}

func (s *captchaV2Session) fetchCaptchaHTML(redirectURI string) (string, error) {
	body, err := s.doRaw(fhttp.MethodGet, redirectURI, nil, map[string]string{
		"Accept":         "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
		"Sec-Fetch-Dest": "document",
		"Sec-Fetch-Mode": "navigate",
		"Sec-Fetch-Site": "cross-site",
	})
	if err != nil {
		return "", err
	}
	return string(body), nil
}

func (s *captchaV2Session) fetchDebugInfo(scriptURL string) (string, error) {
	if cached, ok := captchaV2DebugCache.Load(scriptURL); ok {
		if cachedDebugInfo, ok := cached.(string); ok {
			return cachedDebugInfo, nil
		}
		captchaV2DebugCache.Delete(scriptURL)
	}
	body, err := s.doRaw(fhttp.MethodGet, scriptURL, nil, map[string]string{
		"Accept":  "text/javascript,*/*",
		"Referer": "https://id.vk.com/",
	})
	if err != nil {
		return "", err
	}
	m := reCaptchaV2DebugInfo.FindSubmatch(body)
	if len(m) < 2 {
		return "", errors.New("debug_info match not found")
	}
	v := string(m[1])
	captchaV2DebugCache.Store(scriptURL, v)
	log.Printf("[КАПЧА] v2 debug_info fetched url=%s", scriptURL)
	return v, nil
}

func parseCaptchaV2Page(html string) (*captchaV2Page, error) {
	page := &captchaV2Page{}

	match := reCaptchaV2WindowInit.FindStringSubmatch(html)
	if len(match) < 2 {
		return nil, errors.New("captcha init json not found")
	}
	var init captchaV2Init
	if err := json.Unmarshal([]byte(match[1]), &init); err != nil {
		return nil, fmt.Errorf("captcha init json parse: %w", err)
	}
	page.Init = &init

	match = reCaptchaV2ScriptSrc.FindStringSubmatch(html)
	if len(match) < 2 {
		return nil, errors.New("captcha script url not found")
	}
	page.ScriptURL = match[1]

	if m := reCaptchaV2PowInput.FindStringSubmatch(html); len(m) >= 2 {
		page.PowInput = m[1]
	}
	if page.PowInput == "" {
		return page, nil
	}

	match = reCaptchaV2Difficulty.FindStringSubmatch(html)
	if len(match) < 2 {
		return nil, errors.New("captcha difficulty const not found")
	}
	difficulty, err := strconv.Atoi(match[1])
	if err != nil || difficulty <= 0 {
		return nil, fmt.Errorf("invalid captcha difficulty %q", match[1])
	}
	page.PowDifficulty = difficulty
	return page, nil
}

func (s *captchaV2Session) captchaRequest(method string, form [][2]string) (map[string]any, error) {
	endpoint := "https://api.vk.ru/method/" + method + "?v=" + captchaV2APIVersion
	body, err := s.doRaw(fhttp.MethodPost, endpoint, form, map[string]string{
		"Origin":   "https://id.vk.com",
		"Referer":  "https://id.vk.com/",
		"Priority": "u=1, i",
	})
	if err != nil {
		return nil, err
	}
	var out map[string]any
	if err := json.Unmarshal(body, &out); err != nil {
		return nil, fmt.Errorf("captcha api decode: %w", err)
	}
	return out, nil
}

func (s *captchaV2Session) performCaptchaCheck(
	sessionToken string,
	browserFP string,
	hash string,
	answerJSON string,
	cursor string,
	debugInfo string,
) (*captchaV2Check, error) {
	values := [][2]string{
		{"session_token", sessionToken},
		{"domain", s.domain},
		{"adFp", ""},
		{"accelerometer", "[]"},
		{"gyroscope", "[]"},
		{"motion", "[]"},
		{"cursor", cursor},
		{"taps", "[]"},
		{"connectionRtt", "[]"},
		{"connectionDownlink", "[]"},
		{"browser_fp", browserFP},
		{"hash", hash},
		{"answer", base64.StdEncoding.EncodeToString([]byte(answerJSON))},
		{"debug_info", debugInfo},
		{"access_token", ""},
	}
	resp, err := s.captchaRequest("captchaNotRobot.check", values)
	if err != nil {
		return nil, fmt.Errorf("captcha check failed: %w", err)
	}
	check, err := parseCaptchaV2Check(resp)
	if err != nil {
		return nil, err
	}
	if check.ShowType != "" {
		log.Printf("[КАПЧА] v2 check status=%s show_type=%s", check.Status, check.ShowType)
	} else {
		log.Printf("[КАПЧА] v2 check status=%s", check.Status)
	}
	return check, nil
}

func parseCaptchaV2Check(raw map[string]any) (*captchaV2Check, error) {
	resp, ok := raw["response"].(map[string]any)
	if !ok {
		return nil, fmt.Errorf("invalid captcha check response: %v", raw)
	}
	out := &captchaV2Check{
		Status:       captchaV2StringifyAny(resp["status"]),
		SuccessToken: captchaV2StringifyAny(resp["success_token"]),
		ShowType:     captchaV2StringifyAny(resp["show_captcha_type"]),
	}
	if out.Status == "" {
		return nil, fmt.Errorf("captcha check status missing: %v", raw)
	}
	return out, nil
}

func (s *captchaV2Session) solveCheckboxCaptcha(
	sessionToken string,
	browserFP string,
	hash string,
	debugInfo string,
) (string, error) {
	deviceJSON := s.deviceJSON()
	if _, err := s.captchaRequest("captchaNotRobot.componentDone", [][2]string{
		{"session_token", sessionToken},
		{"domain", s.domain},
		{"adFp", ""},
		{"browser_fp", browserFP},
		{"device", deviceJSON},
		{"access_token", ""},
	}); err != nil {
		return "", fmt.Errorf("captcha componentDone failed: %w", err)
	}

	select {
	case <-s.ctx.Done():
		return "", s.ctx.Err()
	case <-time.After(time.Duration(800+mathrand.Intn(500)) * time.Millisecond):
	}

	check, err := s.performCaptchaCheck(sessionToken, browserFP, hash, "{}", "[]", debugInfo)
	if err != nil {
		return "", err
	}
	if check.ShowType != "" && !strings.EqualFold(check.ShowType, "checkbox") {
		return "", &captchaV2ShowTypeError{ShowType: check.ShowType}
	}
	if strings.EqualFold(check.Status, "error_limit") {
		return "", errCaptchaV2RateLimit
	}
	if strings.EqualFold(check.Status, "bot") {
		return "", fmt.Errorf("%w: checkbox captcha rejected: status=%s", errCaptchaV2Bot, check.Status)
	}
	if !strings.EqualFold(check.Status, "ok") {
		return "", fmt.Errorf("checkbox captcha rejected: status=%s", check.Status)
	}
	if check.SuccessToken == "" {
		return "", errors.New("captcha success token not found")
	}
	return check.SuccessToken, nil
}

func solveCaptchaPoWV2(ctx context.Context, input string, difficulty int) string {
	if input == "" || difficulty <= 0 {
		return ""
	}
	target := strings.Repeat("0", difficulty)
	for nonce := 1; nonce <= 10_000_000; nonce++ {
		if nonce%4096 == 0 {
			select {
			case <-ctx.Done():
				return ""
			default:
			}
		}
		sum := sha256.Sum256([]byte(input + strconv.Itoa(nonce)))
		hashHex := hex.EncodeToString(sum[:])
		if strings.HasPrefix(hashHex, target) {
			return hashHex
		}
	}
	return ""
}

func (s *captchaV2Session) doRaw(
	method string,
	endpoint string,
	form [][2]string,
	extraHeaders map[string]string,
) ([]byte, error) {
	var body []byte
	if form != nil {
		body = []byte(captchaV2EncodeForm(form))
	}
	req, err := fhttp.NewRequestWithContext(s.ctx, method, endpoint, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	applyBrowserProfileFhttp(req, s.profile)
	req.Header.Set("Accept", "*/*")
	req.Header.Set("Sec-Fetch-Site", "same-site")
	req.Header.Set("Sec-Fetch-Mode", "cors")
	req.Header.Set("Sec-Fetch-Dest", "empty")
	req.Header.Set("Origin", "https://vk.com")
	req.Header.Set("Referer", "https://vk.com/")
	if form != nil {
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	}
	for k, v := range extraHeaders {
		req.Header.Set(k, v)
	}
	req.Header[fhttp.HeaderOrderKey] = captchaV2HeaderOrder
	req.Header[fhttp.PHeaderOrderKey] = captchaV2PHeaderOrder

	resp, err := s.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer func() {
		if closeErr := resp.Body.Close(); closeErr != nil {
			log.Printf("[КАПЧА] v2 close body: %s", closeErr)
		}
	}()
	return io.ReadAll(resp.Body)
}

func captchaV2EncodeForm(values [][2]string) string {
	if len(values) == 0 {
		return ""
	}
	var sb strings.Builder
	for i, kv := range values {
		if i > 0 {
			sb.WriteByte('&')
		}
		sb.WriteString(captchaV2QueryEscape(kv[0]))
		sb.WriteByte('=')
		sb.WriteString(captchaV2QueryEscape(kv[1]))
	}
	return sb.String()
}

func captchaV2QueryEscape(s string) string {
	const upper = "0123456789ABCDEF"
	hexDigits := func(b byte) [3]byte {
		return [3]byte{'%', upper[b>>4], upper[b&0xF]}
	}
	out := make([]byte, 0, len(s))
	for i := 0; i < len(s); i++ {
		c := s[i]
		switch {
		case c == ' ':
			out = append(out, '+')
		case ('a' <= c && c <= 'z') || ('A' <= c && c <= 'Z') || ('0' <= c && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~':
			out = append(out, c)
		default:
			h := hexDigits(c)
			out = append(out, h[:]...)
		}
	}
	return string(out)
}

func captchaV2StringifyAny(value any) string {
	switch v := value.(type) {
	case nil:
		return ""
	case string:
		return v
	case float64:
		return strconv.FormatFloat(v, 'f', -1, 64)
	case bool:
		return strconv.FormatBool(v)
	default:
		data, err := json.Marshal(v)
		if err != nil {
			return fmt.Sprintf("%v", v)
		}
		return string(data)
	}
}

// applyBrowserProfileFhttp applies browser headers to fhttp requests
func applyBrowserProfileFhttp(req *fhttp.Request, profile Profile) {
	req.Header.Set("User-Agent", profile.UserAgent)
	req.Header.Set("sec-ch-ua", profile.SecChUa)
	req.Header.Set("sec-ch-ua-mobile", profile.SecChUaMobile)
	req.Header.Set("sec-ch-ua-platform", profile.SecChUaPlatform)
	req.Header.Set("Accept-Language", captchaV2AcceptLanguage(profile))
	req.Header.Set("DNT", "1")
}

// VkCaptchaError represents a VK captcha challenge
type VkCaptchaError struct {
	ErrorCode      int
	ErrorMsg       string
	CaptchaSid     string
	CaptchaImg     string
	RedirectURI    string
	SessionToken   string
	CaptchaTs      string
	CaptchaAttempt string
}

func (e *VkCaptchaError) Error() string {
	if e == nil {
		return "VK captcha required"
	}
	if e.ErrorCode != 0 && e.ErrorCode != 14 {
		if e.ErrorMsg != "" {
			return fmt.Sprintf("VK API error %d: %s", e.ErrorCode, e.ErrorMsg)
		}
		return fmt.Sprintf("VK API error %d", e.ErrorCode)
	}
	if e.RedirectURI != "" {
		return fmt.Sprintf("VK captcha required: redirect_uri, sid=%q", e.CaptchaSid)
	}
	if e.CaptchaImg != "" {
		return fmt.Sprintf("VK captcha required: captcha_img, sid=%q", e.CaptchaSid)
	}
	if e.CaptchaSid != "" {
		return fmt.Sprintf("VK captcha required: sid=%q", e.CaptchaSid)
	}
	if e.ErrorMsg != "" {
		return fmt.Sprintf("VK captcha required: %s", e.ErrorMsg)
	}
	return "VK captcha required"
}

func parseVkCaptchaError(errData map[string]interface{}) *VkCaptchaError {
	codeFloat, _ := errData["error_code"].(float64)
	redirectUri, _ := errData["redirect_uri"].(string)
	errorMsg, _ := errData["error_msg"].(string)
	captchaImg, _ := errData["captcha_img"].(string)

	captchaSid, _ := errData["captcha_sid"].(string)
	if captchaSid == "" {
		if sidNum, ok := errData["captcha_sid"].(float64); ok {
			captchaSid = fmt.Sprintf("%.0f", sidNum)
		}
	}

	var sessionToken string
	if redirectUri != "" {
		if parsed, err := neturl.Parse(redirectUri); err == nil {
			sessionToken = parsed.Query().Get("session_token")
		}
	}

	var captchaTs string
	if tsFloat, ok := errData["captcha_ts"].(float64); ok {
		captchaTs = fmt.Sprintf("%.0f", tsFloat)
	} else if tsStr, ok := errData["captcha_ts"].(string); ok {
		captchaTs = tsStr
	}

	var captchaAttempt string
	if attFloat, ok := errData["captcha_attempt"].(float64); ok {
		captchaAttempt = fmt.Sprintf("%.0f", attFloat)
	} else if attStr, ok := errData["captcha_attempt"].(string); ok {
		captchaAttempt = attStr
	}

	return &VkCaptchaError{
		ErrorCode:      int(codeFloat),
		ErrorMsg:       errorMsg,
		CaptchaSid:     captchaSid,
		CaptchaImg:     captchaImg,
		RedirectURI:    redirectUri,
		SessionToken:   sessionToken,
		CaptchaTs:      captchaTs,
		CaptchaAttempt: captchaAttempt,
	}
}
