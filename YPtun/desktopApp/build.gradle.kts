import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.os.OperatingSystem
import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.net.URI
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":sharedUI"))
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.jna)
    implementation(libs.jna.platform) // Win32 RegisterHotKey for the global hotkey
    implementation(libs.zxing.core)
    // Material icons used by the custom tray menu (sharedUI keeps them internal).
    implementation(compose.materialIconsExtended)
}

abstract class DownloadFileTask : DefaultTask() {
    @get:Input
    abstract val sourceUrl: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun download() {
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        URI(sourceUrl.get())
            .toURL()
            .openStream()
            .use { input ->
                output.outputStream().use { outputStream ->
                    input.copyTo(outputStream)
                }
            }
    }
}

abstract class ExtractZipEntryTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val zipFile: RegularFileProperty

    @get:Input
    abstract val entrySuffix: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun extract() {
        val zip = zipFile.get().asFile
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()

        ZipFile(zip).use { archive ->
            val entry = archive.entries().asSequence()
                .firstOrNull { it.name.endsWith(entrySuffix.get()) }
                ?: error("${entrySuffix.get()} entry was not found in ${zip.absolutePath}")

            archive.getInputStream(entry).use { input ->
                output.outputStream().use { outputStream ->
                    input.copyTo(outputStream)
                }
            }
        }
    }
}

/**
 * Pulls one entry out of a .tar.gz. The JDK has gzip but no tar, and the build script has no
 * commons-compress on its classpath, so the (trivial) tar header is read by hand: 512-byte header
 * blocks, name at 0, size as octal at 124, payload padded up to the next 512-byte boundary. Good
 * enough for the release archives we consume, which are flat and use short paths (no GNU longname).
 */
abstract class ExtractTarGzEntryTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val tarGzFile: RegularFileProperty

    @get:Input
    abstract val entrySuffix: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun extract() {
        val archive = tarGzFile.get().asFile
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()

        GZIPInputStream(archive.inputStream().buffered()).use { input ->
            val header = ByteArray(512)
            while (true) {
                if (input.readNBytes(header, 0, 512) != 512) break
                // Two consecutive zero blocks terminate the archive; one is enough to stop here.
                if (header.all { it == 0.toByte() }) break

                val name = String(header, 0, 100, Charsets.UTF_8).substringBefore('\u0000')
                val size = String(header, 124, 12, Charsets.UTF_8)
                    .trim('\u0000', ' ')
                    .ifEmpty { "0" }
                    .toLong(8)

                if (name.endsWith(entrySuffix.get())) {
                    output.outputStream().use { out ->
                        var remaining = size
                        val buffer = ByteArray(64 * 1024)
                        while (remaining > 0) {
                            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (read <= 0) error("Truncated archive: ${archive.absolutePath}")
                            out.write(buffer, 0, read)
                            remaining -= read
                        }
                    }
                    return
                }

                // Skip the payload plus its padding to land on the next header.
                var toSkip = size + ((512 - size % 512) % 512)
                while (toSkip > 0) {
                    val skipped = input.skip(toSkip)
                    if (skipped <= 0) error("Truncated archive: ${archive.absolutePath}")
                    toSkip -= skipped
                }
            }
        }

        error("${entrySuffix.get()} entry was not found in ${archive.absolutePath}")
    }
}

/**
 * Copies one file out of a Go module in the local module cache.
 *
 * Used for cronet's shared library (NaïveProxy): it is published as a per-platform Go module whose
 * only real content is the binary, and `go list -m -f {{.Dir}}` is the supported way to ask where
 * the cache put it.
 */
abstract class CopyGoModuleFileTask : DefaultTask() {
    @get:Input
    abstract val moduleName: Property<String>

    @get:Input
    abstract val fileName: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val goModFile: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun copy() {
        val workDir = goModFile.get().asFile.parentFile
        val process = ProcessBuilder("go", "list", "-m", "-f", "{{.Dir}}", moduleName.get())
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        check(process.waitFor() == 0 && output.isNotEmpty()) {
            "go list -m ${moduleName.get()} failed: $output"
        }
        val source = File(output.lines().last().trim(), fileName.get())
        check(source.isFile) { "${source.absolutePath} not found in the Go module cache" }
        val target = outputFile.get().asFile
        target.parentFile.mkdirs()
        source.copyTo(target, overwrite = true)
    }
}

abstract class VerifyNativeResourcesTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resourcesDir: DirectoryProperty

    @get:Input
    abstract val requiredPaths: ListProperty<String>

    @TaskAction
    fun verify() {
        val root = resourcesDir.get().asFile
        val missing = requiredPaths.get()
            .map { root.resolve(it) }
            .filterNot { it.isFile }

        require(missing.isEmpty()) {
            "Missing desktop native resources:\n" +
                    missing.joinToString(separator = "\n") { "- ${it.relativeTo(root).invariantSeparatorsPath}" }
        }
    }
}

