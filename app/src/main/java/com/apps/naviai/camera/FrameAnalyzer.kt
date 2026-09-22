package com.apps.naviai.camera

import android.graphics.ImageFormat
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.apps.naviai.detection.detector.FrameInput
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX analyzer that converts frames and hands them off for inference on
 * a background dispatcher, dropping (not queueing) any frame that arrives
 * while a previous one is still being processed. This is what keeps the
 * camera preview smooth even when inference is slower than the sensor's
 * frame rate: paired with STRATEGY_KEEP_ONLY_LATEST on the ImageAnalysis
 * use case, CameraX only ever hands us the newest frame, and this class
 * ensures we never pile up more than one in-flight inference on top of it.
 *
 * [imageProxy.close()] is called in every code path, including all early
 * returns, so CameraX is never starved waiting for a buffer back.
 */
class FrameAnalyzer(
    private val scope: CoroutineScope,
    private val inferenceDispatcher: CoroutineDispatcher,
    private val mirror: Boolean,
    private val onFrame: suspend (FrameInput) -> Unit
) : ImageAnalysis.Analyzer {

    private val busy = AtomicBoolean(false)

    override fun analyze(imageProxy: ImageProxy) {
        if (!busy.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        val rgba: ByteArray?
        val width: Int
        val height: Int
        val rotationDegrees: Int
        try {
            if (imageProxy.format != ImageFormat.YUV_420_888) {
                Log.w(TAG, "Unsupported image format: ${imageProxy.format}")
                rgba = null
                width = 0
                height = 0
                rotationDegrees = 0
            } else {
                width = imageProxy.width
                height = imageProxy.height
                rotationDegrees = imageProxy.imageInfo.rotationDegrees
                rgba = ImageUtils.yuv420ToRgba(imageProxy)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Frame conversion failed", t)
            busy.set(false)
            imageProxy.close()
            return
        }

        imageProxy.close()

        if (rgba == null) {
            busy.set(false)
            return
        }

        scope.launch(inferenceDispatcher) {
            try {
                onFrame(FrameInput(rgba, width, height, rotationDegrees, mirror))
            } catch (t: Throwable) {
                Log.e(TAG, "Frame analysis callback failed", t)
            } finally {
                busy.set(false)
            }
        }
    }

    private companion object {
        const val TAG = "FrameAnalyzer"
    }
}
