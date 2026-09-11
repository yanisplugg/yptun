import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.metro)
}

val olcrtcRepoPath = providers.environmentVariable("OLCRTC_REPO")
    .orElse(rootProject.layout.projectDirectory.asFile.parentFile.resolve("olcrtc").absolutePath)
val olcrtcRepoDir = rootProject.file(olcrtcRepoPath.get())
val olcboxVersion = providers.gradleProperty("olcbox.version").orElse("1.0.0")
val olcboxVersionValue = olcboxVersion.get()
val generatedAppInfoDir = layout.buildDirectory.dir("generated/source/olcboxAppInfo/commonMain")

abstract class GenerateAppInfoTask : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val packageDir = outputDir.get().asFile.resolve("org/olcbox/app")
        packageDir.mkdirs()
        val escapedVersion = version.get().replace("\\", "\\\\").replace("\"", "\\\"")
        packageDir.resolve("GeneratedAppInfo.kt").writeText(
            """
            package org.olcbox.app

            internal object GeneratedAppInfo {
                const val NAME: String = "YPtun"
                const val VERSION: String = "$escapedVersion"
            }
            """.trimIndent() + "\n"
        )
    }
}

// --- sing-box checkout, consumed by the combined cores AAR below ---
// Clone github.com/SagerNet/sing-box (pinned v1.14.0,
// see SingBoxEngine.kt which targets that PlatformInterface/CommandServer) next to this repo,
// or set SINGBOX_REPO to its path.
val singboxRepoPath = providers.environmentVariable("SINGBOX_REPO")
    .orElse(rootProject.layout.projectDirectory.asFile.parentFile.resolve("sing-box").absolutePath)
val singboxRepoDir = rootProject.file(singboxRepoPath.get())

// Build tags must include with_utls (uTLS fingerprints, e.g. fp=firefox) for the user's
// VLESS profiles; the rest mirror sing-box's own mobile build.
// with_quic is ON since sing-box 1.13: sagernet/quic-go v0.59 moved to qpack v0.6, so it no
// longer clashes with xray-core's apernet/quic-go — native hysteria2/TUIC via sing-box work.
// with_naive_outbound adds the NaïveProxy client (matches upstream SFA); it statically links
// Chromium cronet (libcronet.a, ~60 MB per ABI pre-strip) — drop the tag if size matters more.
val libboxBuildTags =
    "with_gvisor,with_dhcp,with_wireguard,with_utls,with_clash_api,with_quic,with_naive_outbound"

// sing-box version embedded into libbox via ldflags (-X constant.Version); otherwise libbox.Version()
// reports "unknown". Читается ИЗ вендоренного дерева (первый заголовок в docs/changelog.md), а не
// вбивается руками: константа уже разъехалась однажды — ядро обновили до 1.13.19, а в настройках
// приложения ещё висела 1.13.18. Через providers.fileContents, чтобы правка чейнджлога честно
// инвалидировала configuration cache.
val singboxChangelog = objects.fileProperty().fileValue(singboxRepoDir.resolve("docs/changelog.md"))
val singboxVersion: String = providers
    .fileContents(singboxChangelog)
    .asText
    .map { text ->
        text.lineSequence()
            .mapNotNull { Regex("""^####\s+(\d+\.\d+\.\d+\S*)\s*$""").find(it.trim())?.groupValues?.get(1) }
            .firstOrNull() ?: "unknown"
    }
    .getOrElse("unknown")

// --- Combined cores AAR: olcrtc (mobile) + sing-box (libbox) in ONE gomobile bind ---
// Two separate gomobile AARs would ship two Go runtimes (duplicate go.* classes + two
// libgojni.so) and crash. The sibling `cores` Go module requires both cores; binding both
// package paths in a single gomobile invocation yields one AAR with one shared Go runtime.
val coresRepoPath = providers.environmentVariable("CORES_REPO")
    .orElse(rootProject.layout.projectDirectory.asFile.parentFile.resolve("cores").absolutePath)
val coresRepoDir = rootProject.file(coresRepoPath.get())
val coresAndroidAar = layout.buildDirectory.file("generated/cores/cores.aar")
val coresAndroidAarFile = coresAndroidAar.get().asFile
coresAndroidAarFile.parentFile.mkdirs()

