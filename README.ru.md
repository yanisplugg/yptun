<div align="center">

# 🛡️ YPtun

### Быстрый VPN для обхода блокировок · Android

*VLESS · Reality · XHTTP поверх **Xray** и **sing-box** — плюс WebRTC-стелс-транспорт, который маскирует трафик под видеозвонок.*

<br>

[![Последний релиз](https://img.shields.io/github/v/release/yanisplugg/olcvpn-client?style=for-the-badge&color=4c8eff&label=%D1%81%D0%BA%D0%B0%D1%87%D0%B0%D1%82%D1%8C)](https://github.com/yanisplugg/olcvpn-client/releases/latest)
[![Загрузки](https://img.shields.io/github/downloads/yanisplugg/olcvpn-client/total?style=for-the-badge&color=2ea043&label=%D0%B7%D0%B0%D0%B3%D1%80%D1%83%D0%B7%D0%BA%D0%B8)](https://github.com/yanisplugg/olcvpn-client/releases)
[![Звёзды](https://img.shields.io/github/stars/yanisplugg/olcvpn-client?style=for-the-badge&color=f0b429)](https://github.com/yanisplugg/olcvpn-client/stargazers)

![Платформа](https://img.shields.io/badge/%D0%BF%D0%BB%D0%B0%D1%82%D1%84%D0%BE%D1%80%D0%BC%D0%B0-Android%206.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white)
![Ядра](https://img.shields.io/badge/%D1%8F%D0%B4%D1%80%D0%B0-Xray%20%2B%20sing--box-blueviolet?style=flat-square)
![Лицензия](https://img.shields.io/badge/%D0%BB%D0%B8%D1%86%D0%B5%D0%BD%D0%B7%D0%B8%D1%8F-MIT-lightgrey?style=flat-square)

<br>

[**English**](README.md) · **🌍 Русский**

</div>

---

## ✨ Зачем YPtun?

Большинство VPN-клиентов дают одно ядро и один способ подключения. **YPtun даёт набор инструментов.**
В одном приложении — **два прокси-ядра** и **стелс-транспорт**: заблокировали один способ — переключился и работаешь дальше.

> Сделано для мест, где интернет сопротивляется. 🌐

> 🖥️ **Скоро на десктопе** — готовятся нативные сборки под **Windows** и **Linux**.

---

## 🚀 Возможности

| | |
|---|---|
| 🔀 **Два ядра** | Работает на **Xray** *или* **sing-box** — выбирается автоматически под протокол, либо вручную. |
| 🧬 **Протоколы** | VLESS · VMess · Trojan · Shadowsocks |
| 🚇 **Транспорты** | TCP · WS · gRPC · HTTPUpgrade · **XHTTP** · TLS · **Reality** · отпечатки uTLS |
| 🎭 **Стелс-режим** | Туннель внутри **WebRTC**-канала ([olcRTC](https://github.com/openlibrecommunity/olcrtc)) — для DPI выглядит как видеозвонок. |
| 📥 **Умный импорт** | ссылки vless/vmess/trojan/ss, base64, JSON-панели, **полные сырые конфиги Xray / sing-box**, olcRTC-URI. |
| 🧭 **DNS и маршруты** | Импорт полного Xray-конфига (применяется *как есть*) или встроенный тумблер **«Блокировать РФ-домены»**. |
| 🧱 **Обход DPI** | Фрагментация TLS + мультиплексирование соединений. |
| 🔒 **Без утечек** | Перехватывает **и IPv4, и IPv6** — мимо туннеля ничего не уходит. |
| 📱 **Раздельный туннель** | Выбираешь, какие приложения идут через VPN. |
| 🗂️ **Подписки** | Автообновление, показ трафика/остатка, списки локаций. |

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

---

## 🧠 Как это работает

```
┌──────────────┐   пакеты   ┌───────────────┐   SOCKS5   ┌────────────────────────┐
│  Приложения  │ ─────────▶ │  Android TUN  │ ─────────▶ │   Движок (1 процесс)   │
└──────────────┘            │  (IPv4+IPv6)  │            │  ┌──────────────────┐  │
                            └───────────────┘            │  │ Xray / sing-box  │  │
                                                         │  │  + стелс olcRTC  │  │
                                                         │  └──────────────────┘  │
                                                         └───────────┬────────────┘
                                                                     ▼
                                                              🌍 открытый интернет
```

Все нативные ядра собраны в **одну** `gomobile`-библиотеку (единый Go-рантайм), поэтому Xray, sing-box и olcRTC уживаются без конфликтов.

---

## 🛠️ Сборка из исходников

Всё необходимое уже в репозитории (`cores`, `olcrtc`, `sing-box`). Понадобится:

- **JDK 17** (подойдёт встроенный в Android Studio)
- **Android SDK** (укажи `sdk.dir` в `YPtun/local.properties`) + **NDK `28.2.13676358`**
- **Go** + [`gomobile`](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile) в `PATH`

> ⚠️ `gomobile` вызывает `javac`, поэтому добавь `bin/` из JDK в `PATH` — не только `JAVA_HOME`.

```bash
cd YPtun
./gradlew :androidApp:assembleRelease \
  -Polcbox.version=1.0.0 -Polcbox.versionCode=1
```

APK появятся в `YPtun/androidApp/build/outputs/apk/release/`.

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

## 🗂️ Структура проекта

```
YPtun/      Kotlin Multiplatform приложение — Compose UI, Android VpnService, движки
cores/      Go-связка: один gomobile-AAR из sing-box (libbox) + olcRTC + Xray-мост
olcrtc/     WebRTC стелс-транспорт            (вендорено)
sing-box/   sing-box / libbox, пин v1.12.25   (вендорено)
```

---

## 🗺️ Планы

- [x] Релиз на Android
- [ ] 🪟 Сборка под **Windows** — *скоро*
- [ ] 🐧 Сборка под **Linux** — *скоро*

> Общий движок уже работает на JVM (`desktopApp`), так что десктоп — следующий на очереди.

---

## 🙏 Благодарности

На плечах гигантов:
[Xray-core](https://github.com/XTLS/Xray-core) ·
[sing-box](https://github.com/SagerNet/sing-box) ·
[olcRTC](https://github.com/openlibrecommunity/olcrtc).

## 📄 Лицензия

[MIT](LICENSE) на приложение. Вендоренные компоненты сохраняют свои лицензии
(`sing-box/LICENSE`, `olcrtc/LICENSE`).

<div align="center">
<br>

<img src="docs/no-rkn.svg" alt="Нет РКН" width="140">

<br><br>

> *«Нация, которая боится позволить своему народу судить о правде и лжи на открытом рынке, — это нация, которая боится своего народа.»*
>
> — **Джон Ф. Кеннеди**

<br>

<sub>Для свободного интернета. Поставь ⭐, если пригодилось.</sub>

</div>
