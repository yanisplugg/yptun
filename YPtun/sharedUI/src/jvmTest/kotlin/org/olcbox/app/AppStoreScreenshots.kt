package org.olcbox.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.olcbox.app.data.datasource.JvmLocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.exporter.LogExporter
import org.olcbox.app.data.importer.ConfigImporter
import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.LocationMetadata
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.data.model.SubscriptionMetadata
import org.olcbox.app.ui.OlcboxAppContent
import org.olcbox.app.ui.features.home.HomeScreenViewModel
import org.olcbox.app.ui.features.locations.LocationViewModel
import org.olcbox.app.ui.navigation.AppScreen
import org.olcbox.app.ui.theme.AppTheme
import org.olcbox.app.ui.theme.ThemeState
import org.olcbox.app.vpn.VpnManager
import org.olcbox.app.vpn.VpnStatus
import java.io.File
import kotlin.test.Test

/**
 * Renders the real shared UI (the very composables the iOS app shows) at App Store screenshot size with demo
 * servers and a faked VPN manager. Run: `gradlew :sharedUI:jvmTest --tests '*AppStoreScreenshots*' -Dyptun.shots=<dir>`.
 * Skipped (passes) when the property is absent, so it never touches the normal test run.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class AppStoreScreenshots {

    private class FakeVpn(initial: VpnStatus) : VpnManager {
        override val logs = MutableStateFlow(
            listOf(
                "[09:41:02] Starting tunnel…",
                "[09:41:03] Engine: Xray (VLESS + REALITY)",
                "[09:41:04] Connected · 38 ms"
            )
        )
        override val status = MutableStateFlow(initial)
        override val isConnected = MutableStateFlow(initial is VpnStatus.Connected)
        override val connectedSinceEpochMs: StateFlow<Long> =
            MutableStateFlow(if (initial is VpnStatus.Connected) System.currentTimeMillis() - 47 * 60_000L - 12_000L else 0L)

        override fun needsPermission() = false
        override fun startVpn() {}
        override fun stopVpn() {}
        override suspend fun ping(locationConfig: LocationConfig): Long? = fakePing(locationConfig.proxy?.tag.orEmpty())
        override suspend fun checkConnection(locationConfig: LocationConfig): Long? = 38L
    }

    private companion object {
        fun fakePing(seed: String): Long = 22L + (seed.hashCode().toLong() and 0x7fffffff) % 95L

        val SERVERS = listOf(
            Triple("🇩🇪", "Frankfurt", "203.0.113.11"),
            Triple("🇳🇱", "Amsterdam", "203.0.113.12"),
            Triple("🇫🇮", "Helsinki", "203.0.113.13"),
            Triple("🇸🇪", "Stockholm", "203.0.113.14"),
            Triple("🇬🇧", "London", "203.0.113.15"),
            Triple("🇺🇸", "New York", "203.0.113.16"),
            Triple("🇯🇵", "Tokyo", "203.0.113.17"),
            Triple("🇸🇬", "Singapore", "203.0.113.18"),
        )
        const val SUB_URL = "https://sub.example.com/yptun-demo"

        fun entries(): List<LocationEntry> = SERVERS.mapIndexed { i, (flag, city, ip) ->
            val tag = "$flag $city"
            LocationEntry(
                storageId = "demo-$i",
                name = tag,
                subscriptionUrl = SUB_URL,
                engine = EngineType.Standard,
                proxy = ProxyProfile(
                    tag = tag,
                    server = ip,
                    serverPort = 443,
                    uuid = "00000000-0000-4000-8000-00000000000$i",
                    security = ProxyProfile.SECURITY_REALITY,
                    sni = "www.example.com",
                    fingerprint = "chrome",
                    realityPublicKey = "x".repeat(43),
                    realityShortId = "ab12cd34",
                    flow = "xtls-rprx-vision"
                ),
                metadata = LocationMetadata(
                    subscription = SubscriptionMetadata(
                        name = "YPtun",
                        expiresAtEpochMs = System.currentTimeMillis() + 41L * 86_400_000L,
                        lastRefreshAtEpochMs = System.currentTimeMillis() - 600_000L,
                        updateIntervalHours = 24 * 365
                    )
                )
            )
        } + LocationEntry(
            storageId = "demo-custom",
            name = "Home server",
            engine = EngineType.Standard,
            proxy = ProxyProfile(
                tag = "Home server", server = "198.51.100.7", serverPort = 443,
                uuid = "00000000-0000-4000-8000-0000000000ff",
                security = ProxyProfile.SECURITY_REALITY, sni = "www.example.com", fingerprint = "chrome",
                realityPublicKey = "x".repeat(43), realityShortId = "ab12cd34", flow = "xtls-rprx-vision"
            )
        )
    }

    @Test
    fun render() {
        val outDir = System.getProperty("yptun.shots")?.takeIf { it.isNotBlank() } ?: return
        File(outDir).mkdirs()
        // iPhone 6.9" (iPhone 16/17 Pro Max): 440x956 pt @3x = 1320x2868 px.
        shoot(outDir, "iphone69", 1320, 2868, 3f, 62, 34, 17, 126, 37, 11, 1090f, 282f)
        // iPad 13" (M4/M5 Pro): 1032x1376 pt @2x = 2064x2752. No island; thin status bar, no cellular glyph.
        shoot(outDir, "ipad13", 2064, 2752, 2f, 24, 20, 15, 0, 0, 0, 1917f, 112f)
    }

    private fun shoot(
        outDir: String, device: String, w: Int, h: Int, scale: Float,
        statusTop: Int, bottomInset: Int, statusFont: Int, islandW: Int, islandH: Int, islandY: Int,
        plusX: Float, plusY: Float
    ) {
        data class Shot(val name: String, val connected: Boolean, val light: Boolean, val action: String = "")
        val shots = listOf(false, true).flatMap { light ->
            val t = if (light) "light" else "dark"
            listOf(
                Shot("1-connected-$t", true, light),
                Shot("2-servers-$t", false, light),
                Shot("3-settings-$t", false, light, "edit"),
                Shot("4-add-$t", false, light, "plus"),
            )
        }
        for (s in shots) {
            val repo = LocationsRepositoryImpl(JvmLocationsDataSourceImpl(java.nio.file.Files.createTempDirectory("yptun-shots")))
            runBlocking { repo.saveBundle(LocationBundleV4(activeLocationId = "demo-0", locations = entries())) }
            ThemeState.lightMode = s.light
            val vpn = FakeVpn(if (s.connected) VpnStatus.Connected else VpnStatus.Disconnected)
            val home = HomeScreenViewModel(
                vpnManager = vpn,
                locationsRepository = repo,
                configImporter = object : ConfigImporter {
                    override fun getFromClipboard(): String? = null
                    override fun copyToClipboard(text: String) {}
                    override suspend fun readTextFromSource(source: Any): String? = null
                },
                logExporter = object : LogExporter {
                    override suspend fun writeLogs(target: Any, content: String) = Result.success("")
                }
            )
            val locations = LocationViewModel(repo)
            var screen by mutableStateOf<AppScreen>(AppScreen.Home)

            val scene = ImageComposeScene(w, h, Density(scale)) {
                AppTheme(useDynamicColor = false) {
                    IosFrame(statusTop, bottomInset, statusFont, islandW, islandH, islandY) {
                        OlcboxAppContent(
                            homeViewModel = home,
                            locationViewModel = locations,
                            currentScreen = screen,
                            onNavigate = { screen = it },
                            onToggleClick = {},
                            onImportFileRequested = {},
                            onImportFromClipboardRequested = { _, _ -> },
                            onCopyConfigRequested = {},
                            onSaveLogsRequested = { _, _ -> },
                            showAppSettingsButton = true,
                            canScanQr = true,
                            onAppSettingsClick = {}
                        )
                    }
                }
            }
            try {
                locations.loadLocations { home.loadCurrentConfig() }
                pump(scene, 2500)
                when (s.action) {
                    "edit" -> {
                        locations.startEditing("demo-custom")
                        screen = AppScreen.LocationSettings("demo-custom")
                    }
                    "tab" -> tap(scene, 994f, 2603f)
                    "plus" -> tap(scene, plusX, plusY)
                }
                if (s.action.isNotEmpty()) pump(scene, 2500)
                val bytes = scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes
                File(outDir, "${device}_${s.name}.png").writeBytes(bytes)
            } finally {
                scene.close()
            }
        }
    }

    private fun tap(scene: ImageComposeScene, x: Float, y: Float) {
        scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, Offset(x, y))
        pump(scene, 100)
        scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, Offset(x, y))
        pump(scene, 150)
        scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, Offset(x, y))
    }

    private fun pump(scene: ImageComposeScene, ms: Int) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            scene.render(System.nanoTime())
            Thread.sleep(16)
        }
    }
}

/** iOS chrome: Dynamic Island, 9:41, full signal/Wi-Fi/battery, home indicator. Content sits between them. */
@Composable
private fun IosFrame(
    statusTop: Int, bottomInset: Int, statusFont: Int, islandW: Int, islandH: Int, islandY: Int,
    content: @Composable () -> Unit
) {
    val fg = MaterialTheme.colorScheme.onBackground
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().padding(top = statusTop.dp, bottom = bottomInset.dp)) { content() }

        // status bar
        Box(Modifier.fillMaxWidth().height(statusTop.dp)) {
            val ipad = islandW == 0
            if (ipad) {
                Text(
                    "9:41", color = fg, fontSize = statusFont.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.TopStart).offset(x = 26.dp, y = 2.dp)
                )
                Text(
                    "вт, 9 янв.", color = fg, fontSize = statusFont.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.TopStart).offset(x = 76.dp, y = 2.dp)
                )
                Text(
                    "100%", color = fg, fontSize = statusFont.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = (-(26 + 56 + 6)).dp, y = 2.dp)
                )
            } else {
                Text(
                    "9:41", color = fg, fontSize = statusFont.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.TopStart).offset(x = 54.dp, y = (islandY + 8).dp)
                )
                Box(
                    Modifier.align(Alignment.TopCenter).offset(y = islandY.dp)
                        .size(islandW.dp, islandH.dp).clip(RoundedCornerShape(50)).background(Color.Black)
                )
            }
            Canvas(
                if (ipad) Modifier.align(Alignment.TopEnd).offset(x = (-26).dp, y = 5.dp).size(56.dp, 14.dp)
                else Modifier.align(Alignment.TopEnd)
                    .offset(x = (-27).dp, y = (islandY + islandH / 2f - 7f).dp).size(76.dp, 14.dp)
            ) {
                val u = size.height / 14f
                // iPad has no cellular glyph here: drop it and slide wifi + battery to the left edge
                if (ipad) translate(left = -21.85f * u) { drawIosStatusIcons(fg, u, cellular = false) }
                else drawIosStatusIcons(fg, u, cellular = true)
            }
        }

        // home indicator
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                .size(144.dp, 5.dp).clip(RoundedCornerShape(50)).background(fg)
        )
    }
}

