# Архитектура и структура приложения YPtun (iOS, Android, Desktop)

> **Назначение документа:** Полная техническая карта и архитектурное описание репозитория YPtun. Используется как долговременная база знаний при компактизации контекста LLM, онбординге и проведении аудитов.

---

## 1. Обзор проекта и стек технологий

**YPtun** — кроссплатформенный клиент VPN и обхода цензуры нового поколения, поддерживающий как стандартные современные прокси-протоколы (VLESS, VMess, Trojan, Shadowsocks, Hysteria2), так и специализированные стелс-протоколы (AmneziaWG, olcRTC/WebRTC, VK-TURN freeturn/qWDTT/CSQTT, MasterDNS, OpenFlux, snolc).

### Технологический стек:
- **UI & Shared Logic**: Kotlin Multiplatform (KMP 2.3.x) + Jetpack / Compose Multiplatform (Material 3).
- **iOS Platform Shell**: Swift 5.10 + NetworkExtension framework (`NEPacketTunnelProvider`, `NEPacketTunnelNetworkSettings`), ActivityKit (Live Activities & Dynamic Island), WidgetKit.
- **VPN Packet Capture & Bridging**: `hev-socks5-tunnel` (легковесный высокопроизводительный C-туннель tun2socks, обернутый в `Tun2SocksKit`).
- **Core Backends (Go & Cgo)**:
  - `cores/coreapi`: Единый gomobile-фасад, собираемый в `coresIos.xcframework` для iOS и `libyptuncore` для Android/Desktop.
  - `sing-box` (v1.14.x) — кастомизированный форк.
  - `xray-core` (v26.9.x) — кастомизированный форк с поддержкой xhttp/splithttp.
  - `amneziawg-go` / `awgproxy` — Userspace-реализация AmneziaWG (Jc, Jmin, Jmax, S1, S2, H1-H4, I1-I5).
  - `free-turn-proxy` (freeturn) & `wdtt` (qWDTT) — VK-TURN UDP/TCP ретрансляторы с маскировкой под медиапотоки звонков.
  - `masterdns` — DNS-туннель (encapsulation inside DNS queries/responses).
  - `openflux` — туннелирование через collaborative cloud documents (Yandex Docs, Mail.ru, cups.online, Telemost, MAX).
  - `olcrtc` — WebRTC p2p/SFU стелс-туннель.
- **Rust Core**: `snolc` — криптографический туннель (Noise NK over TCP).
- **Сборка и CI/CD**:
  - Gradle (KMP, Android, Desktop).
  - XcodeGen (`project.yml` генерирует `.xcodeproj` на лету).
  - GitHub Actions (`ios.yml`, `android.yml`, `desktop.yml`) с автоматической подписью Apple TestFlight, Fastlane и SignPath.

---

## 2. Карта директорий репозитория

```
olcvpn-client/
├── .github/
│   └── workflows/
│       ├── ios.yml               # CI/CD сборки, подписи и деплоя в Apple TestFlight
│       ├── android.yml           # Сборка и релиз Android APK / AAB
│       └── desktop.yml           # Сборка дистрибутивов Windows / Linux / macOS
├── amneziawg-go/                 # Ядро Amnezia WireGuard (Go)
├── appstore_info/                # Материалы для App Store Review (Privacy, Support, описание)
├── awgproxy/                     # Userspace SOCKS5-прокси над AmneziaWG
├── bond-server/                  # Серверная часть multi-room bonding (olcRTC)
├── cores/                        # Единый Go-модуль сборщика всех ядер
│   ├── cmd/                      # Точки входа cli утилит
│   ├── coreapi/                  # Экспортируемый API для gomobile (coresIos.xcframework)
│   └── xraybridge/               # Патчи и интеграция Xray
├── free-turn-proxy/              # VK-TURN freeturn клиент и прокси
├── masterdns/                    # Клиент DNS-туннеля
├── olcrtc/                       # WebRTC транспорт olcRTC
├── openflux/                     # Клиент туннелирования OpenFlux (WebSockets / Cloud Docs)
├── sing-box/                     # Форк sing-box с мобильными оптимизациями
├── snolc/                        # Rust туннель Noise NK
├── wdtt/                         # Клиент qWDTT (VK-TURN с raw/wg режимами)
├── xray-core/                    # Форк Xray-core
├── YPtun/                        # Основной KMP проект
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   ├── gradle.properties
│   ├── androidApp/               # Специфичный для Android код и манифесты
│   ├── desktopApp/               # Десктопное приложение (Compose for Desktop)
│   ├── iosApp/                   # iOS проект и нативные расширения
│   │   ├── project.yml           # XcodeGen спецификация (таргеты: iosApp, PacketTunnel, YPtunWidget)
│   │   ├── iosApp/               # Главный апп-таргет: App Delegate, LiveActivityManager, SwifUI обертка
│   │   ├── PacketTunnel/         # NetworkExtension PacketTunnelProvider.swift + entitlements
│   │   ├── YPtunWidget/          # Control Center виджеты, Live Activities (Dynamic Island)
│   │   ├── AppShared/            # Общие Swift структуры (VpnActivityAttributes, Shortcuts)
│   │   └── Shared/               # SwiftCoreBridge
│   └── sharedUI/                 # Общий KMP код (UI, репозитории, IPC, сетевые клиенты)
│       └── src/
│           ├── commonMain/       # Общий Compose UI, модели данных, парсеры ссылок
│           ├── iosMain/          # iOS-специфичная логика: IosVpnManager, IosTunnelSession, IPC
│           ├── androidMain/      # Android VpnService, автозапуск, тайлы
│           └── nonAndroidMain/   # Общая логика для iOS + Desktop
└── APP_STRUCTURE.md              # Данный архитектурный документ
```

