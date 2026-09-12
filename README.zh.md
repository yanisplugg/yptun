<div align="center">

# YPtun

### 快速抗审查 VPN · Android、Windows 与 Linux · iOS 测试中

*基于 **Xray** 与 **sing-box** 的 VLESS · Reality · XHTTP，**Hysteria2**（QUIC），混淆的 **AmneziaWG**，通过 **VK-TURN** 通话的隧道，**MasterDNS** DNS 隧道，基于 **WARP** 的独立 Telegram 代理 —— 以及把流量伪装成视频通话的 **olcRTC**。*

<br>

[![最新版本](https://img.shields.io/github/v/release/yanisplugg/olcvpn-client?style=for-the-badge&color=4c8eff&label=%E4%B8%8B%E8%BD%BD)](https://github.com/yanisplugg/olcvpn-client/releases/latest)
[![下载量](https://img.shields.io/github/downloads/yanisplugg/olcvpn-client/total?style=for-the-badge&color=2ea043&label=%E4%B8%8B%E8%BD%BD%E9%87%8F)](https://github.com/yanisplugg/olcvpn-client/releases)
[![星标](https://img.shields.io/github/stars/yanisplugg/olcvpn-client?style=for-the-badge&color=f0b429)](https://github.com/yanisplugg/olcvpn-client/stargazers)

![平台](https://img.shields.io/badge/platform-Android%206.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white)
![平台](https://img.shields.io/badge/platform-Windows%2010%2B-0078d4?style=flat-square&logo=windows&logoColor=white)
![平台](https://img.shields.io/badge/platform-Linux%20.deb-fcc624?style=flat-square&logo=linux&logoColor=black)
![平台](https://img.shields.io/badge/platform-iOS%20%E6%B5%8B%E8%AF%95%E7%89%88-8e8e93?style=flat-square&logo=apple&logoColor=white)
![内核](https://img.shields.io/badge/cores-Xray%20%2B%20sing--box-blueviolet?style=flat-square)
![许可证](https://img.shields.io/badge/license-GPL--3.0-blue?style=flat-square)

<br>

[Русский](README.md) · [English](README.en.md) · [فارسی](README.fa.md) · **简体中文**

</div>

---

## 为什么选 YPtun？

大多数 VPN 客户端只给你一个内核、一种连接方式。**YPtun 给你一整套工具箱。** 一个应用里集成了多种翻墙引擎：一种方式被封，就切换到另一种继续用。

> **核心在于多样性。** Xray 与 sing-box 支持所有常见协议与传输，通过 AmneziaWG 实现混淆的 WireGuard，通过真实通话隧道（VK-TURN 与 olcRTC），MasterDNS 的 DNS 隧道，几乎可导入任何东西，以及兼容 Happ 的分流配置。封掉一条路，旁边还有好几条。

> 为互联网受阻的地方而生 —— 俄罗斯、伊朗，以及任何网站会无预警消失的国家。

> **Windows 版已发布** —— 安装程序与单文件 `.exe` 便携版，支持 x64 与原生 ARM64。与手机端完全相同的应用和内核：订阅、路由配置、链式代理、VK-TURN、olcRTC、MasterDNS、Trust Tunnel。

---

## 3.5.0 新功能

| | |
|---|---|
| **WDTT Plus 取代 WDTT** | Android 与桌面端的客户端和服务端：「RT 网络」模式（TURN/TLS 与 TCP，UDP 作为备用）、Cloudflare WARP 备用、备用 VK 哈希、自定义 VK ID 与密钥、手动 TURN 地址。⚠️ 旧版 WDTT 服务端与新客户端不兼容 —— 请在节点设置中点「自动安装」重新安装服务端。freeturn 节点不受影响。 |
| **新引擎 OpenFlux** | 通过 Yandex 文档或 MAX 通话连到你自己的出口节点的 TCP 隧道 —— 用于其他方式全被封锁的情况。节点可一键装到 VPS，DNS 走隧道本身，「OpenFlux 上的代理」可再加一层端到端加密。实验性功能，速度不快。 |
| **二维码扫描器重写** | 采用 zxing-cpp，可识别模糊、倾斜、密集和反色的二维码；点按对焦、双指缩放、手电筒，大底旗舰机会自动放大。也能识别应用自己生成的二维码（`yptun://`、`hysteria2://`、`naive+https://`、`tt://`、`happ://`）。 |
| **VK-TURN** | AmneziaWG 之上的第二代理可经由 Xray（xhttp 与原始配置），出口 MTU 限制为 1200，freeturn 连接更快。不带第二代理的 AmneziaWG 出口不再丢失 DNS。 |
| **JSON 订阅中的分流** | 订阅中的每个服务器现在都会拿到带规则的完整配置，而不只是 xhttp 服务器。订阅通过 `dns.hosts` 指定的俄罗斯网站在「仅 IPv4」模式下重新可以直连。 |
| **订阅不再丢服务器** | 订阅中服务器变多时，最后一个不再消失，已选服务器也不再跳到相邻的那个。感谢 @Zamotashka（#41）。 |
| **小修复** | 在节点编辑器中粘贴后半个屏幕变黑（#40）；Windows 上 VK 验证码改在浏览器中打开，而不是资源管理器窗口。 |

---

## 功能

| | |
|---|---|
| **多引擎** | Xray、sing-box、AmneziaWG、VK-TURN、MasterDNS —— 内核按协议自动或手动选择。 |
| **协议** | VLESS · VMess · Trojan · Shadowsocks · Hysteria2 · WireGuard / AmneziaWG |
| **传输** | TCP · WS · gRPC · HTTPUpgrade · XHTTP · TLS · Reality · uTLS 指纹 |
| **MasterDNS（DNS 隧道）** | 基于 DNS 查询的隧道（MasterDnsVPN：自研 ARQ 传输、同时使用多个解析器、数据包冗余）—— 在其他流量都被封、仅 DNS 可用时仍能工作。可通过 SSH 一键在 VPS 上安装服务端。 |
| **基于 WARP 的 Telegram 代理** | 轻量后台服务：WARP 隧道 + 供 Telegram 使用的本地 SOCKS5，独立于主连接。 |
| **olcRTC** | [olcRTC](https://github.com/openlibrecommunity/olcrtc) 传输 —— 流量经过真实视频通话服务（Jazz、Telemost、WB Stream、Jitsi）；对 DPI 而言像一次真实通话，而非代理。 |
| **智能导入** | vless/vmess/trojan/ss 链接、base64、JSON 面板、**完整的原始 Xray / sing-box 配置**（原样应用）、AmneziaWG `.conf`/二维码、olcRTC URI、Happ 配置、批量链接导入。 |
| **DNS 与分流** | 兼容 Happ 的分流配置（按 `geoip:`/`geosite:`/`asn:`/域名/CIDR 进行 拦截/直连/代理）、v2rayNG 风格的逐条规则、「拦截俄罗斯域名」开关、自定义 DNS 与 fakedns。 |
| **自动选服** | 一键连接到最快的可用节点，失败自动切换。 |
| **HTTP 代理** | 在活动引擎之上提供兼容 Happ 的本地 HTTP 代理。 |
| **抗 DPI** | TLS 分片、多路复用、AmneziaWG 混淆，并在会泄漏处拦截 QUIC。 |
| **无泄漏** | 同时接管 IPv4 与 IPv6 —— 不让任何流量绕过隧道。 |
| **分应用代理** | 自行选择哪些应用走 VPN。 |
| **订阅** | 自动更新（可逐个订阅关闭）、可用服务器计数、服务器描述、流量/余量、可折叠/置顶/按延迟排序的分组、文件夹。 |

---

## 下载

从 **[发布页](https://github.com/yanisplugg/olcvpn-client/releases/latest)** 获取最新的已签名 APK。

| 版本 | 适用 |
|------|------|
| **`arm64-v8a`** | 现代手机 —— 拿不准就选它 |
| `armeabi-v7a` | 较旧的 32 位设备 |
| `x86_64` | 模拟器 / x86 平板 |
| `universal` | 一个文件通吃（体积最大） |

最低 **Android 6.0**（API 23）。

---

## 权限及其用途

YPtun 只申请某项功能缺之不可的权限。相机、通知、不受电池限制和安装更新都在你使用对应功能时才会申请，而不是在安装时。

### Android

| 权限 | 用途 |
|---|---|
| **VPN**（`BIND_VPN_SERVICE`） | 首次连接时系统弹出的「允许 VPN 连接」。没有它，应用无法建立隧道并让流量经过隧道。 |
| **网络与网络状态**（`INTERNET`、`ACCESS_NETWORK_STATE`） | 连接服务器、测延迟、拉取订阅；在 Wi-Fi 与移动数据之间切换时重新连接。 |
| **后台运行**（`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、`WAKE_LOCK`） | VPN 和 Telegram 代理以带通知的服务形式运行，避免息屏时被系统停止。 |
| **通知**（`POST_NOTIFICATIONS`，Android 13+） | 显示网速和断开按钮的连接通知 —— Android 要求后台服务必须有它。 |
| **不受电池限制**（`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`） | 仅在设置中点按钮时申请：让系统的 Doze 省电模式不影响隧道，避免夜间断线。不点就不会申请。 |
| **相机**（`CAMERA`） | 用于导入服务器的二维码扫描器。首次打开扫描器时申请；画面只在手机上处理，不会发送到任何地方。没有相机也可以从图片文件导入二维码。 |
| **应用列表**（`QUERY_ALL_PACKAGES`） | 分应用代理：列出所有已安装应用（包括没有图标的系统应用），以便选择哪些走 VPN。列表不会离开手机。 |
| **安装应用**（`REQUEST_INSTALL_PACKAGES`） | 应用内更新：下载的 APK 交给系统安装器，安装器仍会请你确认。 |
| **重启后的任务**（`RECEIVE_BOOT_COMPLETED`、`FOREGROUND_SERVICE_DATA_SYNC`） | 由 WorkManager 库添加，使定期任务在重启后继续。这样的任务只有一个 —— 为带有 Happ `providerid` 的订阅做每日签到（见下文）。YPtun 自身没有开机自启。 |
| **数据迁移**（`org.olcbox.app.permission.MIGRATION`） | 应用自己的 signature 级权限：把设置从旧安装 `org.olcbox.app` 迁到新安装 `org.yptun.app`。只有签名相同的 APK 才能读取这些数据。 |

### Windows

| 权限 | 用途 |
|---|---|
| **管理员**（UAC） | 仅「隧道」模式需要：创建网络适配器（wintun）并添加路由。选中该模式时，启动时询问一次。「代理」模式无需管理员权限。 |
| **系统代理** | 「代理」模式下应用为当前用户设置 Windows 代理，断开时恢复原先的设置。若应用崩溃，残留的代理会在下次启动时清除。 |
| **进程列表** | 按进程分流 —— 以及检测正在运行、妨碍连接的其他 VPN 客户端。应用会提议关闭它，但未经你同意不会结束任何进程。 |

### 除你的服务器外，应用还会访问

- **GitHub** —— 检查更新，以及（默认情况下）分流用的列表（geoip/geosite、ASN）。
- **`check.happ-proxy.com`** —— 仅当订阅带有 Happ `providerid` 时：应用会像 Happ 一样每天发送一次签到，附带的设备信息与订阅本身收到的相同（HWID、系统、型号、应用版本）。没有 `providerid` 就不会有此请求。
- **你所选连接方式用到的服务** —— VK（VK-TURN）、Cloudflare（经 WARP 的 Telegram）、通话服务（olcRTC）、Yandex 文档或 MAX（OpenFlux）。

---

## 工作原理

```
┌──────────────┐  packets   ┌───────────────┐   SOCKS5   ┌────────────────────────────┐
│     Apps     │ ─────────▶ │  Android TUN  │ ─────────▶ │      Engine (1 process)    │
└──────────────┘            │  (IPv4+IPv6)  │            │  ┌──────────────────────┐  │
                            └───────────────┘            │  │  Xray / sing-box     │  │
                                                         │  │  AmneziaWG / VK-TURN │  │
                                                         │  │  MasterDNS / olcRTC      │  │
                                                         │  └──────────────────────┘  │
                                                         └─────────────┬──────────────┘
                                                                       ▼
                                                                 open internet
```

所有原生内核都被构建进**同一个** `gomobile` 库（单一 Go 运行时），因此 Xray、sing-box、AmneziaWG、VK-TURN、MasterDNS 与 olcRTC 在同一进程中互不冲突。应用启动 `VpnService`，把数据包送入 TUN，再通过本地 SOCKS5 包进所选引擎。

---

## 引擎简述

- **Xray / sing-box** —— 经典代理内核：VLESS+Reality、XHTTP、WS+TLS 等。内核按传输自动选择。
- **AmneziaWG** —— 带混淆的 WireGuard：握手与数据包不像「普通」WireGuard（后者常按特征被切断）。
- **Hysteria2** —— 基于 QUIC 的高速协议，带 Salamander 混淆与端口跳跃；在不稳定线路上速度保持好。
- **VK-TURN** —— 启动本地 WireGuard 并经 VK 通话的 TURN 服务器转发；多路「通话」绑定以提升带宽。
- **MasterDNS** —— 基于 DNS 查询的隧道；在只有 DNS 可用时仍可工作。
- **olcRTC** —— 视频通话伪装：流量经过真实会议服务，对 DPI 而言像一次真实通话。
- **基于 WARP 的 Telegram 代理** —— 在 Cloudflare WARP 之上为 Telegram 提供的独立后台代理。

---

## 从源码构建

所需的一切都已随仓库提供（`cores`、`olcrtc`、`sing-box`、`awgproxy`、`hysteria2proxy`、`free-turn-proxy`、`masterdns`、`wdtt`、`amneziawg-go`）。需要：

- **JDK 17**（Android Studio 自带的即可）
- **Android SDK**（在 `YPtun/local.properties` 中设置 `sdk.dir`）+ **NDK `28.2.13676358`**
- **Go** + `PATH` 中的 [`gomobile`](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile)

> `gomobile` 会调用 `javac`，所以请把 JDK 的 `bin/` 放进 `PATH` —— 不只是 `JAVA_HOME`。

```bash
cd YPtun
./gradlew :androidApp:assembleRelease \
  -Polcbox.version=3.5.0 -Polcbox.versionCode=352
```

APK 会生成在 `YPtun/androidApp/build/outputs/apk/release/`。
只想为自己的手机更快构建？加上 `-Polcbox.android.abiFilters=arm64-v8a`。

<details>
<summary>为自己的发布版本签名（可选，面向维护者）</summary>

<br>

默认情况下 Gradle 产出 debug 签名的 APK。若要发布你自己的已签名版本，创建一个 keystore 并在 `YPtun/keystore.properties` 中指向它：

```properties
storeFile=release.keystore
storePassword=你的密码
keyAlias=你的别名
keyPassword=你的密码
```

该文件（以及 `.keystore`）已被 git 忽略、绝不提交，只存在于你的机器上。请妥善保管 keystore：更新用同一密钥签名，才能覆盖安装到旧版本之上。

</details>

---

## 开发

YPtun 采用 **Kotlin Multiplatform**：所有逻辑（导入、配置生成、引擎、UI 状态）都在 `commonMain`，平台相关部分在 `androidMain`。同一套代码也能在 JVM 桌面端运行。

- **界面** —— Jetpack Compose，各平台统一设计。
- **本地化** —— 俄语、英语、波斯语与简体中文，集中在一个字符串文件中。
- **原生内核** —— Go，由 `buildCoresAndroidAar` 任务构建成单个 gomobile AAR；内核输入被跟踪，仅在 Go 代码改动时才重建。
- **测试** —— 针对分流解析/转换的单元测试（`./gradlew :sharedUI:jvmTest`）。
- **分支** —— 稳定版在 `main`，活跃开发在 `Beta`；发布以 `vX.Y.Z` 打标签。

发现 Bug 或想要新功能？提个 issue 或 PR —— 见 **[CONTRIBUTING.md](CONTRIBUTING.md)**。

---

## 项目结构

```
YPtun/            Kotlin Multiplatform 应用 —— Compose UI、Android VpnService、引擎
cores/            Go 胶水层：由 sing-box + olcRTC + Xray + AmneziaWG + VK-TURN + MasterDNS 构成的单个 gomobile AAR
olcrtc/           olcRTC —— 视频通话伪装传输                       (第三方，已 vendored)
sing-box/         sing-box / libbox                                (已 vendored)
awgproxy/         AmneziaWG 封装 → 本地 SOCKS5                     (Go 模块)
hysteria2proxy/   Hysteria2 (apernet) 封装 → 本地 SOCKS5           (Go 模块)
free-turn-proxy/  VK-TURN —— 经 VK 通话的隧道                      (Go 模块)
masterdns/        MasterDNS —— DNS 之上的隧道                          (客户端 + 服务端)
wdtt/             WDTT —— 隧道变体                                 (客户端 + 服务端)
amneziawg-go/     AmneziaWG 实现                                   (已 vendored)
```

---

## 路线图

- [x] Android 发布
- [x] AmneziaWG、VK-TURN 与 MasterDNS 引擎
- [x] 分流配置（兼容 Happ）+ ASN
- [x] **Windows** 版本（x64 与 ARM64）
- [x] **Linux** 版本（`.deb`，x64 与 ARM64）
- [ ] **iOS** 版本 —— *测试中*

> 共享引擎已能在 JVM（`desktopApp`）上运行，所以桌面端是下一步。

---

## 参与贡献

欢迎 PR 与 issue。开始前请看：
- **[CONTRIBUTING.md](CONTRIBUTING.md)** —— 如何构建、格式化并提交改动
- **[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)** —— 社区准则
- **[SECURITY.md](SECURITY.md)** —— 如何报告安全漏洞

---

## 致谢

站在巨人的肩膀上：
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
[tun2socks](https://github.com/xjasonlyu/tun2socks)。

## 许可证

[GPL-3.0](LICENSE) —— 本应用以 GNU GPL v3.0 发布，因为它打包了 **sing-box**（同为 GPL-3.0）：copyleft 适用于整个产品。Vendored 组件保留各自的许可证（`sing-box` — GPL-3.0，Xray — MPL-2.0，`amneziawg-go` — MIT，`olcrtc` — WTFPL，OpenFlux — GPL-3.0，qWDTT（`wdtt`）— GPL-3.0，MasterDnsVPN（`masterdns`）— MIT，`free-turn-proxy` — Happy Bunny License（类 MIT），Trust Tunnel — Apache-2.0，`hev-socks5-tunnel` — MIT，`tun2socks` — MIT）。

<div align="center">
<br>

<img src="docs/no-rkn.jpg" alt="拒绝审查" width="150">

<br><br>

> *「一个害怕让人民在公开市场上判断真伪的国家，是一个害怕自己人民的国家。」*
>
> — **约翰·肯尼迪**

<br>

<sub>为了自由的互联网</sub>

</div>
