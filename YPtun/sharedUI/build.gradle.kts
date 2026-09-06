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
val olcrtcIosXcframework = layout.buildDirectory.dir("generated/olcrtc/ios/OlcRtcMobile.xcframework")
val olcrtcIosXcframeworkDir = olcrtcIosXcframework.get().asFile
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
    // dnstt DNS-tunnel core (sibling replace module www.bamsoftware.com/git/dnstt.git); track its sources.
    inputs.dir(coresRepoDir.resolve("../dnstt"))
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
        "www.bamsoftware.com/git/dnstt.git/dnsttmobile",
        "github.com/olc/awgproxy/awg",
        "kazcores/xraybridge"
    )
}

val coresAndroidAarDependency = files(coresAndroidAarFile).builtBy(buildCoresAndroidAar)

val buildOlcrtcIosXcframework by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds olcrtc iOS XCFramework from OLCRTC_REPO using gomobile."

    inputs.dir(olcrtcRepoDir.resolve("mobile"))
    inputs.dir(olcrtcRepoDir.resolve("internal"))
    inputs.files(olcrtcRepoDir.resolve("go.mod"), olcrtcRepoDir.resolve("go.sum"))
    outputs.dir(olcrtcIosXcframework)

    workingDir = olcrtcRepoDir

    doFirst {
        delete(olcrtcIosXcframeworkDir)
        olcrtcIosXcframeworkDir.parentFile.mkdirs()
    }

    commandLine(
        "gomobile",
        "bind",
        "-target=ios",
        "-ldflags",
        "-s -w -checklinkname=0",
        "-o",
        olcrtcIosXcframeworkDir.absolutePath,
        "./mobile"
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

    sourceSets {
        commonMain {
            kotlin.srcDir(generateAppInfo)
        }

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
            implementation(libs.zxing.core)
            // SSH client for the one-tap WDTT-server VPS installer (WdttServerInstaller).
            // mwiede's maintained JSch fork: pure-Java, modern algorithms, no native deps.
            implementation("com.github.mwiede:jsch:0.2.21")
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
