package captcha

import (
	neturl "net/url"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"

	fhttp "github.com/bogdanfinn/fhttp"
)

const (
	assetsMaxParallel = 6
	assetsMaxCount    = 40
)

var (
	reAssetScript = regexp.MustCompile(`<script[^>]+src="([^"]+)"`)
	reAssetLink   = regexp.MustCompile(`<link[^>]+>`)
	reAssetHref   = regexp.MustCompile(`href="([^"]+)"`)
	reAssetRel    = regexp.MustCompile(`rel="([^"]+)"`)
	reAssetAs     = regexp.MustCompile(`as="([^"]+)"`)
	reAssetImg    = regexp.MustCompile(`<img[^>]+src="([^"]+)"`)
)

type asset struct {
	URL  string
	Dest string
}

func parsePageAssets(html string) []asset {
	seen := map[string]struct{}{}
	out := make([]asset, 0, assetsMaxCount)

	add := func(raw, dest string) {
		url := absoluteAssetURL(raw)
		if url == "" || len(out) >= assetsMaxCount {
			return
		}
		if _, dup := seen[url]; dup {
			return
		}
		seen[url] = struct{}{}
		out = append(out, asset{URL: url, Dest: dest})
	}

	for _, m := range reAssetScript.FindAllStringSubmatch(html, -1) {
		add(m[1], "script")
	}
	for _, tag := range reAssetLink.FindAllString(html, -1) {
		href := reAssetHref.FindStringSubmatch(tag)
		if len(href) < 2 {
			continue
		}
		add(href[1], linkDest(tag))
	}
	for _, m := range reAssetImg.FindAllStringSubmatch(html, -1) {
		add(m[1], "image")
	}
	return out
}

func linkDest(tag string) string {
	if as := reAssetAs.FindStringSubmatch(tag); len(as) > 1 {
		return as[1]
	}
	rel := ""
	if m := reAssetRel.FindStringSubmatch(tag); len(m) > 1 {
		rel = m[1]
	}
	switch {
	case strings.Contains(rel, "stylesheet"):
		return "style"
	case strings.Contains(rel, "icon"):
		return "image"
	default:
		return "empty"
	}
}

// Рекламные хосты сюда не входят: manual их блокирует и всё равно проходит.
var assetHostSuffixes = []string{".vk.com", ".vk.ru", ".userapi.com", ".okcdn.ru", ".mycdn.me"}

// Относительные пути пропускаются: статика раздаётся с CDN.
func absoluteAssetURL(raw string) string {
	raw = strings.TrimSpace(raw)
	switch {
	case strings.HasPrefix(raw, "https://"):
	case strings.HasPrefix(raw, "//"):
		raw = "https:" + raw
	default:
		return ""
	}
	u, err := neturl.Parse(raw)
	if err != nil {
		return ""
	}
	host := u.Hostname()
	for _, suffix := range assetHostSuffixes {
		if strings.HasSuffix(host, suffix) || host == suffix[1:] {
			return raw
		}
	}
	return ""
}

func assetAccept(dest string) string {
	switch dest {
	case "style":
		return "text/css,*/*;q=0.1"
	case "image":
		return "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
	default:
		return "*/*"
	}
}

func (s *captchaSession) loadAssets(assets []asset) {
	if len(assets) == 0 {
		return
	}
	sem := make(chan struct{}, assetsMaxParallel)
	var wg sync.WaitGroup
	var loaded, failed atomic.Int32

	for _, a := range assets {
		wg.Go(func() {
			sem <- struct{}{}
			defer func() { <-sem }()

			// Шрифт браузер тянет в cors, остальное - no-cors.
			mode := "no-cors"
			if a.Dest == "font" {
				mode = "cors"
			}
			_, err := s.doRaw(fhttp.MethodGet, a.URL, nil, map[string]string{
				"Accept":         assetAccept(a.Dest),
				"Sec-Fetch-Dest": a.Dest,
				"Sec-Fetch-Mode": mode,
				"Sec-Fetch-Site": "same-site",
				"Referer":        s.pageOrigin + "/",
			})
			if err != nil {
				failed.Add(1)
			} else {
				loaded.Add(1)
			}
		})
	}
	wg.Wait()
	s.logger().Debugf("[Captcha] assets loaded=%d failed=%d", loaded.Load(), failed.Load())
}
