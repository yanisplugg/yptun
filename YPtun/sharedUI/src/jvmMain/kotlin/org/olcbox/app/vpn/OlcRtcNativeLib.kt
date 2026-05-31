package org.olcbox.app.vpn

import com.sun.jna.Library
import com.sun.jna.Native
import java.nio.file.Files

internal interface OlcRtcNativeLib : Library {

    companion object {
        val INSTANCE: OlcRtcNativeLib? by lazy {
            try {
                val spec = olcRtcNativeLibrarySpec(
                    osName = System.getProperty("os.name"),
                    archName = System.getProperty("os.arch")
                )

                if (spec != null) {
                    val resourcePath = "native/${spec.fileName}"
                    val inputStream = OlcRtcNativeLib::class.java.classLoader?.getResourceAsStream(resourcePath)

                    if (inputStream == null) {
                        println("OlcRtcNativeLib: Resource not found: $resourcePath")
                        return@lazy null
                    }

                    val tempFile = Files.createTempFile("olcrtc-native-", "-${spec.fileName}")
                    tempFile.toFile().deleteOnExit()

                    Files.copy(inputStream, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    inputStream.close()

                    Native.load(tempFile.toAbsolutePath().toString(), OlcRtcNativeLib::class.java)
                } else {
                    println("OlcRtcNativeLib: Unsupported platform")
                    null
                }
            } catch (e: UnsatisfiedLinkError) {
                println("OlcRtcNativeLib: Failed to load native library: ${e.message}")
                e.printStackTrace()
                null
            } catch (e: Exception) {
                println("OlcRtcNativeLib: Failed to extract/load native library: ${e.message}")
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * Ping starts an isolated short-lived client, waits until its SOCKS listener is ready,
     * performs HTTP requests through that SOCKS tunnel, and returns HTTP latency in milliseconds.
     *
     * The returned value does not include RTC startup time. It measures only HTTP request latency
     * after the tunnel is ready.
     *
     * Returns -1 on error.
     */
    fun Ping(
        carrierName: String,
        transportName: String,
        roomID: String,
        clientID: String,
        keyHex: String,
        socksPort: Long,
        timeoutMillis: Long,
        pingURL: String,
        vp8FPS: Long,
        vp8BatchSize: Long
    ): Long

    /**
     * Check starts an isolated short-lived client and returns elapsed milliseconds once ready.
     * It does not use the singleton Start/Stop runtime, so callers may run checks in parallel.
     *
     * Returns -1 on error.
     */
    fun Check(
        carrierName: String,
        transportName: String,
        roomID: String,
        clientID: String,
        keyHex: String,
        socksPort: Long,
        timeoutMillis: Long,
        vp8FPS: Long,
        vp8BatchSize: Long
    ): Long
}

internal data class OlcRtcNativeLibrarySpec(
    val fileName: String
)

internal fun olcRtcNativeLibrarySpec(
    osName: String?,
    archName: String?
): OlcRtcNativeLibrarySpec? {
    val os = osName.orEmpty().lowercase()
    val arch = when (archName.orEmpty().lowercase()) {
        "aarch64", "arm64" -> "arm64"
        "x86_64", "amd64" -> "amd64"
        else -> archName.orEmpty().lowercase()
    }

    return when {
        "mac" in os || "darwin" in os -> when (arch) {
            "arm64" -> OlcRtcNativeLibrarySpec("libolcrtc-darwin-arm64.dylib")
            "amd64" -> OlcRtcNativeLibrarySpec("libolcrtc-darwin-amd64.dylib")
            else -> null
        }
        "linux" in os -> when (arch) {
            "arm64" -> OlcRtcNativeLibrarySpec("libolcrtc-linux-arm64.so")
            "amd64" -> OlcRtcNativeLibrarySpec("libolcrtc-linux-amd64.so")
            else -> null
        }
        "win" in os -> when (arch) {
            "amd64" -> OlcRtcNativeLibrarySpec("olcrtc-windows-amd64.dll")
            else -> null
        }
        else -> null
    }
}
