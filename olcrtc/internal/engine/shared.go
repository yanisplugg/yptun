package engine

import (
	"github.com/pion/interceptor"
)

const (
	// DefaultSendQueueSize is the shared outbound queue capacity.
	DefaultSendQueueSize = 5000
	// DefaultSendQueueCapHard is the shared send-readiness high watermark.
	DefaultSendQueueCapHard = 4000
)

// CloseSignal closes ch once and accepts nil channels.
func CloseSignal(ch chan struct{}) {
	if ch == nil {
		return
	}
	select {
	case <-ch:
	default:
		close(ch)
	}
}

// ApplyRefreshedCredentials updates non-empty common and extra credentials.
func ApplyRefreshedCredentials(creds Credentials, url, token *string, extra map[string]*string) {
	if creds.URL != "" && url != nil {
		*url = creds.URL
	}
	if creds.Token != "" && token != nil {
		*token = creds.Token
	}
	for key, target := range extra {
		if value := creds.Extra[key]; value != "" && target != nil {
			*target = value
		}
	}
}

// RTCPReader is the common read surface of pion RTP senders and receivers.
type RTCPReader interface {
	Read(buf []byte) (n int, attributes interceptor.Attributes, err error)
}

// DrainRTCP reads and discards RTCP until the pion endpoint closes.
func DrainRTCP(reader RTCPReader) {
	buf := make([]byte, 1500)
	for {
		if _, _, err := reader.Read(buf); err != nil {
			return
		}
	}
}
