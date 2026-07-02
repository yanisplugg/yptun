package org.olcbox.app.update

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.data.datasource.createProxyHttpClient
import org.olcbox.app.data.datasource.withProxyAuthentication
import org.olcbox.app.data.identity.DeviceIdentityProvider
import org.olcbox.app.data.repository.SubscriptionFetchProxy

@Serializable
enum class ReleaseChannel {
    Stable,
    Nightly
}

data class ReleaseMirror(
    val name: String,
    val repositoryUrl: String
) {
    val ownerRepo: String
        get() = repositoryUrl
            .removePrefix("https://github.com/")
            .removeSuffix("/")

    companion object {
        val GitHub = ReleaseMirror(
            name = "GitHub",
            repositoryUrl = "https://github.com/yanisplugg/olcvpn-client"
        )
    }
}

data class AppUpdateAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long?,
    val updatedAt: String? = null
)

data class AppUpdateInfo(
    val channel: ReleaseChannel,
    val version: String,
    val htmlUrl: String,
    val publishedAt: String?,
    val asset: AppUpdateAsset,
    val isUpdateAvailable: Boolean,
    /**
     * Optional binary-delta asset that patches the user's CURRENT version up to [version] for their
     * ABI (Android only), so only a few MB are downloaded instead of the whole APK. Null when no
     * matching patch is published (then the installer downloads [asset] in full). The reconstructed
     * APK is signature-verified before install, so a missing/forged patch always degrades safely.
     */
    val deltaAsset: AppUpdateAsset? = null
)

