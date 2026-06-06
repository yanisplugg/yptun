# olcvpnhide — Zygisk VPN-hide module

A Magisk/Zygisk module that hides VPN interfaces (`tun*`/`ppp*`/`tap*`/`wg*`/`awg*`) from other
apps, so VPN-detection (banking/streaming apps enumerating network interfaces) can't see the tunnel.

## How it works
`jni/module.cpp` PLT-hooks libc's `getifaddrs` inside **`libjavacore.so`** of every ordinary app
process (uid ≥ 10000, excluding `org.olcbox.app` and all system/privileged processes). The hook
calls the real `getifaddrs`, then splices VPN-looking entries out of the returned list. This covers
the Java `NetworkInterface.getNetworkInterfaces()` path that Android apps use.

Renaming the tun (the old approach) can't work: the kernel refuses to rename an UP interface
(`EBUSY`), and bringing it down detaches Android's name-keyed `oif tun0` routing rules and breaks
per-app VPN routing. Hooking the detection call avoids touching the interface at all.

## Build
Requires Android NDK (tested with 28.2). From this directory:

```
ndk-build NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=jni/Android.mk NDK_APPLICATION_MK=jni/Application.mk -B
cp libs/arm64-v8a/libolcvpnhide.so   module/zygisk/arm64-v8a.so
cp libs/armeabi-v7a/libolcvpnhide.so module/zygisk/armeabi-v7a.so
```

Then zip `module/module.prop` + `module/zygisk/*.so` (forward-slash entry names!) into
`../YPtun/androidApp/src/main/assets/olcvpnhide.zip` — the app bundles this and installs it via
`magisk --install-module` when the user enables the "Hide tun0" experimental toggle.

`deploy_test.ps1` automates build-zip → push → install → reboot → logcat for on-device testing.
