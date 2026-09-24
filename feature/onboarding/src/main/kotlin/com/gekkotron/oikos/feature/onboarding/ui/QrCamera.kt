package com.gekkotron.oikos.feature.onboarding.ui

import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gekkotron.oikos.designsystem.OikosTheme
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.Executors

/** Back-camera preview with a QR analyzer. Device-only glue: excluded from coverage. */
@Composable
internal fun QrCamera(modifier: Modifier, onText: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnText by rememberUpdatedState(onText)
    var request by remember { mutableStateOf<SurfaceRequest?>(null) }
    LaunchedEffect(lifecycleOwner) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val preview = Preview.Builder().build().apply { setSurfaceProvider { request = it } }
        val executor = Executors.newSingleThreadExecutor()
        val analyzer = QrAnalyzer { currentOnText(it) }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply { setAnalyzer(executor, analyzer) }
        try {
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            awaitCancellation()
        } finally {
            provider.unbind(preview, analysis)
            analyzer.close()
            executor.shutdown()
        }
    }
    Box(modifier.background(OikosTheme.colors.backgroundBottom)) {
        request?.let { CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize()) }
    }
}

/** ML Kit's bundled on-device model, QR only; no Play services (spec §5 step 2). Results arrive on the main thread. */
internal class QrAnalyzer(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer, AutoCloseable {
    private val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())

    @ExperimentalGetImage
    override fun analyze(image: ImageProxy) {
        val media = image.image ?: return image.close()
        scanner.process(InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees))
            .addOnSuccessListener { codes -> codes.firstNotNullOfOrNull { it.rawValue }?.let(onText) }
            .addOnCompleteListener { image.close() }
    }

    override fun close() = scanner.close()
}