val defaultOlcRtcRepo = rootProject.layout.projectDirectory.asFile.parentFile
    .resolve("olcrtc")
    .absolutePath
val olcrtcRepo = providers.environmentVariable("OLCRTC_REPO")
    .orElse(defaultOlcRtcRepo)
val olcrtcRepoDir = olcrtcRepo.map { rootProject.file(it) }
val generatedNativeResources = layout.buildDirectory.dir("generated/desktopNativeResources")
val hevSocks5TunnelSourceDir = rootProject.layout.projectDirectory.dir("androidApp/src/main/jni/hev-socks5-tunnel")
val currentBuildOs = OperatingSystem.current()
val desktopPackageName = "YPtun"
val desktopPackageVersion = providers.gradleProperty("olcbox.version").orElse("1.0.0").get()
val tun2SocksVersion = "2.6.0"
val wintunVersion = "0.14.1"

val currentBuildTargetFormats = when {
    currentBuildOs.isMacOsX -> arrayOf(TargetFormat.Dmg)
    currentBuildOs.isWindows -> arrayOf(TargetFormat.Exe, TargetFormat.Msi)
    // Deb is the shipped Linux installer (Debian + Ubuntu). AppImage stays for the existing
    // packageReleaseLinuxAppImage path so nothing that relied on it breaks.
    currentBuildOs.isLinux -> arrayOf(TargetFormat.Deb, TargetFormat.AppImage)
    else -> emptyArray()
}

fun desktopArchName(arch: String): String = when (arch.lowercase()) {
    "x86_64", "amd64" -> "amd64"
    "aarch64", "arm64" -> "arm64"
    else -> error("Unsupported desktop architecture: $arch")
}

fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

val hostDesktopArch = desktopArchName(System.getProperty("os.arch"))

// AdGuard Trust Tunnel CLI client. Android links the prebuilt AAR (libtrusttunnel_android.so), which
// is Android-only, so desktop uses the official release binaries instead and drives them as a
// subprocess in SOCKS-only mode — the same shape as the olcrtc/hev-socks5-tunnel assets. The archive
// also carries setup_wizard, which is how a tt:// deep link gets decoded without the AAR's
// DeepLink.decode. Releases name architectures the GNU way, hence the mapping below.
val trustTunnelVersion = "1.0.49"
val trustTunnelArch = when (hostDesktopArch) {
    "amd64" -> "x86_64"
    "arm64" -> "aarch64"
    else -> error("Unsupported desktop architecture for Trust Tunnel: $hostDesktopArch")
}

fun registerOlcRtcBuildTask(
    taskName: String,
    goos: String,
    goarch: String,
    outputName: String
) = tasks.register<Exec>(taskName) {
    val outputFile = generatedNativeResources.map { it.file("native/$outputName") }

    // Without an input the task is "up to date" for as long as the output file merely
    // exists, so a re-vendored olcrtc would ship as a weeks-old binary.
    inputs.dir(olcrtcRepoDir.get())
    outputs.file(outputFile)
    workingDir = olcrtcRepoDir.get()
    environment("GOOS", goos)
    environment("GOARCH", goarch)
    environment("CGO_ENABLED", "0")
    commandLine(
        "go",
        "build",
        "-trimpath",
        "-ldflags",
        "-s -w",
        "-o",
        outputFile.get().asFile.absolutePath,
        "./cmd/olcrtc"
    )

    doFirst {
        outputFile.get().asFile.parentFile.mkdirs()
    }
}

fun registerOlcRtcLibraryBuildTask(
    taskName: String,
    goos: String,
    goarch: String,
    outputName: String
) = tasks.register<Exec>(taskName) {
    val outputFile = generatedNativeResources.map { it.file("native/$outputName") }

    // Without an input the task is "up to date" for as long as the output file merely
    // exists, so a re-vendored olcrtc would ship as a weeks-old binary.
    inputs.dir(olcrtcRepoDir.get())
    outputs.file(outputFile)
    workingDir = olcrtcRepoDir.get()
    environment("GOOS", goos)
    environment("GOARCH", goarch)
    environment("CGO_ENABLED", "1")
    commandLine(
        "go",
        "build",
        "-buildmode=c-shared",
        "-trimpath",
        "-ldflags",
        "-s -w",
        "-o",
        outputFile.get().asFile.absolutePath,
        "./cmd/olcrtc-cgo"
    )

    doFirst {
        outputFile.get().asFile.parentFile.mkdirs()
    }
}

val buildOlcRtcDarwinArm64 = registerOlcRtcBuildTask(
    taskName = "buildOlcRtcDarwinArm64",
    goos = "darwin",
    goarch = "arm64",
    outputName = "olcrtc-darwin-arm64"
)

