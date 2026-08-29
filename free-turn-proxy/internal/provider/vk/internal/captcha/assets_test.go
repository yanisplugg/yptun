package captcha

import (
	"strings"
	"testing"
)

const assetsPageHTML = `<html><head>
<link rel="stylesheet" href="https://static.vk.ru/vkid/1.1.1391/main.css">
<link rel="preload" as="font" href="https://static.vk.ru/fonts/vk.woff2">
<link rel="icon" href="//static.vk.ru/favicon.ico">
<script src="https://static.vk.ru/vkid/1.1.1391/not_robot_captcha.js"></script>
<script src="https://static.vk.ru/vkid/1.1.1391/chunk.js"></script>
<script src="/local/inline.js"></script>
</head><body>
<img src="https://static.vk.ru/img/logo.png">
<img src="https://static.vk.ru/img/logo.png">
</body></html>`

func TestParsePageAssetsClassifiesDest(t *testing.T) {
	got := parsePageAssets(assetsPageHTML)

	want := map[string]string{
		"https://static.vk.ru/vkid/1.1.1391/main.css":             "style",
		"https://static.vk.ru/fonts/vk.woff2":                     "font",
		"https://static.vk.ru/favicon.ico":                        "image",
		"https://static.vk.ru/vkid/1.1.1391/not_robot_captcha.js": "script",
		"https://static.vk.ru/vkid/1.1.1391/chunk.js":             "script",
		"https://static.vk.ru/img/logo.png":                       "image",
	}
	if len(got) != len(want) {
		t.Fatalf("assets = %d, want %d: %+v", len(got), len(want), got)
	}
	for _, a := range got {
		if want[a.URL] != a.Dest {
			t.Fatalf("%s dest = %q, want %q", a.URL, a.Dest, want[a.URL])
		}
	}
}

// Относительные пути виджета обслуживает не CDN, и абсолютного URL для них нет.
func TestParsePageAssetsDropsRelative(t *testing.T) {
	for _, a := range parsePageAssets(assetsPageHTML) {
		if a.URL == "/local/inline.js" {
			t.Fatal("relative asset must be dropped")
		}
	}
}

// Рекламу manual блокирует и всё равно проходит - авто-путь не должен её тянуть.
func TestParsePageAssetsDropsForeignHosts(t *testing.T) {
	html := `<script src="https://ad.mail.ru/static/sync-loader.js"></script>` +
		`<script src="https://static.vk.ru/vkid/app.js"></script>`
	got := parsePageAssets(html)
	if len(got) != 1 || got[0].URL != "https://static.vk.ru/vkid/app.js" {
		t.Fatalf("assets = %+v, want only the vk.ru script", got)
	}
}

func TestParsePageAssetsCaps(t *testing.T) {
	var html strings.Builder
	for i := range assetsMaxCount + 20 {
		html.WriteString(`<script src="https://static.vk.ru/` + string(rune('a'+i%26)) + string(rune('a'+i/26)) + `.js"></script>`)
	}
	if got := len(parsePageAssets(html.String())); got > assetsMaxCount {
		t.Fatalf("assets = %d, want <= %d", got, assetsMaxCount)
	}
}
