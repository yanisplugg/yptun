package vkauth

import (
	"context"
	"net"
	"time"

	"github.com/samosvalishe/free-turn-proxy/internal/netconn"

	fhttp "github.com/bogdanfinn/fhttp"
	tlsclient "github.com/bogdanfinn/tls-client"
	"github.com/bogdanfinn/tls-client/profiles"
	"golang.org/x/net/proxy"
)

const (
	// clientHelloSplitAt — смещение разбиения первого TCP-сегмента TLS
	// ClientHello. SNI уезжает во второй сегмент, ТСПУ не собирает его из
	// раздробленного потока и не инжектит delayed-RST. Совпадает с TURN
	// (turndial.SplitFirstWriteConn).
	clientHelloSplitAt    = 6
	clientHelloSplitDelay = 20 * time.Millisecond
)

// splitDialer оборачивает base.DialContext и дробит первый Write
// результирующего conn (TLS ClientHello) для обхода SNI-based DPI RST.
// Реализует proxy.ContextDialer — tls-client берёт его через
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
	return &netconn.SplitFirstWriteConn{Conn: c, SplitAt: clientHelloSplitAt, Delay: clientHelloSplitDelay}, nil
}

// newTLSClient строит tls-client с Chrome-fingerprint и фрагментацией
// ClientHello на всех исходящих control-plane TLS-соединениях. Базовый дилер —
// c.dialer (несёт DNS-резолвер dnsdial); фабрика вызывается без proxyUrl, поэтому
// CONNECT не используется — splitDialer работает как прямой транспорт.
func (c *Client) newTLSClient(jar tlsclient.CookieJar) (tlsclient.HttpClient, error) {
	return tlsclient.NewHttpClient(tlsclient.NewNoopLogger(),
		tlsclient.WithTimeoutSeconds(20),
		tlsclient.WithClientProfile(profiles.Chrome_146),
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
