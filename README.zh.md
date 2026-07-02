<div align="center">

# YPtun

### 快速抗审查 VPN · Android

*基于 **Xray** 与 **sing-box** 的 VLESS · Reality · XHTTP，**Hysteria2**（QUIC），混淆的 **AmneziaWG**，通过 **VK-TURN** 通话的隧道，**DNSTT** DNS 隧道，基于 **WARP** 的独立 Telegram 代理 —— 以及把流量伪装成视频通话的 **olcRTC**。*

<br>

[![最新版本](https://img.shields.io/github/v/release/yanisplugg/olcvpn-client?style=for-the-badge&color=4c8eff&label=%E4%B8%8B%E8%BD%BD)](https://github.com/yanisplugg/olcvpn-client/releases/latest)
[![下载量](https://img.shields.io/github/downloads/yanisplugg/olcvpn-client/total?style=for-the-badge&color=2ea043&label=%E4%B8%8B%E8%BD%BD%E9%87%8F)](https://github.com/yanisplugg/olcvpn-client/releases)
[![星标](https://img.shields.io/github/stars/yanisplugg/olcvpn-client?style=for-the-badge&color=f0b429)](https://github.com/yanisplugg/olcvpn-client/stargazers)

![平台](https://img.shields.io/badge/platform-Android%206.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white)
![内核](https://img.shields.io/badge/cores-Xray%20%2B%20sing--box-blueviolet?style=flat-square)
![许可证](https://img.shields.io/badge/license-GPL--3.0-blue?style=flat-square)

<br>

[Русский](README.md) · [English](README.en.md) · [فارسی](README.fa.md) · **简体中文**

</div>

---

## 为什么选 YPtun？

大多数 VPN 客户端只给你一个内核、一种连接方式。**YPtun 给你一整套工具箱。** 一个应用里集成了多种翻墙引擎：一种方式被封，就切换到另一种继续用。

> **核心在于多样性。** Xray 与 sing-box 支持所有常见协议与传输，通过 AmneziaWG 实现混淆的 WireGuard，通过真实通话隧道（VK-TURN 与 olcRTC），DNSTT 的 DNS 隧道，几乎可导入任何东西，以及兼容 Happ 的分流配置。封掉一条路，旁边还有好几条。

> 为互联网受阻的地方而生 —— 俄罗斯、伊朗，以及任何网站会无预警消失的国家。

> **桌面版即将到来** —— 原生 Windows 和 Linux 版本正在开发中。

---

## 3.0.0 新功能

| | |
|---|---|
| **sing-box 内核升级到 1.13** | 带来最新修复与兼容性。最重要的是，Hysteria2 与 NaïveProxy 现在**原生**内置于 sing-box，无需单独桥接。 |
| **原生 Hysteria2** | 完全改用 sing-box 原生出站（auth、上/下行、Salamander 混淆、端口跳跃）—— 更快更稳；旧的外部模块已移除。 |
| **NaïveProxy** | 新协议：把流量伪装成普通的 Chrome HTTP/2（Chromium cronet 引擎）。适用于按特征（DPI）阻断连接的环境。 |
| **透明代理（tproxy）** | 新的连接模式：内核级拦截（TCP + UDP），无需 TUN。仅限 root（需要 `CAP_NET_ADMIN`）。 |
| **主屏小组件** | 两个与应用主题一致的小组件：一个开关，以及带 `↓/↑` 速率、`‹ ›` 服务器切换和「自动」按钮的状态小组件。直接从服务连接/切换 —— **无需打开应用**；「自动」按钮现在也在后台运行并即时响应。 |
| **构建保护 + 增量更新** | 若 APK 被他人重新签名（重打包/植入广告的构建）会给出警告。更新以约 1 MB 的补丁下载，而非完整 APK。 |
| **VK-TURN 内核更新** | freeturn 1.6.0 与 WDTT —— 刷新 VK 认证（`vkcalls` 模式）、验证码与指纹。 |

---

## 功能

| | |
|---|---|
| **多引擎** | Xray、sing-box、AmneziaWG、VK-TURN、DNSTT —— 内核按协议自动或手动选择。 |
| **协议** | VLESS · VMess · Trojan · Shadowsocks · Hysteria2 · WireGuard / AmneziaWG |
| **传输** | TCP · WS · gRPC · HTTPUpgrade · XHTTP · TLS · Reality · uTLS 指纹 |
| **DNSTT（DNS 隧道）** | 基于 DNS 查询的隧道（KCP + Noise）—— 在其他流量都被封、仅 DNS 可用时仍能工作。可通过 SSH 一键在 VPS 上安装服务端。 |
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

## 工作原理

```
┌──────────────┐  packets   ┌───────────────┐   SOCKS5   ┌────────────────────────────┐
│     Apps     │ ─────────▶ │  Android TUN  │ ─────────▶ │      Engine (1 process)    │
└──────────────┘            │  (IPv4+IPv6)  │            │  ┌──────────────────────┐  │
                            └───────────────┘            │  │  Xray / sing-box     │  │
                                                         │  │  AmneziaWG / VK-TURN │  │
                                                         │  │  DNSTT / olcRTC      │  │
                                                         │  └──────────────────────┘  │
                                                         └─────────────┬──────────────┘
                                                                       ▼
                                                                 open internet
```

所有原生内核都被构建进**同一个** `gomobile` 库（单一 Go 运行时），因此 Xray、sing-box、AmneziaWG、VK-TURN、DNSTT 与 olcRTC 在同一进程中互不冲突。应用启动 `VpnService`，把数据包送入 TUN，再通过本地 SOCKS5 包进所选引擎。

---

## 引擎简述

- **Xray / sing-box** —— 经典代理内核：VLESS+Reality、XHTTP、WS+TLS 等。内核按传输自动选择。
- **AmneziaWG** —— 带混淆的 WireGuard：握手与数据包不像「普通」WireGuard（后者常按特征被切断）。
- **Hysteria2** —— 基于 QUIC 的高速协议，带 Salamander 混淆与端口跳跃；在不稳定线路上速度保持好。
- **VK-TURN** —— 启动本地 WireGuard 并经 VK 通话的 TURN 服务器转发；多路「通话」绑定以提升带宽。
- **DNSTT** —— 基于 DNS 查询的隧道；在只有 DNS 可用时仍可工作。
- **olcRTC** —— 视频通话伪装：流量经过真实会议服务，对 DPI 而言像一次真实通话。
- **基于 WARP 的 Telegram 代理** —— 在 Cloudflare WARP 之上为 Telegram 提供的独立后台代理。

---

## 从源码构建

所需的一切都已随仓库提供（`cores`、`olcrtc`、`sing-box`、`awgproxy`、`hysteria2proxy`、`free-turn-proxy`、`dnstt`、`wdtt`、`amneziawg-go`）。需要：

- **JDK 17**（Android Studio 自带的即可）
- **Android SDK**（在 `YPtun/local.properties` 中设置 `sdk.dir`）+ **NDK `28.2.13676358`**
- **Go** + `PATH` 中的 [`gomobile`](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile)

> `gomobile` 会调用 `javac`，所以请把 JDK 的 `bin/` 放进 `PATH` —— 不只是 `JAVA_HOME`。

```bash
cd YPtun
./gradlew :androidApp:assembleRelease \
  -Polcbox.version=3.0.0 -Polcbox.versionCode=287
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
cores/            Go 胶水层：由 sing-box + olcRTC + Xray + AmneziaWG + VK-TURN + DNSTT 构成的单个 gomobile AAR
olcrtc/           olcRTC —— 视频通话伪装传输                       (第三方，已 vendored)
sing-box/         sing-box / libbox                                (已 vendored)
awgproxy/         AmneziaWG 封装 → 本地 SOCKS5                     (Go 模块)
hysteria2proxy/   Hysteria2 (apernet) 封装 → 本地 SOCKS5           (Go 模块)
free-turn-proxy/  VK-TURN —— 经 VK 通话的隧道                      (Go 模块)
dnstt/            DNSTT —— DNS 之上的隧道                          (客户端 + 服务端)
wdtt/             WDTT —— 隧道变体                                 (客户端 + 服务端)
amneziawg-go/     AmneziaWG 实现                                   (已 vendored)
```

---

## 路线图

- [x] Android 发布
- [x] AmneziaWG、VK-TURN 与 DNSTT 引擎
- [x] 分流配置（兼容 Happ）+ ASN
- [ ] **Windows** 版本 —— *即将推出*
- [ ] **Linux** 版本 —— *即将推出*

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
[AmneziaWG](https://github.com/amnezia-vpn/amneziawg-go)。

## 许可证

[GPL-3.0](LICENSE) —— 本应用以 GNU GPL v3.0 发布，因为它打包了 **sing-box**（同为 GPL-3.0）：copyleft 适用于整个产品。Vendored 组件保留各自的许可证（`sing-box` — GPL-3.0，Xray — MPL-2.0，`amneziawg-go` — MIT，`olcrtc` — WTFPL）。

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