---

## 3. Архитектура iOS NetworkExtension и сетевой стек

```
+-----------------------------------------------------------------------------------+
| iOS Main App (UI Process: org.yptun.app)                                          |
|                                                                                   |
|  [Compose Multiplatform UI] -> [LocationViewModel] -> [LocationsRepository]     |
|              |                                                                    |
|              v                                                                    |
|      [IosVpnManager]                                                              |
|              |                                                                    |
|              +---> 1. Пишет tunnel_request.json в App Group container             |
|              +---> 2. NETunnelProviderManager.startVPNTunnel()                    |
|              +---> 3. NETunnelProviderSession.sendProviderMessage("reload")       |
+-----------------------------------------------------------------------------------+
                                       |
                   App Group Shared Storage & IPC
                   (group.org.yptun.app / yptun/)
                                       |
+-----------------------------------------------------------------------------------+
| iOS Packet Tunnel Extension Process (org.yptun.app.PacketTunnel)                  |
|                                                                                   |
|  [PacketTunnelProvider.swift]                                                     |
|         |                                                                         |
|         v                                                                         |
|  [IosTunnelSession.kt] (KMP Native)                                               |
|         |                                                                         |
|         +---> Читает tunnel_request.json и traffic_settings.json                  |
|         +---> Инициализирует и запускает IosEngineController                      |
|         |        |                                                                |
|         |        v                                                                |
|         |     [coresIos.xcframework (Go Runtime)]                                 |
|         |        |-> sing-box / Xray / awgproxy / freeturn / wdtt / masterdns     |
|         |        +-> Слушает локальный SOCKS5 на 127.0.0.1:10808                  |
|         |                                                                         |
|         +---> Настраивает NEPacketTunnelNetworkSettings (IPv4, IPv6, DoH/DoT)     |
|         +---> Генерирует hev_config.yml                                           |
|         v                                                                         |
|  [hev-socks5-tunnel (Tun2SocksKit / C)]                                           |
|         |                                                                         |
|  utun (Системный TUN) <====> hev-socks5-tunnel <====> 127.0.0.1:10808 (Go Cores)  |
+-----------------------------------------------------------------------------------+
```

### Режимы подключения:
1. **TUN Mode (По умолчанию)**:
   - Создается системный интерфейс `utun` с адресом `198.51.100.1/24` (TEST-NET-2, вне FakeDNS и локальных подсетей).
   - Весь трафик устройства направляется в `utun`.
   - `hev-socks5-tunnel` перехватывает IP-пакеты и транслирует их в TCP/UDP соединения к локальному SOCKS5-порту `127.0.0.1:10808`.
   - DNS транслируется через встроенный `mapdns` пул `100.64.0.0/10` либо нативный iOS `NEDNSOverHTTPSSettings`/`NEDNSOverTLSSettings`.
2. **Proxy Mode**:
   - `hev-socks5-tunnel` не запускается.
   - `NEPacketTunnelNetworkSettings` конфигурирует системный HTTP/HTTPS прокси `NEProxySettings` на `127.0.0.1:httpProxyPort`.
   - Трафик приложений, уважающих системный прокси (Safari, системные сервисы), идет через ядро; остальной трафик идет мимо.

---

## 4. Межпроцессное взаимодействие (IPC) и общее хранилище

Так как главное приложение и Network Extension исполняются в изолированных процессах iOS с отдельными песочницами, обмен данными осуществляется через:
1. **App Group Shared Container**: `group.org.yptun.app` (директория `yptun/`).
   - `tunnel_request.json`: сериализованный `IosTunnelRequest` (конфиг активной локации, deviceId). Записывается атомарно перед стартом или перезагрузкой.
   - `traffic_settings.json`: MTU, Remote DNS, IPv6 drop, режим соединения.
   - `routing_settings.json`: настройки обхода LAN, списки маршрутизации.
   - `tunnel.log`: циклический буфер логов (до 512 КБ), ротируемый автоматически.
   - `tunnel_error.txt`: текст последней ошибки подключения для отображения пользователю.
   - `tunnel_sessions.txt`: монотонный счетчик сессий для отслеживания рестартов и Jetsam OOM.
