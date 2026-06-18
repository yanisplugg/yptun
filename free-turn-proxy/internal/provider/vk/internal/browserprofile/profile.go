package browserprofile

import (
	"encoding/json"
	"os"

	fhttp "github.com/bogdanfinn/fhttp"
)

// Kind - семейство браузера. Определяет UA, JA3-профиль и набор client hints.
type Kind string

const (
	Chrome  Kind = "chrome"
	Firefox Kind = "firefox"
)

// KindFromString мапит строку флага -browser в Kind. Пустое/неизвестное -
// Firefox (текущий дефолт продукта).
func KindFromString(s string) Kind {
	if s == string(Chrome) {
		return Chrome
	}
	return Firefox
}

type Profile struct {
	UserAgent       string
	SecChUa         string // пусто для Firefox (Chromium-only client hint)
	SecChUaMobile   string
	SecChUaPlatform string
	AcceptLanguage  string
}

// ForKind возвращает канонический профиль для браузера. JA3 (см.
// vkauth.clientProfile) обязан совпадать с UA отсюда, иначе рассинхрон = флаг.
func ForKind(k Kind) Profile {
	if k == Firefox {
		return Profile{
			UserAgent:      "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:147.0) Gecko/20100101 Firefox/147.0",
			AcceptLanguage: "en-US,en;q=0.5",
		}
	}
	return Profile{
		UserAgent:       "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
		SecChUa:         `"Not(A:Brand";v="99", "Google Chrome";v="146", "Chromium";v="146"`,
		SecChUaMobile:   "?0",
		SecChUaPlatform: `"Windows"`,
		AcceptLanguage:  "en-US,en;q=0.9",
	}
}

type Saved struct {
	Profile
	DeviceJSON string
	BrowserFp  string
}

const profileFile = "vk_profile.json"

func Load() (*Saved, error) {
	data, err := os.ReadFile(profileFile)
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
	return os.WriteFile(profileFile, data, 0600)
}

func ApplyFhttp(req *fhttp.Request, profile Profile) {
	req.Header.Set("User-Agent", profile.UserAgent)
	// sec-ch-ua* - Chromium-only client hints. Для Firefox SecChUa пуст -
	// заголовки не ставим (UA Firefox + sec-ch-ua = мгновенный флаг).
	if profile.SecChUa != "" {
		req.Header.Set("sec-ch-ua", profile.SecChUa)
		req.Header.Set("sec-ch-ua-mobile", profile.SecChUaMobile)
		req.Header.Set("sec-ch-ua-platform", profile.SecChUaPlatform)
	}
	acceptLang := profile.AcceptLanguage
	if acceptLang == "" {
		acceptLang = "en-US,en;q=0.9"
	}
	req.Header.Set("Accept-Language", acceptLang)
	req.Header.Set("DNT", "1")
}
