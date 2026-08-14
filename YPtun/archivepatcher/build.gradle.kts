/**
 * Vendored subset of Google's archive-patcher (Apache-2.0): `shared` + `applier` only — the
 * File-by-File v1 patch APPLIER. The patch GENERATOR (host-side) is kept out of the app under
 * scripts/patchgen. Vendored because archive-patcher is not published to Maven Central.
 * Source: https://github.com/google/archive-patcher
 *
 * Plain `java-library`, not an Android library: the desktop build applies the same patches for its
 * own delta updates, and a jvm KMP target cannot consume an AAR. Android consumes a java-library
 * project without ceremony — this is pure Java with no Android APIs.
 */
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
