package org.olcbox.app.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import org.olcbox.app.data.identity.DeviceIdentityProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppUpdateServiceTest {
    @Test
    fun stableVersionComparisonUsesSemverParts() {
        assertTrue(AppUpdateService.isUpdateAvailable(ReleaseChannel.Stable, "v1.2.0", "1.1.9"))
        assertFalse(AppUpdateService.isUpdateAvailable(ReleaseChannel.Stable, "v1.0.0", "1.0.0"))
        assertTrue(AppUpdateService.isUpdateAvailable(ReleaseChannel.Nightly, "nightly", "9.9.9"))
        assertTrue(AppUpdateService.isUpdateAvailable(ReleaseChannel.Nightly, "1.0.2", "1.0.1"))
        assertFalse(AppUpdateService.isUpdateAvailable(ReleaseChannel.Nightly, "1.0.2", "1.0.2"))
    }

    @Test
    fun updateSettingsNormalizeToNightlyChannel() {
        val settings = AppUpdateSettings(channel = ReleaseChannel.Stable)

        assertEquals(ReleaseChannel.Nightly, settings.normalized().channel)
    }

    @Test
    fun nightlyUsesVersionFromAssetNameWhenAvailable() = runTest {
        val engine = MockEngine {
            respond(
                """
                {
                  "tag_name": "nightly",
                  "html_url": "https://example/release",
                  "published_at": "2026-05-13T12:00:00Z",
                  "assets": [
                    {
                      "name": "Olcbox-1.0.42-android-release.apk",
                      "browser_download_url": "https://example/app.apk",
                      "size": 100,
                      "updated_at": "2026-05-13T12:00:00Z"
                    }
                  ]
                }
                """.trimIndent()
            )
        }
        val service = AppUpdateService(
            httpClient = HttpClient(engine),
            deviceIdentityProvider = StaticIdentityProvider("hwid"),
            currentVersion = "1.0.42",
            platform = UpdatePlatform("android", "arm64")
        )

        val info = service.check(ReleaseChannel.Nightly).getOrThrow()

        assertEquals("1.0.42", info.version)
        assertFalse(info.isUpdateAvailable)
    }

    @Test
    fun selectsPreferredAssetForPlatform() {
        val assets = listOf(
            GithubReleaseAsset("Olcbox-1.0.0-windows-amd64-portable.zip", "https://example/zip"),
            GithubReleaseAsset("Olcbox-1.0.0-windows-amd64.msi", "https://example/msi")
        )

        val selected = AppUpdateService.selectAsset(
            assets = assets,
            platform = UpdatePlatform("windows", "amd64")
        )

        assertEquals("Olcbox-1.0.0-windows-amd64.msi", selected?.name)
    }

    @Test
    fun selectsNightlyAndroidApk() {
        val selected = AppUpdateService.selectAsset(
            assets = listOf(
                GithubReleaseAsset("olcbox-nightly-android-arm64.apk", "https://example/app.apk")
            ),
            platform = UpdatePlatform("android", "arm64")
        )

        assertEquals("https://example/app.apk", selected?.downloadUrl)
    }

    @Test
    fun prefersAndroidAbiSpecificApk() {
        val selected = AppUpdateService.selectAsset(
            assets = listOf(
                GithubReleaseAsset("Olcbox-1.0.70-android-release.apk", "https://example/universal.apk"),
                GithubReleaseAsset("Olcbox-1.0.70-android-armeabi-v7a-release.apk", "https://example/armeabi.apk")
            ),
            platform = UpdatePlatform("android", "armeabi-v7a")
        )

        assertEquals("https://example/armeabi.apk", selected?.downloadUrl)
    }

    @Test
    fun fallsBackToUniversalAndroidApk() {
        val selected = AppUpdateService.selectAsset(
            assets = listOf(
                GithubReleaseAsset("Olcbox-1.0.70-android-armeabi-v7a-release.apk", "https://example/armeabi.apk"),
                GithubReleaseAsset("Olcbox-1.0.70-android-release.apk", "https://example/universal.apk")
            ),
            platform = UpdatePlatform("android", "arm64")
        )

        assertEquals("https://example/universal.apk", selected?.downloadUrl)
    }

    @Test
    fun updateSettingsPersistAndDueCheckUsesInterval() {
        val settings = AppUpdateSettings(
            channel = ReleaseChannel.Nightly,
            intervalHours = 6,
            lastCheckAtEpochMs = 1_000L,
            lastSeenUpdateVersion = "Nightly:nightly:apk"
        )
        val store = InMemoryAppUpdateSettingsStore()

        runTest {
            store.save(settings)

            assertEquals(settings, store.load())
            assertFalse(store.load().isUpdateCheckDue(1_000L + 5L * 60L * 60L * 1_000L))
            assertTrue(store.load().isUpdateCheckDue(1_000L + 6L * 60L * 60L * 1_000L))
        }
    }

    @Test
    fun laterSuppressesSameUpdateUntilNextIntervalOrNewVersion() {
        val asset = AppUpdateAsset("olcbox-android.apk", "https://example/app.apk", 100)
        val info = AppUpdateInfo(
            channel = ReleaseChannel.Nightly,
            version = "nightly",
            htmlUrl = "https://example/release",
            publishedAt = null,
            asset = asset,
            isUpdateAvailable = true
        )
        val settings = AppUpdateSettings(
            channel = ReleaseChannel.Nightly,
            intervalHours = 6,
            lastCheckAtEpochMs = 10_000L,
            lastSeenUpdateVersion = info.identity()
        )

        assertFalse(info.shouldShowOffer(settings, 10_000L + 2L * 60L * 60L * 1_000L))
        assertTrue(info.shouldShowOffer(settings, 10_000L + 6L * 60L * 60L * 1_000L))
        assertTrue(info.copy(asset = asset.copy(name = "olcbox-android-new.apk")).shouldShowOffer(settings, 10_001L))
    }

    @Test
    fun downloadedUpdateDoesNotShowOfferAgain() {
        val asset = AppUpdateAsset(
            name = "olcbox-nightly-android.apk",
            downloadUrl = "https://example/app.apk",
            sizeBytes = 100,
            updatedAt = "2026-05-13T10:00:00Z"
        )
        val info = AppUpdateInfo(
            channel = ReleaseChannel.Nightly,
            version = "nightly",
            htmlUrl = "https://example/release",
            publishedAt = "2026-05-13T10:01:00Z",
            asset = asset,
            isUpdateAvailable = true
        )
        val settings = AppUpdateSettings(
            channel = ReleaseChannel.Nightly,
            intervalHours = 1,
            lastCheckAtEpochMs = 10_000L,
            lastSeenUpdateVersion = info.identity(),
            lastDownloadedUpdateVersion = info.identity()
        )

        assertFalse(info.shouldShowOffer(settings))
        assertFalse(info.shouldShowOffer(settings, 10_000L + 2L * 60L * 60L * 1_000L))
        assertTrue(
            info.copy(
                asset = asset.copy(updatedAt = "2026-05-13T11:00:00Z")
            ).shouldShowOffer(settings, 10_001L)
        )
    }

    private class StaticIdentityProvider(
        private val value: String
    ) : DeviceIdentityProvider {
        override suspend fun hwid(): String = value
    }
}
