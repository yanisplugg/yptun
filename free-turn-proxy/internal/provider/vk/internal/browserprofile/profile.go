package browserprofile

import (
	"encoding/json"
	"os"
	"strings"

	fhttp "github.com/bogdanfinn/fhttp"
)

// Kind - семейство браузера.
type Kind string

const (
	Chrome  Kind = "chrome"
	Firefox Kind = "firefox"
	Safari  Kind = "safari"
)

// KindFromString мапит строку флага -browser в Kind. Пустое/неизвестное -
// Firefox (текущий дефолт продукта).
func KindFromString(s string) Kind {
	switch s {
	case string(Chrome):
		return Chrome
	case string(Safari):
		return Safari
	}
	return Firefox
}

type Profile struct {
	UserAgent       string
	SecChUa         string // пусто для non-Chromium профилей (Chromium-only client hint)
	SecChUaMobile   string
	SecChUaPlatform string
	AcceptLanguage  string
}

// ForKind возвращает канонический профиль для браузера.
func ForKind(k Kind) Profile {
	switch k {
	case Safari:
		return Profile{
			UserAgent:      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Safari/605.1.15",
			AcceptLanguage: "ru-RU,ru;q=0.9",
		}
	case Firefox:
		return Profile{
			UserAgent:      "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:148.0) Gecko/20100101 Firefox/148.0",
			AcceptLanguage: "ru-RU,ru;q=0.9",
		}
	}
	return Profile{
		UserAgent:       "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
		SecChUa:         `"Google Chrome";v="146", "Chromium";v="146", "Not)A;Brand";v="24"`,
		SecChUaMobile:   "?0",
		SecChUaPlatform: `"Windows"`,
		AcceptLanguage:  "ru-RU,ru;q=0.9",
	}
}

func Family(p Profile) Kind {
	ua := strings.ToLower(p.UserAgent)
	switch {
	case strings.Contains(ua, "firefox/"):
		return Firefox
	case strings.Contains(ua, "version/") && strings.Contains(ua, "safari/") && !strings.Contains(ua, "chrome/") && !strings.Contains(ua, "chromium/"):
		return Safari
	case strings.Contains(ua, "chrome/") || strings.Contains(ua, "chromium/"):
		return Chrome
	default:
		return ""
	}
}

func IsMobile(p Profile) bool {
	ua := strings.ToLower(p.UserAgent)
	return strings.Contains(ua, " mobile ") ||
		strings.Contains(ua, " mobile/") ||
		strings.Contains(ua, "android") ||
		strings.Contains(strings.ToLower(p.SecChUaMobile), "?1")
}

type Saved struct {
	Profile
	DeviceJSON string
	BrowserFp  string
}

const profileFile = "vk_profile.json"

// profilePath возвращает путь к сохранённому профилю.
func profilePath() string {
	if p := strings.TrimSpace(os.Getenv("VK_PROFILE_PATH")); p != "" {
		return p
	}
	return profileFile
}

func Load() (*Saved, error) {
	data, err := os.ReadFile(profilePath())
	if err != nil {
		return nil, err
	}
	var sp Saved
	if err := json.Unmarshal(data, &sp); err != nil {
		return nil, err
	}
	return &sp, nil
}

func Save(sp Saved) error {
	data, err := json.MarshalIndent(sp, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(profilePath(), data, 0600)
}

func ApplyFhttp(req *fhttp.Request, profile Profile) {
	req.Header.Set("User-Agent", profile.UserAgent)
	if profile.SecChUa != "" {
		req.Header.Set("sec-ch-ua", profile.SecChUa)
		req.Header.Set("sec-ch-ua-mobile", profile.SecChUaMobile)
		req.Header.Set("sec-ch-ua-platform", profile.SecChUaPlatform)
	}
	acceptLang := profile.AcceptLanguage
	if acceptLang == "" {
		acceptLang = "ru-RU,ru;q=0.9"
	}
	req.Header.Set("Accept-Language", acceptLang)
}
