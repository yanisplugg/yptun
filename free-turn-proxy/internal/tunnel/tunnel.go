// Package tunnel - userspace WireGuard/AmneziaWG поверх клиентской сессии.
//
// Раскладка:
//
//   - config.go - типизированный конфиг и его проверка;
//   - uapi.go   - конфиг -> формат device.IpcSet, без склейки текста;
//   - bind.go   - SinglePeerBind: устройство разговаривает прямо с релеем;
//   - wgconf/   - разбор пользовательского .conf в Config;
//   - awg/      - реализация Backend на amneziawg-go.
//
// Ванильный WireGuard - частный случай: пустой AmneziaParams не добавляет в
// UAPI ни одного ключа, и протокол совпадает с обычным WG побитово. Поэтому
// зависимость одна, а режимов два.
package tunnel

import "time"

// EndpointSinglePeer - заглушка адреса пира для tunnel/bind.SinglePeerBind. Пир
// без endpoint'а не инициирует handshake, поэтому строка в конфиге нужна; её
// содержимое не используется - ParseEndpoint всегда отдаёт единственный
// endpoint.
const EndpointSinglePeer = "single-peer"

type Mode string

const (
	// ModeNone - туннеля нет: клиент слушает локальный порт, а WireGuard или
	// Xray снаружи ходит на него сам.
	ModeNone Mode = "none"
	// ModeWG - userspace WireGuard внутри процесса.
	ModeWG Mode = "wg"
	// ModeAWG - то же плюс маскировка AmneziaWG.
	ModeAWG Mode = "awg"
)

func (m Mode) Valid() bool {
	switch m {
	case ModeNone, ModeWG, ModeAWG:
		return true
	default:
		return false
	}
}

type Stats struct {
	RxBytes       int64
	TxBytes       int64
	LastHandshake time.Time // нулевое время - handshake ещё не случился
}

// Backend - реализация userspace-туннеля. Интерфейс существует, чтобы замена
// форка amneziawg-go (например, если он отстанет от upstream WireGuard) была
// локальной, а не переписыванием вызывающего кода.
type Backend interface {
	// Up поднимает устройство поверх tunFD - файлового дескриптора tun,
	// созданного платформой.
	//
	// Дескриптор переходит во владение Backend: он закроет его в Down. Хост
	// обязан отдать именно оторванный fd (на Android - detachFd), иначе
	// получится двойное закрытие.
	Up(cfg *Config, tunFD int) error
	Down() error
	Stats() (Stats, error)
}
