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

Stream slots (YPtun 02.11.2026): every flow (TCP connection, UDP flow) holds one yamux user stream; the limit is
`yamux.max_streams_per_session - 1`. Two bugs reset the whole tunnel ("yamux user stream limit is exhausted") ~100x/day:
(1) `reap_closed` released slots only while the session still owned its `mux` - on the server it sits inside the pending
accept almost always, so closed flows leaked their slots (now released via the shared `UserStreamSlots` handle);
(2) `accept_flow` reserved a slot BEFORE waiting and a full session returned a session error - now a flow takes a slot only
once it arrives, and a full session (client: before taking the flow off the adapter) refuses just that flow with
`ResourceLimit`. Check: `scripts/stream_limit_check.py <binary> <streams> <flows>` (burst) and `CHURN=1 CHURN_THREADS=8` (leak).
YPtun configs now use 129 streams (SnolcFiles.kt, panel recipes.py).