2. **App Provider Messages (`NETunnelProviderSession.sendProviderMessage`)**:
   - `"reload"`: быстрый бесшовный хот-своп ядра/сервера без переподключения сетевого интерфейса `utun`.
   - `"status"`: запрос статуса капчи VK (если ядро ожидает ввода капчи).
   - `"wdtt-captcha:<token>"`: передача решенного токена капчи в ядро.
3. **Darwin / NSNotificationCenter**:
   - `"org.yptun.notify.post"`: публикация локальных пуш-уведомлений (истечение срока подписки, анонсы панелей).

---

## 5. Модель данных и матрица протоколов

### Основные сущности:
- `LocationConfig` — полное описание сервера/подключения.
- `LocationEntry` — персистентная запись в локальном хранилище `LocationBundleV4` (хранит `storageId`, метаданные подписки, пин, теги).
- `ProxyProfile` — параметры прокси-аутбаунда (VLESS, VMess, Trojan, Shadowsocks, Hysteria2, WireGuard, AmneziaWG).
- `VkTurnConfig` — параметры VK-TURN (freeturn:// ссылка, vkLink звонка, qWDTT/CSQTT опции).
- `OpenFluxConfig` — параметры туннеля через облачные документы (Mail.ru, cups.online, Yandex, Telemost, MAX, Avito) с поддержкой мультитранспортных сессий `OpenFluxCarrier`.
- `AppBehaviorSettings` — глобальные флаги поведения (таймауты пинга 2-15с, кратность 1x/2x/3x, лимиты бесплатных серверов 500..unlimited, отображение описаний и счетчиков живых серверов).

### Поддерживаемые ядра на iOS:
| Протокол / Технология | Ядро | Реализация на iOS |
|---|---|---|
| VLESS / VMess / Trojan | sing-box / Xray | `coresIos.xcframework` (In-process Go) |
| AmneziaWG (Awg3) | awgproxy (amneziawg-go) | `coresIos.xcframework` (SOCKS5 loopback) |
| VK-TURN (freeturn) | free-turn-proxy | `coresIos.xcframework` (In-process Go) |
| VK-TURN (qWDTT) | wdtt (Go/Cgo) | `coresIos.xcframework` (In-process Go, clamp workers to 9) |
| VK-TURN (CSQTT) | csqtt (Rust) | Не поддерживается на iOS (ограничения sandbox на fork/exec) |
| MasterDNS | masterdns | `coresIos.xcframework` (In-process Go) |
| OpenFlux | openflux | `coresIos.xcframework` (In-process Go) |
| snolc | snolc (Rust) | В разработке / экспериментально |

---

## 6. Управление памятью и лимиты iOS (Jetsam)

- **Лимит памяти расширения**:
  - На старых устройствах (iPhone 8, SE 2020) лимит Jetsam для `PacketTunnel.appex` составляет **15–20 МБ**.
  - На современных устройствах — **30–50 МБ**.
- **Оптимизации в коде**:
  - `coreapi.go`: выставлены `debug.SetGCPercent(50)` и soft limit `debug.SetMemoryLimit(32 << 20)`.
  - qWDTT: число параллельных воркеров принудительно зажато до 9 на iOS во избежание всплесков аллокаций.
  - Логи: tail логов ограничен 512 КБ с циклической ротацией.
  - GeoIP / GeoSite: используются легковесные правила без загрузки полных многомегабайтных `.dat` баз в память расширения.

---

## 7. CI/CD пайплайн сборки iOS (GitHub Actions)

Workflow-файл: `.github/workflows/ios.yml`
- **Триггер**: строго ручной через `workflow_dispatch` (с параметрами `version_code`, `comment`, `testflight_upload`).
- **Среда**: `macos-14` / `macos-15` (Apple Silicon M1/M2/M3).
- **Шаги сборки**:
  1. Установка Go 1.23, gomobile.
  2. Сборка `coresIos.xcframework` из `cores/coreapi`.
  3. Сборка Kotlin/Native `SharedUI.framework` (`:sharedUI:linkReleaseFrameworkIosArm64`).
  4. Генерация Xcode проекта утилитой `xcodegen` из `project.yml`.
  5. Декодирование сертификатов Apple Distribution и Provisioning Profiles из GitHub Secrets.
  6. Санитизация `entitlements` (устранение конфликтов APS-environment и App Groups).
  7. `xcodebuild archive` и `xcodebuild -exportArchive`.
  8. Валидация и загрузка `.ipa` в TestFlight через `altool` / App Store Connect API Key (`APP_STORE_CONNECT_KEY_ID`, `APP_STORE_CONNECT_ISSUER_ID`).
