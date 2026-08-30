package captcha

import (
	"errors"
	"testing"
)

// Значения query в лог не уезжают - в них session_token.
func TestSafeURL(t *testing.T) {
	tests := []struct{ raw, want string }{
		{"", "-"},
		{"https://api.vk.ru/not_robot_captcha?variant=popup&domain=vk.com&session_token=jwt", "api.vk.ru/not_robot_captcha?domain,session_token,variant"},
		{"https://id.vk.ru/", "id.vk.ru/"},
		{"https://api.vk.ru", "api.vk.ru/"},
	}
	for _, tt := range tests {
		if got := SafeURL(tt.raw); got != tt.want {
			t.Errorf("SafeURL(%q) = %q, want %q", tt.raw, got, tt.want)
		}
	}
}

func TestCaptchaInitSettingContentRefPrefersSettingsKey(t *testing.T) {
	setting := captchaInitSetting{
		Type:        "slider",
		Settings:    "legacy-settings",
		SettingsKey: "new-settings-key",
	}

	got := setting.contentRef()
	if got.Source != "settings_key" || got.Value != "new-settings-key" {
		t.Fatalf("contentRef = %+v, want settings_key/new-settings-key", got)
	}
}

func TestCaptchaInitSettingContentRefLegacySettings(t *testing.T) {
	setting := captchaInitSetting{
		Type:     "slider",
		Settings: "legacy-settings",
	}

	got := setting.contentRef()
	if got.Source != "captcha_settings" || got.Value != "legacy-settings" {
		t.Fatalf("contentRef = %+v, want captcha_settings/legacy-settings", got)
	}
}

func TestParseCaptchaInitSession(t *testing.T) {
	raw := map[string]any{"response": map[string]any{
		"show_captcha_type": "slider",
		"captcha_id":        "cid",
		"content_settings": []any{
			map[string]any{"type": "slider", "settings_key": "sliderkey"},
			map[string]any{"type": "sound", "settings_key": "soundkey"},
		},
	}}
	showType, content := parseCaptchaInitSession(raw)
	if showType != "slider" {
		t.Fatalf("show_type = %q, want slider", showType)
	}
	if content.Value != "sliderkey" || content.Source != "settings_key" {
		t.Fatalf("content = %+v, want sliderkey/settings_key", content)
	}
}

func TestParseCaptchaInitSessionCheckbox(t *testing.T) {
	raw := map[string]any{"response": map[string]any{
		"show_captcha_type": "checkbox",
		"content_settings": []any{
			map[string]any{"type": "slider", "settings_key": "k"},
		},
	}}
	showType, _ := parseCaptchaInitSession(raw)
	if showType != "checkbox" {
		t.Fatalf("show_type = %q, want checkbox", showType)
	}
}

func TestCaptchaDomainFromRedirectURI(t *testing.T) {
	tests := []struct {
		name        string
		redirectURI string
		want        string
	}{
		{
			name:        "vk com from query",
			redirectURI: "https://id.vk.ru/not_robot_captcha?domain=vk.com&session_token=x",
			want:        "vk.com",
		},
		{
			name:        "vk ru from query",
			redirectURI: "https://id.vk.ru/not_robot_captcha?domain=vk.ru&session_token=x",
			want:        "vk.ru",
		},
		{
			name:        "fallback without domain",
			redirectURI: "https://id.vk.ru/not_robot_captcha?session_token=x",
			want:        captchaDomain,
		},
		{
			name:        "fallback invalid url",
			redirectURI: "%",
			want:        captchaDomain,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := captchaDomainFromRedirectURI(tt.redirectURI); got != tt.want {
				t.Fatalf("domain = %q, want %q", got, tt.want)
			}
		})
	}
}

func TestPickSliderAttempts(t *testing.T) {
	tests := []struct {
		name    string
		indexes []int
		limit   int
		want    []int
	}{
		{
			name:    "skips neighbours of the top guess",
			indexes: []int{20, 19, 21, 18, 40, 5},
			limit:   2,
			want:    []int{20, 40},
		},
		{
			name:    "falls back to neighbours when nothing is far enough",
			indexes: []int{20, 19, 21},
			limit:   3,
			want:    []int{20, 19, 21},
		},
		{
			name:    "limit above candidate count",
			indexes: []int{7},
			limit:   4,
			want:    []int{7},
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			guesses := make([]sliderGuess, 0, len(tt.indexes))
			for _, idx := range tt.indexes {
				guesses = append(guesses, sliderGuess{Index: idx})
			}
			got := pickSliderAttempts(guesses, tt.limit)
			if len(got) != len(tt.want) {
				t.Fatalf("len = %d, want %d", len(got), len(tt.want))
			}
			for i := range tt.want {
				if got[i].Index != tt.want[i] {
					t.Fatalf("attempts = %v, want %v", got, tt.want)
				}
			}
		})
	}
}