val buildCoresAndroidAar by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds one Android AAR with both olcrtc (mobile) and sing-box (libbox) via gomobile."

    inputs.files(
        coresRepoDir.resolve("go.mod"),
        coresRepoDir.resolve("go.sum"),
        coresRepoDir.resolve("bind.go")
    )
    inputs.dir(coresRepoDir.resolve("xraybridge"))
    // free-turn-proxy is a sibling replace module; track its bound sources so edits there
    // (e.g. the freeturn wrapper / relay) re-trigger the gomobile bind.
    inputs.dir(coresRepoDir.resolve("../free-turn-proxy/freeturn"))
    inputs.dir(coresRepoDir.resolve("../free-turn-proxy/internal"))
    // WDTT VK-TURN core (sibling replace module wg-turn-client); track its sources.
    inputs.dir(coresRepoDir.resolve("../wdtt"))
    // MasterDNS DNS-tunnel core (sibling replace module masterdnsvpn-go); track its sources.
    inputs.dir(coresRepoDir.resolve("../masterdns/internal"))
    inputs.dir(coresRepoDir.resolve("../masterdns/mdnsmobile"))
    // AmneziaWG SOCKS bridge (sibling module) + its local amneziawg-go fork.
    inputs.dir(coresRepoDir.resolve("../awgproxy/awg"))
    // NOTE: the old hysteria2proxy SOCKS bridge is gone — hysteria2 is native in sing-box
    // since the 1.13 upgrade (with_quic no longer clashes with xray's quic fork).
    // olcrtc (sibling replace module) bound packages — track so edits (e.g. telemost cookies)
    // re-trigger the bind.
    inputs.dir(olcrtcRepoDir.resolve("mobile"))
    inputs.dir(olcrtcRepoDir.resolve("internal"))
    inputs.property("tags", libboxBuildTags)
    inputs.property("singboxVersion", singboxVersion)
    outputs.file(coresAndroidAar)

    workingDir = coresRepoDir
    commandLine(
        "gomobile",
        "bind",
        "-target=android/arm,android/arm64,android/amd64",
        "-androidapi",
        "21",
        "-tags",
        libboxBuildTags,
        "-trimpath",
        "-ldflags",
        "-X github.com/sagernet/sing-box/constant.Version=$singboxVersion -s -w -checklinkname=0",
        "-o",
        coresAndroidAarFile.absolutePath,
        "github.com/openlibrecommunity/olcrtc/mobile",
        "github.com/sagernet/sing-box/experimental/libbox",
        "github.com/samosvalishe/free-turn-proxy/freeturn",
        "wg-turn-client/wdttmobile",
        "masterdnsvpn-go/mdnsmobile",
        "github.com/olc/awgproxy/awg",
        "kazcores/xraybridge"
    )
}

val coresAndroidAarDependency = files(coresAndroidAarFile).builtBy(buildCoresAndroidAar)

// iOS: every core through the flat kazcores/coreapi package (the same one the desktop DLL wraps), in
// ONE gomobile framework linked by both the app (pings) and the packet-tunnel extension. macOS + Xcode
// only. No with_gvisor (sing-box owns no TUN on iOS — hev does) and no with_naive_outbound (cronet
// has no iOS build here yet).
val coresIosBuildTags = "with_dhcp,with_wireguard,with_utls,with_clash_api,with_quic"
val coresIosXcframework = layout.buildDirectory.dir("generated/cores/ios/Coreapi.xcframework")

val buildCoresIosXcframework by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds Coreapi.xcframework (every core, kazcores/coreapi) for iOS via gomobile."

    inputs.files(coresRepoDir.resolve("go.mod"), coresRepoDir.resolve("go.sum"))
    inputs.dir(coresRepoDir.resolve("coreapi"))
    inputs.dir(coresRepoDir.resolve("../free-turn-proxy/freeturn"))
    inputs.dir(coresRepoDir.resolve("../free-turn-proxy/internal"))
    inputs.dir(coresRepoDir.resolve("../wdtt"))
    inputs.dir(coresRepoDir.resolve("../masterdns/internal"))
    inputs.dir(coresRepoDir.resolve("../masterdns/mdnsmobile"))
    inputs.dir(coresRepoDir.resolve("../awgproxy/awg"))
    inputs.dir(olcrtcRepoDir.resolve("mobile"))
    inputs.dir(olcrtcRepoDir.resolve("internal"))
    inputs.property("tags", coresIosBuildTags)
    inputs.property("singboxVersion", singboxVersion)
    outputs.dir(coresIosXcframework)

    workingDir = coresRepoDir
    val outDir = coresIosXcframework.get().asFile
    doFirst {
        delete(outDir)
        outDir.parentFile.mkdirs()
    }

    commandLine(
        "gomobile",
        "bind",
        "-target=ios",
        "-iosversion",
        "15.0",
        "-tags",
        coresIosBuildTags,
        "-trimpath",
        "-ldflags",
        "-X github.com/sagernet/sing-box/constant.Version=$singboxVersion -s -w -checklinkname=0",
        "-o",
        outDir.absolutePath,
        "./coreapi"
    )
}

val generateAppInfo by tasks.registering(GenerateAppInfoTask::class) {
    version.set(olcboxVersionValue)
    outputDir.set(generatedAppInfoDir)
}

