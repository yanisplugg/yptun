# YPtunRustBridge

Local Swift package containing generated UniFFI bindings and the Apple XCFramework for `yptun-ffi`.

Generate its ignored artifacts on macOS from the repository root:

```bash
bash core/scripts/build-apple.sh
```

The generated `Artifacts/yptun_ffiFFI.xcframework` and
`Sources/YPtunRustBridge/Generated/yptun_ffi.swift` are build outputs and are not committed.
