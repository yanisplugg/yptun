import org.gradle.api.JavaVersion

/**
 * Vendored subset of Google's archive-patcher (Apache-2.0): `shared` + `applier` only — the
 * File-by-File v1 patch APPLIER that runs on-device. The patch GENERATOR (host-side) is kept out of
 * the app under scripts/patchgen. Vendored because archive-patcher is not published to Maven Central.
 * Source: https://github.com/google/archive-patcher
 */
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.google.archivepatcher"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Pure-Java library, no Android resources.
    buildFeatures {
        buildConfig = false
        resValues = false
    }
}
