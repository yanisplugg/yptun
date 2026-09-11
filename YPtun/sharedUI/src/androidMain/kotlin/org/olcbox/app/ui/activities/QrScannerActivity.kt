package org.olcbox.app.ui.activities

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.os.Bundle
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageProxy
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.olcbox.app.ui.i18n.LocalizationState
import org.olcbox.app.ui.i18n.stringsFor
import org.olcbox.app.ui.theme.AppTheme
import org.olcbox.app.ui.theme.ThemeState
import zxingcpp.BarcodeReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class QrScannerActivity : ComponentActivity() {
    private val handled = AtomicBoolean(false)
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var controller: LifecycleCameraController

    // zxing-cpp (native) instead of zxing-java: reads blurred, tilted, dense and inverted codes that
    // the Java port gives up on, several times faster, so more frames per second get a try.
    private val reader by lazy {
        BarcodeReader(
            BarcodeReader.Options(
                formats = setOf(BarcodeReader.Format.QR_CODE),
                tryHarder = true,
                tryInvert = true, // white-on-black codes from dark-themed panels/screens
                tryDownscale = true // a close-up code filling the frame reads better downscaled
            )
        )
    }

    private val torchAvailable = mutableStateOf(false)
    private val torchOn = mutableStateOf(false)
    private var cameraStateObserved = false

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            toast(stringsFor(LocalizationState.effective).cameraPermissionDenied)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()
        // CameraController gives tap-to-focus and pinch-to-zoom on the PreviewView for free, and crops
        // analysis frames to what the preview shows.
        controller = LifecycleCameraController(this).apply {
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            // CameraX analyses 640x480 by default — too coarse for dense config QRs (AWG, xhttp/reality
            // links): modules blur into each other and nothing decodes however well it's focused.
            setImageAnalysisResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            ANALYSIS_SIZE,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()
            )
            setImageAnalysisAnalyzer(cameraExecutor, ::analyzeImage)
            // Emits once a camera is bound — the first point where cameraInfo is available.
            zoomState.observe(this@QrScannerActivity) { observeCameraState() }
            torchState.observe(this@QrScannerActivity) { torchOn.value = it == TorchState.ON }
        }
        enableEdgeToEdge()

        setContent {
            QrScannerScreen(
                controller = controller,
                torchAvailable = torchAvailable.value,
                torchOn = torchOn.value,
                onToggleTorch = { controller.enableTorch(!torchOn.value) },
                onClose = { finish() }
            )
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        controller.bindToLifecycle(this)
        val init = controller.initializationFuture
        init.addListener(
            {
                val ok = runCatching { init.get() }.isSuccess &&
                    controller.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                if (!ok) {
                    toast(stringsFor(LocalizationState.effective).cameraUnavailable)
                    finish()
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun observeCameraState() {
        if (cameraStateObserved) return
        val info = controller.cameraInfo ?: return
        cameraStateObserved = true
        torchAvailable.value = info.hasFlashUnit()
        // CameraX resets zoom and metering every time the camera closes (app backgrounded, screen
        // off), so tune on every OPEN, not once.
        info.cameraState.observe(this) { state ->
            if (state.type == CameraState.Type.OPEN) tuneCamera(info)
        }
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun tuneCamera(info: CameraInfo) {
        // Centre-weighted exposure: with whole-frame metering a QR on a bright phone/monitor screen
        // in a dim room is blown out to white. AE only — AF stays continuous (tap still refocuses).
        val centre = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f, 0.4f)
        controller.cameraControl?.startFocusAndMetering(
            FocusMeteringAction.Builder(centre, FocusMeteringAction.FLAG_AE)
                .disableAutoCancel()
                .build()
        )

        // Flagship main cameras (Galaxy S Ultra: big 200 MP sensor) can't focus closer than ~15-20 cm,
        // and people bring a QR to ~10 cm to fill the frame — permanently out of focus. Start zoomed
        // in so the code fills the frame from a distance the lens can focus at. The 1080p-ish
        // analysis stream is cut from a 12 MP binned readout, so up to ~2.5x costs no detail.
        // ponytail: heuristic (framing of an ideal 8 cm-focus camera), pinch-to-zoom covers the rest.
        val diopters = Camera2CameraInfo.from(info)
            .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            ?: 0f // 0 = fixed focus
        val zoom = info.zoomState.value ?: return
        if (diopters <= 0f) return
        val start = (100f / diopters / REFERENCE_FOCUS_CM)
            .coerceAtMost(MAX_START_ZOOM)
            .coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio)
        if (start > 1.05f) controller.setZoomRatio(start)
    }

    private fun analyzeImage(image: ImageProxy) {
        image.use {
            if (handled.get()) return
            val text = runCatching { reader.read(it) }.getOrNull()
                ?.firstNotNullOfOrNull { result -> result.text?.trim()?.takeIf(::isAcceptableQr) }
            if (text != null && handled.compareAndSet(false, true)) {
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_QR_TEXT, text))
                finish()
            }
        }
    }

    /**
     * Only config-looking QRs end the scan — otherwise a random QR (vCard, Wi-Fi, plain text) would be
     * returned. Any `scheme://` passes: the old scheme whitelist silently rejected the app's own share
     * QRs (yptun://, hysteria2://, naive+https://, tt://, happ://…) and kept "scanning" forever.
     */
    private fun isAcceptableQr(text: String): Boolean =
        text.isNotEmpty() && ("://" in text || text.startsWith("{") || text.startsWith("[") ||
            text.contains("[Interface]", ignoreCase = true)) // AmneziaWG / WireGuard config

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_QR_TEXT = "org.olcbox.app.QR_TEXT"
        private val ANALYSIS_SIZE = Size(1920, 1440)
        private const val REFERENCE_FOCUS_CM = 8f
        // Stay below 3x: on logical multi-cameras (Samsung) 3x switches to the telephoto, whose
        // minimum focus distance is half a metre.
        private const val MAX_START_ZOOM = 2.5f
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QrScannerScreen(
    controller: CameraController,
    torchAvailable: Boolean,
    torchOn: Boolean,
    onToggleTorch: () -> Unit,
    onClose: () -> Unit
) {
    // Match the main screen's theme (custom vs device-dynamic) instead of always using dynamic.
    AppTheme(useDynamicColor = ThemeState.dynamicEnabled) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                QrScannerTopBar(
                    torchAvailable = torchAvailable,
                    torchOn = torchOn,
                    onToggleTorch = onToggleTorch,
                    onClose = onClose
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 32.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                QrScannerPreview(
                    controller = controller,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                QrScannerStatusPanel(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QrScannerTopBar(
    torchAvailable: Boolean,
    torchOn: Boolean,
    onToggleTorch: () -> Unit,
    onClose: () -> Unit
) {
    CenterAlignedTopAppBar(
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = org.olcbox.app.ui.i18n.LocalStrings.current.scanQrTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = org.olcbox.app.ui.i18n.LocalStrings.current.subscriptionOrLocationUri,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Close scanner",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        actions = {
            if (torchAvailable) {
                IconButton(onClick = onToggleTorch) {
                    Icon(
                        imageVector = if (torchOn) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
                        contentDescription = "Flashlight",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    )
}

@Composable
private fun QrScannerPreview(
    controller: CameraController,
    modifier: Modifier = Modifier
) {
    val previewShape = RoundedCornerShape(16.dp)

    Surface(
        modifier = Modifier
            .then(modifier),
        shape = previewShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(previewShape)
        ) {
            AndroidView(
                factory = { context ->
                    PreviewView(context).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                        this.controller = controller
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            QrScannerFrame(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(42.dp)
                    .fillMaxWidth()
                    .aspectRatio(1f)
            )
        }
    }
}

@Composable
private fun QrScannerFrame(modifier: Modifier = Modifier) {
    val cornerRadius = 24.dp

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {}
    }
}

@Composable
private fun QrScannerStatusPanel(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.QrCodeScanner,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = org.olcbox.app.ui.i18n.LocalStrings.current.readyToScan,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = org.olcbox.app.ui.i18n.LocalStrings.current.subscriptionOrLocationUri,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