class AppUpdateService(
    private val httpClient: HttpClient = createUpdateHttpClient(),
    private val deviceIdentityProvider: DeviceIdentityProvider,
    private val mirror: ReleaseMirror = ReleaseMirror.GitHub,
    private val currentVersion: String = CurrentAppInfo.value.version,
    private val platform: UpdatePlatform = UpdatePlatform.current()
) {
    suspend fun check(
        channel: ReleaseChannel,
        proxy: SubscriptionFetchProxy? = null
    ): Result<AppUpdateInfo> = runCatching {
        val release = fetchRelease(channel, proxy)
        val asset = selectAsset(release.assets, platform)
            ?: error(
                "No ${platform.assetToken.joinToString(" + ")} update asset in ${release.tagName}. " +
                        "Expected asset name containing ${platform.assetToken.joinToString(", ")}" +
                        platform.preferredExtensions.takeIf { it.isNotEmpty() }
                            ?.joinToString(prefix = " and ending with one of: ")
                            .orEmpty()
            )

        val resolvedVersion = updateVersion(channel, release.tagName, asset)
        val available = isUpdateAvailable(
            channel = channel,
            releaseTag = resolvedVersion,
            currentVersion = currentVersion
        )
        AppUpdateInfo(
            channel = channel,
            version = resolvedVersion,
            htmlUrl = release.htmlUrl,
            publishedAt = release.publishedAt,
            asset = asset,
            isUpdateAvailable = available,
            deltaAsset = if (available) {
                selectDeltaAsset(release.assets, platform, currentVersion, resolvedVersion, asset.name)
            } else null
        )
    }

    suspend fun fetchRelease(
        channel: ReleaseChannel,
        proxy: SubscriptionFetchProxy? = null
    ): GithubRelease {
        val client = if (proxy == null) {
            httpClient
        } else {
            createUpdateHttpClient(proxy)
        }

        return try {
            withProxyAuthentication(proxy) {
                fetchRelease(client, channel)
            }
        } finally {
            if (client !== httpClient) {
                client.close()
            }
        }
    }

    private suspend fun fetchRelease(client: HttpClient, channel: ReleaseChannel): GithubRelease {
        val base = "https://api.github.com/repos/${mirror.ownerRepo}"
        return when (channel) {
            // Use the releases LIST (newest first), NOT /releases/latest — the latter returns 404 when
            // every published release is flagged "pre-release". Pick the newest non-draft entry.
            ReleaseChannel.Stable -> {
                val body = getBody(client, "$base/releases?per_page=20")
                val releases = json.decodeFromString(ListSerializer(GithubRelease.serializer()), body)
                releases.firstOrNull { !it.draft }
                    ?: error("No releases published for ${mirror.ownerRepo}")
            }
            ReleaseChannel.Nightly ->
                json.decodeFromString(GithubRelease.serializer(), getBody(client, "$base/releases/tags/nightly"))
        }
    }

    private suspend fun getBody(client: HttpClient, endpoint: String): String {
        val hwid = deviceIdentityProvider.hwid()
        val response = client.get(endpoint) {
            headers {
                append(HttpHeaders.Accept, "application/vnd.github+json")
                append(HttpHeaders.UserAgent, CurrentAppInfo.userAgent)
                append("x-hwid", hwid)
            }
        }
        if (response.status.value !in 200..299) {
            error("GitHub release request failed with HTTP ${response.status.value}")
        }
        return response.bodyAsText()
    }

    companion object {
        fun selectAsset(assets: List<GithubReleaseAsset>, platform: UpdatePlatform): AppUpdateAsset? {
            val asset = when (platform.os) {
                "android" -> selectAndroidAsset(assets, platform)
                else -> selectAssetByTokens(assets, platform.assetToken, platform.preferredExtensions)
            }

            return asset?.let {
                AppUpdateAsset(
                    name = it.name,
                    downloadUrl = it.browserDownloadUrl,
                    sizeBytes = it.size,
                    updatedAt = it.updatedAt
                )
            }
        }

        /**
         * Finds the binary-delta patch that upgrades [fromVersion]→[toVersion] for the device ABI.
         * Convention: assets are named `YPtun-delta-<from>-<to>-<abi>.patch.gz` (raw File-by-File v1
         * patch, gzip-compressed for transport). The ABI is taken from the already-chosen full
         * [fullAssetName] so the patch matches exactly what's installed. Android only.
         */
        fun selectDeltaAsset(
            assets: List<GithubReleaseAsset>,
            platform: UpdatePlatform,
            fromVersion: String,
            toVersion: String,
            fullAssetName: String
        ): AppUpdateAsset? {
            if (platform.os != "android") return null
            val from = fromVersion.removePrefix("v")
            val to = toVersion.removePrefix("v")
            if (from.isBlank() || to.isBlank() || from == to) return null
            val fullName = fullAssetName.lowercase()
            val abi = platform.androidArchTokens.firstOrNull { it in fullName }
            val candidates = assets.filter { asset ->
                val name = asset.name.lowercase()
                (name.endsWith(".patch.gz") || name.endsWith(".patch")) &&
                    "delta" in name && from in name && to in name
            }
            val chosen = if (abi != null) candidates.firstOrNull { abi in it.name.lowercase() }
            else candidates.firstOrNull()
            return chosen?.let {
                AppUpdateAsset(
                    name = it.name,
                    downloadUrl = it.browserDownloadUrl,
                    sizeBytes = it.size,
                    updatedAt = it.updatedAt
                )
            }
        }

        private fun selectAndroidAsset(
            assets: List<GithubReleaseAsset>,
            platform: UpdatePlatform
        ): GithubReleaseAsset? {
            // Match on the ABI token alone — release assets are named like "YPtun-v2.1.0-arm64-v8a.apk"
            // and do NOT carry an "android" token, so requiring one here skipped every real asset.
            val preferredExtensions = platform.preferredExtensions
            val apkAssets = assets.filter { it.name.lowercase().endsWith(".apk") }
            val exactAbiAsset = platform.androidArchTokens.firstNotNullOfOrNull { archToken ->
                selectAssetByTokens(apkAssets, listOf(archToken), preferredExtensions)
            }
            if (exactAbiAsset != null) return exactAbiAsset

            // No ABI-specific apk for this device: fall back to a universal apk (one without any known
            // ABI token in its name, e.g. "YPtun-v2.1.0-universal.apk").
            val universalCandidates = apkAssets.filter { asset ->
                val name = asset.name.lowercase()
                knownAndroidArchTokens.none { it in name }
            }

            return selectPreferredAsset(universalCandidates, preferredExtensions)
        }

        private fun selectAssetByTokens(
            assets: List<GithubReleaseAsset>,
            tokens: List<String>,
            preferredExtensions: List<String>
        ): GithubReleaseAsset? {
            val candidates = assets.filter { asset ->
                val name = asset.name.lowercase()
                tokens.all { it in name }
            }

            return selectPreferredAsset(candidates, preferredExtensions)
        }

        private fun selectPreferredAsset(
            candidates: List<GithubReleaseAsset>,
            preferredExtensions: List<String>
        ): GithubReleaseAsset? {
            return preferredExtensions
                .firstNotNullOfOrNull { extension ->
                    candidates.firstOrNull { it.name.lowercase().endsWith(extension) }
                }
                ?: candidates.firstOrNull()
        }

        fun isUpdateAvailable(
            channel: ReleaseChannel,
            releaseTag: String,
            currentVersion: String
        ): Boolean {
            val release = releaseTag.removePrefix("v")
            if (channel == ReleaseChannel.Nightly && release == "nightly") return true

            return compareVersions(release, currentVersion) > 0
        }

        fun compareVersions(left: String, right: String): Int {
            val leftParts = left.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
            val rightParts = right.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
            val size = maxOf(leftParts.size, rightParts.size)
            for (index in 0 until size) {
                val diff = (leftParts.getOrNull(index) ?: 0) - (rightParts.getOrNull(index) ?: 0)
                if (diff != 0) return diff
            }
            return 0
        }

        private fun updateVersion(
            channel: ReleaseChannel,
            releaseTag: String,
            asset: AppUpdateAsset
        ): String {
            return when (channel) {
                ReleaseChannel.Stable -> releaseTag.removePrefix("v")
                ReleaseChannel.Nightly -> asset.name.versionToken() ?: releaseTag.removePrefix("v")
            }
        }

        private fun String.versionToken(): String? {
            return Regex("""(?:^|[-_])v?(\d+\.\d+\.\d+)(?:[-_.]|$)""")
                .find(this)
                ?.groupValues
                ?.getOrNull(1)
        }
    }
}