val buildOlcRtcDarwinAmd64 = registerOlcRtcBuildTask(
    taskName = "buildOlcRtcDarwinAmd64",
    goos = "darwin",
    goarch = "amd64",
    outputName = "olcrtc-darwin-amd64"
)

val buildOlcRtcWindowsAmd64 = registerOlcRtcBuildTask(
    taskName = "buildOlcRtcWindowsAmd64",
    goos = "windows",
    goarch = "amd64",
    outputName = "olcrtc-windows-amd64.exe"
)

val buildOlcRtcWindowsArm64 = registerOlcRtcBuildTask(
    taskName = "buildOlcRtcWindowsArm64",
    goos = "windows",
    goarch = "arm64",
    outputName = "olcrtc-windows-arm64.exe"
)

val buildOlcRtcLinuxAmd64 = registerOlcRtcBuildTask(
    taskName = "buildOlcRtcLinuxAmd64",
    goos = "linux",
    goarch = "amd64",
    outputName = "olcrtc-linux-amd64"
)

val buildOlcRtcLinuxArm64 = registerOlcRtcBuildTask(
    taskName = "buildOlcRtcLinuxArm64",
    goos = "linux",
    goarch = "arm64",
    outputName = "olcrtc-linux-arm64"
)

val buildOlcRtcLibDarwinArm64 = registerOlcRtcLibraryBuildTask(
    taskName = "buildOlcRtcLibDarwinArm64",
    goos = "darwin",
    goarch = "arm64",
    outputName = "libolcrtc-darwin-arm64.dylib"
)

val buildOlcRtcLibDarwinAmd64 = registerOlcRtcLibraryBuildTask(
    taskName = "buildOlcRtcLibDarwinAmd64",
    goos = "darwin",
    goarch = "amd64",
    outputName = "libolcrtc-darwin-amd64.dylib"
)

val buildOlcRtcLibLinuxAmd64 = registerOlcRtcLibraryBuildTask(
    taskName = "buildOlcRtcLibLinuxAmd64",
    goos = "linux",
    goarch = "amd64",
    outputName = "libolcrtc-linux-amd64.so"
)

val buildOlcRtcLibLinuxArm64 = registerOlcRtcLibraryBuildTask(
    taskName = "buildOlcRtcLibLinuxArm64",
    goos = "linux",
    goarch = "arm64",
    outputName = "libolcrtc-linux-arm64.so"
)

val buildOlcRtcLibWindowsAmd64 = registerOlcRtcLibraryBuildTask(
    taskName = "buildOlcRtcLibWindowsAmd64",
    goos = "windows",
    goarch = "amd64",
    outputName = "olcrtc-windows-amd64.dll"
)

val buildOlcRtcLibWindowsArm64 = registerOlcRtcLibraryBuildTask(
    taskName = "buildOlcRtcLibWindowsArm64",
    goos = "windows",
    goarch = "arm64",
    outputName = "olcrtc-windows-arm64.dll"
)

val copyOlcRtcDataAssets = tasks.register<Copy>("copyOlcRtcDataAssets") {
    from(olcrtcRepoDir.map { it.resolve("data") }) {
        include("names", "surnames")
    }
    into(generatedNativeResources.map { it.dir("olcrtc-data") })
}

val desktopNativeAssetTasks = mutableListOf<Any>(
    buildOlcRtcDarwinArm64,
    buildOlcRtcDarwinAmd64,
    buildOlcRtcWindowsAmd64,
    buildOlcRtcWindowsArm64,
    buildOlcRtcLinuxAmd64,
    buildOlcRtcLinuxArm64,
    buildOlcRtcLibDarwinArm64,
    buildOlcRtcLibDarwinAmd64,
    buildOlcRtcLibLinuxAmd64,
    buildOlcRtcLibLinuxArm64,
    buildOlcRtcLibWindowsAmd64,
    buildOlcRtcLibWindowsArm64,
    copyOlcRtcDataAssets
)
val hostDesktopNativeAssetTasks = mutableListOf<Any>(
    copyOlcRtcDataAssets
)