// VK раздаёт страницу то с id.vk.ru, то с api.vk.ru: Origin от одного хоста при
// странице на другом браузер выдать не может.
func TestAPIRequestHeadersFollowPageOrigin(t *testing.T) {
	const pageQuery = "/not_robot_captcha?domain=vk.com&session_token=x&variant=popup"

	cases := []struct {
		name     string
		page     string
		apiHost  string
		wantSite string
		wantRef  string
		wantOrig string
	}{
		{
			name:     "cross-origin page",
			page:     "https://id.vk.ru" + pageQuery,
			apiHost:  "api.vk.ru",
			wantSite: "same-site",
			wantRef:  "https://id.vk.ru/",
			wantOrig: "https://id.vk.ru",
		},
		{
			name:     "same-origin page keeps full url in referer",
			page:     "https://api.vk.ru" + pageQuery,
			apiHost:  "api.vk.ru",
			wantSite: "same-origin",
			wantRef:  "https://api.vk.ru" + pageQuery,
			wantOrig: "https://api.vk.ru",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			s := &captchaSession{apiHost: tc.apiHost}
			s.setPageURL(tc.page)
			got := s.apiRequestHeaders()
			if got["Sec-Fetch-Site"] != tc.wantSite {
				t.Fatalf("Sec-Fetch-Site = %q, want %q", got["Sec-Fetch-Site"], tc.wantSite)
			}
			if got["Referer"] != tc.wantRef {
				t.Fatalf("Referer = %q, want %q", got["Referer"], tc.wantRef)
			}
			if got["Origin"] != tc.wantOrig {
				t.Fatalf("Origin = %q, want %q", got["Origin"], tc.wantOrig)
			}
		})
	}
}

// Пока challenge есть в window.init, виджет не ходит в initSession - и мы тоже.
func TestParseCaptchaInitGlobal(t *testing.T) {
	html := `<script>window.init = {"hosts":{"api":"api.vk.ru"},` +
		`"data":{"show_captcha_type":"slider","captcha_settings":[{"type":"slider","settings_key":"a2V5"}]},` +
		`"tail":{"brace":"}"}};</script>`
	got := parseCaptchaInitGlobal(html)
	if !got.Found || got.APIHost != "api.vk.ru" || got.ShowType != "slider" {
		t.Fatalf("init = %+v", got)
	}
	if got.Content.Value != "a2V5" || got.Content.Source != "settings_key" {
		t.Fatalf("content = %+v", got.Content)
	}
}

// Без window.init виджет запрашивает initSession сам - и Found обязан быть false.
func TestParseCaptchaInitGlobalAbsent(t *testing.T) {
	if got := parseCaptchaInitGlobal(`<script>var x = 1;</script>`); got.Found || got.APIHost != "" {
		t.Fatalf("init = %+v, want empty", got)
	}
}

// Битый redirect_uri не должен оставлять Origin пустым.
func TestSetPageURLFallsBackToWidgetOrigin(t *testing.T) {
	s := &captchaSession{apiHost: "api.vk.ru"}
	s.setPageURL("not-a-url")
	if s.pageOrigin != captchaAPIOrigin || s.pageURL != captchaAPIOrigin+"/" {
		t.Fatalf("page = %q / %q", s.pageOrigin, s.pageURL)
	}
}

func TestParseCaptchaDebugInfo(t *testing.T) {
	tests := []struct {
		name, html, want string
	}{
		{
			name: "renamed key",
			html: `<script>window.vk = {stDomain: "https://st.vk.ru", qqqqqqqq: "273cc83f-426f-4d98-9ce5-92490107e3a6", id: 0};</script>`,
			want: "273cc83f-426f-4d98-9ce5-92490107e3a6",
		},
		{
			name: "quoted keys of nested json are not candidates",
			html: `<script>window.vk = {statsMeta: {"hash":"X84u4GhF","uuid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"}, k: "273cc83f-426f-4d98-9ce5-92490107e3a6"};</script>`,
			want: "273cc83f-426f-4d98-9ce5-92490107e3a6",
		},
		{name: "no window.vk", html: `<script>var x = 1;</script>`},
		{name: "no uuid in block", html: `<script>window.vk = {id: 0, logoutUrl: ""};</script>`},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := parseCaptchaDebugInfo(tt.html); got != tt.want {
				t.Fatalf("debug_info = %q, want %q", got, tt.want)
			}
		})
	}
}

func TestParseCaptchaDebugInfoAmbiguous(t *testing.T) {
	html := `<script>window.vk = {a: "273cc83f-426f-4d98-9ce5-92490107e3a6", b: "ec772ebb-0d69-4fa0-b974-904549c8a7d1"};</script>`
	if got := parseCaptchaDebugInfo(html); got != "273cc83f-426f-4d98-9ce5-92490107e3a6" {
		t.Fatalf("debug_info = %q", got)
	}
}

func TestParseCaptchaPageRejectsNonCaptchaHTML(t *testing.T) {
	t.Parallel()
	_, err := parseCaptchaPage("<html><head><title>429 Too Many Requests</title></head><body></body></html>")
	if !errors.Is(err, ErrUnavailable) {
		t.Fatalf("err = %v, want ErrUnavailable", err)
	}
}

// Смена обфускации - не аутаж: ошибка обязана дойти до эскалации на другой решатель.
// Переименованный конверт PoW - именно этот случай, страница настоящая.
func TestParseCaptchaPageBrokenParserIsNotUnavailable(t *testing.T) {
	t.Parallel()
	for name, html := range map[string]string{
		"pow renamed":  `<script>window.vk = {}; window.zzz = "v2." + solve();</script>`,
		"debug absent": `<script>window.init = {};</script>`,
	} {
		_, err := parseCaptchaPage(html)
		if err == nil || errors.Is(err, ErrUnavailable) {
			t.Fatalf("%s: err = %v, want plain parse error", name, err)
		}
	}
}
