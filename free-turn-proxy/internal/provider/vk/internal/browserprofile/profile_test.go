package browserprofile

import (
	"encoding/json"
	"regexp"
	"slices"
	"strings"
	"testing"

	fhttp "github.com/bogdanfinn/fhttp"
	"github.com/bogdanfinn/tls-client/profiles"
)

const testSeed = "persona-test"

// personas возвращает по персоне на каждый spec платформы: инварианты обязаны
// держаться на всех, а не только на той, что выпала дефолтной identity.
func personas(t *testing.T, plat Platform) []Profile {
	t.Helper()
	specs := desktopSpecs
	if plat == Mobile {
		specs = mobileSpecs
	}
	seen := make(map[string]Profile, len(specs))
	for gen := 0; gen < 200 && len(seen) < len(specs); gen++ {
		p := For(plat, Identity{Seed: testSeed, Gen: gen})
		seen[p.UserAgent+p.DeviceJSON] = p
	}
	if len(seen) != len(specs) {
		t.Fatalf("%s: covered %d/%d specs", plat, len(seen), len(specs))
	}
	out := make([]Profile, 0, len(seen))
	for _, p := range seen {
		out = append(out, p)
	}
	return out
}

func deviceOf(t *testing.T, p Profile) device {
	t.Helper()
	var dev device
	if err := json.Unmarshal([]byte(p.DeviceJSON), &dev); err != nil {
		t.Fatalf("DeviceJSON: %v", err)
	}
	return dev
}

