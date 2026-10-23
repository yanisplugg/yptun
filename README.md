<div align="center">

# YPtun

### Мультипротокольный сетевой клиент · Android, Windows и Linux · iOS в бете

*VLESS · Reality · XHTTP поверх **Xray** и **sing-box**, **Hysteria2** (QUIC), обфусцированный **AmneziaWG**, туннель через звонки **VK-TURN**, DNS-туннель **MasterDNS**, отдельный Telegram-прокси через **WARP** — и **olcRTC** — транспорт поверх сервисов видеосвязи.*

<br>

[![Последний релиз](https://img.shields.io/github/v/release/yanisplugg/yptun?style=for-the-badge&color=4c8eff&label=%D1%81%D0%BA%D0%B0%D1%87%D0%B0%D1%82%D1%8C)](https://github.com/yanisplugg/yptun/releases/latest)
[![Загрузки](https://img.shields.io/github/downloads/yanisplugg/yptun/total?style=for-the-badge&color=2ea043&label=%D0%B7%D0%B0%D0%B3%D1%80%D1%83%D0%B7%D0%BA%D0%B8)](https://github.com/yanisplugg/yptun/releases)
[![Звёзды](https://img.shields.io/github/stars/yanisplugg/yptun?style=for-the-badge&color=f0b429)](https://github.com/yanisplugg/yptun/stargazers)

💎 **Поддержать проект** — TON или USDT (сеть TON):

```
UQAPC9J9UY8oaYV4AwjEAYIIJMswo7qVzJDkf4pzY8kVtzJ-
```

🇷🇺 **Поддержать из России** — через DonationAlerts:<br>
[![DonationAlerts](https://img.shields.io/badge/DonationAlerts-%D0%BF%D0%BE%D0%B4%D0%B4%D0%B5%D1%80%D0%B6%D0%B0%D1%82%D1%8C-F57D07?style=for-the-badge&logo=githubsponsors&logoColor=white)](https://www.donationalerts.com/r/yanisplugg)

![Платформа](https://img.shields.io/badge/%D0%BF%D0%BB%D0%B0%D1%82%D1%84%D0%BE%D1%80%D0%BC%D0%B0-Android%206.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white)
![Платформа](https://img.shields.io/badge/%D0%BF%D0%BB%D0%B0%D1%82%D1%84%D0%BE%D1%80%D0%BC%D0%B0-Windows%2010%2B-0078d4?style=flat-square&logo=windows&logoColor=white)
![Платформа](https://img.shields.io/badge/%D0%BF%D0%BB%D0%B0%D1%82%D1%84%D0%BE%D1%80%D0%BC%D0%B0-Linux%20.deb-fcc624?style=flat-square&logo=linux&logoColor=black)
![Платформа](https://img.shields.io/badge/%D0%BF%D0%BB%D0%B0%D1%82%D1%84%D0%BE%D1%80%D0%BC%D0%B0-iOS%20%D0%B1%D0%B5%D1%82%D0%B0-8e8e93?style=flat-square&logo=apple&logoColor=white)
![Ядра](https://img.shields.io/badge/%D1%8F%D0%B4%D1%80%D0%B0-Xray%20%2B%20sing--box-blueviolet?style=flat-square)
![Лицензия](https://img.shields.io/badge/%D0%BB%D0%B8%D1%86%D0%B5%D0%BD%D0%B7%D0%B8%D1%8F-GPL--3.0-blue?style=flat-square)

<br>

**Русский** · [English](README.en.md) · [فارسی](README.fa.md) · [简体中文](README.zh.md)

</div>

---

## Зачем YPtun?

Большинство VPN-клиентов дают одно ядро и один способ подключения. **YPtun даёт набор инструментов.** В одном приложении сразу несколько сетевых движков: один транспорт работает нестабильно — переключился на другой и работаешь дальше.

> **Главное — универсальность.** Xray и sing-box со всеми ходовыми протоколами и транспортами, обфусцированный WireGuard через AmneziaWG, туннелирование через реальные звонки (VK-TURN и olcRTC), DNS-туннель MasterDNS, импорт чего угодно и Happ-совместимые профили маршрутизации. Если один способ подключения не подходит — рядом ещё несколько.

> Для тех, кому нужен гибкий контроль над сетевыми соединениями: свои серверы, свои подписки, своя маршрутизация.

> **Теперь и на Windows** — установщик и портативная версия одним `.exe`, x64 и нативный ARM64. То же приложение и те же движки, что на телефоне: подписки, профили маршрутизации, каскад, VK-TURN, olcRTC, MasterDNS, Trust Tunnel.

---

## Что нового в 3.7.0

| | |
|---|---|
| **Cloudflare WARP для JSON-подписок** | Клиентская сторона: каждый прокси подписки идёт через WARP; серверная — выход через WARP с UDP-релеем. Android и ПК, WireGuard и AmneziaWG. |
| **Бесплатные серверы + WARP** | WARP для каждого найденного сервера или сразу для всех: AmneziaWG для российских, WireGuard для остальных. |
| **Движок AmneziaWG / WireGuard** | Выбор протокола, конфиг wg-quick или файлом; тумблер «Разрешить редактирование конфигураций» открывает все параметры (Jc, S1–S4, H1–H4, I1–I5, MTU, DNS…) отдельными полями. |
| **Поиск бесплатных серверов в фоне** | Кнопка «В фон», автообновление по расписанию, остановка с сохранением найденного (#72), закрепление сервера, лимиты 2500 и 5000. |
| **Пинг и подписки** | Пауза, продолжение и перезапуск пинга с прогрессом; ссылку подписки можно посмотреть и поменять, закрепления и папки переезжают (#71); каскад для всей подписки. |
| **Приватность** | Новая страница: отчёт ProviderID (#69), User-Agent подписки, данные об устройстве и свой HWID (#70); при проверке обновлений HWID на GitHub не уходит. |
| **Маршрутизация и ядра** | `geosite:telegram` включает адреса дата-центров Telegram (#68); OpenFlux 0.13.0 (Avito, несколько транспортов в сессии, #66); понятнее ошибки csqtt (#67); на ПК можно сменить порт SOCKS5. |
| **Меньше нагрузки** | Автообновление бесплатных серверов только при открытом окне, мягкий лимит памяти ядер Go, рабочие потоки VPN — на менее мощных ядрах CPU. |
| **Исправления** | Зеркало при недоступном API Cloudflare WARP; серверный WARP AmneziaWG без пакетов I1–I5; обновление по дельте на ПК; списки больше не прыгают в начало. |

---

## Возможности

| | |
|---|---|
| **Несколько движков** | Xray, sing-box, AmneziaWG, VK-TURN, MasterDNS — ядро подбирается под протокол автоматически либо вручную. |
| **Протоколы** | VLESS · VMess · Trojan · Shadowsocks · Hysteria2 · WireGuard / AmneziaWG |
| **Транспорты** | TCP · WS · gRPC · HTTPUpgrade · XHTTP · TLS · Reality · отпечатки uTLS |
| **MasterDNS (DNS-туннель)** | Туннель поверх DNS-запросов (MasterDnsVPN: свой ARQ-транспорт, несколько резолверов сразу, дублирование пакетов) — вариант для сетей, где доступен только DNS. Автоустановка сервера на VPS по SSH в один тап. |
| **Telegram-прокси через WARP** | Лёгкий фоновый сервис: WARP-туннель + локальный SOCKS5 для Telegram, независимо от основного подключения. |
| **olcRTC** | Транспорт [olcRTC](https://github.com/openlibrecommunity/olcrtc) — трафик идёт через реальные сервисы видеозвонков (Jazz, Telemost, WB Stream, Jitsi); трафик передаётся поверх этих сервисов. |
| **Умный импорт** | ссылки vless/vmess/trojan/ss, base64, JSON-панели, **полные сырые конфиги Xray / sing-box** (применяются как есть), AmneziaWG `.conf`/QR, olcRTC-URI, Happ-профили, массовый импорт списка ссылок. |
| **DNS и маршруты** | Happ-совместимые профили маршрутизации (запретить/напрямую/через прокси по `geoip:`/`geosite:`/`asn:`/доменам/CIDR), per-rule правила v2rayNG-стиля, тумблер фильтрации РФ-доменов, свои DNS и fakedns. |
| **Авто-выбор сервера** | Подключение к быстрейшему живому узлу в один тап, с перебором при неудаче. |
| **HTTP-прокси** | Happ-совместимый локальный HTTP-прокси поверх активного движка. |
| **Тонкая настройка соединения** | Фрагментация TLS, мультиплексирование, обфускация AmneziaWG, отключение QUIC там, где он протекает. |
| **Без утечек** | Перехватывает и IPv4, и IPv6 — мимо туннеля ничего не уходит. |
| **Раздельный туннель** | Выбираешь, какие приложения идут через VPN. |
| **Подписки** | Автообновление (можно отключать поштучно), счётчик доступных серверов, описания серверов, трафик/остаток, группы со сворачиванием/закрепом/сортировкой по пингу, папки. |

---

## Скачать

Бери последний подписанный APK со **[страницы релизов](https://github.com/yanisplugg/yptun/releases/latest)**.

| Сборка | Кому |
|--------|------|
| **`arm64-v8a`** | Современные телефоны — бери эту, если сомневаешься |
| `armeabi-v7a` | Старые 32-битные устройства |
| `x86_64` | Эмуляторы / x86-планшеты |
| `universal` | Один файл на всё (самый большой) |

Минимум — **Android 6.0** (API 23).

---

## Разрешения и зачем они нужны

YPtun запрашивает только то, без чего не работает конкретная функция. Камеру, уведомления, работу без ограничений батареи и установку обновлений приложение спрашивает в тот момент, когда вы пользуетесь этой функцией, а не при установке.

### Android

| Разрешение | Зачем |
|---|---|
| **VPN** (`BIND_VPN_SERVICE`) | Системный запрос «Разрешить подключение VPN» при первом подключении. Без него приложение не может поднять туннель и пропустить через него трафик. |
| **Интернет и состояние сети** (`INTERNET`, `ACCESS_NETWORK_STATE`) | Соединение с серверами, пинг, загрузка подписок; переподключение при смене Wi-Fi ↔ мобильная сеть. |
| **Работа в фоне** (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK`) | VPN и Telegram-прокси работают как службы с уведомлением, чтобы система не останавливала их при выключенном экране. |
| **Уведомления** (`POST_NOTIFICATIONS`, Android 13+) | Уведомление о подключении со скоростью и кнопкой отключения — Android требует его для фоновой службы. |
| **Без ограничений батареи** (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) | Только по кнопке в настройках: исключение из Doze, чтобы туннель не рвался ночью. Без вашего нажатия не запрашивается. |
| **Камера** (`CAMERA`) | Сканер QR-кодов для импорта серверов. Запрашивается при первом открытии сканера; кадры обрабатываются на телефоне и никуда не отправляются. Без камеры QR можно импортировать из файла. |
| **Список приложений** (`QUERY_ALL_PACKAGES`) | Раздельное туннелирование: показать все установленные приложения, включая системные без значка, чтобы выбрать, какие идут через VPN. Список не покидает телефон. |
| **Установка приложений** (`REQUEST_INSTALL_PACKAGES`) | Обновление из самого приложения: скачанный APK передаётся системному установщику, который всё равно спросит подтверждение. |
| **Задачи после перезагрузки** (`RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE_DATA_SYNC`) | Добавляет библиотека WorkManager, чтобы периодическая задача пережила перезагрузку. Задача одна — ежедневная отметка для подписок с Happ `providerid` (см. ниже). Своего автозапуска у YPtun нет. |
| **Перенос данных** (`org.olcbox.app.permission.MIGRATION`) | Собственное право уровня signature: переносит настройки со старой установки `org.olcbox.app` на новую `org.yptun.app`. Прочитать эти данные может только APK с той же подписью. |

### Windows

| Право | Зачем |
|---|---|
| **Администратор** (UAC) | Только для режима «Туннель»: создать сетевой адаптер (wintun) и прописать маршруты. Запрос появляется один раз при запуске, если выбран этот режим. В режиме «Прокси» права администратора не нужны. |
| **Системный прокси** | В режиме «Прокси» приложение прописывает прокси Windows для текущего пользователя и возвращает прежние настройки при отключении. Если приложение упало, оставшийся прокси убирается при следующем запуске. |
| **Список процессов** | Разделение по процессам — и поиск другого запущенного VPN-клиента, который мешает подключению. Закрыть его приложение предложит, но без вашего согласия ничего не завершает. |

### Куда приложение обращается, кроме ваших серверов

- **GitHub** — проверка обновлений и, по умолчанию, списки для маршрутизации (geoip/geosite, ASN).
- **`check.happ-proxy.com`** — только если в подписке есть Happ `providerid`: раз в сутки, как и Happ, приложение отправляет туда отметку с теми же данными устройства, что получает сама подписка (HWID, ОС, модель, версия приложения). Без `providerid` этого запроса нет.
- **Сервисы выбранного способа подключения** — VK (VK-TURN), Cloudflare (Telegram через WARP), сервисы звонков (olcRTC), Яндекс Документы или MAX (OpenFlux).

---

## Как это работает

```
┌──────────────┐   пакеты   ┌───────────────┐   SOCKS5   ┌────────────────────────────┐
│  Приложения  │ ─────────▶ │  Android TUN  │ ─────────▶ │     Движок (1 процесс)     │
└──────────────┘            │  (IPv4+IPv6)  │            │  ┌──────────────────────┐  │
                            └───────────────┘            │  │  Xray / sing-box     │  │
                                                         │  │  AmneziaWG / VK-TURN │  │
                                                         │  │  MasterDNS / стелс olcRTC│  │
                                                         │  └──────────────────────┘  │
                                                         └─────────────┬──────────────┘
                                                                       ▼
                                                                открытый интернет
```

Все нативные ядра собраны в **одну** `gomobile`-библиотеку (единый Go-рантайм), поэтому Xray, sing-box, AmneziaWG, VK-TURN, MasterDNS и olcRTC уживаются в одном процессе без конфликтов. Приложение поднимает `VpnService`, отдаёт пакеты в TUN и заворачивает их в выбранный движок через локальный SOCKS5.

---

## Движки — простыми словами

- **Xray / sing-box** — классические прокси-ядра: VLESS+Reality, XHTTP, WS+TLS и т.д. Ядро выбирается под транспорт автоматически.
- **AmneziaWG** — WireGuard с обфускацией: рукопожатие и пакеты отличаются от стандартного WireGuard.
- **Hysteria2** — быстрый протокол поверх QUIC с обфускацией Salamander и перескоком портов; хорошо держит скорость на нестабильных каналах.
- **VK-TURN** — поднимает локальный WireGuard и гонит его через TURN-серверы звонков VK; несколько «звонков» связываются для пропускной способности.
- **MasterDNS** — туннель поверх DNS-запросов; для сетей, где доступен только DNS.
- **olcRTC** — транспорт через сервисы видеоконференций: трафик передаётся поверх реального звонка.
- **Telegram-прокси через WARP** — отдельный фоновый прокси для Telegram поверх Cloudflare WARP.

---

## Сборка из исходников

Всё необходимое уже в репозитории (`cores`, `olcrtc`, `sing-box`, `awgproxy`, `hysteria2proxy`, `free-turn-proxy`, `masterdns`, `wdtt`, `amneziawg-go`). Понадобится:

- **JDK 17** (подойдёт встроенный в Android Studio)
- **Android SDK** (укажи `sdk.dir` в `YPtun/local.properties`) + **NDK `28.2.13676358`**
- **Go** + [`gomobile`](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile) в `PATH`

> `gomobile` вызывает `javac`, поэтому добавь `bin/` из JDK в `PATH` — не только `JAVA_HOME`.

```bash
cd YPtun
./gradlew :androidApp:assembleRelease \
  -Polcbox.version=3.6.0 -Polcbox.versionCode=381
```

APK появятся в `YPtun/androidApp/build/outputs/apk/release/`.
Хочешь только под свой телефон и быстрее — добавь `-Polcbox.android.abiFilters=arm64-v8a`.

Десктопная сборка (Windows, нужен **Go** в `PATH`, Android SDK всё равно требуется — Gradle
конфигурирует общие модули):

```bash
cd YPtun
./gradlew :desktopApp:createDistributable          # готовый образ приложения
powershell -File desktopApp/packaging/windows/build-portable.ps1    # портативный .exe (СНАЧАЛА: нормализует образ на месте)
powershell -File desktopApp/packaging/windows/build-installer.ps1   # установщик
```

<details>
<summary>Подпись своих релизных сборок (опционально, для мейнтейнеров)</summary>

<br>

По умолчанию Gradle собирает debug-подписанные APK. Если хочешь публиковать свои подписанные релизы — создай keystore и укажи его в `YPtun/keystore.properties`:

```properties
storeFile=release.keystore
storePassword=твой-пароль
keyAlias=твой-алиас
keyPassword=твой-пароль
```

Этот файл (и сам `.keystore`) — в `.gitignore` и никогда не коммитятся, живут только на твоей машине. Береги keystore: тем же ключом подписываются обновления, чтобы они ставились поверх прошлых версий.

</details>

---

## Процесс разработки

YPtun — **Kotlin Multiplatform**: вся логика (импорт, сборка конфигов, движки, состояние UI) живёт в `commonMain`, платформенные мелочи — в `androidMain`. Тот же код крутится и на JVM-десктопе.

- **UI** — Jetpack Compose, единый дизайн на всех платформах.
- **Локализация** — русский, английский, فارسی и 简体中文 в одном файле строк.
- **Native-ядра** — Go, собираются в один gomobile-AAR таском `buildCoresAndroidAar`; входы ядер отслеживаются, AAR пересобирается только при правке Go-кода.
- **Тесты** — модульные тесты на парсеры/конвертеры маршрутизации (`./gradlew :sharedUI:jvmTest`).
- **Ветки** — стабильное в `main`, активная разработка в `Beta`, десктопная — в `pc-client-beta`; релизы тегируются `vX.Y.Z`.

Нашёл баг или хочешь фичу — открывай issue или PR, см. **[CONTRIBUTING.md](CONTRIBUTING.md)**.

---

## Структура проекта

```
YPtun/            Kotlin Multiplatform приложение — Compose UI, Android VpnService, движки
cores/            Go-связка: один gomobile-AAR из sing-box + olcRTC + Xray + AmneziaWG + VK-TURN + MasterDNS
olcrtc/           olcRTC — транспорт поверх видеозвонков          (сторонний, вендорено)
sing-box/         sing-box / libbox                                (вендорено)
awgproxy/         обёртка AmneziaWG → локальный SOCKS5             (Go-модуль)
hysteria2proxy/   обёртка Hysteria2 (apernet) → локальный SOCKS5   (Go-модуль)
free-turn-proxy/  VK-TURN — туннель через звонки VK                (Go-модуль)
masterdns/        MasterDNS — туннель поверх DNS                    (клиент + сервер)
wdtt/             WDTT — вариант туннеля                           (клиент + сервер)
amneziawg-go/     реализация AmneziaWG                             (вендорено)
```

---

## Планы

- [x] Релиз на Android
- [x] Движки AmneziaWG, VK-TURN и MasterDNS
- [x] Профили маршрутизации (Happ-совместимые) + ASN
- [x] Сборка под **Windows** (x64 и ARM64)
- [x] Сборка под **Linux** (`.deb`, x64 и ARM64)
- [ ] Сборка под **iOS** — *в бете*

---

## Участие

PR и issue приветствуются. Перед началом загляни в:
- **[CONTRIBUTING.md](CONTRIBUTING.md)** — как собрать, оформить и прислать изменения
- **[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)** — правила общения
- **[SECURITY.md](SECURITY.md)** — как сообщить об уязвимости

---

## Благодарности

На плечах гигантов:
[Xray-core](https://github.com/XTLS/Xray-core) ·
[sing-box](https://github.com/SagerNet/sing-box) ·
[olcRTC](https://github.com/openlibrecommunity/olcrtc) ·
[AmneziaWG](https://github.com/amnezia-vpn/amneziawg-go) ·
[OpenFlux](https://github.com/p1neappleXpress/OpenFlux) ·
[qWDTT](https://github.com/SpaceNeuroX/proxy-turn-vk-android) ·
[MasterDnsVPN](https://github.com/masterking32/MasterDnsVPN) ·
[free-turn-proxy](https://github.com/samosvalishe/free-turn-proxy) ·
[TrustTunnel](https://github.com/TrustTunnel/TrustTunnelClient) ·
[hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) ·
[tun2socks](https://github.com/xjasonlyu/tun2socks).

## Политика подписи кода

Бесплатная подпись кода — [SignPath.io](https://about.signpath.io), сертификат — [SignPath Foundation](https://signpath.org).
Подписываются Windows-установщик и portable, собранные в [GitHub Actions](.github/workflows/windows-desktop.yml) из этого репозитория.

- Коммиттеры и ревьюеры: [участники репозитория](https://github.com/yanisplugg/yptun/graphs/contributors)
- Утверждение подписи: [владелец репозитория](https://github.com/yanisplugg)

Конфиденциальность: приложение само не передаёт данные в другие сетевые системы, кроме тех, что выбрал пользователь (его серверы, подписки, выбранные им сервисы подключения), и проверки обновлений на GitHub. Подробности — в разделе [«Разрешения и зачем они нужны»](#разрешения-и-зачем-они-нужны).

## Лицензия

[GPL-3.0](LICENSE) — приложение распространяется под GNU GPL v3.0, так как включает **sing-box** (тоже GPL-3.0): копилефт распространяется на весь продукт. Вендоренные компоненты сохраняют свои лицензии (`sing-box` — GPL-3.0, Xray — MPL-2.0, `amneziawg-go` — MIT, `olcrtc` — WTFPL, OpenFlux — GPL-3.0, qWDTT (`wdtt`) — GPL-3.0, MasterDnsVPN (`masterdns`) — MIT, `free-turn-proxy` — Happy Bunny License (MIT-подобная), Trust Tunnel — Apache-2.0, `hev-socks5-tunnel` — MIT, `tun2socks` — MIT).
