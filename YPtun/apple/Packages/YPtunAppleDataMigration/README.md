# YPtunAppleDataMigration

Native Swift foundation for preserving Apple user data while YPtun moves away from Kotlin.

## Storage contract

The package knows the existing Kotlin locations without decoding their contents:

| Data | Existing location | Native destination |
| --- | --- | --- |
| `locations_v4.json`, `locations_v3.json` | `Library/Application Support/Olcbox` (also discovers `YPtun`) | `Library/Application Support/YPtun` |
| `traffic.json`, `routing.json`, `app_behavior.json` | `group.org.yptun.app/yptun` | unchanged App Group path |
| Shared-setting fallback | `Documents/yptun` | used only when the App Group is unavailable |

Settings are also discovered in the `Olcbox` and `YPtun` Application Support directories so data
from development builds is not silently missed.

`AppleDataMigrator` is an actor. It discovers every known file, builds a value-type plan, and then
copies opaque bytes with atomic replacement. By default it never overwrites a different destination.
Opt-in replacement first stores the old destination in a content-addressed backup. Sources used for
a write are backed up by default, and repeating a migration reuses the same backup and makes no new
changes.

The default `BytePreservingMigrationValidator` intentionally does nothing. A later Rust bridge can
implement `MigrationPayloadValidating`; the protocol only validates immutable bytes and cannot return
rewritten data, keeping model parsing and normalization out of Swift.

## Tests

On macOS with Swift 6:

```bash
cd YPtun/apple/Packages/YPtunAppleDataMigration
swift test
```
