#!/usr/bin/env python3
"""Validate every native Apple XCFramework before use or cache save."""

from __future__ import annotations

import plistlib
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
FRAMEWORKS = [
    ROOT / "YPtun/apple/Packages/YPtunRustBridge/Artifacts/yptun_ffiFFI.xcframework",
    ROOT / "YPtun/sharedUI/build/generated/cores/ios/Coreapi.xcframework",
    ROOT / "YPtun/sharedUI/build/generated/cores/ios/SnolcCore.xcframework",
    ROOT / "YPtun/sharedUI/build/generated/cores/ios/CsqttCore.xcframework",
]
EXPECTED = {
    ("ios", ""): {"arm64"},
    ("ios", "simulator"): {"arm64", "x86_64"},
    ("macos", ""): {"arm64", "x86_64"},
}


def fail(message: str) -> None:
    print(f"Apple XCFramework validation failed: {message}", file=sys.stderr)
    raise SystemExit(1)


def lipo_arches(path: Path) -> set[str]:
    result = subprocess.run(
        ["xcrun", "lipo", "-archs", str(path)],
        check=True,
        text=True,
        capture_output=True,
    )
    return set(result.stdout.strip().split())


def validate(framework: Path) -> None:
    info_path = framework / "Info.plist"
    if not info_path.is_file():
        fail(f"missing {info_path}")
    with info_path.open("rb") as source:
        info = plistlib.load(source)
    libraries = info.get("AvailableLibraries") or []
    found: dict[tuple[str, str], set[str]] = {}
    for entry in libraries:
        platform = str(entry.get("SupportedPlatform", ""))
        variant = str(entry.get("SupportedPlatformVariant", ""))
        key = (platform, variant)
        declared = set(entry.get("SupportedArchitectures") or [])
        identifier = entry.get("LibraryIdentifier")
        library_path = entry.get("LibraryPath")
        if not identifier or not library_path:
            fail(f"malformed library entry in {framework.name}: {entry}")
        library = framework / identifier / library_path
        binary = library / library.stem if library.is_dir() else library
        if not binary.is_file():
            fail(f"missing binary {binary}")
        actual = lipo_arches(binary)
        if actual != declared:
            fail(f"{binary}: plist architectures {sorted(declared)} != lipo {sorted(actual)}")
        found[key] = actual

        headers_path = entry.get("HeadersPath")
        if headers_path:
            headers = framework / identifier / headers_path
            if not any(headers.rglob("*.h")):
                fail(f"no public header under {headers}")
            if not any(headers.rglob("module.modulemap")):
                fail(f"no module.modulemap under {headers}")
        elif library.is_dir():
            if not any(library.rglob("*.h")):
                fail(f"no public header under {library}")
            if not any(library.rglob("module.modulemap")):
                fail(f"no module.modulemap under {library}")

    if found != EXPECTED:
        fail(f"{framework.name}: slices {found!r}, expected {EXPECTED!r}")
    print(f"Validated {framework.name}: device, simulator, macOS")


for item in FRAMEWORKS:
    validate(item)

print("All Apple XCFrameworks are structurally valid.")
