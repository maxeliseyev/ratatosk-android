// In-app QR scanner: CameraX frames through zxing. The iOS app uses the
// system VisionKit scanner; Android has no equivalent, so this is the same
// idea with a few more lines.

package dev.ratatosk.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun QrScannerDialog(onResult: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Palette.ground)) {
            if (granted) {
                CameraPreview(onResult)
            } else {
                Text(
                    "camera permission is needed to scan a QR code",
                    color = Palette.text,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }
            Text(
                "close",
                color = Palette.dim,
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(20.dp)
                    .clickable(onClick = onDismiss),
            )
        }
    }
}

@Composable
private fun CameraPreview(onResult: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context) }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

    DisposableEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            runCatching {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor, QrAnalyzer(onResult))
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }
        }
        future.addListener(listener, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { future.get().unbindAll() }
            executor.shutdown()
        }
    }
}

private class QrAnalyzer(private val onResult: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            )
        )
    }
    private val done = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        try {
            if (done.get()) return
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val rowPadding = rowStride - pixelStride * image.width
            val width = image.width + rowPadding / pixelStride
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val source = PlanarYUVLuminanceSource(
                bytes,
                width,
                image.height,
                0,
                0,
                image.width,
                image.height,
                false,
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = reader.decode(bitmap)
            if (done.compareAndSet(false, true)) onResult(result.text)
        } catch (_: NotFoundException) {
            // No QR in this frame.
        } catch (_: Throwable) {
            // Bad frame; the next one will be fine.
        } finally {
            image.close()
        }
    }
}
