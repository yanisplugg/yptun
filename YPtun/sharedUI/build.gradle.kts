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
val olcrtcAndroidAar = layout.buildDirectory.file("generated/olcrtc/olcrtc.aar")
val olcrtcAndroidAarFile = olcrtcAndroidAar.get().asFile
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

olcrtcAndroidAarFile.parentFile.mkdirs()

val buildOlcrtcAndroidAar by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds olcrtc Android AAR from OLCRTC_REPO using gomobile."

    inputs.dir(olcrtcRepoDir.resolve("mobile"))
    inputs.dir(olcrtcRepoDir.resolve("internal"))
    inputs.files(olcrtcRepoDir.resolve("go.mod"), olcrtcRepoDir.resolve("go.sum"))
    outputs.file(olcrtcAndroidAar)

    workingDir = olcrtcRepoDir
    commandLine(
        "gomobile",
        "bind",
        "-target=android/arm,android/arm64,android/amd64",
        "-androidapi",
        "21",
        "-ldflags",
        "-s -w -checklinkname=0",
        "-o",
        olcrtcAndroidAarFile.absolutePath,
        "./mobile"
    )
}

val olcrtcAndroidAarDependency = files(olcrtcAndroidAarFile).builtBy(buildOlcrtcAndroidAar)

// --- sing-box (libbox) Android AAR, built from SINGBOX_REPO via gomobile ---
// Mirrors the olcrtc build above. Clone github.com/SagerNet/sing-box (pinned v1.12.25,
// see SingBoxEngine.kt which targets that PlatformInterface) next to this repo, or set
// SINGBOX_REPO to its path.
val singboxRepoPath = providers.environmentVariable("SINGBOX_REPO")
    .orElse(rootProject.layout.projectDirectory.asFile.parentFile.resolve("sing-box").absolutePath)
val singboxRepoDir = rootProject.file(singboxRepoPath.get())
val libboxAndroidAar = layout.buildDirectory.file("generated/libbox/libbox.aar")
val libboxAndroidAarFile = libboxAndroidAar.get().asFile

// Build tags must include with_utls (uTLS fingerprints, e.g. fp=firefox) for the user's
// VLESS profiles; the rest mirror sing-box's own mobile build.
// NOTE: with_quic is intentionally OMITTED. xray-core pulls quic-go/qpack v0.5.x while
// sagernet/quic-go (sing-box) needs the older qpack API (DecodeFull); compiling both is
// impossible. Dropping with_quic excludes sing-box's QUIC code (hysteria2/tuic via sing-box)
// so the two quic forks no longer clash. VLESS/VMess/Trojan/SS + Xray (incl. QUIC/xhttp) are
// unaffected.
val libboxBuildTags = "with_gvisor,with_dhcp,with_wireguard,with_utls,with_clash_api"

// sing-box version embedded into libbox via ldflags (-X constant.Version); otherwise libbox.Version()
// reports "unknown". Keep in sync with the pinned sing-box checkout (v1.12.25, see comment above).
val singboxVersion = "1.12.25"

libboxAndroidAarFile.parentFile.mkdirs()

val buildLibboxAndroidAar by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds sing-box libbox Android AAR from SINGBOX_REPO using gomobile."

    inputs.files(singboxRepoDir.resolve("go.mod"), singboxRepoDir.resolve("go.sum"))
    inputs.property("tags", libboxBuildTags)
    outputs.file(libboxAndroidAar)

    workingDir = singboxRepoDir
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
        libboxAndroidAarFile.absolutePath,
        "./experimental/libbox"
    )
}

val libboxAndroidAarDependency = files(libboxAndroidAarFile).builtBy(buildLibboxAndroidAar)

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
    // AmneziaWG SOCKS bridge (sibling module) + its local amneziawg-go fork.
    inputs.dir(coresRepoDir.resolve("../awgproxy/awg"))
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
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.core)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kstore.file)
            implementation(libs.zxing.core)
            implementation(coresAndroidAarDependency)
        }

        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kstore.file)
            implementation(libs.jna)
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