when {
    currentBuildOs.isMacOsX -> when (hostDesktopArch) {
        "amd64" -> {
            hostDesktopNativeAssetTasks.add(buildOlcRtcDarwinAmd64)
            hostDesktopNativeAssetTasks.add(buildOlcRtcLibDarwinAmd64)
        }
        "arm64" -> {
            hostDesktopNativeAssetTasks.add(buildOlcRtcDarwinArm64)
            hostDesktopNativeAssetTasks.add(buildOlcRtcLibDarwinArm64)
        }
    }
    currentBuildOs.isWindows -> when (hostDesktopArch) {
        "amd64" -> {
            hostDesktopNativeAssetTasks.add(buildOlcRtcWindowsAmd64)
            hostDesktopNativeAssetTasks.add(buildOlcRtcLibWindowsAmd64)
        }
        "arm64" -> {
            hostDesktopNativeAssetTasks.add(buildOlcRtcWindowsArm64)
            hostDesktopNativeAssetTasks.add(buildOlcRtcLibWindowsArm64)
        }
    }
    currentBuildOs.isLinux -> when (hostDesktopArch) {
        "amd64" -> {
            hostDesktopNativeAssetTasks.add(buildOlcRtcLinuxAmd64)
            hostDesktopNativeAssetTasks.add(buildOlcRtcLibLinuxAmd64)
        }
        "arm64" -> {
            hostDesktopNativeAssetTasks.add(buildOlcRtcLinuxArm64)
            hostDesktopNativeAssetTasks.add(buildOlcRtcLibLinuxArm64)
        }
    }
}

if (currentBuildOs.isLinux) {
    val buildHevSocks5TunnelLinux = tasks.register<Exec>("buildHevSocks5TunnelLinux") {
        val outputFile = generatedNativeResources.map {
            it.file("native/hev-socks5-tunnel-linux-$hostDesktopArch")
        }
        val output = outputFile.get().asFile

        outputs.file(outputFile)
        workingDir = hevSocks5TunnelSourceDir.asFile
        commandLine(
            "sh",
            "-c",
            "mkdir -p ${shellQuote(output.parentFile.absolutePath)} && make clean exec && install -m 0755 bin/hev-socks5-tunnel ${shellQuote(output.absolutePath)}"
        )
    }
    desktopNativeAssetTasks.add(buildHevSocks5TunnelLinux)
    hostDesktopNativeAssetTasks.add(buildHevSocks5TunnelLinux)
}

// yptuncore: every proxy core (sing-box, xray, AmneziaWG, Hysteria2, VK-TURN, olcrtc) compiled
// into ONE c-shared library from cores/cmd/yptuncore, consumed from jvmMain via JNA. Mirrors the
// Android gomobile AAR (one shared Go runtime). Same build tags as the Android libbox build.
// with_quic is NOT an optional extra: without it sing-box still registers hysteria/hysteria2/TUIC,
// but as stubs that fail every dial with ErrQUICNotIncluded (see sing-box include/quic_stub.go), and
// DNS-over-QUIC/HTTP3 go with them. It was dropped here back when it clashed with xray-core's quic
// fork; sing-box 1.13 moved to sagernet/quic-go v0.59 (qpack v0.6) and the clash is gone, which is
// why sharedUI already ships it.
//
// with_naive_outbound (NaïveProxy) rides on with_purego, which is what makes it work on desktop at
// all. The earlier attempt used the default cgo path and was blocked on BOTH targets: on Windows the
// cronet windows_* modules ship no static archive, and on Linux libcronet.a is built with CREL
// relocations (`.crel.text`) that the GNU ld on ubuntu-22.04 (binutils 2.38) rejects outright.
// with_purego sidesteps both: cronet is then a SHARED library (libcronet.dll / libcronet.so) loaded
// at runtime, so nothing is statically linked. Its loader finds the library by name in the exe
// directory / PATH / LD_LIBRARY_PATH, none of which reach inside our app jar — so the library is
// bundled as a native resource, unpacked next to the other natives, and the core is pointed at that
// directory via YpAddNativeSearchPath (see copyCronet* below and DesktopNativeAssets).
//
// Android is unaffected: it links cronet's android_* archives with the NDK toolchain.
val ypTunCoreBuildTags =
    "with_gvisor,with_dhcp,with_wireguard,with_utls,with_clash_api,with_quic," +
        "with_naive_outbound,with_purego"
val coresRepoDir = rootProject.layout.projectDirectory.asFile.parentFile.resolve("cores")

// sing-box version embedded via ldflags (-X constant.Version); otherwise YpSbVersion() reports
// "unknown" and the settings screen has to guess. Keep in sync with sharedUI's singboxVersion and
// the sing-box version pinned in cores/go.mod.
val ypTunCoreSingboxVersion = "1.13.18"

/**
 * cronet, NaïveProxy's engine, taken from the Go module cache and shipped as a native resource.
 * The core's `with_purego` loader opens it by name at dial time (see ypTunCoreBuildTags).
 */
fun registerCronetCopyTask(goos: String, goarch: String, fileName: String) =
    tasks.register<CopyGoModuleFileTask>(
        "copyCronet${goos.replaceFirstChar { it.uppercase() }}${goarch.replaceFirstChar { it.uppercase() }}"
    ) {
        moduleName.set("github.com/sagernet/cronet-go/lib/${goos}_$goarch")
        this.fileName.set(fileName)
        goModFile.set(layout.file(provider { coresRepoDir.resolve("go.mod") }))
        outputFile.set(generatedNativeResources.map { it.file("native/$fileName") })
    }

