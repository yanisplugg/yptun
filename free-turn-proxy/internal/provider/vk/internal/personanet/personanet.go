// Package personanet собирает HTTP-клиент, чей TLS-отпечаток и заголовки
// принадлежат браузерной персоне. Один конструктор на всех потребителей:
// расхождение JA3 между control-plane и captcha само по себе сигнал бота.
package personanet

import (
	"context"
	"io"
	"net"
	"net/http"
	"time"

	fhttp "github.com/bogdanfinn/fhttp"
	tlsclient "github.com/bogdanfinn/tls-client"
	"golang.org/x/net/proxy"

	"github.com/samosvalishe/free-turn-proxy/internal/netconn"
	"github.com/samosvalishe/free-turn-proxy/internal/provider/vk/internal/browserprofile"
)

const (
	// clientHelloSplitAt - фоллбэк-offset разбиения, когда SNI в ClientHello не
	// распарсился. Совпадает с TURN STUN-split.
	clientHelloSplitAt = 6
	// clientHelloSplitDelay / clientHelloSplitJitter - пауза между сегментами
	// ClientHello (base + случайная добавка [0,jitter)) для антифингерпринта тайминга.
	clientHelloSplitDelay  = 20 * time.Millisecond
	clientHelloSplitJitter = 15 * time.Millisecond

	clientTimeoutSeconds = 20
)

// splitDialer дробит первый Write результирующего conn (TLS ClientHello) по
// границам внутри SNI host_name для обхода SNI-based DPI RST. Реализует
// proxy.ContextDialer - tls-client берёт его через WithProxyDialerFactory как
// прямой (не прокси) дилер.
type splitDialer struct {
	base net.Dialer
}

func (d *splitDialer) Dial(network, addr string) (net.Conn, error) {
	return d.DialContext(context.Background(), network, addr)
}

func (d *splitDialer) DialContext(ctx context.Context, network, addr string) (net.Conn, error) {
	c, err := d.base.DialContext(ctx, network, addr)
	if err != nil {
		return nil, err
	}
	return &netconn.MultiSplitWriteConn{
		Conn:            c,
		Delay:           clientHelloSplitDelay,
		Jitter:          clientHelloSplitJitter,
		FallbackSplitAt: clientHelloSplitAt,
	}, nil
}

type Option func(*options)

type options struct {
	noFollowRedirects bool
}

// NoFollowRedirects нужен прокси-режиму: по редиректам ходит браузер, иначе
// rewrite Location мёртв, а Set-Cookie промежуточных хопов теряются.
func NoFollowRedirects() Option {
	return func(o *options) { o.noFollowRedirects = true }
}

// NewClient строит tls-client с ClientHello персоны поверх dialer. jar может быть
// nil, если куки ведёт вызывающий.
func NewClient(profile browserprofile.Profile, dialer net.Dialer, jar tlsclient.CookieJar, extra ...Option) (tlsclient.HttpClient, error) {
	var o options
	for _, apply := range extra {
		apply(&o)
	}
	opts := []tlsclient.HttpClientOption{
		tlsclient.WithTimeoutSeconds(clientTimeoutSeconds),
		tlsclient.WithClientProfile(profile.ClientProfile()),
		tlsclient.WithProxyDialerFactory(func(_ string, timeout time.Duration, localAddr *net.TCPAddr, _ fhttp.Header, _ tlsclient.Logger) (proxy.ContextDialer, error) {
			base := dialer
			base.Timeout = timeout
			if localAddr != nil {
				base.LocalAddr = localAddr
			}
			return &splitDialer{base: base}, nil
		}),
	}
	if jar != nil {
		opts = append(opts, tlsclient.WithCookieJar(jar))
	}
	if o.noFollowRedirects {
		opts = append(opts, tlsclient.WithNotFollowRedirects())
	}
	return tlsclient.NewHttpClient(tlsclient.NewNoopLogger(), opts...)
}

// RoundTripper подаёт net/http-запросы (httputil.ReverseProxy) через клиент
// персоны: иначе апстрим уходил бы с Go-отпечатком под браузерным User-Agent.
func RoundTripper(client tlsclient.HttpClient, profile browserprofile.Profile) http.RoundTripper {
	return &personaRoundTripper{client: client, profile: profile}
}

type personaRoundTripper struct {
	client  tlsclient.HttpClient
	profile browserprofile.Profile
}

func (rt *personaRoundTripper) RoundTrip(req *http.Request) (*http.Response, error) {
	var body io.Reader
	if req.Body != nil {
		body = req.Body
	}
	out, err := fhttp.NewRequestWithContext(req.Context(), req.Method, req.URL.String(), body)
	if err != nil {
		return nil, err
	}
	out.Host = req.Host
	for name, values := range req.Header {
		// Сжатие ведёт tls-client: он же и разожмёт ответ.
		if http.CanonicalHeaderKey(name) == "Accept-Encoding" {
			continue
		}
		for _, v := range values {
			out.Header.Add(name, v)
		}
	}
	browserprofile.ApplyFhttp(out, rt.profile)

	resp, err := rt.client.Do(out)
	if err != nil {
		return nil, err
	}
	header := make(http.Header, len(resp.Header))
	for name, values := range resp.Header {
		header[http.CanonicalHeaderKey(name)] = append([]string(nil), values...)
	}
	// По HTTP/2 fhttp разжимает тело, но заголовки сжатия не чистит (в отличие от
	// HTTP/1.1-пути) - иначе потребитель режет тело по сжатой длине.
	if resp.Uncompressed {
		header.Del("Content-Encoding")
		header.Del("Content-Length")
	}
	return &http.Response{
		Status:        resp.Status,
		StatusCode:    resp.StatusCode,
		Proto:         resp.Proto,
		ProtoMajor:    resp.ProtoMajor,
		ProtoMinor:    resp.ProtoMinor,
		Header:        header,
		Body:          resp.Body,
		ContentLength: resp.ContentLength,
		Request:       req,
	}, nil
}

func ProxyRoundTripper(client tlsclient.HttpClient) http.RoundTripper {
	return &proxyRoundTripper{client: client}
}

type proxyRoundTripper struct {
	client tlsclient.HttpClient
}

func (rt *proxyRoundTripper) RoundTrip(req *http.Request) (*http.Response, error) {
	var body io.Reader
	if req.Body != nil {
		body = req.Body
	}
	out, err := fhttp.NewRequestWithContext(req.Context(), req.Method, req.URL.String(), body)
	if err != nil {
		return nil, err
	}
	out.Host = req.Host
	for name, values := range req.Header {
		if http.CanonicalHeaderKey(name) == "Accept-Encoding" {
			continue
		}
		for _, v := range values {
			out.Header.Add(name, v)
		}
	}

	// Важное отличие: задаём только порядок, не трогаем User-Agent
	browserprofile.ApplyProxyFhttp(out)

	resp, err := rt.client.Do(out)
	if err != nil {
		return nil, err
	}
	header := make(http.Header, len(resp.Header))
	for name, values := range resp.Header {
		header[http.CanonicalHeaderKey(name)] = append([]string(nil), values...)
	}
	if resp.Uncompressed {
		header.Del("Content-Encoding")
		header.Del("Content-Length")
	}
	return &http.Response{
		Status:        resp.Status,
		StatusCode:    resp.StatusCode,
		Proto:         resp.Proto,
		ProtoMajor:    resp.ProtoMajor,
		ProtoMinor:    resp.ProtoMinor,
		Header:        header,
		Body:          resp.Body,
		ContentLength: resp.ContentLength,
		Request:       req,
	}, nil
}
