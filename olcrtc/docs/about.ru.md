<div align="center">

<img src="https://github.com/openlibrecommunity/material/blob/master/olcrtc.png" width="250" height="250">

![License](https://img.shields.io/badge/license-WTFPL-0D1117?style=flat-square&logo=open-source-initiative&logoColor=green&labelColor=0D1117)
![Golang](https://img.shields.io/badge/-Golang-0D1117?style=flat-square&logo=go&logoColor=00A7D0)

**RU** / [EN](about.md)

</div>



# olcRTC - общее описание

`olcRTC` (OpenLibreCommunity RTC) - зашифрованный TCP-over-WebRTC туннель. Он маскирует трафик под обычное участие в WebRTC/SFU-сервисе: Jitsi Meet, Yandex Telemost или WbStream.

Проект: [github.com/openlibrecommunity/olcrtc](https://github.com/openlibrecommunity/olcrtc)  
Лицензия: WTFPL  
Статус: **Beta**

## Зачем это нужно

В сценариях, где прямой доступ к произвольному VPS / IP заблокирован, приходится переносить трафик через сервисы, которые уже доступны у пользователя. Для внешнего наблюдателя соединение выглядит как обычный WebRTC-звонок по разрешенному IP сервиса, а полезная нагрузка внутри дополнительно шифруется общим ключом `crypto.key`.

> **Важно:** Обязательно проверяйте, есть ли сервис видеозвонков у вас в белых списках. Если его там нет - используйте другой. Список всех сервисов в белых списках скоро будет опубликован.

Базовая схема:

```text
приложение
  -> SOCKS5 127.0.0.1:8808
   -> olcrtc cnc
    -> WebRTC/SFU сервис
     -> olcrtc srv
       -> интернет
```

## Как это работает

Клиентский режим `cnc` поднимает локальный SOCKS5. Браузер, curl, sing-box, olcbox или другое приложение подключается к нему как к обычному proxy.

Серверный режим `srv` подключается к той же комнате/сессии, принимает зашифрованный smux stream и от своего имени открывает TCP-соединения к целевым адресам.

Внутри туннеля:

```text
SOCKS CONNECT
  -> smux stream
   -> XChaCha20-Poly1305
    -> transport
     -> engine
      -> WebRTC/SFU
```

## Режимы

| Режим | Назначение |
|---|---|
| `srv` | серверная сторона, принимает tunnel streams и делает TCP dial к целям |
| `cnc` | клиентская сторона, слушает локальный SOCKS5 |
| `gen` | создаёт Room ID для провайдеров, которые умеют создавать комнаты |

CLI принимает один YAML-файл:

```bash
olcrtc server.yaml
olcrtc client.yaml
```

## Провайдеры

`auth.provider` выбирает сервис и способ получения credentials.

| Provider | Engine | Комментарий |
|---|---|---|
| `jitsi` | `jitsi` | URL комнаты Jitsi, инстансы в docs/jitsi.instances.yaml, без отдельной регистрации |
| `telemost` | `goolom` | credentials через Yandex Telemost API, с отдельной регистрацией |
| `wbstream` | `livekit` | credentials через WbBStream API, с отдельной регистрацией |
| `none` | задаётся в `engine.name` | прямой engine-режим с `engine.url` и `engine.token`, с отдельной регистрацией |

Во всех Go-конфигах, логах, флагах и тестах используется одно имя: `Provider` в Go и `auth.provider` в YAML.

## Engines

`engine` - низкоуровневый протокол конкретного SFU/signaling:

| Engine | Пакет | Возможности |
|---|---|---|
| `livekit` | `internal/engine/livekit` | data packets/video tracks/LiveKit SDK |
| `goolom` | `internal/engine/goolom` | Telemost/Goolom signaling, publisher/subscriber PeerConnection |
| `jitsi` | `internal/engine/jitsi` | Jitsi MUC/Jingle/colibri-ws, datachannel/best-effort video |

`internal/engine/builtin` связывает `auth.provider` с нужным engine. Отдельного пакета `internal/provider` в текущем проекте нет.

## Transports

`net.transport` определяет, как tunnel bytes помещаются в WebRTC primitive.

| Transport | Как передаёт данные | Основной сценарий |
|---|---|---|
| `datachannel` | нативный byte/data path engine | самый простой и быстрый путь, стабильно с Jitsi |
| `vp8channel` | KCP поверх VP8-like video frames | основной video-path для WB Stream и Telemost |
| `seichannel` | payload в H264 SEI NAL units, ACK/retry | fallback для WB Stream / Jitsi|
| `videochannel` | QR/tile кадры с кодированием VP8 на чистом Go, ACK/retry | экспериментальный визуальный транспорт |

Рекомендуемый старт: `jitsi + datachannel`. Альтернатива: `wbstream + vp8channel`.

## Шифрование и handshake

`internal/crypto` использует версионированный record layer v2 на XChaCha20-Poly1305. Общий PSK задаётся как 64 hex-символа:

```bash
openssl rand -hex 32
```

Из PSK через HKDF-SHA256 выводятся независимые ключи с фиксированными метками `olcrtc/v2/client-to-server` и `olcrtc/v2/server-to-client`. Клиент и сервер выбирают противоположные send/receive ключи, поэтому отражённая обратно запись не проходит AEAD-проверку.

Формат каждой v2-записи:

```text
OLC2 (4 байта) | counter uint64 BE (8 байт) | sender prefix (16 байт) | ciphertext | Poly1305 tag (16 байт)
```

XChaCha20 nonce строится как `sender prefix || counter`. Случайный prefix создаётся один раз для send-части keyset, counter начинается с 1 и общий для data/control соединений и reconnect. При исчерпании `uint64` отправка завершается ошибкой без оборачивания счётчика. Полный crypto overhead равен 44 байтам.

Плоскости разделены AEAD associated data: `olcrtc/muxconn/v2/data` и `olcrtc/muxconn/v2/control`. Перенос ciphertext между data и control не проходит аутентификацию.

После успешной AEAD-проверки receive keyset применяет 64-записное скользящее replay-окно отдельно для каждого sender prefix. Состояние общее для data/control muxconn и новых muxconn после reconnect. Хранилище ограничено 256 sender prefix и вытесняет наименее недавно использованный prefix. Неаутентифицированные записи не создают и не изменяют replay state. Повторы и записи старше окна отклоняются отдельными ошибками.

Формат v2 намеренно несовместим с прежним форматом. Декодер не имеет v1 fallback и отклоняет записи без magic `OLC2`.

Поверх зашифрованного `muxconn` запускается `smux`. Первый smux stream занят handshake и control protocol:

```text
CLIENT_HELLO(challenge) -> SERVER_WELCOME(challenge, authenticated peer ID)
CONTROL_PING <-> CONTROL_PONG
```

Если control pong не приходит несколько раз подряд, runtime пересобирает smux-сессию или отдаёт управление failover supervisor.

Общий формат видеокадров OLVC для `seichannel` и `videochannel` имеет версию 5. Он содержит роль отправителя, binding сессии, данные ACK для каждого фрагмента, контрольную сумму фрагмента и CRC всего сообщения. Фрагмент, не прошедший свою контрольную сумму, не подтверждается и переспрашивается, а не теряется вместе с сообщением. Старые кадры отклоняются по magic или версии, поэтому старые сборки видеотранспортов несовместимы.

## YAML

Минимальный сервер:

```yaml
mode: srv
auth:
  provider: jitsi
room:
  # Используйте тот Jitsi-сервер, который работает в вашей сети:
  # Инстансы: docs/jitsi.instances.yaml - https://HOST/ROOM
  id: "https://meet.example.org/REPLACE_ME_WITH_ROOM_ID"
crypto:
  key: "REPLACE_ME_WITH_64_HEX_CHARS"
net:
  transport: datachannel
  dns: "8.8.8.8:53"
```

Минимальный клиент:

```yaml
mode: cnc
auth:
  provider: jitsi
room:
  # Используйте тот Jitsi-сервер, который работает в вашей сети:
  # Инстансы: docs/jitsi.instances.yaml - https://HOST/ROOM
  id: "https://meet.example.org/REPLACE_ME_WITH_ROOM_ID"
crypto:
  key: "REPLACE_ME_WITH_64_HEX_CHARS"
net:
  transport: datachannel
  dns: "8.8.8.8:53"
socks:
  host: "127.0.0.1"
  port: 8808
```

Подробнее: [configuration.md](configuration.ru.md), [settings.md](settings.ru.md).

## Failover

`profiles[]` позволяет запускать несколько конфигураций по порядку. Например, сначала `wbstream + vp8channel`, потом `jitsi + datachannel`. Верхнеуровневые поля работают как defaults, профиль переопределяет только нужные части.

Активные smux streams при смене профиля не мигрируют. Новые подключения смогут подняться на следующем профиле.

## Структура репозитория

| Путь | Что внутри |
|---|---|
| `cmd/olcrtc` | CLI entrypoint |
| `cmd/olcrtc-cgo` | c-shared entrypoint |
| `pkg/olcrtc/client` | полный встраиваемый клиентский туннель с SOCKS5 |
| `pkg/olcrtc/tunnel` | полный встраиваемый серверный туннель |
| `pkg/olcrtc/engineconn` | сырой незашифрованный byte stream движка |
| `mobile` | gomobile bindings для Android |
| `internal/config` | YAML parsing, `crypto.key_file` |
| `internal/app/session` | defaults, validation, routing в `srv`/`cnc`/`gen` |
| `internal/auth` | provider-specific credential flows |
| `internal/engine` | SFU/signaling implementations |
| `internal/transport` | datachannel/vp8/sei/video transports |
| `internal/server` | server-side smux, handshake, TCP dial |
| `internal/client` | SOCKS5 listener, client-side smux |
| `internal/control` | liveness ping/pong |
| `internal/supervisor` | failover profiles |
| `docs` | документация и примеры YAML |

## Сборка

```bash
go install github.com/magefile/mage@latest

mage build
mage cross
mage test
mage lint
mage mobile
```

Go версия: `1.26+`. `videochannel` реализован на чистом Go; для `codec: tile` требуется разрешение `1080x1080`.

## Public API

`pkg/olcrtc/client` запускает полный зашифрованный клиентский стек и открывает SOCKS5 listener:

Публичные конструкторы автоматически регистрируют все встроенные providers, engines и transports. Вызывай `RegisterDefaults` вручную только после пользовательского изменения или расширения registry.

```go
cli := client.New(client.Config{
    Transport: "datachannel",
    Provider: "jitsi",
    RoomURL: "https://meet.example.org/myroom",
    KeyHex: "<64-char hex>",
    LocalAddr: "127.0.0.1:8808",
    DNSServer: "8.8.8.8:53",
})
err := cli.Run(ctx)
```

`pkg/olcrtc/tunnel` встраивает серверную сторону и даёт hooks:

```go
srv := tunnel.New(tunnel.Config{
    Transport: "datachannel",
    Provider:   "jitsi",
    // Инстансы: docs/jitsi.instances.yaml
    RoomURL:   "https://meet.example.org/myroom",
    KeyHex:    "<64-char hex>",
    DNSServer: "8.8.8.8:53",
})
err := srv.Run(ctx)
```

`pkg/olcrtc/engineconn` предоставляет сырой API движка. Он не применяет OLC2-шифрование, handshake, smux, SOCKS и liveness. Его `Dial` возвращает `io.ReadWriteCloser`, а не `net.Conn`, потому что отправку движка нельзя прервать по deadline.

Необязательное верхнеуровневое поле YAML `data` указывает каталог с файлами `names` и `surnames`. Если поле не задано, используются словари, встроенные в бинарник.

## Mobile / Android

Пакет `mobile` предоставляет instance-based gomobile API. Каждый `Runtime`
имеет независимые конфигурацию и lifecycle:

```go
runtime := mobile.New()
_ = runtime.SetProvider("jitsi")
_ = runtime.SetTransport("datachannel")
_ = runtime.SetRoom("https://meet.example.org/myroom")
_ = runtime.SetKey("<64-char hex>")
_ = runtime.SetSocksPort(8808)

_ = runtime.Start()
_ = runtime.WaitReady(10_000)
_ = runtime.Stop(5_000)
```

`SetTransport` принимает `datachannel`, `vp8channel`, `seichannel` и
`videochannel`; неизвестное значение возвращает ошибку. `SetVP8Options`,
`SetSEIOptions` и `SetVideoOptions` настраивают соответствующие транспорты.
Provider, room/channel, ключ, DNS/resolver, SOCKS credentials, provider token,
device identity, liveness и traffic также задаются методами Runtime. Активное
поколение сохраняет неизменяемый снимок конфигурации, поэтому вызовы setter
влияют на следующий запуск.

`WaitReady` остается привязанным к поколению, активному в момент вызова.
`Stop` отменяет именно это поколение и возвращает `ErrStopTimeout`, если
ограниченное по времени завершение не успело закончиться. `Check` и `Ping` -
методы Runtime с изолированным временным клиентом; SOCKS-порт `0` выбирает
временный loopback-порт.

`Runtime.SetProtector` настраивает Android VPN `protect(fd)`. Этот callback -
process-wide состояние Android networking, а не состояние конкретного Runtime.
Он хранится атомарно, и каждая socket operation использует один снимок callback.
`Runtime.SetDebug` управляет process-wide подробностью internal logger и не
заменяет и не перенастраивает вывод стандартного пакета log.

## Клиенты

Готовые клиенты, которые говорят на `olcrtc`:

| Клиент | Роль | Протоколы |
|---|---|---|
| [owenewans/owenclave](https://github.com/owenewans/owenclave) ([src.owenewans.org/owenrtc](https://src.owenewans.org/owenrtc)) | **основной клиент**, Android (форк exclave) | все распространённые протоколы (vless, hysteria2, mieru, trojan, vmess, tuic, shadowsocks, socks ...) плюс `olcrtc`, формат URI `olcrtc://` и подписки |
| [venterum/veil](https://github.com/venterum/veil) | клиент сообщества, Android (форк v2rayNG), Material 3 | VMess, VLESS, Shadowsocks, Trojan, SOCKS, WireGuard, Hysteria2 + `olcrtc` |
| [alananisimov/olcbox](https://github.com/alananisimov/olcbox) | клиент сообщества, мультиплатформенный (Android, iOS, macOS, Windows, Linux) | Все провайдеры (Jitsi, Telemost, WB Stream, Jazz), все транспорты, split tunneling, режимы TUN/proxy |

`owenclave` - референсный клиент для URI `olcrtc://` и формата подписки. Нативный бинарник `olcrtc` в `mode: cnc` - тоже полноценный клиент, он только поднимает SOCKS5-слушатель без UI.

## Тесты

```bash
go test -count=1 ./...
mage test
mage e2e
```

Real-provider E2E включаются через переменные:

```bash
E2E_PROVIDERS=wbstream E2E_TRANSPORTS=vp8channel mage e2e
```

## Частые проблемы

| Симптом | Что проверить |
|---|---|
| `key required` или `invalid key` | на обеих сторонах одинаковый 64-символьный hex key |
| SOCKS5 не слушает | `mode: cnc`, `socks.host`, `socks.port`, логи клиента |
| Jitsi не соединяется без второго участника | сервер и клиент должны быть в одной комнате |
| WB Stream + datachannel не работает | в guest flow нет `canPublishData`; используй `vp8channel`, `seichannel` или `videochannel` |
| `seichannel ack timeout` | провайдер режет/не маршрутизирует video path; смени transport/provider |

## Ссылки

- [Быстрый старт](fast.ru.md)
- [Ручная сборка](manual.ru.md)
- [Настройка YAML](configuration.ru.md)
- [Матрица совместимости](settings.ru.md)
- [URI формат](uri.ru.md)
- [Формат подписки](sub.ru.md)
