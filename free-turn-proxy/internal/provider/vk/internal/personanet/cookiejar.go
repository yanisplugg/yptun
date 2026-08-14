package personanet

import (
	neturl "net/url"
	"time"

	fhttp "github.com/bogdanfinn/fhttp"
	tlsclient "github.com/bogdanfinn/tls-client"
)

// cookieJar чинит удаление кук в tls-client: его фильтр смотрит только на
// Max-Age (jar.go, проверка Expires закомментирована), а VK гасит куки прошедшим
// Expires. Без этого remixir=DELETED уезжает в каждом запросе - живой браузер
// такую куку удаляет и не шлёт никогда.
type cookieJar struct {
	inner tlsclient.CookieJar
}

func NewCookieJar() tlsclient.CookieJar {
	return &cookieJar{inner: tlsclient.NewCookieJar()}
}

func (j *cookieJar) SetCookies(u *neturl.URL, cookies []*fhttp.Cookie) {
	now := time.Now()
	for _, c := range cookies {
		if c.MaxAge == 0 && !c.Expires.IsZero() && !c.Expires.After(now) {
			c.MaxAge = -1
		}
	}
	j.inner.SetCookies(u, cookies)
}

func (j *cookieJar) Cookies(u *neturl.URL) []*fhttp.Cookie { return j.inner.Cookies(u) }

func (j *cookieJar) GetAllCookies() map[string][]*fhttp.Cookie { return j.inner.GetAllCookies() }