fun registerYpTunCoreBuildTask(
    taskName: String,
    goos: String,
    goarch: String,
    outputName: String
) = tasks.register<Exec>(taskName) {
    val outputFile = generatedNativeResources.map { it.file("native/$outputName") }

    inputs.dir(coresRepoDir.resolve("cmd"))
    inputs.file(coresRepoDir.resolve("go.mod"))
    // olcrtc/mobile is compiled into this core through the path-replace in
    // cores/go.mod, so a core re-sync has to invalidate it as well.
    inputs.dir(olcrtcRepoDir.get())
    inputs.property("tags", ypTunCoreBuildTags)
    inputs.property("singboxVersion", ypTunCoreSingboxVersion)
    outputs.file(outputFile)
    workingDir = coresRepoDir
    environment("GOOS", goos)
    environment("GOARCH", goarch)
    environment("CGO_ENABLED", "1")
    commandLine(
        "go",
        "build",
        "-buildmode=c-shared",
        "-trimpath",
        "-tags",
        ypTunCoreBuildTags,
        "-ldflags",
        "-X github.com/sagernet/sing-box/constant.Version=$ypTunCoreSingboxVersion -s -w",
        "-o",
        outputFile.get().asFile.absolutePath,
        "./cmd/yptuncore"
    )

    doFirst {
        outputFile.get().asFile.parentFile.mkdirs()
    }
}

// Linux: the same one-runtime core as Windows, as a c-shared .so. Without it YpTunCore.isAvailable
// is false, so DesktopEngineController reports isSupported=false and every engine falls back to the
// olcrtc subprocess - i.e. no sing-box/Xray/AmneziaWG/VK-TURN at all. Host arch only (CGo).
if (currentBuildOs.isLinux) {
    val buildYpTunCoreLinux = registerYpTunCoreBuildTask(
        taskName = "buildYpTunCoreLinux${hostDesktopArch.replaceFirstChar { it.uppercase() }}",
        goos = "linux",
        goarch = hostDesktopArch,
        outputName = "yptuncore-linux-$hostDesktopArch.so"
    )
    desktopNativeAssetTasks.add(buildYpTunCoreLinux)
    hostDesktopNativeAssetTasks.add(buildYpTunCoreLinux)

    val copyCronetLinux = registerCronetCopyTask("linux", hostDesktopArch, "libcronet.so")
    desktopNativeAssetTasks.add(copyCronetLinux)
    hostDesktopNativeAssetTasks.add(copyCronetLinux)

    val downloadTrustTunnelLinux = tasks.register<DownloadFileTask>("downloadTrustTunnelLinux") {
        sourceUrl.set(
            "https://github.com/TrustTunnel/TrustTunnelClient/releases/download/" +
                "v$trustTunnelVersion/trusttunnel_client-v$trustTunnelVersion-linux-$trustTunnelArch.tar.gz"
        )
        outputFile.set(
            layout.buildDirectory.file("tmp/trusttunnel/trusttunnel-linux-$trustTunnelArch-$trustTunnelVersion.tar.gz")
        )
    }

    val extractTrustTunnelClientLinux = tasks.register<ExtractTarGzEntryTask>("extractTrustTunnelClientLinux") {
        tarGzFile.set(downloadTrustTunnelLinux.flatMap { it.outputFile })
        entrySuffix.set("/trusttunnel_client")
        outputFile.set(generatedNativeResources.map { it.file("native/trusttunnel-client-linux-$hostDesktopArch") })
    }

    val extractTrustTunnelWizardLinux = tasks.register<ExtractTarGzEntryTask>("extractTrustTunnelWizardLinux") {
        tarGzFile.set(downloadTrustTunnelLinux.flatMap { it.outputFile })
        entrySuffix.set("/setup_wizard")
        outputFile.set(generatedNativeResources.map { it.file("native/trusttunnel-wizard-linux-$hostDesktopArch") })
    }

    desktopNativeAssetTasks.add(extractTrustTunnelClientLinux)
    desktopNativeAssetTasks.add(extractTrustTunnelWizardLinux)
    hostDesktopNativeAssetTasks.add(extractTrustTunnelClientLinux)
    hostDesktopNativeAssetTasks.add(extractTrustTunnelWizardLinux)
}

