# snolc (vendored, patched for YPtun)

Upstream: github.com/owenewans/snolc (engine, rev master 2026-09-19) + snolc-modules (adapter-socks5,
adapter-direct, protection-noise, carrier-tcp, policy-dummy), 0.0.4, Unlicense. Toolchain rustc 1.98.1.

Why patched: upstream loads modules with dlopen from a signed package store — impossible on native Windows
and on Android (SELinux). Here all modules are linked into ONE static executable:
- `crates/snolc/src/loader.rs`: `register_builtin(pkg, entry)`; a module path `builtin:<pkg>` skips dlopen.
- `crates/snolc/src/deployment.rs`: `[paths] packages = "builtin"` resolves to those.
- modules: `static-link` feature drops `#[no_mangle]` on `snolc_module_entry` (they all share one name); rlib only.
- `crates/snolc-cli`: registers the modules; new `snolc keygen <priv.hex> <pub.hex>` (Noise NK).
- `Cargo.toml`: snow without `std` feature — that feature drags in `ring` (C code, no cross-build).
Build everything: `./build-all.sh` -> prebuilt/ (windows-amd64.exe, android-{arm64,armv7}, linux-{amd64,arm64} static musl).
musl: no `cc`, so rust-lld + an empty libdl.a stub (libloading asks for -ldl).
Static musl has no dlopen at all — never use `Library::this()`.

Cascade (YPtun 25.10.2026): `adapter-direct` option `upstream = "127.0.0.1:PORT"` sends every TCP/UDP flow through a local
SOCKS5 (a snolc client to the exit node) instead of dialing directly; domain names are passed to the exit as-is (no local DNS).
If the upstream is down flows fail — nothing leaves directly. Panel side: node param `exit_node` on a snolc node.
