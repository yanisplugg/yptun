import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.util.Properties
import java.io.FileInputStream
import java.util.zip.GZIPOutputStream

plugins {
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.application)
}

/** Configuration-cache-safe gzip step for server binaries bundled into Android assets. */
abstract class GzipBinaryTask : DefaultTask() {
    @get:InputFile
    abstract val inputFile: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun gzip() {
        val input = inputFile.get().asFile
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        input.inputStream().buffered().use { source ->
            GZIPOutputStream(output.outputStream().buffered()).use { target -> source.copyTo(target) }
        }
    }
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

// --- Turnable client + VPS server (vendored ../turnable, GPL-2.0, separate subprocess) ---
// Android owns this integration: other platform builds only configure these tasks and never execute
// them. The client is packaged as libturnable.so so useLegacyPackaging extracts an executable file;
// the two Linux server builds are gzip assets consumed by the one-tap SSH installer.
val turnableRepoDir = rootProject.layout.projectDirectory.asFile.parentFile.resolve("turnable")
val turnableSources = fileTree(turnableRepoDir) {
    include("**/*.go", "go.mod", "go.sum", "LICENSE", "UPSTREAM_COMMIT")
    exclude("**/*_test.go")
}
val turnableJniLibsDir = layout.buildDirectory.dir("generated/turnable/jniLibs")
val buildTurnableAndroid = tasks.register("buildTurnableAndroid") {
    group = "build"
    description = "Builds the Turnable Android client subprocess for every packaged ABI."
}
androidAbiFilters.forEach { abi ->
    val (goarch, clang, goarm) = when (abi) {
        "arm64-v8a" -> Triple("arm64", "aarch64-linux-android23-clang", "")
        "armeabi-v7a" -> Triple("arm", "armv7a-linux-androideabi23-clang", "7")
        "x86_64" -> Triple("amd64", "x86_64-linux-android23-clang", "")
        "x86" -> Triple("386", "i686-linux-android23-clang", "")
        else -> error("Unsupported ABI for Turnable: $abi")
    }
    val task = tasks.register<Exec>("buildTurnableAndroid_$abi") {
        val output = turnableJniLibsDir.map { it.file("$abi/libturnable.so") }
        inputs.files(turnableSources)
        outputs.file(output)
        workingDir = turnableRepoDir
        environment("GOOS", "android")
        environment("GOARCH", goarch)
        environment("CGO_ENABLED", "1")
        environment(
            "CC",
            openfluxNdkDir.resolve("toolchains/llvm/prebuilt/$openfluxHostTag/bin/$clang$openfluxClangSuffix").absolutePath,
        )
        if (goarm.isNotEmpty()) environment("GOARM", goarm)
        commandLine("go", "build", "-trimpath", "-ldflags", "-s -w", "-o", output.get().asFile.absolutePath, "./cmd")
        doFirst { output.get().asFile.parentFile.mkdirs() }
    }
    buildTurnableAndroid.configure { dependsOn(task) }
}
android.sourceSets.getByName("main").jniLibs.srcDir(turnableJniLibsDir.get().asFile)
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(buildTurnableAndroid) }

val turnableServerRawDir = layout.buildDirectory.dir("generated/turnable/server")
val turnableAssetsDir = layout.buildDirectory.dir("generated/turnable/assets")
val buildTurnableServerAssets = tasks.register("buildTurnableServerAssets") {
    group = "build"
    description = "Builds compressed Turnable Linux server binaries for the Android SSH installer."
}
mapOf("amd64" to "amd64", "arm64" to "arm64").forEach { (name, goarch) ->
    val raw = turnableServerRawDir.map { it.file("turnable-linux-$name") }
    val build = tasks.register<Exec>("buildTurnableServer_$name") {
        inputs.files(turnableSources)
        outputs.file(raw)
        workingDir = turnableRepoDir
        environment("GOOS", "linux")
        environment("GOARCH", goarch)
        environment("CGO_ENABLED", "0")
        commandLine("go", "build", "-trimpath", "-ldflags", "-s -w", "-o", raw.get().asFile.absolutePath, "./cmd")
        doFirst { raw.get().asFile.parentFile.mkdirs() }
    }
    val gzip = tasks.register<GzipBinaryTask>("gzipTurnableServer_$name") {
        dependsOn(build)
        inputFile.set(raw)
        outputFile.set(turnableAssetsDir.map { it.file("turnable/turnable-linux-$name.gz") })
    }
    buildTurnableServerAssets.configure { dependsOn(gzip) }
}
android.sourceSets.getByName("main").assets.srcDir(turnableAssetsDir.get().asFile)
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(buildTurnableServerAssets) }
// Lint models inspect every Android source-set asset directly (not through mergeAssets), so Gradle 9
// requires the generated-assets producer to be wired to those tasks too.
tasks.matching { it.name.contains("lint", ignoreCase = true) }
    .configureEach { dependsOn(buildTurnableServerAssets) }