// Windows natives are built/downloaded for the HOST arch only: the cores are CGo (a c-shared .dll),
// so cross-building them needs a full cross C toolchain. amd64 comes off a normal runner, arm64 off a
// native windows-11-arm runner (see .github/workflows/windows-arm64.yml).
if (currentBuildOs.isWindows) {
    val buildYpTunCoreWindows = registerYpTunCoreBuildTask(
        taskName = "buildYpTunCoreWindows${hostDesktopArch.replaceFirstChar { it.uppercase() }}",
        goos = "windows",
        goarch = hostDesktopArch,
        outputName = "yptuncore-windows-$hostDesktopArch.dll"
    )
    desktopNativeAssetTasks.add(buildYpTunCoreWindows)
    hostDesktopNativeAssetTasks.add(buildYpTunCoreWindows)

    val copyCronetWindows = registerCronetCopyTask("windows", hostDesktopArch, "libcronet.dll")
    desktopNativeAssetTasks.add(copyCronetWindows)
    hostDesktopNativeAssetTasks.add(copyCronetWindows)

    val tun2SocksWindowsOutput = generatedNativeResources.map {
        it.file("native/tun2socks-windows-$hostDesktopArch.exe")
    }
    val wintunWindowsOutput = generatedNativeResources.map {
        it.file("native/wintun.dll")
    }

    val downloadTun2SocksWindows = tasks.register<DownloadFileTask>("downloadTun2SocksWindows") {
        sourceUrl.set("https://github.com/xjasonlyu/tun2socks/releases/download/v$tun2SocksVersion/tun2socks-windows-$hostDesktopArch.zip")
        outputFile.set(layout.buildDirectory.file("tmp/tun2socks/tun2socks-windows-$hostDesktopArch-$tun2SocksVersion.zip"))
    }

    val extractTun2SocksWindows = tasks.register<ExtractZipEntryTask>("extractTun2SocksWindows") {
        zipFile.set(downloadTun2SocksWindows.flatMap { it.outputFile })
        entrySuffix.set("tun2socks-windows-$hostDesktopArch.exe")
        outputFile.set(tun2SocksWindowsOutput)
    }

    val downloadWintunWindows = tasks.register<DownloadFileTask>("downloadWintunWindows") {
        sourceUrl.set("https://www.wintun.net/builds/wintun-$wintunVersion.zip")
        outputFile.set(layout.buildDirectory.file("tmp/wintun/wintun-$wintunVersion.zip"))
    }

    val extractWintunWindows = tasks.register<ExtractZipEntryTask>("extractWintunWindows") {
        zipFile.set(downloadWintunWindows.flatMap { it.outputFile })
        // The wintun archive ships one DLL per arch; pick the host's.
        entrySuffix.set("/bin/$hostDesktopArch/wintun.dll")
        outputFile.set(wintunWindowsOutput)
    }

    val downloadTrustTunnelWindows = tasks.register<DownloadFileTask>("downloadTrustTunnelWindows") {
        sourceUrl.set(
            "https://github.com/TrustTunnel/TrustTunnelClient/releases/download/" +
                "v$trustTunnelVersion/trusttunnel_client-v$trustTunnelVersion-windows-$trustTunnelArch.zip"
        )
        outputFile.set(
            layout.buildDirectory.file("tmp/trusttunnel/trusttunnel-windows-$trustTunnelArch-$trustTunnelVersion.zip")
        )
    }

    val extractTrustTunnelClientWindows = tasks.register<ExtractZipEntryTask>("extractTrustTunnelClientWindows") {
        zipFile.set(downloadTrustTunnelWindows.flatMap { it.outputFile })
        // The Windows archive is flat, unlike the Linux tarball which nests everything one level deep.
        entrySuffix.set("trusttunnel_client.exe")
        outputFile.set(
            generatedNativeResources.map { it.file("native/trusttunnel-client-windows-$hostDesktopArch.exe") }
        )
    }

    val extractTrustTunnelWizardWindows = tasks.register<ExtractZipEntryTask>("extractTrustTunnelWizardWindows") {
        zipFile.set(downloadTrustTunnelWindows.flatMap { it.outputFile })
        entrySuffix.set("setup_wizard.exe")
        outputFile.set(
            generatedNativeResources.map { it.file("native/trusttunnel-wizard-windows-$hostDesktopArch.exe") }
        )
    }

    desktopNativeAssetTasks.add(extractTun2SocksWindows)
    desktopNativeAssetTasks.add(extractWintunWindows)
    desktopNativeAssetTasks.add(extractTrustTunnelClientWindows)
    desktopNativeAssetTasks.add(extractTrustTunnelWizardWindows)
    hostDesktopNativeAssetTasks.add(extractTun2SocksWindows)
    hostDesktopNativeAssetTasks.add(extractWintunWindows)
    hostDesktopNativeAssetTasks.add(extractTrustTunnelClientWindows)
    hostDesktopNativeAssetTasks.add(extractTrustTunnelWizardWindows)
}

