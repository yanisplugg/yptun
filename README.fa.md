<div align="center">

# 🛡️ YPtun

### وی‌پی‌ان سریع و مقاوم در برابر سانسور برای اندروید

*VLESS · Reality · XHTTP روی **Xray** و **sing-box**، وایرگارد مبهم‌سازی‌شده‌ی **AmneziaWG**، تونل از طریق تماس‌های **VK-TURN** — و مهم‌تر از همه، انعطاف‌پذیری محض: حتی پشتیبانی از **olcRTC** که ترافیک شما را شبیه یک تماس تصویری می‌کند.*

<br>

[![آخرین نسخه](https://img.shields.io/github/v/release/yanisplugg/olcvpn-client?style=for-the-badge&color=4c8eff&label=download)](https://github.com/yanisplugg/olcvpn-client/releases/latest)
[![دانلودها](https://img.shields.io/github/downloads/yanisplugg/olcvpn-client/total?style=for-the-badge&color=2ea043)](https://github.com/yanisplugg/olcvpn-client/releases)
[![ستاره‌ها](https://img.shields.io/github/stars/yanisplugg/olcvpn-client?style=for-the-badge&color=f0b429)](https://github.com/yanisplugg/olcvpn-client/stargazers)

![پلتفرم](https://img.shields.io/badge/platform-Android%206.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white)
![هسته‌ها](https://img.shields.io/badge/cores-Xray%20%2B%20sing--box-blueviolet?style=flat-square)
![مجوز](https://img.shields.io/badge/license-MIT-lightgrey?style=flat-square)

<br>

[**Русский**](README.md) · [**English**](README.en.md) · **🌍 فارسی**

</div>

---

<div dir="rtl">

## ✨ چرا YPtun؟

بیشتر کلاینت‌های وی‌پی‌ان یک هسته و یک راه اتصال به شما می‌دهند. **YPtun یک جعبه‌ابزار به شما می‌دهد.**
این برنامه **چند موتور دور زدن سانسور** را در یک اپ جمع کرده است؛ وقتی یک روش مسدود شد، روش دیگری را انتخاب می‌کنید و کارتان را ادامه می‌دهید.

> ⭐ **نقطه‌ی قوت، انعطاف‌پذیری محض است.** Xray و sing-box با همه‌ی پروتکل‌ها و ترنسپورت‌های رایج، وایرگارد مبهم‌سازی‌شده با **AmneziaWG**، تونل‌زنی از طریق تماس‌های واقعی (**VK-TURN** و **olcRTC**)، ایمپورت تقریباً هر چیزی و پروفایل‌های مسیریابی سازگار با Happ. یک مسیر را ببندند، چند مسیر دیگر کنارش هست.

> ساخته‌شده برای جاهایی که اینترنت مقاومت می‌کند — برای 🇮🇷 ایران، 🇷🇺 روسیه و هر کشوری که سایت‌ها بی‌خبر ناپدید می‌شوند. 🌐

> 🖥️ **به‌زودی روی دسکتاپ** — نسخه‌های بومی **ویندوز** و **لینوکس** در دست ساخت است.

---

## 🆕 تازه‌ها در نسخه ۲٫۰

| | |
|---|---|
| 🌀 **موتور AmneziaWG** | وایرگارد مبهم‌سازی‌شده — ایمپورت `.conf`/QR، تنظیم دقیق مبهم‌سازی (Jc/Jmin/Jmax/S1/S2/H1–H4). به‌صورت خروجی مستقل یا حلقه‌ای در زنجیره. |
| 📞 **موتور VK-TURN** | تونل روی زیرساخت TURN تماس‌های VK. چند «تماس» موازی برای سرعت ترکیب می‌شوند؛ انتخاب خروجی: WireGuard / AmneziaWG / پراکسی. |
| 🧭 **پروفایل‌های مسیریابی** | پروفایل‌های سازگار با Happ (`happ://routing/add/…`): block/direct/proxy بر اساس `geoip:`/`geosite:`/دامنه/CIDR، کلید «همه‌ی ترافیک از پراکسی»، DNS سفارشی و fakedns. به هر دو هسته تبدیل می‌شود. |
| 🗂️ **اشتراک‌های بهتر** | گروه‌های اشتراک را می‌توان **جمع کرد**، **به بالا سنجاق کرد** و **بر اساس پینگ مرتب کرد** — وضعیت ذخیره می‌شود. ایمپورت گروهی فهرستی از لینک‌ها با یک‌بار چسباندن. |
| 🛡️ **نشت کمتر** | مسدودسازی بی‌قیدوشرط QUIC روی ترنسپورت‌هایی که آن را پشتیبانی نمی‌کنند، به‌علاوه‌ی resolve دامنه برای قواعد geoip. |
| 🔔 **اعلان** | لوگوی رنگی و نمایش اختیاری سرعت دانلود/آپلود زنده در نوار اعلان. |

---

## 🚀 امکانات

| | |
|---|---|
| 🔀 **چند موتور** | **Xray**، **sing-box**، **AmneziaWG** و **VK-TURN** — هسته به‌صورت خودکار بر اساس پروتکل انتخاب می‌شود یا دستی. |
| 🧬 **پروتکل‌ها** | VLESS · VMess · Trojan · Shadowsocks · WireGuard / AmneziaWG |
| 🚇 **ترنسپورت‌ها** | TCP · WS · gRPC · HTTPUpgrade · **XHTTP** · TLS · **Reality** · اثرانگشت‌های uTLS |
| 🎭 **پشتیبانی olcRTC** | ترنسپورت [olcRTC](https://github.com/openlibrecommunity/olcrtc) — ترافیک از روی سرویس‌های واقعی تماس تصویری عبور می‌کند، پس برای DPI یک تماس عادی است، نه پراکسی. |
| 📥 **ایمپورت هوشمند** | لینک‌های vless/vmess/trojan/ss، base64، JSON پنل، **کانفیگ خام کامل Xray / sing-box**، `.conf`/QR وایرگارد، آدرس‌های olcRTC و پروفایل‌های Happ. |
| 🧭 **DNS و مسیریابی** | پروفایل‌های مسیریابی، ایمپورت کانفیگ کامل Xray (دقیقاً همان‌طور اعمال می‌شود) یا کلید داخلی **«مسدودسازی دامنه‌های RU»**. |
| 🧱 **دور زدن DPI** | تکه‌تکه‌سازی TLS، multiplexing، مبهم‌سازی AmneziaWG، مسدودسازی QUIC. |
| 🔒 **بدون نشت** | هم **IPv4 و هم IPv6** را می‌گیرد — چیزی از تونل بیرون نمی‌رود. |
| 📱 **تونل تفکیکی** | انتخاب اینکه دقیقاً کدام اپ‌ها از وی‌پی‌ان عبور کنند. |
| 🗂️ **اشتراک‌ها** | به‌روزرسانی خودکار، نمایش ترافیک/مصرف، گروه‌ها با جمع‌کردن/سنجاق/مرتب‌سازی بر اساس پینگ. |

---

## 📦 دانلود

آخرین APK امضاشده را از **[صفحه‌ی Releases](https://github.com/yanisplugg/olcvpn-client/releases/latest)** بگیرید.

| نسخه | مناسبِ |
|------|--------|
| 🟢 **`arm64-v8a`** | گوشی‌های امروزی — **اگر مطمئن نیستید این را بگیرید** |
| 🟡 `armeabi-v7a` | دستگاه‌های قدیمی ۳۲ بیتی |
| 🔵 `x86_64` | شبیه‌سازها / تبلت‌های x86 |
| ⚪ `universal` | یک فایل که همه‌جا اجرا می‌شود (بزرگ‌ترین) |

> 💡 مطمئن نیستید؟ **arm64-v8a** یا **universal** را دانلود کنید.

حداقل نسخه **اندروید ۶٫۰** (API 23) است.

---

## 🧩 موتورها به زبان ساده

- **Xray / sing-box** — هسته‌های پراکسی کلاسیک. VLESS+Reality، XHTTP، WS+TLS و غیره. هسته بر اساس ترنسپورت خودکار انتخاب می‌شود.
- **AmneziaWG** — وایرگارد همراه با مبهم‌سازی: دست‌دادن و بسته‌ها شبیه وایرگارد «معمولی» نیستند که اغلب با اثرانگشت شناسایی و قطع می‌شود.
- **VK-TURN** — یک وایرگارد محلی بالا می‌آورد و آن را از سرورهای TURN تماس‌های VK عبور می‌دهد؛ چند «تماس» برای پهنای‌باند ترکیب می‌شوند.
- **olcRTC** — استتار به‌صورت تماس تصویری: ترافیک از روی سرویس‌های واقعی کنفرانس عبور می‌کند و برای DPI شبیه یک تماس زنده است.

---

## 🛠️ ساخت از سورس

هر چیزی که لازم دارید این‌جا vendored شده است (`cores`، `olcrtc`، `sing-box`، `awgproxy`، `free-turn-proxy`، `amneziawg-go`). نیاز دارید به:

- **JDK 17** (همانی که همراه Android Studio است کافی است)
- **Android SDK** (مقدار `sdk.dir` را در `YPtun/local.properties` تنظیم کنید) + **NDK `28.2.13676358`**
- **Go** + [`gomobile`](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile) در `PATH`

> ⚠️ `gomobile` به `javac` نیاز دارد، پس مطمئن شوید پوشه‌ی `bin/` از JDK در `PATH` باشد — نه فقط `JAVA_HOME`.

```bash
cd YPtun
./gradlew :androidApp:assembleRelease \
  -Polcbox.version=2.0.0 -Polcbox.versionCode=2
```

فایل‌های APK در `YPtun/androidApp/build/outputs/apk/release/` ساخته می‌شوند.

---

## 🧪 توسعه

YPtun یک پروژه‌ی **Kotlin Multiplatform** است: همه‌ی منطق (ایمپورت، ساخت کانفیگ، موتورها، وضعیت UI)
در `commonMain` است و چسب پلتفرمی در `androidMain`. همین کد روی هدف دسکتاپ JVM هم اجرا می‌شود.

- **رابط کاربری** — Jetpack Compose، یک طراحی روی همه‌ی پلتفرم‌ها.
- **بومی‌سازی** — سه زبان (🇮🇷 فارسی، 🇷🇺 روسی، 🇬🇧 انگلیسی) در یک فایل رشته‌ها.
- **هسته‌های بومی** — Go، با تسک `buildCoresAndroidAar` در یک AAR ساخته می‌شوند.
- **تست‌ها** — تست‌های واحد برای پارسرها/مبدل‌های مسیریابی (`./gradlew :sharedUI:jvmTest`).
- **شاخه‌ها** — پایدار در `main`، توسعه‌ی فعال در `Beta`؛ نسخه‌ها با `vX.Y.Z` تگ می‌شوند.

باگ پیدا کردید یا قابلیتی می‌خواهید؟ issue یا PR باز کنید — به **[CONTRIBUTING.md](CONTRIBUTING.md)** نگاه کنید.

---

## 🗺️ نقشه‌ی راه

- [x] انتشار اندروید
- [x] موتورهای AmneziaWG و VK-TURN
- [x] پروفایل‌های مسیریابی (سازگار با Happ)
- [ ] 🪟 نسخه‌ی دسکتاپ **ویندوز** — *به‌زودی*
- [ ] 🐧 نسخه‌ی دسکتاپ **لینوکس** — *به‌زودی*

---

## 🙏 سپاس‌گزاری

بر شانه‌ی غول‌ها ایستاده‌ایم:
[Xray-core](https://github.com/XTLS/Xray-core) ·
[sing-box](https://github.com/SagerNet/sing-box) ·
[olcRTC](https://github.com/openlibrecommunity/olcrtc) ·
[AmneziaWG](https://github.com/amnezia-vpn/amneziawg-go).

## 📄 مجوز

[MIT](LICENSE) برای اپ. اجزای vendored مجوز خودشان را حفظ می‌کنند
(`sing-box/LICENSE`، `olcrtc/LICENSE`، `amneziawg-go/LICENSE`).

</div>

<div align="center">
<br>

<img src="docs/no-rkn.jpg" alt="نه به سانسور" width="150">

<br><br>

> *«ملتی که می‌ترسد مردمش حقیقت و دروغ را در بازاری آزاد قضاوت کنند، ملتی است که از مردم خود می‌ترسد.»*
>
> — **جان اف. کندی**

<br>

<sub>برای اینترنتی آزادتر. اگر به کارتان آمد ⭐ بدهید.</sub>

</div>
