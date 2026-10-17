plugins {
    alias(libs.plugins.kotlin.multiplatform).apply(false)
    alias(libs.plugins.compose.compiler).apply(false)
    alias(libs.plugins.compose.multiplatform).apply(false)
    alias(libs.plugins.kotlin.android).apply(false)
    alias(libs.plugins.android.application).apply(false)
    alias(libs.plugins.android.library).apply(false)
    alias(libs.plugins.android.kmp.library).apply(false)
    alias(libs.plugins.kotlin.jvm).apply(false)
    alias(libs.plugins.kotlinx.serialization).apply(false)
    alias(libs.plugins.metro).apply(false)
}

// Compose's downloadWix/unzipWix (pulled into every desktop jpackage task on Windows) read Task.project while
// running, which the configuration cache rejects and fails the whole build. Such a build just runs without the cache.
tasks.matching { it.name == "downloadWix" || it.name == "unzipWix" }.configureEach {
    notCompatibleWithConfigurationCache("Compose downloadWix/unzipWix use Task.project at execution time")
}