data class UpdatePlatform(
    val os: String,
    val arch: String
) {
    val assetToken: List<String>
        get() = when (os) {
            "windows" -> listOf("windows", "amd64")
            "macos" -> listOf("macos", arch)
            "linux" -> listOf("linux", arch)
            "android" -> listOf("android")
            else -> listOf(os, arch)
        }

    val androidArchTokens: List<String>
        get() = when (os) {
            "android" -> when (arch.lowercase()) {
                "armeabi-v7a", "armeabi" -> listOf("armeabi-v7a", "armeabi")
                "arm64", "arm64-v8a" -> listOf("arm64", "arm64-v8a")
                "amd64", "x86_64" -> listOf("amd64", "x86_64")
                "x86" -> listOf("x86")
                else -> emptyList()
            }
            else -> emptyList()
        }

    val preferredExtensions: List<String>
        get() = when (os) {
            "windows" -> listOf(".msi", ".exe", ".zip")
            "macos" -> listOf(".dmg")
            "linux" -> listOf(".appimage")
            "android" -> listOf(".apk")
            else -> emptyList()
        }

    companion object {
        fun current(): UpdatePlatform = currentUpdatePlatform()
    }
}

expect fun currentUpdatePlatform(): UpdatePlatform

private val knownAndroidArchTokens = setOf(
    "armeabi-v7a",
    "armeabi",
    "arm64-v8a",
    "arm64",
    "x86_64",
    "amd64",
    "x86"
)

@Serializable
data class GithubRelease(
    @SerialName("tag_name")
    val tagName: String,
    @SerialName("html_url")
    val htmlUrl: String,
    @SerialName("published_at")
    val publishedAt: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GithubReleaseAsset> = emptyList()
)

@Serializable
data class GithubReleaseAsset(
    val name: String,
    @SerialName("browser_download_url")
    val browserDownloadUrl: String,
    val size: Long? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null
)

private val json = Json {
    ignoreUnknownKeys = true
}

private fun createUpdateHttpClient(proxy: SubscriptionFetchProxy? = null): HttpClient =
    createProxyHttpClient(
        subscriptionProxy = proxy,
        connectTimeoutMs = 5_000,
        requestTimeoutMs = 15_000,
        socketTimeoutMs = 15_000
    )
