package org.olcbox.app.ui.activities

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.olcbox.app.ui.theme.AppTheme
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class QrScannerActivity : ComponentActivity() {
    private val handled = AtomicBoolean(false)
    private lateinit var cameraExecutor: ExecutorService
    private val qrReader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true
            )
        )
    }
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewView: PreviewView? = null
    private var hasCameraPermission = false
    private var cameraStarted = false

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            hasCameraPermission = true
            maybeStartCamera()
        } else {
            Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()
        enableEdgeToEdge()

        setContent {
            QrScannerScreen(
                onClose = { finish() },
                onPreviewReady = { preview ->
                    previewView = preview
                    maybeStartCamera()
                }
            )
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            hasCameraPermission = true
            maybeStartCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun maybeStartCamera() {
        if (!hasCameraPermission || previewView == null || cameraStarted) return
        startCamera()
    }

    private fun startCamera() {
        val previewView = previewView ?: return
        cameraStarted = true
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(
            {
                val provider = runCatching { cameraProviderFuture.get() }
                    .getOrElse {
                        cameraStarted = false
                        Toast.makeText(this, "Camera unavailable", Toast.LENGTH_SHORT).show()
                        finish()
                        return@addListener
                    }
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { imageAnalysis ->
                        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            analyzeImage(imageProxy)
                        }
                    }

                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                }.onFailure {
                    cameraStarted = false
                    Toast.makeText(this, "Camera unavailable", Toast.LENGTH_SHORT).show()
                    finish()
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun analyzeImage(imageProxy: ImageProxy) {
        try {
            if (handled.get()) return

            val rawValue = runCatching {
                decodeQr(imageProxy.toLuminanceSource())
            }.getOrNull()

            if (rawValue != null && handled.compareAndSet(false, true)) {
                setResult(
                    Activity.RESULT_OK,
                    Intent().putExtra(EXTRA_QR_TEXT, rawValue)
                )
                finish()
            }
        } finally {
            imageProxy.close()
        }
    }

    private fun decodeQr(source: LuminanceSource): String? {
        var current = source
        repeat(QR_ROTATION_ATTEMPTS) { attempt ->
            val decoded = runCatching {
                qrReader.decodeWithState(BinaryBitmap(HybridBinarizer(current)))
                    .text
                    .trim()
                    .takeIf { it.isNotBlank() }
            }.getOrElse { error ->
                if (error is NotFoundException) null else null
            }
            qrReader.reset()
            if (decoded != null) return decoded

            if (attempt < QR_ROTATION_ATTEMPTS - 1 && current.isRotateSupported) {
                current = current.rotateCounterClockwise()
            }
        }
        return null
    }

    private fun ImageProxy.toLuminanceSource(): PlanarYUVLuminanceSource {
        val yPlane = planes.first()
        val luminance = yPlane.buffer.copyLuminance(
            width = width,
            height = height,
            rowStride = yPlane.rowStride,
            pixelStride = yPlane.pixelStride
        )

        return PlanarYUVLuminanceSource(
            luminance,
            width,
            height,
            0,
            0,
            width,
            height,
            false
        )
    }

    private fun ByteBuffer.copyLuminance(
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ): ByteArray {
        val output = ByteArray(width * height)

        if (pixelStride == 1 && rowStride == width) {
            val duplicate = duplicate()
            duplicate.rewind()
            duplicate.get(output, 0, output.size)
            return output
        }

        for (y in 0 until height) {
            val rowOffset = y * rowStride
            val outputOffset = y * width
            for (x in 0 until width) {
                output[outputOffset + x] = get(rowOffset + x * pixelStride)
            }
        }

        return output
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        previewView = null
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_QR_TEXT = "org.olcbox.app.QR_TEXT"
        private const val QR_ROTATION_ATTEMPTS = 4
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QrScannerScreen(
    onClose: () -> Unit,
    onPreviewReady: (PreviewView) -> Unit
) {
    AppTheme {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                QrScannerTopBar(onClose = onClose)
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
                    onPreviewReady = onPreviewReady,
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
private fun QrScannerTopBar(onClose: () -> Unit) {
    CenterAlignedTopAppBar(
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Scan QR",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "subscription or location URI",
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
        }
    )
}

@Composable
private fun QrScannerPreview(
    onPreviewReady: (PreviewView) -> Unit,
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
                        onPreviewReady(this)
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
                        text = "Ready to scan",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Subscription or location URI",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