func TestPlatformFromString(t *testing.T) {
	cases := []struct {
		in   string
		want Platform
	}{
		{"mobile", Mobile},
		{"desktop", Desktop},
		{"", Desktop},
		{"unknown", Desktop},
	}
	for _, tc := range cases {
		if got := PlatformFromString(tc.in); got != tc.want {
			t.Fatalf("PlatformFromString(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}

func TestEveryPersonaIsChrome146(t *testing.T) {
	for _, plat := range []Platform{Desktop, Mobile} {
		for _, p := range personas(t, plat) {
			if !strings.Contains(p.UserAgent, "Chrome/146.0.0.0") {
				t.Fatalf("%s UA not chrome 146: %q", plat, p.UserAgent)
			}
			if p.SecChUa != chromeSecChUa {
				t.Fatalf("%s sec-ch-ua = %q", plat, p.SecChUa)
			}
		}
	}
}

// Одна identity - одна личность: иначе visitorId прыгал бы между запросами
// одной сессии.
func TestForIsDeterministic(t *testing.T) {
	for _, plat := range []Platform{Desktop, Mobile} {
		id := Identity{Seed: testSeed, Gen: 3}
		first, again := For(plat, id), For(plat, id)
		if first != again {
			t.Fatalf("%s: For not deterministic", plat)
		}
	}
}

// Сжигание персоны обязано менять и устройство, и visitorId: иначе отвергнутый
// отпечаток переиспользуется.
func TestBurnChangesIdentity(t *testing.T) {
	for _, plat := range []Platform{Desktop, Mobile} {
		prev := For(plat, Identity{Seed: testSeed})
		changed := false
		for gen := 1; gen <= 4; gen++ {
			next := For(plat, Identity{Seed: testSeed, Gen: gen})
			if next.VisitorID == prev.VisitorID {
				t.Fatalf("%s gen=%d reused visitorId %q", plat, gen, next.VisitorID)
			}
			if next.DeviceJSON != prev.DeviceJSON {
				changed = true
			}
			prev = next
		}
		if !changed {
			t.Fatalf("%s: burning never changed the device", plat)
		}
	}
}

// Разные установки - разные личности, иначе visitorId общий на всех.
func TestSeedSeparatesInstalls(t *testing.T) {
	a := For(Desktop, Identity{Seed: "install-a"})
	b := For(Desktop, Identity{Seed: "install-b"})
	if a.VisitorID == b.VisitorID {
		t.Fatal("different seeds share visitorId")
	}
}

func TestVisitorIDFormat(t *testing.T) {
	re := regexp.MustCompile(`^[0-9a-f]{32}$`)
	for _, plat := range []Platform{Desktop, Mobile} {
		for _, p := range personas(t, plat) {
			if !re.MatchString(p.VisitorID) {
				t.Fatalf("%s visitorId = %q", plat, p.VisitorID)
			}
		}
	}
}

func TestPlatformSet(t *testing.T) {
	for _, plat := range []Platform{Desktop, Mobile} {
		p := For(plat, Identity{Seed: testSeed})
		if p.Platform != plat {
			t.Fatalf("For(%s).Platform = %q", plat, p.Platform)
		}
		if p.IsMobile() != (plat == Mobile) {
			t.Fatalf("For(%s).IsMobile() = %v", plat, p.IsMobile())
		}
	}
}

// Показания акселерометра даёт только мобильная персона: на десктопе сенсора нет.
func TestAccelerometerOnlyOnMobile(t *testing.T) {
	if For(Desktop, Identity{Seed: testSeed}).Accelerometer() {
		t.Fatal("desktop persona claims accelerometer")
	}
	if !For(Mobile, Identity{Seed: testSeed}).Accelerometer() {
		t.Fatal("mobile persona lost accelerometer")
	}
}

// Viewport и device считаются из одного источника: координаты телеметрии обязаны
// лежать внутри окна, которое персона заявила в componentDone.
func TestViewportMatchesDeviceJSON(t *testing.T) {
	for _, plat := range []Platform{Desktop, Mobile} {
		for _, p := range personas(t, plat) {
			dev := deviceOf(t, p)
			if p.Viewport.W != dev.InnerWidth || p.Viewport.H != dev.InnerHeight {
				t.Fatalf("%s viewport %+v != device %dx%d", plat, p.Viewport, dev.InnerWidth, dev.InnerHeight)
			}
		}
	}
}

// Окно не бывает больше экрана, а рабочая область - больше самого экрана.
func TestViewportFitsScreen(t *testing.T) {
	for _, plat := range []Platform{Desktop, Mobile} {
		for _, p := range personas(t, plat) {
			dev := deviceOf(t, p)
			if dev.InnerWidth > dev.ScreenAvailWidth || dev.InnerHeight > dev.ScreenAvailHeight {
				t.Fatalf("%s window %dx%d exceeds avail %dx%d", plat,
					dev.InnerWidth, dev.InnerHeight, dev.ScreenAvailWidth, dev.ScreenAvailHeight)
			}
			if dev.ScreenAvailWidth > dev.ScreenWidth || dev.ScreenAvailHeight > dev.ScreenHeight {
				t.Fatalf("%s avail %dx%d exceeds screen %dx%d", plat,
					dev.ScreenAvailWidth, dev.ScreenAvailHeight, dev.ScreenWidth, dev.ScreenHeight)
			}
		}
	}
}

// Chrome квантует navigator.deviceMemory по степеням двойки и режет потолок до 8:
// любое другое значение живым браузером быть не может.
func TestDeviceMemoryIsChromeQuantized(t *testing.T) {
	allowed := []int{1, 2, 4, 8}
	for _, plat := range []Platform{Desktop, Mobile} {
		for _, p := range personas(t, plat) {
			dev := deviceOf(t, p)
			if dev.DeviceMemory == nil || !slices.Contains(allowed, *dev.DeviceMemory) {
				t.Fatalf("%s deviceMemory = %v, want one of %v", plat, dev.DeviceMemory, allowed)
			}
		}
	}
}

// Порядок заголовков обязан быть задан всегда, иначе на проводе останется
// случайный порядок Go-мапы.
func TestApplyFhttpSetsChromeHeaders(t *testing.T) {
	for _, plat := range []Platform{Desktop, Mobile} {
		req, err := fhttp.NewRequest(fhttp.MethodPost, "https://example.com/", nil)
		if err != nil {
			t.Fatal(err)
		}
		p := For(plat, Identity{Seed: testSeed})
		ApplyFhttp(req, p)

		if req.Header.Get("sec-ch-ua") != p.SecChUa {
			t.Fatalf("For(%s) sec-ch-ua = %q", plat, req.Header.Get("sec-ch-ua"))
		}
		if got := req.Header.Get("Priority"); got != "u=1, i" {
			t.Fatalf("For(%s) priority = %q", plat, got)
		}
		if len(req.Header[fhttp.HeaderOrderKey]) == 0 {
			t.Fatalf("For(%s) header order not set", plat)
		}
	}
}

// Персона обязана быть самосогласованной: мобильная платформа -> мобильный UA,
// мобильный sec-ch-ua и непустой device для captcha.
func TestPersonaSelfConsistent(t *testing.T) {
	for _, p := range personas(t, Mobile) {
		if !strings.Contains(strings.ToLower(p.UserAgent), "mobile") {
			t.Fatalf("mobile UA not mobile: %q", p.UserAgent)
		}
		if p.DeviceJSON == "" {
			t.Fatal("mobile persona has empty DeviceJSON")
		}
		if p.SecChUaMobile != "?1" {
			t.Fatalf("mobile sec-ch-ua-mobile = %q, want ?1", p.SecChUaMobile)
		}
	}
	for _, p := range personas(t, Desktop) {
		if strings.Contains(strings.ToLower(p.UserAgent), "mobile") {
			t.Fatalf("desktop UA is mobile: %q", p.UserAgent)
		}
		if p.SecChUaMobile != "?0" {
			t.Fatalf("desktop sec-ch-ua-mobile = %q, want ?0", p.SecChUaMobile)
		}
	}
}

// sec-ch-ua-platform и UA читает один и тот же анти-бот: заявленная в hints ОС
// обязана совпадать с ОС в User-Agent.
func TestPersonaPlatformMatchesUA(t *testing.T) {
	marker := map[string]string{
		`"Windows"`: "Windows NT",
		`"macOS"`:   "Macintosh",
		`"Android"`: "Android",
	}
	for _, plat := range []Platform{Desktop, Mobile} {
		for _, p := range personas(t, plat) {
			want, ok := marker[p.SecChUaPlatform]
			if !ok {
				t.Fatalf("unknown sec-ch-ua-platform %q", p.SecChUaPlatform)
			}
			if !strings.Contains(p.UserAgent, want) {
				t.Fatalf("sec-ch-ua-platform %s but UA %q", p.SecChUaPlatform, p.UserAgent)
			}
		}
	}
}

// Accept-Language и navigator.languages читает один и тот же анти-бот: тег,
// объявленный в JS, но отсутствующий в заголовке - несоответствие персоны.
func TestPersonaLanguagesMatchHeader(t *testing.T) {
	for _, plat := range []Platform{Desktop, Mobile} {
		for _, p := range personas(t, plat) {
			dev := deviceOf(t, p)
			tags := strings.Split(p.AcceptLanguage, ",")
			for i, tag := range tags {
				tags[i], _, _ = strings.Cut(tag, ";")
			}
			if len(dev.Languages) == 0 || dev.Languages[0] != dev.Language {
				t.Fatalf("%s languages[0]=%v, want language %q", plat, dev.Languages, dev.Language)
			}
			for _, want := range dev.Languages {
				if !slices.Contains(tags, want) {
					t.Fatalf("%s language %q missing from Accept-Language %q", plat, want, p.AcceptLanguage)
				}
			}
			if tags[0] != dev.Language {
				t.Fatalf("%s Accept-Language starts with %q, want %q", plat, tags[0], dev.Language)
			}
		}
	}
}

// Chrome использует один TLS-стек на всех платформах.
func TestClientProfileIsChrome146(t *testing.T) {
	for _, plat := range []Platform{Desktop, Mobile} {
		got := For(plat, Identity{Seed: testSeed}).ClientProfile()
		if got.GetClientHelloStr() != profiles.Chrome_146.GetClientHelloStr() {
			t.Fatalf("For(%s).ClientProfile() = %s", plat, got.GetClientHelloStr())
		}
	}
}