fun requiredHostNativeResourcePaths(): List<String> = buildList {
    add("olcrtc-data/names")
    add("olcrtc-data/surnames")
    when {
        currentBuildOs.isMacOsX -> {
            add("native/olcrtc-darwin-$hostDesktopArch")
            add("native/libolcrtc-darwin-$hostDesktopArch.dylib")
        }
        currentBuildOs.isWindows -> {
            add("native/olcrtc-windows-$hostDesktopArch.exe")
            add("native/olcrtc-windows-$hostDesktopArch.dll")
            add("native/tun2socks-windows-$hostDesktopArch.exe")
            add("native/wintun.dll")
            add("native/yptuncore-windows-$hostDesktopArch.dll")
            add("native/trusttunnel-client-windows-$hostDesktopArch.exe")
            add("native/trusttunnel-wizard-windows-$hostDesktopArch.exe")
        }
        currentBuildOs.isLinux -> {
            add("native/olcrtc-linux-$hostDesktopArch")
            add("native/libolcrtc-linux-$hostDesktopArch.so")
            add("native/hev-socks5-tunnel-linux-$hostDesktopArch")
            add("native/yptuncore-linux-$hostDesktopArch.so")
            add("native/trusttunnel-client-linux-$hostDesktopArch")
            add("native/trusttunnel-wizard-linux-$hostDesktopArch")
        }
    }
}

val verifyDesktopNativeResources = tasks.register<VerifyNativeResourcesTask>("verifyDesktopNativeResources") {
    dependsOn(hostDesktopNativeAssetTasks.toList())
    resourcesDir.set(generatedNativeResources)
    requiredPaths.set(requiredHostNativeResourcePaths())
}

tasks.register("buildDesktopNativeAssets") {
    dependsOn(desktopNativeAssetTasks)
    dependsOn(verifyDesktopNativeResources)
}

sourceSets {
    main {
        resources.srcDir(generatedNativeResources)
        resources.srcDir(layout.projectDirectory.dir("appIcons"))
    }
}

if (currentBuildOs.isWindows) {
    val jpackageAppRootDir = layout.buildDirectory.dir("compose/binaries/main-release/app")

    tasks.register<Zip>("packageReleasePortableZip") {
        group = "distribution"
        description = "Packages a portable Windows zip from the jpackage app image."

        dependsOn("createReleaseDistributable")
        from(jpackageAppRootDir)
        archiveFileName.set("$desktopPackageName-$desktopPackageVersion-windows-$hostDesktopArch-portable.zip")
        destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/main-release/portable"))

        doFirst {
            val appRoot = jpackageAppRootDir.get().asFile
            val appEntries = appRoot.listFiles().orEmpty()
            require(appRoot.isDirectory && appEntries.isNotEmpty()) {
                "Windows portable app image was not created at ${appRoot.absolutePath}"
            }
        }
    }

    /**
     * The portable the user actually asked for: ONE .exe, no folder, no re-unpacking on every start.
     * A native launcher carries the app image appended to itself and unpacks it once into
     * %LOCALAPPDATA%\YPtun\portable\<version> — see packaging/windows/build-portable.ps1.
     */
    tasks.register<Exec>("packageReleasePortableExe") {
        group = "distribution"
        description = "Packages the single-file portable Windows .exe from the jpackage app image."

        dependsOn("createReleaseDistributable")
        val script = layout.projectDirectory.file("packaging/windows/build-portable.ps1")
        val appDir = jpackageAppRootDir.map { it.dir("YPtun") }
        val outDir = layout.buildDirectory.dir("compose/binaries/main-release/portable")
        commandLine(
            "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
            "-File", script.asFile.absolutePath,
            "-Version", desktopPackageVersion,
            "-AppDir", appDir.get().asFile.absolutePath,
            "-OutDir", outDir.get().asFile.absolutePath,
            "-Arch", hostDesktopArch,
        )
    }
}

tasks.named("processResources") {
    dependsOn(verifyDesktopNativeResources)
}

listOf(
    "run",
    "createReleaseDistributable",
    "packageReleaseDistributionForCurrentOS",
    "packageReleaseExe",
    "packageReleaseMsi",
    "packageReleaseDmg",
    "packageReleaseAppImage",
    "packageReleasePortableZip",
    "packageReleasePortableExe",
    "packageDeb",
    "packageReleaseDeb"
).forEach { taskName ->
    tasks.matching { it.name == taskName }.configureEach {
        dependsOn(verifyDesktopNativeResources)
    }
}

