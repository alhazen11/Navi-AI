package com.apps.naviai.detection.detector

import android.content.Context
import android.graphics.RectF
import android.util.Log
import com.apps.naviai.core.common.InferenceDispatcher
import com.apps.naviai.data.model.ModelAssetManager
import com.apps.naviai.native.NcnnJniBridge
import com.apps.naviai.native.NativeDetection
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ObjectDetector] backed by the vendored NCNN library via
 * [NcnnJniBridge]. All native calls are funneled through [inferenceDispatcher]
 * (a dedicated single thread) so at most one inference is ever in flight and
 * frames never pile up behind a slow device.
 */
@Singleton
class NcnnObjectDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    @InferenceDispatcher private val inferenceDispatcher: CoroutineDispatcher
) : ObjectDetector {

    private val modelAssetManager = ModelAssetManager(context)

    @Volatile
    override var state: DetectorState = DetectorState.Uninitialized
        private set

    override suspend fun initialize(useVulkan: Boolean): Boolean = withContext(inferenceDispatcher) {
        if (!NcnnJniBridge.isLibraryLoaded) {
            state = DetectorState.Error("libnaviai_ncnn.so failed to load (UnsatisfiedLinkError)")
            return@withContext false
        }

        state = DetectorState.Initializing
        try {
            val model = modelAssetManager.ensureModelExtracted()
            val ok = NcnnJniBridge.nativeInit(model.paramPath, model.binPath, model.labels, useVulkan)
            state = if (ok) {
                DetectorState.Ready
            } else {
                DetectorState.Error(NcnnJniBridge.nativeGetLastError().ifBlank { "Unknown native init failure" })
            }
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize detector", t)
            state = DetectorState.Error(t.message ?: "Model initialization failed", t)
            false
        }
    }

    override suspend fun detect(
        frame: FrameInput,
        confidenceThreshold: Float,
        iouThreshold: Float
    ): InferenceResult = withContext(inferenceDispatcher) {
        if (state != DetectorState.Ready) {
            return@withContext InferenceResult(emptyList(), 0.0)
        }

        val startNanos = System.nanoTime()
        val raw: Array<NativeDetection> = try {
            NcnnJniBridge.nativeDetect(
                frame.rgba,
                frame.width,
                frame.height,
                frame.rotationDegrees,
                frame.mirror,
                confidenceThreshold,
                iouThreshold
            )
        } catch (t: UnsatisfiedLinkError) {
            Log.e(TAG, "Native detect() unavailable", t)
            state = DetectorState.Error("Native library unavailable", t)
            emptyArray()
        } catch (t: Throwable) {
            Log.e(TAG, "Native detect() failed", t)
            emptyArray()
        }
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0

        val timestamp = System.currentTimeMillis()
        val detections = raw.mapNotNull { it.toDomainOrNull(timestamp) }
        InferenceResult(detections, elapsedMs)
    }

    override fun isVulkanSupported(): Boolean =
        if (NcnnJniBridge.isLibraryLoaded) NcnnJniBridge.nativeIsVulkanSupported() else false

    override fun release() {
        if (NcnnJniBridge.isLibraryLoaded) {
            NcnnJniBridge.nativeRelease()
        }
        state = DetectorState.Uninitialized
    }

    private fun NativeDetection.toDomainOrNull(timestamp: Long): Detection? {
        // Defense in depth: the native layer already clamps classId, but a
        // detector is never trusted blindly at a layer boundary.
        if (classId < 0 || classId >= Detection.NUM_COCO_CLASSES) {
            Log.w(TAG, "Dropping detection with out-of-range classId=$classId")
            return null
        }
        if (x2 <= x1 || y2 <= y1) return null
        return Detection(
            classId = classId,
            label = label,
            confidence = score.coerceIn(0f, 1f),
            boundingBox = RectF(x1, y1, x2, y2),
            timestamp = timestamp
        )
    }

    companion object {
        private const val TAG = "NcnnObjectDetector"
    }
}
