# Contributing to YPtun

Thanks for taking the time to contribute! 🎉 This project exists to keep the
internet open, and every bug report, translation, and patch helps.

## Ways to help

- 🐛 **Report bugs** — open an [issue](https://github.com/yanisplugg/olcvpn-client/issues)
  with steps to reproduce, your device/Android version, and (if a connection
  fails) anonymized logs from the in-app log viewer.
- 💡 **Suggest features** — open an issue describing the problem you want solved.
- 🌍 **Translations** — strings live in
  `YPtun/sharedUI/src/commonMain/kotlin/org/olcbox/app/ui/i18n/Strings.kt`
  (Russian, English, Persian today). Add a new `Strings` implementation for your locale.
- 🔧 **Code** — pick an open issue or propose your own change, then send a PR.

## Project layout

| Path | What |
|------|------|
| `YPtun/` | Kotlin Multiplatform app — Compose UI, Android `VpnService`, engine wiring |
| `cores/` | Go glue: one gomobile AAR (sing-box + olcRTC + Xray + AmneziaWG + VK-TURN) |
| `olcrtc/`, `sing-box/`, `amneziawg-go/` | vendored third-party cores |
| `awgproxy/`, `free-turn-proxy/` | Go modules for the AmneziaWG and VK-TURN engines |

Most logic is in `commonMain`; Android-only code is in `androidMain`.

## Building

See **[README → Build from source](README.en.md#%EF%B8%8F-build-from-source)** for the full toolchain (JDK 17, Android SDK + NDK `28.2.13676358`, Go + `gomobile`).

```bash
cd YPtun
# fast single-ABI debug build for your phone
./gradlew :androidApp:assembleDebug -Polcbox.android.abiFilters=arm64-v8a
# run the JVM unit tests
./gradlew :sharedUI:jvmTest
```

## Pull requests

1. **Branch from `Beta`** — that's where active development happens (`main` is the
   stable, released branch).
2. **Keep PRs focused.** One feature or fix per PR makes review easy. Split unrelated
   changes into separate commits/PRs.
3. **Match the surrounding style.** Follow the existing naming, formatting, and comment
   density of the file you're editing.
4. **Build and test** before pushing — at minimum `assembleDebug` and `jvmTest`.
5. **Write clear commit messages** (`type: short summary`, e.g. `fix(routing): …`).
6. Open the PR against `Beta` and fill in the template.

## Reporting security issues

Please **do not** open a public issue for security vulnerabilities — see
**[SECURITY.md](SECURITY.md)**.

By contributing, you agree that your contributions are licensed under the
project's [MIT License](LICENSE).
