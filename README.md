<div align="center">

# YPtun

### Быстрый VPN для обхода блокировок · Android

*VLESS · Reality · XHTTP поверх **Xray** и **sing-box**, **Hysteria2** (QUIC), обфусцированный **AmneziaWG**, туннель через звонки **VK-TURN**, DNS-туннель **DNSTT**, отдельный Telegram-прокси через **WARP** — и **olcRTC**, маскирующий трафик под видеозвонок.*

<br>

[![Последний релиз](https://img.shields.io/github/v/release/yanisplugg/olcvpn-client?style=for-the-badge&color=4c8eff&label=%D1%81%D0%BA%D0%B0%D1%87%D0%B0%D1%82%D1%8C)](https://github.com/yanisplugg/olcvpn-client/releases/latest)
[![Загрузки](https://img.shields.io/github/downloads/yanisplugg/olcvpn-client/total?style=for-the-badge&color=2ea043&label=%D0%B7%D0%B0%D0%B3%D1%80%D1%83%D0%B7%D0%BA%D0%B8)](https://github.com/yanisplugg/olcvpn-client/releases)
[![Звёзды](https://img.shields.io/github/stars/yanisplugg/olcvpn-client?style=for-the-badge&color=f0b429)](https://github.com/yanisplugg/olcvpn-client/stargazers)

![Платформа](https://img.shields.io/badge/%D0%BF%D0%BB%D0%B0%D1%82%D1%84%D0%BE%D1%80%D0%BC%D0%B0-Android%206.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white)
![Ядра](https://img.shields.io/badge/%D1%8F%D0%B4%D1%80%D0%B0-Xray%20%2B%20sing--box-blueviolet?style=flat-square)
![Лицензия](https://img.shields.io/badge/%D0%BB%D0%B8%D1%86%D0%B5%D0%BD%D0%B7%D0%B8%D1%8F-GPL--3.0-blue?style=flat-square)

<br>

**Русский** · [English](README.en.md) · [فارسی](README.fa.md) · [简体中文](README.zh.md)

</div>

---

## Зачем YPtun?

Большинство VPN-клиентов дают одно ядро и один способ подключения. **YPtun даёт набор инструментов.** В одном приложении сразу несколько движков обхода: заблокировали один способ — переключился и работаешь дальше.

> **Главное — универсальность.** Xray и sing-box со всеми ходовыми протоколами и транспортами, обфусцированный WireGuard через AmneziaWG, туннелирование через реальные звонки (VK-TURN и olcRTC), DNS-туннель DNSTT, импорт чего угодно и Happ-совместимые профили маршрутизации. Один способ зарезали — рядом ещё несколько.

> Сделано для мест, где интернет сопротивляется — для России, Ирана и любой страны, где сайты пропадают без предупреждения.

> **Скоро на десктопе** — готовятся нативные сборки под Windows и Linux.

---

## Что нового в 2.6.1

| | |
|---|---|
| **Авто-подключение к быстрейшему** | Кнопка «Авто» рядом с основной: параллельно пингует все готовые серверы реальным рукопожатием через прокси (а не просто TCP/ICMP), подключается к самому быстрому и перебирает дальше, если узел не поднялся. Кнопка доступна и при активном подключении — тап заново выбирает быстрейший. |
| **Маршрутизация по ASN** | Новый селектор `asn:62041` (Telegram), `asn:13335` (Cloudflare) и т.п. в профилях маршрутизации — ловит **все** сети оператора, включая сервисы на голых IP, которые доменные списки пропускают. Раскрывается в реальные диапазоны на лету, работает на обоих ядрах. В редакторе — пресеты в один тап. |
| **Скорость на главном экране** | Опциональная строка `↓ / ↑` под выбранной конфигурацией (тумблер в настройках, по умолчанию выключено). |
| **Telegram-прокси через WARP** | Отдельный фоновый прокси: поднимает AmneziaWG-туннель Cloudflare WARP и отдаёт локальный SOCKS5 для Telegram. Работает независимо от основного VPN, автоматически уходит с «мёртвых» точек WARP. |
| **Каскад из двух прокси** | Второй (выходной) прокси поверх основного, в том числе поверх xhttp-соединения через локальный SOCKS, с корректным `xmux` и XTLS Vision; DNS резолвится поверх каскада через TCP/DoH. |

---

## Возможности

| | |
|---|---|
| **Несколько движков** | Xray, sing-box, AmneziaWG, VK-TURN, DNSTT — ядро подбирается под протокол автоматически либо вручную. |
| **Протоколы** | VLESS · VMess · Trojan · Shadowsocks · Hysteria2 · WireGuard / AmneziaWG |
| **Транспорты** | TCP · WS · gRPC · HTTPUpgrade · XHTTP · TLS · Reality · отпечатки uTLS |
| **DNSTT (DNS-туннель)** | Туннель поверх DNS-запросов (KCP + Noise) — работает там, где весь остальной трафик заблокирован, а DNS ещё ходит. Автоустановка сервера на VPS по SSH в один тап. |
| **Telegram-прокси через WARP** | Лёгкий фоновый сервис: WARP-туннель + локальный SOCKS5 для Telegram, независимо от основного подключения. |
| **olcRTC** | Транспорт [olcRTC](https://github.com/openlibrecommunity/olcrtc) — трафик идёт через реальные сервисы видеозвонков (Jazz, Telemost, WB Stream, Jitsi); для DPI это обычный созвон, а не прокси. |
| **Умный импорт** | ссылки vless/vmess/trojan/ss, base64, JSON-панели, **полные сырые конфиги Xray / sing-box** (применяются как есть), AmneziaWG `.conf`/QR, olcRTC-URI, Happ-профили, массовый импорт списка ссылок. |
| **DNS и маршруты** | Happ-совместимые профили маршрутизации (блок/директ/прокси по `geoip:`/`geosite:`/`asn:`/доменам/CIDR), per-rule правила v2rayNG-стиля, тумблер «Блокировать РФ-домены», свои DNS и fakedns. |
| **Авто-выбор сервера** | Подключение к быстрейшему живому узлу в один тап, с перебором при неудаче. |
| **HTTP-прокси** | Happ-совместимый локальный HTTP-прокси поверх активного движка. |
| **Обход DPI** | Фрагментация TLS, мультиплексирование, обфускация AmneziaWG, блокировка QUIC где она протекает. |
| **Без утечек** | Перехватывает и IPv4, и IPv6 — мимо туннеля ничего не уходит. |
| **Раздельный туннель** | Выбираешь, какие приложения идут через VPN. |
| **Подписки** | Автообновление (можно отключать поштучно), счётчик доступных серверов, описания серверов, трафик/остаток, группы со сворачиванием/закрепом/сортировкой по пингу, папки. |

---

## Скачать

Бери последний подписанный APK со **[страницы релизов](https://github.com/yanisplugg/olcvpn-client/releases/latest)**.

| Сборка | Кому |
|--------|------|
| **`arm64-v8a`** | Современные телефоны — бери эту, если сомневаешься |
| `armeabi-v7a` | Старые 32-битные устройства |
| `x86_64` | Эмуляторы / x86-планшеты |
| `universal` | Один файл на всё (самый большой) |

Минимум — **Android 6.0** (API 23).

---

## Как это работает

```
┌──────────────┐   пакеты   ┌───────────────┐   SOCKS5   ┌────────────────────────────┐
│  Приложения  │ ─────────▶ │  Android TUN  │ ─────────▶ │     Движок (1 процесс)     │
└──────────────┘            │  (IPv4+IPv6)  │            │  ┌──────────────────────┐  │
                            └───────────────┘            │  │  Xray / sing-box     │  │
                                                         │  │  AmneziaWG / VK-TURN │  │
                                                         │  │  DNSTT / стелс olcRTC│  │
                                                         │  └──────────────────────┘  │
                                                         └─────────────┬──────────────┘
                                                                       ▼
                                                                открытый интернет
```

Все нативные ядра собраны в **одну** `gomobile`-библиотеку (единый Go-рантайм), поэтому Xray, sing-box, AmneziaWG, VK-TURN, DNSTT и olcRTC уживаются в одном процессе без конфликтов. Приложение поднимает `VpnService`, отдаёт пакеты в TUN и заворачивает их в выбранный движок через локальный SOCKS5.

---

## Движки — простыми словами

- **Xray / sing-box** — классические прокси-ядра: VLESS+Reality, XHTTP, WS+TLS и т.д. Ядро выбирается под транспорт автоматически.
- **AmneziaWG** — WireGuard с обфускацией: рукопожатие и пакеты не похожи на «обычный» WireGuard, который часто режут по сигнатуре.
- **Hysteria2** — быстрый протокол поверх QUIC с обфускацией Salamander и перескоком портов; хорошо держит скорость на нестабильных каналах.
- **VK-TURN** — поднимает локальный WireGuard и гонит его через TURN-серверы звонков VK; несколько «звонков» связываются для пропускной способности.
- **DNSTT** — туннель поверх DNS-запросов; работает там, где открыт только DNS.
- **olcRTC** — маскировка под видеозвонок: трафик едет через настоящие сервисы конференций, и для DPI выглядит как живой созвон.
- **Telegram-прокси через WARP** — отдельный фоновый прокси для Telegram поверх Cloudflare WARP.

---

## Сборка из исходников

Всё необходимое уже в репозитории (`cores`, `olcrtc`, `sing-box`, `awgproxy`, `hysteria2proxy`, `free-turn-proxy`, `dnstt`, `wdtt`, `amneziawg-go`). Понадобится:

- **JDK 17** (подойдёт встроенный в Android Studio)
- **Android SDK** (укажи `sdk.dir` в `YPtun/local.properties`) + **NDK `28.2.13676358`**
- **Go** + [`gomobile`](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile) в `PATH`

> `gomobile` вызывает `javac`, поэтому добавь `bin/` из JDK в `PATH` — не только `JAVA_HOME`.

```bash
cd YPtun
./gradlew :androidApp:assembleRelease \
  -Polcbox.version=2.6.1 -Polcbox.versionCode=286
```

APK появятся в `YPtun/androidApp/build/outputs/apk/release/`.
Хочешь только под свой телефон и быстрее — добавь `-Polcbox.android.abiFilters=arm64-v8a`.

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
- **Ветки** — стабильное в `main`, активная разработка в `Beta`; релизы тегируются `vX.Y.Z`.

Нашёл баг или хочешь фичу — открывай issue или PR, см. **[CONTRIBUTING.md](CONTRIBUTING.md)**.

---

## Структура проекта

```
YPtun/            Kotlin Multiplatform приложение — Compose UI, Android VpnService, движки
cores/            Go-связка: один gomobile-AAR из sing-box + olcRTC + Xray + AmneziaWG + VK-TURN + DNSTT
olcrtc/           olcRTC — транспорт-маскировка под видеозвонок   (сторонний, вендорено)
sing-box/         sing-box / libbox                                (вендорено)
awgproxy/         обёртка AmneziaWG → локальный SOCKS5             (Go-модуль)
hysteria2proxy/   обёртка Hysteria2 (apernet) → локальный SOCKS5   (Go-модуль)
free-turn-proxy/  VK-TURN — туннель через звонки VK                (Go-модуль)
dnstt/            DNSTT — туннель поверх DNS                        (клиент + сервер)
wdtt/             WDTT — вариант туннеля                           (клиент + сервер)
amneziawg-go/     реализация AmneziaWG                             (вендорено)
```

---

## Планы

- [x] Релиз на Android
- [x] Движки AmneziaWG, VK-TURN и DNSTT
- [x] Профили маршрутизации (Happ-совместимые) + ASN
- [ ] Сборка под **Windows** — *скоро*
- [ ] Сборка под **Linux** — *скоро*

> Общий движок уже работает на JVM (`desktopApp`), так что десктоп — следующий на очереди.

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
[AmneziaWG](https://github.com/amnezia-vpn/amneziawg-go).

## Лицензия

[GPL-3.0](LICENSE) — приложение распространяется под GNU GPL v3.0, так как включает **sing-box** (тоже GPL-3.0): копилефт распространяется на весь продукт. Вендоренные компоненты сохраняют свои лицензии (`sing-box` — GPL-3.0, Xray — MPL-2.0, `amneziawg-go` — MIT, `olcrtc` — WTFPL).

<div align="center">
<br>

<img src="docs/no-rkn.jpg" alt="Нет цензуре" width="150">

<br><br>

> *«Нация, которая боится позволить своему народу судить о правде и лжи на открытом рынке, — это нация, которая боится своего народа.»*
>
> — **Джон Ф. Кеннеди**

<br>

<sub>Для свободного интернета</sub>

</div>
