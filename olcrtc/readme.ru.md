<!-- If you are an AI agent, please read agents.md -->

<p align="center">
  <img src="docs/asset/olcrtc-icon.svg" alt="olcRTC" width="112" height="112">
</p>

<h1 align="center">olcRTC</h1>

<p align="center">
  <b>Зашифрованный TCP-over-WebRTC туннель.</b><br>
  Транспортный движок <a href="https://github.com/ghostlane-project/ghostlane">Ghostlane</a>: библиотека на Go и программа командной строки.
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/github/license/ghostlane-project/olcrtc?color=b5f23d&labelColor=07080d" alt="License: Apache-2.0"></a>
  <a href="https://github.com/ghostlane-project/olcrtc/releases"><img src="https://img.shields.io/github/v/release/ghostlane-project/olcrtc?color=b5f23d&labelColor=07080d&label=release" alt="Последний релиз"></a>
  <img src="https://img.shields.io/github/go-mod/go-version/ghostlane-project/olcrtc/proofkit?label=go&color=b5f23d&labelColor=07080d" alt="Go version">
  <a href="https://github.com/ghostlane-project/olcrtc/actions/workflows/ci.yml"><img src="https://github.com/ghostlane-project/olcrtc/actions/workflows/ci.yml/badge.svg?branch=proofkit" alt="CI"></a>
  <a href="https://opencollective.com/ghostlane"><img src="https://img.shields.io/badge/funding-Open_Collective-b5f23d?labelColor=07080d&logo=opencollective&logoColor=white" alt="Funding: Open Collective"></a>
</p>

<p align="center"><b>RU</b> / <a href="readme.md">EN</a></p>


`olcRTC` (OpenLibreCommunity RTC) - зашифрованный TCP-over-WebRTC туннель. Трафик маскируется под обычный видеозвонок на разрешённых сервисах (Jitsi, Yandex Telemost, WbStream, Sber SaluteJazz, VK Calls, MTS Link). Внутри - шифрование XChaCha20-Poly1305 и мультиплексирование smux поверх WebRTC data/video каналов.

Статус: **Beta**

```text
app -> SOCKS5 -> olcrtc cnc -> WebRTC/SFU сервис -> olcrtc srv -> интернет
```

> **Важно:** проверяйте, что нужный сервис видеозвонков есть в белых списках и работает в вашей сети. Если нет - используйте другой.

## Проект

