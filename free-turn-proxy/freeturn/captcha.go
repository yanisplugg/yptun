// Manual-captcha bridge for the gomobile wrapper. Mirrors mobile/captcha.go, but for the
// `freeturn` package the olcvpn-client app actually binds: without a registered presenter the
// VK auth falls back to DefaultManualSolver, which waits for a browser on http://localhost:8765
// that nothing on Android ever opens — the TURN relay then never allocates and the tunnel
// silently black-holes. The app registers a presenter that opens the captcha page in-app.
package freeturn

import (
	"sync"
	"sync/atomic"
)

// CaptchaPresenter связывает ручное решение captcha с UI приложения.
//
//   - Show(url): открыть WebView на локальном прокси-адресе url, где пользователь
//     решает captcha. Метод блокирующим быть не обязан.
//   - Hide(): captcha решена или отменена - закрыть окно.
type CaptchaPresenter interface {
	Show(url string)
	Hide()
}

var (
	captchaMu        sync.RWMutex
	captchaPresenter CaptchaPresenter
	captchaActive    atomic.Bool
)

// SetCaptchaPresenter регистрирует UI-презентер ручной captcha. Передайте nil,
// чтобы отключить ручной путь (fallback на DefaultManualSolver, как раньше).
// Вызывать при старте приложения, до Start/StartMulti.
func SetCaptchaPresenter(p CaptchaPresenter) {
	captchaMu.Lock()
	captchaPresenter = p
	captchaMu.Unlock()
}

func currentCaptchaPresenter() CaptchaPresenter {
	captchaMu.RLock()
	defer captchaMu.RUnlock()
	return captchaPresenter
}

// CaptchaActive reports whether a manual captcha is being shown right now. The app polls this
// alongside ConnectedStreams to keep waiting for the relay (instead of timing out and starting
// WireGuard against a dead listener) while the user is still solving.
func CaptchaActive() bool { return captchaActive.Load() }