/** The right-hand iOS status glyphs (cellular, Wi-Fi fan, 100 % battery) on a 14-unit-high strip; [u] = px per unit. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawIosStatusIcons(fg: Color, u: Float, cellular: Boolean) {
    // cellular: 4 rounded bars, all lit
    val heights = listOf(4.3f, 6.6f, 8.9f, 11.3f)
    if (cellular) heights.forEachIndexed { i, hh ->
        drawRoundRect(
            fg, Offset(i * 4.7f * u, (12.65f - hh) * u), Size(3.1f * u, hh * u), CornerRadius(1f * u)
        )
    }
    // wifi: filled 90-degree fan — a sector plus two bands, like the iOS glyph
    val cx = 30.7f * u
    val cy = 12.9f * u
    fun band(center: Float, width: Float) = drawArc(
        fg, startAngle = -135f, sweepAngle = 90f, useCenter = false,
        topLeft = Offset(cx - center * u, cy - center * u), size = Size(2 * center * u, 2 * center * u),
        style = Stroke(width * u)
    )
    band(11.45f, 2.3f)
    band(8.0f, 2.2f)
    drawArc(
        fg, startAngle = -135f, sweepAngle = 90f, useCenter = true,
        topLeft = Offset(cx - 5.6f * u, cy - 5.6f * u), size = Size(11.2f * u, 11.2f * u)
    )
    // battery: 100 %
    val bx = 45f * u
    drawRoundRect(
        fg.copy(alpha = 0.38f), Offset(bx + 0.5f * u, 1f * u), Size(26f * u, 12f * u),
        CornerRadius(4f * u), style = Stroke(1f * u)
    )
    drawRoundRect(fg, Offset(bx + 2.2f * u, 2.7f * u), Size(22.6f * u, 8.6f * u), CornerRadius(2.5f * u))
    drawRoundRect(
        fg.copy(alpha = 0.4f), Offset(bx + 27.8f * u, 4.7f * u), Size(1.6f * u, 4.6f * u), CornerRadius(0.8f * u)
    )
}