kotlin {
    android {
        namespace = "org.olcbox.app.sharedui"
        compileSdk = 37
        minSdk = 23

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    macosArm64()
    iosArm64()
    iosSimulatorArm64()

    // The explicit jvmSharedMain edges below switch the default hierarchy off, and without it
    // iosArm64Main/iosSimulatorArm64Main never depend on iosMain — every iOS actual went missing.
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            kotlin.srcDir(generateAppInfo)
        }

        // Android and the desktop JVM share more than commonMain can hold: the VPS auto-installers
        // are plain JVM code (JSch over SSH + a shell script), and duplicating ~700 lines of them per
        // platform is how the two copies drift. Only the source of the bundled server binaries
        // differs (Android assets vs. classpath resources), and that is passed in.
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                // mwiede's maintained JSch fork: pure-Java, modern algorithms, no native deps.
                implementation("com.github.mwiede:jsch:0.2.21")
                // ssh-ed25519 / curve25519 / chacha20 for JSch on every platform (SshSupport pins JSch to
                // these). Android had none of them — its runtime reports Java < 15, where JSch needs BC —
                // so an Ed25519 key was never offered and the VPS auto-install failed with "Auth fail".
                implementation("org.bouncycastle:bcprov-jdk18on:1.86")
            }
        }
        androidMain.get().dependsOn(jvmSharedMain)
        jvmMain.get().dependsOn(jvmSharedMain)

        // Code the desktop and iOS share but Android does not: Android keeps its own copies of the
        // settings screens (they grew Android-only features), the desktop port of them is what iOS
        // reuses. Platform bits go through SettingsPlatform (expect/actual).
        val nonAndroidMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(nonAndroidMain)
        iosMain.get().dependsOn(nonAndroidMain)

        commonMain.dependencies {
            api(libs.compose.runtime)
            api(libs.compose.ui)
            api(libs.compose.foundation)
            api(libs.compose.resources)
            api(libs.compose.ui.tooling.preview)
            api(libs.compose.material3)

            implementation(compose.materialIconsExtended)
            implementation(libs.kermit)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.serialization)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.client.logging)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.multiplatformSettings)
            implementation(libs.kstore)
            implementation(libs.materialKolor)
            implementation(libs.androidx.datastore.preferences)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.compose.ui.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }

        androidMain.dependencies {
            implementation(libs.androidx.activityCompose)
            implementation(libs.androidx.core)
            // Vendored Google archive-patcher (File-by-File v1 applier) for delta app updates.
            implementation(project(":archivepatcher"))
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.core)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kstore.file)
            // zxing-core only draws share QRs; the camera scanner decodes with native zxing-cpp.
            implementation(libs.zxing.core)
            implementation(libs.zxing.cpp)
            implementation(coresAndroidAarDependency)
            // Trust Tunnel (AdGuard) client — vendored prebuilt AAR (com.adguard.trusttunnel:
            // trusttunnel-client-android:1.1.5-rc.1) carrying libtrusttunnel_android.so (all ABIs) +
            // the VpnClient/DeepLink JNI adapter. Isolated engine; does NOT touch the Go cores AAR.
            implementation(files("libs/trusttunnel-client-android-1.1.5-rc.1.aar"))
        }

        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kstore.file)
            implementation(libs.jna)
            // QR decoding for «сканировать QR» on desktop (a picked image, there being no camera).
            implementation(libs.zxing.core)
            // IPHlpAPI.GetIfEntry2 — the tunnel adapter's byte counters for the Home speed line.
            implementation(libs.jna.platform)
            // Vendored Google archive-patcher (File-by-File v1 applier): desktop delta updates
            // patch the installed app jar instead of re-downloading the ~160 MB installer.
            implementation(project(":archivepatcher"))
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.kstore.file)
        }

        macosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.kstore.file)
        }
    }

    targets
        .withType<KotlinNativeTarget>()
        .matching { it.konanTarget.family.isAppleFamily }
        .configureEach {
            binaries {
                framework {
                    baseName = "SharedUI"
                    isStatic = true
                }
            }
        }
}


// Forwards -Dyptun.dumpConfigs=<dir> into the test JVM, so DumpDesktopConfigsTest can write the
// generated sing-box configs out for a real `sing-box check` sweep (see singbox-114 notes).
tasks.withType<Test>().configureEach {
    System.getProperty("yptun.dumpConfigs")?.let {
        systemProperty("yptun.dumpConfigs", it)
        outputs.upToDateWhen { false } // a dump run must re-run even when nothing changed
    }
}

/** Same Compose-train pin as desktopApp, for the jvm target's own classpaths. */
configurations.matching { it.name.startsWith("jvm") }.configureEach {
    val pinned = libs.versions.compose.multiplatform.get()
    resolutionStrategy.eachDependency {
        // Only the drifting train, not material-icons-extended, which is frozen at 1.7.3.
        if (requested.group.startsWith("org.jetbrains.compose") &&
            requested.version.orEmpty().startsWith(pinned.substringBefore('-'))
        ) {
            useVersion(pinned)
            because("material3 has no 1.12.0 release; a mixed train breaks OutlinedTextField")
        }
    }
}
