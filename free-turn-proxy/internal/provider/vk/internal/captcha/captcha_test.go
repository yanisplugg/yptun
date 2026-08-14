package captcha

import (
	"context"
	"strings"
	"testing"
)

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

func TestParseCaptchaPageSPA(t *testing.T) {
	html := `<html><head><script>
const powInput = "Pihj7tyAHFxdwm4t";
const difficulty = 2;
</script>
<script src="https://static.vk.ru/vkid/1.1.1384/not_robot_captcha.js"></script>
</head><body><div id="spa_root"></div></body></html>`

	page, err := parseCaptchaPage(html)
	if err != nil {
		t.Fatal(err)
	}
	if page.PowInput != "Pihj7tyAHFxdwm4t" || page.PowDifficulty != 2 {
		t.Fatalf("pow parse = %q/%d", page.PowInput, page.PowDifficulty)
	}
	if page.ScriptURL != "https://static.vk.ru/vkid/1.1.1384/not_robot_captcha.js" {
		t.Fatalf("script url = %q", page.ScriptURL)
	}
}

func TestParseCaptchaPageMissingPoW(t *testing.T) {
	if _, err := parseCaptchaPage(`<html><body><div id="spa_root"></div></body></html>`); err == nil {
		t.Fatal("expected error when powInput/difficulty absent")
	}
}

func TestSolveCaptchaPoWRawHex(t *testing.T) {
	got := solveCaptchaPoW(context.Background(), "input", 1)
	if len(got) != 64 {
		t.Fatalf("pow = %q, want 64-hex", got)
	}
	if !strings.HasPrefix(got, "0") {
		t.Fatalf("pow = %q, want leading zero for difficulty 1", got)
	}
	if again := solveCaptchaPoW(context.Background(), "input", 1); again != got {
		t.Fatalf("pow not deterministic: %q vs %q", got, again)
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
