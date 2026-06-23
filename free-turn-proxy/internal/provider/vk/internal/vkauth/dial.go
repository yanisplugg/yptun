package vkauth

import (
	"context"
	"net"
	"time"

	"github.com/samosvalishe/free-turn-proxy/internal/netconn"
	"github.com/samosvalishe/free-turn-proxy/internal/provider/vk/internal/browserprofile"

	fhttp "github.com/bogdanfinn/fhttp"
	tlsclient "github.com/bogdanfinn/tls-client"
	"github.com/bogdanfinn/tls-client/profiles"
	"golang.org/x/net/proxy"
)

const (
	// clientHelloSplitAt - фоллбэк-offset разбиения, когда SNI в ClientHello не
	// распарсился. Совпадает с TURN STUN-split.
	clientHelloSplitAt = 6
	// clientHelloSplitDelay / clientHelloSplitJitter - пауза между сегментами
	// ClientHello (base + случайная добавка [0,jitter)) для антифингерпринта тайминга.
	clientHelloSplitDelay  = 20 * time.Millisecond
	clientHelloSplitJitter = 15 * time.Millisecond
)

// splitDialer оборачивает base.DialContext и дробит первый Write результирующего
// conn (TLS ClientHello) по границам внутри SNI host_name для обхода SNI-based
// DPI RST. Реализует proxy.ContextDialer - tls-client берёт его через
// WithProxyDialerFactory как прямой (не прокси) дилер.
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

// clientProfile выбирает TLS/HTTP2-профиль (JA3 + client hints) под браузер.
// JA3 обязан совпадать с UA из browserprofile.ForKind, иначе рассинхрон = флаг.
func (c *Client) clientProfile() profiles.ClientProfile {
	if c.browser == browserprofile.Firefox {
		return profiles.Firefox_147
	}
	return profiles.Chrome_146
}

// newTLSClient строит tls-client с Chrome-fingerprint и фрагментацией
// ClientHello на всех исходящих control-plane TLS-соединениях. Базовый дилер -
// c.dialer (несёт DNS-резолвер dnsdial); фабрика вызывается без proxyUrl, поэтому
// CONNECT не используется - splitDialer работает как прямой транспорт.
func (c *Client) newTLSClient(jar tlsclient.CookieJar) (tlsclient.HttpClient, error) {
	return tlsclient.NewHttpClient(tlsclient.NewNoopLogger(),
		tlsclient.WithTimeoutSeconds(20),
		tlsclient.WithClientProfile(c.clientProfile()),
		tlsclient.WithCookieJar(jar),
		tlsclient.WithProxyDialerFactory(func(_ string, timeout time.Duration, localAddr *net.TCPAddr, _ fhttp.Header, _ tlsclient.Logger) (proxy.ContextDialer, error) {
			base := c.dialer
			base.Timeout = timeout
			if localAddr != nil {
				base.LocalAddr = localAddr
			}
			return &splitDialer{base: base}, nil
		}),
	)
}
