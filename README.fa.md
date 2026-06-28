<div align="center">

# YPtun

### وی‌پی‌ان سریع و مقاوم در برابر سانسور · اندروید

*VLESS · Reality · XHTTP روی **Xray** و **sing-box**، **Hysteria2** (QUIC)، وایرگارد مبهم‌سازی‌شده‌ی **AmneziaWG**، تونل از طریق تماس‌های **VK-TURN**، تونل DNS با **DNSTT**، پروکسی مستقل تلگرام روی **WARP** — و **olcRTC** که ترافیک را شبیه تماس تصویری می‌کند.*

<br>

[![آخرین نسخه](https://img.shields.io/github/v/release/yanisplugg/olcvpn-client?style=for-the-badge&color=4c8eff&label=download)](https://github.com/yanisplugg/olcvpn-client/releases/latest)
[![دانلودها](https://img.shields.io/github/downloads/yanisplugg/olcvpn-client/total?style=for-the-badge&color=2ea043&label=downloads)](https://github.com/yanisplugg/olcvpn-client/releases)
[![ستاره‌ها](https://img.shields.io/github/stars/yanisplugg/olcvpn-client?style=for-the-badge&color=f0b429)](https://github.com/yanisplugg/olcvpn-client/stargazers)

![پلتفرم](https://img.shields.io/badge/platform-Android%206.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white)
![هسته‌ها](https://img.shields.io/badge/cores-Xray%20%2B%20sing--box-blueviolet?style=flat-square)
![مجوز](https://img.shields.io/badge/license-GPL--3.0-blue?style=flat-square)

<br>

[Русский](README.md) · [English](README.en.md) · **فارسی** · [简体中文](README.zh.md)

</div>

---

<div dir="rtl">

## چرا YPtun؟

بیشتر کلاینت‌های وی‌پی‌ان یک هسته و یک راه اتصال به شما می‌دهند. **YPtun یک جعبه‌ابزار به شما می‌دهد.** چند موتور دور زدن سانسور در یک اپ؛ وقتی یک روش مسدود شد، روش دیگری را انتخاب می‌کنید و کارتان را ادامه می‌دهید.

> **نقطه‌ی قوت، انعطاف‌پذیری است.** Xray و sing-box با همه‌ی پروتکل‌ها و ترنسپورت‌های رایج، وایرگارد مبهم‌سازی‌شده با AmneziaWG، تونل‌زنی از طریق تماس‌های واقعی (VK-TURN و olcRTC)، تونل DNS با DNSTT، ایمپورت تقریباً هر چیزی و پروفایل‌های مسیریابی سازگار با Happ. یک مسیر را ببندند، چند مسیر دیگر کنارش هست.

> ساخته‌شده برای جاهایی که اینترنت مقاومت می‌کند — برای ایران، روسیه و هر کشوری که سایت‌ها بی‌خبر ناپدید می‌شوند.

> **به‌زودی روی دسکتاپ** — نسخه‌های بومی ویندوز و لینوکس در دست ساخت است.

---

## تازه‌ها در نسخه ۲٫۶٫۱

| | |
|---|---|
| **اتصال خودکار به سریع‌ترین** | دکمه‌ی «خودکار» کنار دکمه‌ی اتصال: همه‌ی سرورهای آماده را به‌صورت موازی با یک دست‌دادن واقعی از طریق پروکسی پینگ می‌کند (نه فقط TCP/ICMP)، به سریع‌ترین وصل می‌شود و در صورت ناموفق‌بودن به بعدی می‌رود. دکمه هنگام اتصال هم فعال است؛ یک ضربه دوباره سریع‌ترین سرور را انتخاب می‌کند. |
| **مسیریابی بر اساس ASN** | سلکتور جدید `asn:62041` (تلگرام)، `asn:13335` (کلودفلر) در پروفایل‌های مسیریابی — **تمام** شبکه‌های یک اپراتور را می‌گیرد، از جمله سرویس‌هایی روی IP خام که فهرست‌های دامنه از دست می‌دهند. در لحظه به محدوده‌های واقعی باز می‌شود و روی هر دو هسته کار می‌کند. در ویرایشگر، پیش‌تنظیم‌های یک‌ضربه‌ای. |
| **سرعت در صفحه‌ی اصلی** | خط اختیاری `↓ / ↑` زیر پیکربندی انتخاب‌شده (یک کلید در تنظیمات، به‌صورت پیش‌فرض خاموش). |
| **پروکسی تلگرام روی WARP** | یک پروکسی پس‌زمینه‌ی مستقل: یک تونل AmneziaWG کلودفلر WARP بالا می‌آورد و یک SOCKS5 محلی برای تلگرام می‌دهد. مستقل از وی‌پی‌ان اصلی کار می‌کند و به‌طور خودکار از نقاط مرده‌ی WARP عبور می‌کند. |
| **آبشار دو پروکسی** | یک پروکسی دوم (خروجی) روی پروکسی اصلی — از جمله روی اتصال xhttp از طریق SOCKS محلی، با `xmux` و XTLS Vision درست؛ DNS روی آبشار از طریق TCP/DoH حل می‌شود. |

---

## امکانات

| | |
|---|---|
| **چند موتور** | Xray، sing-box، AmneziaWG، VK-TURN، DNSTT — هسته بر اساس پروتکل به‌صورت خودکار یا دستی انتخاب می‌شود. |
| **پروتکل‌ها** | VLESS · VMess · Trojan · Shadowsocks · Hysteria2 · WireGuard / AmneziaWG |
| **ترنسپورت‌ها** | TCP · WS · gRPC · HTTPUpgrade · XHTTP · TLS · Reality · اثرانگشت‌های uTLS |
| **DNSTT (تونل DNS)** | تونل روی کوئری‌های DNS (KCP + Noise) — جایی کار می‌کند که همه‌ی ترافیک دیگر مسدود است اما DNS هنوز کار می‌کند. نصب یک‌ضربه‌ای سرور روی VPS از طریق SSH. |
| **پروکسی تلگرام روی WARP** | سرویس سبک پس‌زمینه: یک تونل WARP + یک SOCKS5 محلی برای تلگرام، مستقل از اتصال اصلی. |
| **olcRTC** | ترنسپورت [olcRTC](https://github.com/openlibrecommunity/olcrtc) — ترافیک از سرویس‌های واقعی تماس تصویری (Jazz، Telemost، WB Stream، Jitsi) عبور می‌کند؛ برای DPI شبیه یک تماس زنده است، نه پروکسی. |
| **ایمپورت هوشمند** | لینک‌های vless/vmess/trojan/ss، base64، پنل‌های JSON، **کانفیگ‌های خام کامل Xray / sing-box** (همان‌طور که هست اعمال می‌شوند)، AmneziaWG `.conf`/QR، URI‌های olcRTC، پروفایل‌های Happ، ایمپورت انبوه فهرست لینک‌ها. |
| **DNS و مسیریابی** | پروفایل‌های مسیریابی سازگار با Happ (مسدود/مستقیم/پروکسی بر اساس `geoip:`/`geosite:`/`asn:`/دامنه/CIDR)، قواعد سبک v2rayNG، کلید «مسدودسازی دامنه‌های روسیه»، DNS و fakedns سفارشی. |
| **انتخاب خودکار سرور** | اتصال یک‌ضربه‌ای به سریع‌ترین گره در دسترس، با جابه‌جایی در صورت خطا. |
| **پروکسی HTTP** | یک پروکسی HTTP محلی سازگار با Happ روی موتور فعال. |
| **دور زدن DPI** | تکه‌تکه‌سازی TLS، مالتی‌پلکسینگ، مبهم‌سازی AmneziaWG، مسدودسازی QUIC جایی که نشت می‌کند. |
| **بدون نشتی** | هم IPv4 و هم IPv6 را می‌گیرد — چیزی از کنار تونل خارج نمی‌شود. |
| **تونل تفکیکی** | انتخاب می‌کنید کدام اپ‌ها از وی‌پی‌ان عبور کنند. |
| **اشتراک‌ها** | به‌روزرسانی خودکار (قابل خاموش‌کردن برای هر اشتراک)، شمارنده‌ی سرورهای در دسترس، توضیح سرورها، ترافیک/باقی‌مانده، گروه‌ها با جمع‌کردن/سنجاق/مرتب‌سازی بر اساس پینگ، پوشه‌ها. |

</div>

---

## دانلود

آخرین APK امضاشده را از **[صفحه‌ی انتشارها](https://github.com/yanisplugg/olcvpn-client/releases/latest)** بگیرید.

| نسخه | برای |
|------|------|
| **`arm64-v8a`** | گوشی‌های امروزی — اگر مطمئن نیستید این را بگیرید |
| `armeabi-v7a` | دستگاه‌های قدیمی ۳۲ بیتی |
| `x86_64` | شبیه‌سازها / تبلت‌های x86 |
| `universal` | یک فایل برای همه (بزرگ‌ترین) |

حداقل **Android 6.0** (API 23).

---

## چطور کار می‌کند

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

<div dir="rtl">

همه‌ی هسته‌های بومی در **یک** کتابخانه‌ی `gomobile` (یک Go runtime واحد) ساخته می‌شوند، بنابراین Xray، sing-box، AmneziaWG، VK-TURN، DNSTT و olcRTC بدون تداخل در یک پراسس کنار هم زندگی می‌کنند. اپ یک `VpnService` بالا می‌آورد، بسته‌ها را به TUN می‌دهد و آن‌ها را از طریق یک SOCKS5 محلی در موتور انتخاب‌شده می‌پیچد.

---

## موتورها به زبان ساده

- **Xray / sing-box** — هسته‌های پروکسی کلاسیک: VLESS+Reality، XHTTP، WS+TLS و … . هسته بر اساس ترنسپورت به‌صورت خودکار انتخاب می‌شود.
- **AmneziaWG** — وایرگارد با مبهم‌سازی: دست‌دادن و بسته‌ها شبیه وایرگارد «معمولی» نیستند که اغلب بر اساس امضا قطع می‌شود.
- **Hysteria2** — پروتکل سریع مبتنی بر QUIC با مبهم‌سازی Salamander و پرش پورت؛ روی خطوط ناپایدار سرعت را خوب نگه می‌دارد.
- **VK-TURN** — یک وایرگارد محلی بالا می‌آورد و آن را از سرورهای TURN تماس‌های VK عبور می‌دهد؛ چند «تماس» برای پهنای باند به هم پیوند می‌خورند.
- **DNSTT** — تونل روی کوئری‌های DNS؛ جایی کار می‌کند که فقط DNS باز است.
- **olcRTC** — استتار به‌صورت تماس تصویری: ترافیک از سرویس‌های واقعی کنفرانس عبور می‌کند و برای DPI شبیه یک تماس زنده است.
- **پروکسی تلگرام روی WARP** — یک پروکسی پس‌زمینه‌ی مستقل برای تلگرام روی کلودفلر WARP.

---

## ساخت از منبع

هر چیز لازم از پیش در مخزن هست (`cores`، `olcrtc`، `sing-box`، `awgproxy`، `hysteria2proxy`، `free-turn-proxy`، `dnstt`، `wdtt`، `amneziawg-go`). به این‌ها نیاز دارید:

- **JDK 17** (همان نسخه‌ی همراه Android Studio کافی است)
- **Android SDK** (مقدار `sdk.dir` را در `YPtun/local.properties` قرار دهید) + **NDK `28.2.13676358`**
- **Go** + [`gomobile`](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile) در `PATH`

> `gomobile` به `javac` فراخوان می‌زند، پس مسیر `bin/` از JDK را در `PATH` قرار دهید — نه فقط `JAVA_HOME`.

</div>

```bash
cd YPtun
./gradlew :androidApp:assembleRelease \
  -Polcbox.version=2.6.1 -Polcbox.versionCode=286
```

<div dir="rtl">

APK‌ها در `YPtun/androidApp/build/outputs/apk/release/` ساخته می‌شوند.
ساخت سریع‌تر فقط برای گوشی خودتان؟ `-Polcbox.android.abiFilters=arm64-v8a` را اضافه کنید.

---

## توسعه

YPtun یک پروژه‌ی **Kotlin Multiplatform** است: همه‌ی منطق (ایمپورت، ساخت کانفیگ، موتورها، وضعیت UI) در `commonMain` و جزئیات پلتفرمی در `androidMain` است. همان کد روی دسکتاپ JVM هم اجرا می‌شود.

- **رابط کاربری** — Jetpack Compose، یک طراحی روی همه‌ی پلتفرم‌ها.
- **بومی‌سازی** — روسی، انگلیسی، فارسی و چینی ساده‌شده در یک فایل رشته‌ها.
- **هسته‌های بومی** — Go، با تسک `buildCoresAndroidAar` در یک gomobile AAR ساخته می‌شوند.
- **تست‌ها** — تست‌های واحد برای پارسرها/مبدل‌های مسیریابی (`./gradlew :sharedUI:jvmTest`).
- **شاخه‌ها** — پایدار در `main`، توسعه‌ی فعال در `Beta`؛ انتشارها با `vX.Y.Z` تگ می‌شوند.

باگ پیدا کردید یا ویژگی می‌خواهید؟ یک issue یا PR باز کنید — **[CONTRIBUTING.md](CONTRIBUTING.md)** را ببینید.

---

## ساختار پروژه

</div>

```
YPtun/            اپ Kotlin Multiplatform — رابط Compose، VpnService اندروید، موتورها
cores/            چسب Go: یک gomobile AAR از sing-box + olcRTC + Xray + AmneziaWG + VK-TURN + DNSTT
olcrtc/           olcRTC — ترنسپورت استتار تماس تصویری             (شخص ثالث، vendored)
sing-box/         sing-box / libbox                                (vendored)
awgproxy/         پوشش AmneziaWG → SOCKS5 محلی                     (ماژول Go)
hysteria2proxy/   پوشش Hysteria2 (apernet) → SOCKS5 محلی           (ماژول Go)
free-turn-proxy/  VK-TURN — تونل از تماس‌های VK                    (ماژول Go)
dnstt/            DNSTT — تونل روی DNS                              (کلاینت + سرور)
wdtt/             WDTT — نوع دیگری از تونل                          (کلاینت + سرور)
amneziawg-go/     پیاده‌سازی AmneziaWG                              (vendored)
```

<div dir="rtl">

---

## نقشه‌ی راه

- [x] انتشار اندروید
- [x] موتورهای AmneziaWG، VK-TURN و DNSTT
- [x] پروفایل‌های مسیریابی (سازگار با Happ) + ASN
- [ ] ساخت **ویندوز** — *به‌زودی*
- [ ] ساخت **لینوکس** — *به‌زودی*

> موتور مشترک هم‌اکنون روی JVM (`desktopApp`) اجرا می‌شود، پس دسکتاپ نفر بعدی است.

---

## مشارکت

PR و issue پذیرفته می‌شوند. پیش از شروع ببینید:
- **[CONTRIBUTING.md](CONTRIBUTING.md)** — نحوه‌ی ساخت، قالب‌بندی و ارسال تغییرات
- **[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)** — قواعد جامعه
- **[SECURITY.md](SECURITY.md)** — نحوه‌ی گزارش آسیب‌پذیری

---

## سپاس‌گزاری

بر شانه‌ی غول‌ها:
[Xray-core](https://github.com/XTLS/Xray-core) ·
[sing-box](https://github.com/SagerNet/sing-box) ·
[olcRTC](https://github.com/openlibrecommunity/olcrtc) ·
[AmneziaWG](https://github.com/amnezia-vpn/amneziawg-go).

## مجوز

[GPL-3.0](LICENSE) — اپ تحت GNU GPL v3.0 منتشر می‌شود چون **sing-box** (آن هم GPL-3.0) را در خود دارد: کپی‌لفت بر کل محصول اعمال می‌شود. اجزای vendored مجوز خود را حفظ می‌کنند (`sing-box` — GPL-3.0، Xray — MPL-2.0، `amneziawg-go` — MIT، `olcrtc` — WTFPL).

</div>

<div align="center">
<br>

<img src="docs/no-rkn.jpg" alt="نه به سانسور" width="150">

<br><br>

> *«ملتی که می‌ترسد مردمش حقیقت و دروغ را در بازار آزاد داوری کنند، ملتی است که از مردم خود می‌ترسد.»*
>
> — **جان اف. کندی**

<br>

<sub>برای اینترنت آزاد</sub>

</div>
