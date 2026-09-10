import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.application)
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")

if (keystorePropertiesFile.exists()) {
    FileInputStream(keystorePropertiesFile).use { input ->
        keystoreProperties.load(input)
    }
}

val hasReleaseKeystore =
    keystorePropertiesFile.exists() &&
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
            .all { key -> !keystoreProperties.getProperty(key).isNullOrBlank() }
// applicationId — это ИДЕНТИЧНОСТЬ приложения для системы и единственное, по чему обновление
// встаёт поверх установленного. Меняется только вместе с переездом (см. LegacyMigration), и только
// после того, как релиз с мостом уже разошёлся пользователям.
val olcboxApplicationId = providers.gradleProperty("olcbox.applicationId").orElse("org.olcbox.app")
val olcboxVersion = providers.gradleProperty("olcbox.version").orElse("1.0.0")
val olcboxVersionCode = providers.gradleProperty("olcbox.versionCode")
    .map { it.toInt() }
    .orElse(1)
val defaultAndroidAbiFilters = listOf("armeabi-v7a", "arm64-v8a", "x86_64")
val androidAbiFilters = providers.gradleProperty("olcbox.android.abiFilters")
    .map { value ->
        value.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
    .getOrElse(defaultAndroidAbiFilters)

require(androidAbiFilters.isNotEmpty()) {
    "olcbox.android.abiFilters must contain at least one Android ABI"
}

android {
    namespace = "org.olcbox.app"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 23
        targetSdk = 37

        applicationId = olcboxApplicationId.get()
        versionCode = olcboxVersionCode.get()
        versionName = olcboxVersion.get()

        ndk {
            abiFilters += androidAbiFilters
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }

            isMinifyEnabled = false
            isShrinkResources = false
        }

        release {
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }

            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs", "jniLibs")
        }
    }

    // Per-ABI APK splits so each release ships a slim arm64-v8a / armeabi-v7a / x86_64 build,
    // plus one fat "universal" APK for anyone who just wants a single file that runs anywhere.
    splits {
        abi {
            isEnable = true
            reset()
            include(*androidAbiFilters.toTypedArray())
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

// --- OpenFlux client executable (../openflux), packaged as lib/<abi>/libopenflux.so ---
// It runs as a SUBPROCESS started from nativeLibraryDir (useLegacyPackaging extracts it there), not as
// a gomobile library: its young transports panic on unexpected input, and a panic in a bound library
// kills the whole app. CGo + the NDK clang is required: without cgo the Go resolver looks for
// /etc/resolv.conf, which Android doesn't have, so every name lookup would fail.
val openfluxRepoDir = rootProject.layout.projectDirectory.asFile.parentFile.resolve("openflux")
val openfluxJniLibsDir = layout.buildDirectory.dir("generated/openflux/jniLibs")
val openfluxNdkDir: File = System.getenv("ANDROID_NDK_HOME")?.let(::File)
    ?: run {
        val local = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        val sdk = local.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: ""
        File(sdk, "ndk/28.2.13676358")
    }
val openfluxHostTag = when {
    System.getProperty("os.name").lowercase().contains("windows") -> "windows-x86_64"
    System.getProperty("os.name").lowercase().contains("mac") -> "darwin-x86_64"
    else -> "linux-x86_64"
}
val openfluxClangSuffix = if (openfluxHostTag.startsWith("windows")) ".cmd" else ""
val buildOpenFluxAndroid = tasks.register("buildOpenFluxAndroid") {
    group = "build"
    description = "Builds the OpenFlux client executable for every packaged ABI."
}
androidAbiFilters.forEach { abi ->
    val (goarch, clang, goarm) = when (abi) {
        "arm64-v8a" -> Triple("arm64", "aarch64-linux-android23-clang", "")
        "armeabi-v7a" -> Triple("arm", "armv7a-linux-androideabi23-clang", "7")
        "x86_64" -> Triple("amd64", "x86_64-linux-android23-clang", "")
        "x86" -> Triple("386", "i686-linux-android23-clang", "")
        else -> error("Unsupported ABI for OpenFlux: $abi")
    }
    val task = tasks.register<Exec>("buildOpenFluxAndroid_$abi") {
        val output = openfluxJniLibsDir.map { it.file("$abi/libopenflux.so") }
        inputs.files(fileTree(openfluxRepoDir) { include("**/*.go", "go.mod", "go.sum"); exclude("**/*_test.go") })
        outputs.file(output)
        workingDir = openfluxRepoDir
        environment("GOOS", "android")
        environment("GOARCH", goarch)
        if (goarm.isNotEmpty()) environment("GOARM", goarm)
        environment("CGO_ENABLED", "1")
        environment("CC", openfluxNdkDir.resolve("toolchains/llvm/prebuilt/$openfluxHostTag/bin/$clang$openfluxClangSuffix").absolutePath)
        commandLine("go", "build", "-trimpath", "-ldflags", "-s -w -checklinkname=0",
            "-o", output.get().asFile.absolutePath, ".")
        doFirst { output.get().asFile.parentFile.mkdirs() }
    }
    buildOpenFluxAndroid.configure { dependsOn(task) }
}
android.sourceSets.getByName("main").jniLibs.srcDir(openfluxJniLibsDir.get().asFile)
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(buildOpenFluxAndroid) }

// In AGP 9.0+ Kotlin settings for Android are configured like this:
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":sharedUI"))
    implementation(libs.androidx.activityCompose)
    implementation(libs.androidx.datastore.preferences)
    // Bundled emoji font so flag emojis render on all devices (many OEM fonts lack flags).
    implementation("androidx.emoji2:emoji2:1.5.0")
    implementation("androidx.emoji2:emoji2-bundled:1.5.0")
    // Background daily provider-usage report (Happ providerid).
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
