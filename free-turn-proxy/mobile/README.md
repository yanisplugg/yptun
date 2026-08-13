# Интеграция free-turn-proxy через gomobile

Пакет `mobile` предоставляет API для встраивания ядра прокси в нативные мобильные приложения (iOS/Android) через `gomobile bind`.

## Сборка

### iOS
Сборка универсального XCFramework (`dist/Mobile.xcframework`) с поддержкой iOS и симулятора:
```bash
task build:ios
```

### Android
Сборка Android Archive (`dist/freeturn.aar` и `freeturn-sources.jar`) с JNI-обертками для Java/Kotlin:
```bash
task build:android
```

## API

Хост-приложение передает конфигурацию в формате JSON, а события получает через интерфейс обратного вызова.

```go
func Start(configJSON string) error                 // Запуск в режиме прокси
func StartTunnel(configJSON string, tunFD int) error // Запуск прокси + WireGuard/AmneziaWG
func Restart(configJSON string, tunFD int) error     // Перезапуск с ожиданием остановки
func Stop()                                         // Остановка сессии
func GetState() *Snapshot                           // Метрики сессии (State, Rates, Streams)
func TunnelStats() *TunnelSnapshot                  // Статистика туннеля
func SetEventSink(s EventSink)                      // Установка коллбека событий
func SetProtect(p Protector)                        // Защита сокетов (VpnService.protect)
func DefaultConfigJSON() string                     // Конфиг с дефолтами
func ValidateConfig(configJSON string) string       // Валидация JSON
func ConfigToArgs(configJSON string) (string, error) // Вывод эквивалентной CLI-команды
func DumpLogs() string
func ClearLogs()
func Version() string
```

В Java класс экспортируется как `com.freeturn.core.mobile.Mobile`. `int` в Go транслируется в `long` на стороне Kotlin/Java.

### Конфигурация JSON

Пример схемы JSON:
```json
{
  "peer": "1.2.3.4:56000",
  "clientId": "0123456789abcdef0123456789abcdef",
  "provider": "vk",
  "turn":  {"n": 12, "transport": "tcp", "host": "", "port": ""},
  "proxy": {"mode": "udp", "bond": false, "listen": "127.0.0.1:9000"},
  "vk":    {"links": ["https://vk.ru/call/join/..."], "streamsPerCred": 12,
            "manualCaptcha": false, "platform": "mobile"},
  "obf":   {"profile": "rtpopus3", "key": "<64 hex>", "timingMs": 0},
  "dns":   {"mode": "auto", "servers": ["8.8.8.8"]},
  "log":   {"debug": false},
  "tunnel": {"mode": "none", "config": "", "mtu": 1280},
  "subUrl": ""
}
```

*   `clientId` - обязателен. Ядро на мобиле не пишет файлы, ID должен храниться в приложении.

### Туннелирование

`tunnel.mode` принимает значения `none`, `wg` (WireGuard) или `awg` (AmneziaWG).

Текст WireGuard-конфигурации (`wg-quick`) передается в `tunnel.config`. В режиме `wg` параметры обфускации AmneziaWG игнорируются. Поле `Endpoint` из конфига не используется, так как трафик идет через TURN-релей.

Для `wg`/`awg` требуется `proxy.mode = "udp"` и запуск через `StartTunnel`:
```kotlin
val fd = vpnBuilder.establish()!!.detachFd()
Mobile.startTunnel(configJson, fd.toLong())
```
Ядро берет владение дескриптором и закроет его при остановке. Самостоятельно закрывать `fd` в приложении нельзя во избежание crash.

WireGuard общается с релеем через in-memory `netconn.PacketPipe` напрямую. Петля `127.0.0.1:9000` не используется, поэтому исключать приложение из VPN-маршрутов не требуется. Защите через `SetProtect` подлежат только сокеты релея.

### Обработка событий

Хост-приложение должно реализовать интерфейс `EventSink`:

```go
type EventSink interface {
    OnState(state string, streams, total int, errMsg string)
    OnLog(level, msg string, unixMillis int64)
    OnCaptcha(url string)
}
```

`OnState` вызывается строго последовательно из одной горутины при изменении статуса. `OnLog` может вызываться параллельно.
Хост обязан удерживать ссылку на объект `EventSink` в памяти (JNI-ссылка со стороны Go не защищает его от GC).