Движок развивается и финансируется вместе с приложением
[Ghostlane](https://github.com/ghostlane-project/ghostlane), через
[Open Collective](https://opencollective.com/ghostlane): деньги держит Open Source Collective
как фискальный хост, каждый приход и расход там публичен. Правила - в
[FUNDING.md](https://github.com/ghostlane-project/ghostlane/blob/main/FUNDING.md).

## Этот форк

Это форк [openlibrecommunity/olcrtc](https://github.com/openlibrecommunity/olcrtc),
который сейчас заархивирован. Работа идёт в ветке **`proofkit`** - она здесь
основная; имя ветки осталось от прежнего названия проекта и менять его пока
дороже, чем оставить, потому что на него завязаны сборки и пины. `master` -
нетронутая копия upstream и ничего из перечисленного не содержит. Именно эту
ветку линкует [Ghostlane](https://github.com/ghostlane-project/ghostlane) на всех
платформах - для этого почти всё ниже и сделано: релей должен отличать своих
пользователей друг от друга, а телефон - не сервер.

| Добавлено здесь | Что это |
| --- | --- |
| Кольцо серверных ключей | `crypto.keys` / `crypto.keys_file`. Сервер держит несколько ключей и закрепляет за пиром тот, под которым аутентифицировалась его первая запись, - так у каждого клиента может быть свой ключ. Одиночный `crypto.key` - кольцо из одной записи и ведёт себя как раньше; клиенты не меняются. |
| Учёт трафика по ключам | `stats.listen` отдаёт `GET /stats` на loopback с побайтовыми итогами по каждому ключу. Этого хватает, чтобы выставить счёт или отключить ключ, и не нужен никакой control plane. Поле `link` в ответе говорит, что происходит с сессией у провайдера конференции - `connecting`, `up` или `down`: слушатель поднимается раньше, чем сервер входит в комнату, поэтому сам по себе ответ подтверждает только то, что процесс жив. |
| Дейтаграммная полоса с потерями | Дейтаграммы идут рядом с байтовым потоком на `vp8channel`, `datachannel` и livekit, так что UDP-потоку больше не нужно притворяться стримом: vp8channel помечает их `OLUD`/`OLUB` и отправляет после control-кадров и перед данными KCP, livekit публикует их ненадёжно в своём топике. |
| SOCKS5 UDP ASSOCIATE | `udp.enabled` пускает через релей звонки, игры и всё остальное, что является дейтаграммами, по этой полосе, с `udp.max_flows` на сторону. Выключено, пока не попросят. |
| DNS - мимо полосы с потерями | За tun2socks каждый пакет телефона приходит как UDP-ассоциация, запросы резолвера в том числе, а потерянный под нагрузкой запрос - это застывшая страница. Дейтаграмма на порт 53 теперь идёт smux-стримом как TCP DNS (RFC 7766): 64 в полёте, по пять секунд каждый, полоса - запасной путь. |
| Кольцо резолверов на мобильных | Имена резолвятся через защищённые сокеты хоста и список резолверов сессии; молчащий понижается, а не ждётся. Внутри туннеля системный резолвер - это сам туннель, а оператор, который блэкхолит публичный, встречается нередко. |
| Потолок памяти, который задаёт хост | `mobile.SetMemoryLimit`, `MemoryLimit`, `MemoryStats`, `FreeOSMemory`, `GoroutineSummary` и писатель логов. Расширению packet tunnel на iOS достаётся около 50 МБ на всё; Go-рантайм, выбирающий потолок сам по объёму памяти устройства, переступает эту границу за одну сборку мусора. |
| Облегчённый мобильный бинд | С `-tags olcrtc_lean` из gomobile-сборки выпадает транспорт videochannel - таблицы кодировок QR строятся в init ради транспорта, который телефон не выбирает. Движок livekit остаётся в любой сборке: его называет auth-провайдер WB Stream, и без него облегчённый бинд ронял сессию сразу после гостевого токена. Обычная сборка и CLI не меняются. |
| Буферы по размеру телефона | Окна KCP, очереди пакетов, история NACK, чтения трека и буферы чтения на ассоциацию рассчитаны на телефон, а не на сервер, и выбираются профилем хоста, а не прибиты в коде. |
| Failover вслед за переезжающим сервером | Supervisor перечитывает список профилей на каждом переходе (`Config.Reload`; CLI перечитывает файл конфига) и ищет в нём тот профиль, который только что отработал, так что клиент идёт по скользящему окну комнат: комната, добавленная при живой сессии, берётся в тот момент, когда эта сессия кончается, без перезапуска, а комната, пропавшая из списка, переводит обход на новую голову, а не заканчивает проход. Комната, в которой никого нет - handshake не прошёл, и за это время оттуда не пришло ни одного кадра, - завершает запуск у клиента, которому есть куда пойти (`EndOnEmptyRoom`); молчащий, но присутствующий пир и клиент с единственной комнатой продолжают попытки. Control-стрим, закрытый пиром намеренно, завершает свою сессию сразу, не выжидая окно liveness, и не трогает комнату: такое же уведомление приходит от сервера, у которого перестроился собственный провайдер. IPv6-литералы отклоняются локально после нескольких отказов подряд, и это решение само истекает. |
| Список запасных комнат на мобильных | `AddFailoverRoom` / `ClearFailoverRooms` рядом с `SetRoom`: рантайм проходит основную комнату и запасные под supervisor'ом, один проход, перечитывая список на каждом переходе, - хост может дописывать комнаты в живое поколение; проход заканчивается ошибкой, и дальше работает собственный цикл повторов хоста. От комнаты, где никого не оказалось, клиент отказывается только пока есть другая, и это считается в момент старта её запуска: единственная комната повторяется на месте ровно как до failover, а дописанная хостом комната действует со следующего старта. `SetSessionListener` называет комнату каждой открывшейся сессии - так хост, не читающий лог, отличает переезд от переподключения. |
| Движок и auth-провайдер salutejazz | JSON-коннектор Sber SaluteJazz (LiveKit-as-JSON поверх pion). |
| Движок и auth-провайдер vkcalls | VK Calls: вход гостем по ссылке на звонок, транспорт `vp8channel`. |
| Движок и auth-провайдер mtslink | MTS Link: вход гостем по ссылке на постоянную встречу. Его SFU пересылает VP9, но не VP8, поэтому `vp8channel` едет по нему одетым в VP9. |
| Профиль DTLS ClientHello | `dtls.profile`, на мобильных `SetDTLSProfile`. `off` (по умолчанию) оставляет рукопожатие библиотеки WebRTC; `chrome-linux-138-compat-v1` открывает соединение так же, как Chrome 138. Экспериментально: в Ghostlane это переключатель, по умолчанию выключен. |
| Окно ретранслятора для SaluteJazz | LiveKit 1.5.3, на котором работает SaluteJazz, подтверждает сразу и копит очередь к каждому подписчику без предела, поэтому на медленном плече Сбера управляющие кадры туннеля ждали за окном потока данных, и проверка живости закрывала живые сессии. Теперь у каждого адресата своё окно: отправитель берёт лучшую скорость, которую плечо показало за последние десять секунд, и умножает на три секунды, от 16 до 192 КиБ. Окно включается, только если обе стороны отметили его в рукопожатии, так что старого пира обслуживают как раньше. |
| Прямые правила на клиенте | `route.direct` / `route.direct_file`, а на мобильных `SetDirectRules`: адресаты, которых клиент открывает сам, а не через туннель, через тот же защищённый сокет, что и соединение с провайдером. Правила называют имена (`domain:`, `full:`) или адреса и подсети, в синтаксисе списков Xray. Для адреса, которого в правилах нет, клиент читает первые байты в поисках TLS server name или HTTP `Host`, так что даже tun2socks, отдающий одни адреса, маршрутизируется по имени. При `udp.enabled` датаграммы к адресату из списка тоже идут напрямую, а DNS-запрос к имени из списка уходит к резолверам самой сети, а не к выходу. Так режимы обхода Ghostlane маршрутизируют комнату olcRTC. Подробнее в [configuration.ru.md](docs/configuration.ru.md). |

Кроме этого: клиент отличает старый пир, неверный ключ и пустую комнату друг от
друга, а не отваливается по таймауту на всех трёх; нумерация записей и
replay-проверка ведутся по каждой полосе отдельно; очередь отправки в бридж
Jitsi притормаживается вместо провала отправки; соединение устанавливается на
обе адресные семьи с повтором, когда маршрута не нашлось, - это нужно и
IPv6-only оператору, и NAT64 в App Review.

Изменения, чьё место в upstream, оформляются туда pull request'ами - ветки
`pr/*` здесь как раз они, по одному изменению в каждой.

## Возможности

- **Провайдеры:** `jitsi`, `telemost`, `wbstream`, `salutejazz`, `vkcalls`, `mtslink`
- **Транспорты:** `datachannel`, `vp8channel`, `seichannel`, `videochannel`
- **Платформы:** Linux, macOS, Windows, Android (gomobile), встраиваемая Go-библиотека
- **Публичные Go-пакеты:** `pkg/olcrtc/client`, `pkg/olcrtc/tunnel`, `pkg/olcrtc/engineconn`

Рекомендуемый старт: `jitsi + datachannel`.

Текущие сборки используют OLC2-шифрование с направленными ключами HKDF-SHA256, отдельным AAD для data/control и replay-защитой. Fallback на старый crypto format отсутствует. `seichannel` и `videochannel` используют OLVC версии 5 и отклоняют старые видеокадры. Обновляй обе стороны одновременно.

Словари display name встроены в бинарник. Необязательное поле YAML `data` может указать каталог с файлами `names` и `surnames` для их замены.

## Установка в один клик

```sh
curl -fsSL https://raw.githubusercontent.com/ghostlane-project/olcrtc/proofkit/install.sh | bash
```

Ставит Podman, если его нет, клонирует ветку `proofkit` этого форка, собирает бинарник в контейнере, задаёт несколько вопросов (сервер или клиент, провайдер, транспорт, комната, ключ) и запускает. Запусти скрипт один раз на сервере (режим `srv`) и один раз на клиенте (режим `cnc`) - им нужны одинаковые room ID и ключ шифрования.

Если репозиторий уже склонирован, просто запусти `./install.sh`.

Полные инструкции в [docs/fast.md](docs/fast.ru.md) и [docs/manual.md](docs/manual.ru.md).

## Документация

- [about.md](docs/about.ru.md) - архитектура, провайдеры, транспорты, публичный API
- [fast.md](docs/fast.ru.md) - быстрый старт для новичков
- [manual.md](docs/manual.ru.md) - ручная сборка
- [configuration.md](docs/configuration.ru.md) - настройка YAML
- [providers.md](docs/providers.ru.md) - карьеры: поднять инстанс, добавить новый
- [settings.md](docs/settings.ru.md) - матрица совместимости
- [uri.md](docs/uri.ru.md) - формат URI клиента
- [sub.md](docs/sub.ru.md) - формат подписки
- [gate.md](docs/gate.ru.md) - release gate: туннель под нагрузкой на настоящих relay и его отчёт
- [versioning.md](docs/versioning.ru.md) - версии: теги, релизы и какая версия где работает

## Сборка

```sh
mage build   # текущая платформа
mage cross   # кросс-компиляция
mage test    # тесты
mage lint    # golangci-lint
mage mobile  # gomobile bindings (Android)
```

## Клиенты

- Основной клиент:
  - [owenewans/owenclave](https://github.com/owenewans/owenclave) - Android-клиент прокси (форк exclave). Поддерживает все распространённые протоколы (vless, hysteria2, mieru, trojan, vmess, tuic, shadowsocks, socks ...) плюс `olcrtc`, формат URI `olcrtc://` и подписки
- Клиент этого форка:
  - [ghostlane-project/ghostlane](https://github.com/ghostlane-project/ghostlane) - Ghostlane, форк olcbox из списка ниже. Kotlin Multiplatform/Compose для Android, iOS, macOS, Windows и Linux; olcRTC рядом с VLESS (Reality или TLS), Hysteria2, XHTTP, Trojan, Shadowsocks и VMess - тот клиент, ради которого сделано всё перечисленное выше

    <a href="https://apps.apple.com/app/id6795355210"><img src="docs/badges/app-store-badge.svg" alt="Download on the App Store" width="168" align="middle"></a>
    &nbsp;
    <a href="https://play.google.com/store/apps/details?id=org.proofkit.app"><img src="docs/badges/google-play-badge.png" alt="Get it on Google Play" width="217" align="middle"></a>

    Сборки для компьютеров и APK для Android: [GitHub Releases](https://github.com/ghostlane-project/ghostlane/releases).

- Клиенты сообщества:
  - [venterum/veil](https://github.com/venterum/veil) - V2Ray/Xray клиент для Android (форк v2rayNG), Material 3. Протоколы: VMess, VLESS, Shadowsocks, Trojan, SOCKS, WireGuard, Hysteria2 + `olcrtc`
  - [alananisimov/olcbox](https://github.com/alananisimov/olcbox) - Мультиплатформенный UI-клиент (Android, iOS, macOS, Windows, Linux). Kotlin Multiplatform/Compose. Все провайдеры (Jitsi, Telemost, WB Stream, Jazz), все транспорты, split tunneling, режимы TUN/proxy

## Сообщество

- Issues и вопросы: [github.com/ghostlane-project/olcrtc/issues](https://github.com/ghostlane-project/olcrtc/issues)
- Приложение на этом движке: [github.com/ghostlane-project/ghostlane](https://github.com/ghostlane-project/ghostlane)
- Как участвовать: [CONTRIBUTING.md](CONTRIBUTING.md) · Как сообщить об уязвимости: [SECURITY.md](SECURITY.md)

Каналы апстрима принадлежат заархивированному проекту, этот форк там не
поддерживается.

## Лицензия

Apache License 2.0, см. [LICENSE](LICENSE).

Это форк [openlibrecommunity/olcrtc](https://github.com/openlibrecommunity/olcrtc),
который выходил под WTFPL. Та лицензия разрешает распространение на других
условиях, и форк этим пользуется: проекту нужна лицензия, на которую могут
опереться пользователи, сборщики пакетов и юристы, с явной патентной
оговоркой. Исходный текст лицензии и копирайт сохранены в
[docs/upstream-license-wtfpl.txt](docs/upstream-license-wtfpl.txt), происхождение и лицензии зависимостей
описаны в [NOTICE](NOTICE).

App Store — товарный знак Apple Inc., зарегистрированный в США и других
странах. Google Play и логотип Google Play — товарные знаки Google LLC.
