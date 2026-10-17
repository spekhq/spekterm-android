package com.spekterm.android.ui

import android.content.pm.ApplicationInfo
import android.util.Log
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import zxingcpp.BarcodeReader

/**
 * A camera preview that decodes QR codes on the phone with zxing-cpp (`scan-connect-zh` D1, D2) — no network
 * service, no Google Play services. Tapping focuses and pinching zooms (the controller's defaults). Every decoded
 * text goes to [onText]; it returns true to stop scanning (D3), after which nothing more is reported.
 */
@Composable
fun QrScanner(modifier: Modifier = Modifier, onText: (String) -> Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnText by rememberUpdatedState(onText)
    val executor = remember { Executors.newSingleThreadExecutor() }
    val done = remember { AtomicBoolean(false) }
    val controller = remember { LifecycleCameraController(context) }
    DisposableEffect(Unit) {
        val reader = BarcodeReader(
            BarcodeReader.Options().apply {
                formats = setOf(BarcodeReader.Format.QR_CODE)
                tryHarder = true
                tryRotate = true
                tryInvert = true
                tryDownscale = true
            },
        )
        val main = ContextCompat.getMainExecutor(context)
        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        controller.imageAnalysisResolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .build()
        controller.imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        controller.setImageAnalysisAnalyzer(executor) { image ->
            val texts = try {
                reader.read(image).mapNotNull { it.text }
            } catch (_: Exception) {
                emptyList()
            } finally {
                image.close()
            }
            if (texts.isNotEmpty() && !done.get()) {
                main.execute {
                    // The first text the caller accepts ends scanning; frames already queued report nothing.
                    for (text in texts) if (!done.get() && currentOnText(text)) done.set(true)
                }
            }
        }
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val zoom = androidx.lifecycle.Observer<androidx.camera.core.ZoomState> { Log.d(TAG, "zoom ratio=${it.zoomRatio}") }
        if (debuggable) controller.zoomState.observe(lifecycleOwner, zoom)
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            // Release the camera as soon as the scanner leaves the screen, not when the activity stops.
            controller.zoomState.removeObserver(zoom)
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            executor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                this.controller = controller
            }
        },
    )
}

private const val TAG = "QrScanner"