compose.desktop {
    application {
        mainClass = "MainKt"

        // Without an explicit cap the JVM sizes its heap at 1/4 of physical RAM (2 GB on an 8 GB box,
        // 4 GB on a 16 GB one) and simply never collects until it gets there — which is what the
        // "YPtun eats 3 GB" reports actually were. The app's live set is a Compose window plus a
        // bounded log buffer, so 512 MB is generous; the cap makes the GC keep the footprint honest.
        // MaxMetaspaceSize bounds the other half (Compose/Kotlin generate a lot of classes).
        jvmArgs += listOf(
            "-Xmx512m",
            "-XX:MaxMetaspaceSize=256m",
            // Hand freed pages back to the OS instead of holding the high-water mark forever, so the
            // number the user sees in Task Manager falls again after a burst.
            "-XX:+UseG1GC",
            "-XX:G1PeriodicGCInterval=30000",
            "-XX:MinHeapFreeRatio=10",
            "-XX:MaxHeapFreeRatio=25",
        )

        buildTypes.release.proguard {
            isEnabled.set(false)
        }

        nativeDistributions {
            modules("jdk.httpserver")
            targetFormats(*currentBuildTargetFormats)
            packageName = desktopPackageName
            packageVersion = desktopPackageVersion

            linux {
                iconFile.set(project.file("appIcons/LinuxIcon.png"))
                // packageName is deliberately NOT overridden: it would also rename the jpackage app
                // image directory and break prepareReleaseLinuxAppDir. jpackage already lowercases the
                // app name for the .deb package name ("YPtun" -> "yptun"), which is what dpkg requires.
                debMaintainer = "yptun@users.noreply.github.com"
                appCategory = "net"
                menuGroup = "Network"
                appRelease = "1"
                shortcut = true
            }
            windows {
                iconFile.set(project.file("appIcons/WindowsIcon.ico"))
                menuGroup = "YPtun"
                shortcut = true
                dirChooser = true
                upgradeUuid = "6f0aaf78-dbed-4745-9d95-9e63f10a30de"
            }
            macOS {
                iconFile.set(project.file("appIcons/MacosIcon.icns"))
                bundleID = "org.olcbox.app.desktopApp"
            }
        }
    }
}

if (currentBuildOs.isLinux) {
    val appImageTool = providers.environmentVariable("APPIMAGETOOL").orElse("appimagetool")
    val jpackageAppDir = layout.buildDirectory.dir("compose/binaries/main-release/app/$desktopPackageName")
    val appDir = layout.buildDirectory.dir("compose/binaries/main-release/appimage/AppDir")
    val linuxIconFile = layout.projectDirectory.file("appIcons/LinuxIcon.png")
    val appImageFile = layout.buildDirectory.file(
        "compose/binaries/main-release/appimage/$desktopPackageName-$desktopPackageVersion-$hostDesktopArch.AppImage"
    )

    val prepareReleaseLinuxAppDir = tasks.register<Exec>("prepareReleaseLinuxAppDir") {
        group = "distribution"
        description = "Prepares the AppDir layout used by appimagetool."

        dependsOn("packageReleaseAppImage")
        inputs.dir(jpackageAppDir)
        inputs.file(linuxIconFile)
        outputs.dir(appDir)

        commandLine(
            "sh",
            "-c",
            """
            set -eu

            source_dir="${'$'}1"
            target_dir="${'$'}2"
            icon_file="${'$'}3"

            rm -rf "${'$'}target_dir"
            mkdir -p "${'$'}target_dir"
            cp -R "${'$'}source_dir/." "${'$'}target_dir/"

            cat > "${'$'}target_dir/AppRun" <<'APPRUN'
            #!/bin/sh
            HERE="${'$'}(dirname "${'$'}(readlink -f "${'$'}0")")"
            exec "${'$'}HERE/bin/$desktopPackageName" "${'$'}@"
            APPRUN
            chmod +x "${'$'}target_dir/AppRun"

            cat > "${'$'}target_dir/org.olcbox.app.desktopApp.desktop" <<'DESKTOP'
            [Desktop Entry]
            Type=Application
            Name=$desktopPackageName
            Exec=$desktopPackageName
            Icon=olcbox
            Categories=Network;Utility;
            Terminal=false
            DESKTOP

            cp "${'$'}icon_file" "${'$'}target_dir/olcbox.png"
            """.trimIndent(),
            "prepareReleaseLinuxAppDir",
            jpackageAppDir.get().asFile.absolutePath,
            appDir.get().asFile.absolutePath,
            linuxIconFile.asFile.absolutePath
        )
    }

    val packageReleaseLinuxAppImage = tasks.register<Exec>("packageReleaseLinuxAppImage") {
        group = "distribution"
        description = "Packages the Linux desktop app as a real .AppImage file."

        dependsOn(prepareReleaseLinuxAppDir)
        inputs.dir(appDir)
        outputs.file(appImageFile)

        commandLine(
            appImageTool.get(),
            appDir.get().asFile.absolutePath,
            appImageFile.get().asFile.absolutePath
        )
    }

    tasks.matching { it.name == "packageReleaseDistributionForCurrentOS" }.configureEach {
        dependsOn(packageReleaseLinuxAppImage)
    }
}