// --- mieru client (github.com/enfein/mieru, GPL-3.0, standalone subprocess) ---
// The app talks to the executable only through its loopback SOCKS5 listener. Keeping it out of the
// Kotlin/Rust/Go libraries preserves a clear GPL process boundary and lets upstream's own `mieru://`
// and `mierus://` import code remain authoritative.
val mieruRepoDir = rootProject.layout.projectDirectory.asFile.parentFile.resolve("mieru")
val mieruSources = fileTree(mieruRepoDir) {
    include("**/*.go", "go.mod", "go.sum", "LICENSE", "UPSTREAM_COMMIT")
    exclude("**/*_test.go")
}
val mieruJniLibsDir = layout.buildDirectory.dir("generated/mieru/jniLibs")
val buildMieruAndroid = tasks.register("buildMieruAndroid") {
    group = "build"
    description = "Builds the mieru Android SOCKS5 client subprocess for every packaged ABI."
}
androidAbiFilters.forEach { abi ->
    val (goarch, clang, goarm) = when (abi) {
        "arm64-v8a" -> Triple("arm64", "aarch64-linux-android23-clang", "")
        "armeabi-v7a" -> Triple("arm", "armv7a-linux-androideabi23-clang", "7")
        "x86_64" -> Triple("amd64", "x86_64-linux-android23-clang", "")
        "x86" -> Triple("386", "i686-linux-android23-clang", "")
        else -> error("Unsupported ABI for mieru: $abi")
    }
    val task = tasks.register<Exec>("buildMieruAndroid_$abi") {
        val output = mieruJniLibsDir.map { it.file("$abi/libmieru.so") }
        inputs.files(mieruSources)
        outputs.file(output)
        workingDir = mieruRepoDir
        environment("GOOS", "android")
        environment("GOARCH", goarch)
        environment("CGO_ENABLED", "1")
        environment(
            "CC",
            openfluxNdkDir.resolve("toolchains/llvm/prebuilt/$openfluxHostTag/bin/$clang$openfluxClangSuffix").absolutePath,
        )
        if (goarm.isNotEmpty()) environment("GOARM", goarm)
        commandLine(
            "go", "build", "-trimpath", "-ldflags", "-s -w",
            "-o", output.get().asFile.absolutePath, "./cmd/mieru",
        )
        doFirst { output.get().asFile.parentFile.mkdirs() }
    }
    buildMieruAndroid.configure { dependsOn(task) }
}
android.sourceSets.getByName("main").jniLibs.srcDir(mieruJniLibsDir.get().asFile)
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(buildMieruAndroid) }

// --- snolc client executable (../snolc, static, all modules linked in), packaged as lib/<abi>/libsnolc.so ---
// Prebuilt by snolc/build-all.sh (Rust 1.98.1 + the NDK) and committed; run as a SUBPROCESS from
// nativeLibraryDir like OpenFlux. ABIs without a build (x86, x86_64) simply ship without the engine.
val snolcPrebuiltDir = rootProject.layout.projectDirectory.asFile.parentFile.resolve("snolc/prebuilt")
val snolcJniLibsDir = layout.buildDirectory.dir("generated/snolc/jniLibs")
val copySnolcAndroid = tasks.register<Copy>("copySnolcAndroid") {
    val abis = mapOf("arm64-v8a" to "snolc-android-arm64", "armeabi-v7a" to "snolc-android-armv7")
    abis.forEach { (abi, name) ->
        from(snolcPrebuiltDir) {
            include(name)
            rename { "libsnolc.so" }
            eachFile { relativePath = RelativePath(true, abi, "libsnolc.so") }
        }
    }
    includeEmptyDirs = false
    into(snolcJniLibsDir)
}
android.sourceSets.getByName("main").jniLibs.srcDir(snolcJniLibsDir.get().asFile)
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(copySnolcAndroid) }

// --- Trust Tunnel client (../trusttunnel/prebuilt): the official static linux-aarch64 CLI, as lib/arm64-v8a/libtrusttunnel_client.so ---
// Run as a SUBPROCESS from nativeLibraryDir like snolc: the AAR's JNI cannot do SOCKS-only (it always wants a TUN fd).
// ABIs without a build ship without the engine (the AAR is still used for tt:// decoding).
val trustTunnelPrebuiltDir = rootProject.layout.projectDirectory.asFile.parentFile.resolve("trusttunnel/prebuilt")
val trustTunnelJniLibsDir = layout.buildDirectory.dir("generated/trusttunnel/jniLibs")
val copyTrustTunnelAndroid = tasks.register<Copy>("copyTrustTunnelAndroid") {
    from(trustTunnelPrebuiltDir) {
        include("trusttunnel-client-android-arm64")
        rename { "libtrusttunnel_client.so" }
        eachFile { relativePath = RelativePath(true, "arm64-v8a", "libtrusttunnel_client.so") }
    }
    includeEmptyDirs = false
    into(trustTunnelJniLibsDir)
}
android.sourceSets.getByName("main").jniLibs.srcDir(trustTunnelJniLibsDir.get().asFile)
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(copyTrustTunnelAndroid) }

// --- csqtt core (../csqtt): the Rust client + its Go bridge, as lib/<abi>/libcsqtt.so and libcsqtthost.so ---
// Prebuilt by csqtt/build-all.sh and committed (Rust + the NDK + Go are not CI requirements); both run as
// SUBPROCESSES from nativeLibraryDir like OpenFlux and snolc. An ABI without a build ships without the core.
val csqttPrebuiltDir = rootProject.layout.projectDirectory.asFile.parentFile.resolve("csqtt/prebuilt")
val csqttJniLibsDir = layout.buildDirectory.dir("generated/csqtt/jniLibs")
val copyCsqttAndroid = tasks.register<Copy>("copyCsqttAndroid") {
    val abis = mapOf("arm64-v8a" to "android-arm64", "armeabi-v7a" to "android-armv7", "x86_64" to "android-x86_64")
    abis.forEach { (abi, suffix) ->
        mapOf("csqtt-$suffix" to "libcsqtt.so", "csqtthost-$suffix" to "libcsqtthost.so").forEach { (name, lib) ->
            from(csqttPrebuiltDir) {
                include(name)
                rename { lib }
                eachFile { relativePath = RelativePath(true, abi, lib) }
            }
        }
    }
    includeEmptyDirs = false
    into(csqttJniLibsDir)
}
android.sourceSets.getByName("main").jniLibs.srcDir(csqttJniLibsDir.get().asFile)
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(copyCsqttAndroid) }

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
