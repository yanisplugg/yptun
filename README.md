<div align="center">

# 🛡️ YPtun

### VPN-клиент для обхода блокировок · Android

*VLESS · Reality · XHTTP поверх **Xray** и **sing-box**, обфусцированный **AmneziaWG**, туннель через звонки **VK-TURN** — и, главное, универсальность: даже поддержка **olcRTC**, маскирующего трафик под видеозвонок.*

<br>

[![Последний релиз](https://img.shields.io/github/v/release/yanisplugg/olcvpn-client?style=for-the-badge&color=4c8eff&label=%D1%81%D0%BA%D0%B0%D1%87%D0%B0%D1%82%D1%8C)](https://github.com/yanisplugg/olcvpn-client/releases/latest)
[![Загрузки](https://img.shields.io/github/downloads/yanisplugg/olcvpn-client/total?style=for-the-badge&color=2ea043&label=%D0%B7%D0%B0%D0%B3%D1%80%D1%83%D0%B7%D0%BA%D0%B8)](https://github.com/yanisplugg/olcvpn-client/releases)
[![Звёзды](https://img.shields.io/github/stars/yanisplugg/olcvpn-client?style=for-the-badge&color=f0b429)](https://github.com/yanisplugg/olcvpn-client/stargazers)

![Платформа](https://img.shields.io/badge/%D0%BF%D0%BB%D0%B0%D1%82%D1%84%D0%BE%D1%80%D0%BC%D0%B0-Android%206.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white)
![Ядра](https://img.shields.io/badge/%D1%8F%D0%B4%D1%80%D0%B0-Xray%20%2B%20sing--box-blueviolet?style=flat-square)
![Лицензия](https://img.shields.io/badge/%D0%BB%D0%B8%D1%86%D0%B5%D0%BD%D0%B7%D0%B8%D1%8F-MIT-lightgrey?style=flat-square)

<br>

**🌍 Русский** · [**English**](README.en.md) · [**فارسی**](README.fa.md)

</div>

---

## ✨ Зачем YPtun?

Большинство VPN-клиентов дают одно ядро и один способ подключения. **YPtun даёт набор инструментов.**
В одном приложении — **несколько движков обхода** сразу: заблокировали один способ — переключился и работаешь дальше.

> ⭐ **Главная особенность — универсальность.** Xray и sing-box со всеми ходовыми протоколами и транспортами, обфусцированный WireGuard через **AmneziaWG**, туннелирование через реальные звонки (**VK-TURN** и **olcRTC**), импорт чего угодно и Happ-совместимые профили маршрутизации. Один способ зарезали — рядом ещё несколько.

> Сделано для мест, где интернет сопротивляется — для 🇷🇺 России, 🇮🇷 Ирана и любой страны, где сайты пропадают без предупреждения. 🌐

> 🖥️ **Скоро на десктопе** — готовятся нативные сборки под **Windows** и **Linux**.

---

## 🆕 Что нового в 2.0

| | |
|---|---|
| 🌀 **Движок AmneziaWG** | Обфусцированный WireGuard (AmneziaWG) — импорт `.conf`/QR, тонкая настройка обфускации (Jc/Jmin/Jmax/S1/S2/H1–H4). Работает как самостоятельный выход или звено в цепочке. |
| 📞 **Движок VK-TURN** | Туннель через TURN-инфраструктуру звонков VK. Связывание нескольких параллельных «звонков» для скорости, выбор выхода: WireGuard / AmneziaWG / прокси. |
| 🧭 **Профили маршрутизации** | Happ-совместимые профили (`happ://routing/add/…`): блок/директ/прокси по `geoip:`/`geosite:`/доменам/CIDR, переключатель «весь трафик в прокси», свои DNS и fakedns. Конвертируются в оба ядра. |
| 🗂️ **Удобные подписки** | Группы подписок можно **сворачивать**, **закреплять наверху** и **сортировать локации по пингу** — состояние запоминается. Массовый импорт списка ссылок одним вставлением. |
| 🛡️ **Меньше утечек** | Безусловная блокировка QUIC на транспортах, которые его не тянут, и резолв доменов под geoip-правила — больше никаких протечек мимо туннеля. |
| 🔔 **Уведомление** | Цветной логотип, опциональная скорость загрузки/отдачи прямо в шторке. |

---

## 🚀 Возможности

| | |
|---|---|
| 🔀 **Несколько движков** | **Xray**, **sing-box**, **AmneziaWG** и **VK-TURN** — ядро подбирается под протокол автоматически либо вручную. |
| 🧬 **Протоколы** | VLESS · VMess · Trojan · Shadowsocks · WireGuard / AmneziaWG |
| 🚇 **Транспорты** | TCP · WS · gRPC · HTTPUpgrade · **XHTTP** · TLS · **Reality** · отпечатки uTLS |
| 🎭 **Поддержка olcRTC** | Транспорт [olcRTC](https://github.com/openlibrecommunity/olcrtc) (от openlibrecommunity) — трафик идёт через реальные сервисы видеозвонков (Jazz, Telemost, WB Stream, Jitsi), для DPI это обычный звонок, а не прокси. |
| 📥 **Умный импорт** | ссылки vless/vmess/trojan/ss, base64, JSON-панели, **полные сырые конфиги Xray / sing-box**, AmneziaWG `.conf`/QR, olcRTC-URI, Happ-профили. |
| 🧭 **DNS и маршруты** | Профили маршрутизации, импорт полного Xray-конфига (применяется *как есть*) или встроенный тумблер **«Блокировать РФ-домены»**. |
| 🧱 **Обход DPI** | Фрагментация TLS, мультиплексирование, обфускация AmneziaWG, блокировка QUIC. |
| 🔒 **Без утечек** | Перехватывает **и IPv4, и IPv6** — мимо туннеля ничего не уходит. |
| 📱 **Раздельный туннель** | Выбираешь, какие приложения идут через VPN. |
| 🗂️ **Подписки** | Автообновление, показ трафика/остатка, группы со сворачиванием/закрепом/сортировкой по пингу. |

---

## 📦 Скачать

Бери последний подписанный APK со **[страницы релизов](https://github.com/yanisplugg/olcvpn-client/releases/latest)**.

| Сборка | Кому |
|--------|------|
| 🟢 **`arm64-v8a`** | Современные телефоны — **бери эту, если сомневаешься** |
| 🟡 `armeabi-v7a` | Старые 32-битные устройства |
| 🔵 `x86_64` | Эмуляторы / x86-планшеты |
| ⚪ `universal` | Один файл на всё (самый большой) |

> 💡 Не уверен? Качай **arm64-v8a** или **universal**.

Минимум — **Android 6.0** (API 23).

---

## 🧠 Как это работает

```
┌──────────────┐   пакеты   ┌───────────────┐   SOCKS5   ┌────────────────────────────┐
│  Приложения  │ ─────────▶ │  Android TUN  │ ─────────▶ │     Движок (1 процесс)     │
└──────────────┘            │  (IPv4+IPv6)  │            │  ┌──────────────────────┐  │
                            └───────────────┘            │  │  Xray / sing-box     │  │
                                                         │  │  AmneziaWG / VK-TURN │  │
                                                         │  │  + стелс olcRTC      │  │
                                                         │  └──────────────────────┘  │
                                                         └─────────────┬──────────────┘
                                                                       ▼
                                                                🌍 открытый интернет
```

Все нативные ядра собраны в **одну** `gomobile`-библиотеку (единый Go-рантайм), поэтому Xray, sing-box, AmneziaWG, VK-TURN и olcRTC уживаются в одном процессе без конфликтов. Приложение лишь поднимает `VpnService`, отдаёт пакеты в TUN и заворачивает их в выбранный движок через локальный SOCKS5.

---

## 🧩 Движки — простыми словами

- **Xray / sing-box** — классические прокси-ядра. VLESS+Reality, XHTTP, WS+TLS и т.д. Ядро выбирается под транспорт автоматически.
- **AmneziaWG** — WireGuard с обфускацией: рукопожатие и пакеты не похожи на «обычный» WireGuard, который часто режут по сигнатуре.
- **VK-TURN** — поднимает локальный WireGuard и гонит его через TURN-серверы звонков VK; несколько «звонков» связываются для пропускной способности.
- **olcRTC** — маскировка под видеозвонок: трафик едет через настоящие сервисы конференций, и для DPI выглядит как живой созвон.

---

## 🛠️ Сборка из исходников

Всё необходимое уже в репозитории (`cores`, `olcrtc`, `sing-box`, `awgproxy`, `free-turn-proxy`, `amneziawg-go`). Понадобится:

- **JDK 17** (подойдёт встроенный в Android Studio)
- **Android SDK** (укажи `sdk.dir` в `YPtun/local.properties`) + **NDK `28.2.13676358`**
- **Go** + [`gomobile`](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile) в `PATH`

> ⚠️ `gomobile` вызывает `javac`, поэтому добавь `bin/` из JDK в `PATH` — не только `JAVA_HOME`.

```bash
cd YPtun
./gradlew :androidApp:assembleRelease \
  -Polcbox.version=2.0.0 -Polcbox.versionCode=2
```

APK появятся в `YPtun/androidApp/build/outputs/apk/release/`.
Хочешь только под свой телефон и быстрее — добавь `-Polcbox.android.abiFilters=arm64-v8a`.

<details>
<summary>🔑 Подпись своих релизных сборок (опционально, для мейнтейнеров)</summary>

<br>

По умолчанию Gradle собирает debug-подписанные APK. Если хочешь публиковать **свои**
подписанные релизы — создай keystore и укажи его в `YPtun/keystore.properties`:

```properties
storeFile=release.keystore
storePassword=твой-пароль
keyAlias=твой-алиас
keyPassword=твой-пароль
```

Этот файл (и сам `.keystore`) — **в `.gitignore` и никогда не коммитятся**, живут только
на твоей машине. Береги keystore: тем же ключом подписываются обновления, чтобы они
ставились поверх прошлых версий.

</details>

---

## 🧪 Процесс разработки

YPtun — **Kotlin Multiplatform**: вся логика (импорт, сборка конфигов, движки, состояние UI)
живёт в `commonMain`, платформенные мелочи — в `androidMain`. Это значит, что тот же код
крутится и на JVM-десктопе.

- **UI** — Jetpack Compose, единый дизайн на всех платформах.
- **Локализация** — три языка (🇷🇺 русский, 🇬🇧 английский, 🇮🇷 فارسی) в одном файле строк.
- **Native-ядра** — Go, собираются в один gomobile-AAR таском `buildCoresAndroidAar`; входы
  ядер отслеживаются, так что AAR пересобирается только при правке Go-кода (кэш Go ускоряет).
- **Тесты** — модульные тесты на парсеры/конвертеры маршрутизации (`./gradlew :sharedUI:jvmTest`).
- **Ветки** — стабильное в `main`, активная разработка в `Beta`; релизы тегируются `vX.Y.Z`.

Нашёл баг или хочешь фичу — открывай issue или PR, см. **[CONTRIBUTING.md](CONTRIBUTING.md)**.

---

## 🗂️ Структура проекта

```
YPtun/            Kotlin Multiplatform приложение — Compose UI, Android VpnService, движки
cores/            Go-связка: один gomobile-AAR из sing-box + olcRTC + Xray + AmneziaWG + VK-TURN
olcrtc/           olcRTC — транспорт-маскировка под видеозвонок   (сторонний, вендорено)
sing-box/         sing-box / libbox                                (вендорено)
awgproxy/         обёртка AmneziaWG → локальный SOCKS5             (Go-модуль)
free-turn-proxy/  VK-TURN — туннель через звонки VK                (Go-модуль)
amneziawg-go/     реализация AmneziaWG                             (вендорено)
```

---

## 🗺️ Планы

- [x] Релиз на Android
- [x] Движки AmneziaWG и VK-TURN
- [x] Профили маршрутизации (Happ-совместимые)
- [ ] 🪟 Сборка под **Windows** — *скоро*
- [ ] 🐧 Сборка под **Linux** — *скоро*

> Общий движок уже работает на JVM (`desktopApp`), так что десктоп — следующий на очереди.

---

## 🤝 Участие

PR и issue приветствуются. Перед началом загляни в:
- **[CONTRIBUTING.md](CONTRIBUTING.md)** — как собрать, оформить и прислать изменения
- **[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)** — правила общения
- **[SECURITY.md](SECURITY.md)** — как сообщить об уязвимости

---

## 🙏 Благодарности

На плечах гигантов:
[Xray-core](https://github.com/XTLS/Xray-core) ·
[sing-box](https://github.com/SagerNet/sing-box) ·
[olcRTC](https://github.com/openlibrecommunity/olcrtc) ·
[AmneziaWG](https://github.com/amnezia-vpn/amneziawg-go).

## 📄 Лицензия

[MIT](LICENSE) на приложение. Вендоренные компоненты сохраняют свои лицензии
(`sing-box/LICENSE`, `olcrtc/LICENSE`, `amneziawg-go/LICENSE`).

<div align="center">
<br>

<img src="docs/no-rkn.jpg" alt="Нет цензуре" width="150">

<br><br>

> *«Нация, которая боится позволить своему народу судить о правде и лжи на открытом рынке, — это нация, которая боится своего народа.»*
>
> — **Джон Ф. Кеннеди**

<br>

<sub>Для свободного интернета⭐</sub>

</div>
