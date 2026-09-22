package com.apps.naviai.detection.detector

/** Lifecycle state of the on-device detector. */
sealed interface DetectorState {
    data object Uninitialized : DetectorState
    data object Initializing : DetectorState
    data object Ready : DetectorState
    data class Error(val message: String, val cause: Throwable? = null) : DetectorState
}

/** One analyzed camera frame, pre-rotation/mirror metadata included. */
data class FrameInput(
    val rgba: ByteArray,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val mirror: Boolean
)

data class InferenceResult(
    val detections: List<Detection>,
    val inferenceTimeMs: Double
)

/**
 * Abstraction over the on-device object detector so the rest of the app
 * (camera pipeline, tests) never depends on NCNN/JNI directly.
 */
interface ObjectDetector {
    val state: DetectorState

    /** Loads the model. Safe to call again to reload (e.g. backend switch). */
    suspend fun initialize(useVulkan: Boolean): Boolean

    /** Runs inference on [frame]. Returns an empty result if not ready. */
    suspend fun detect(
        frame: FrameInput,
        confidenceThreshold: Float,
        iouThreshold: Float
    ): InferenceResult

    fun isVulkanSupported(): Boolean

    fun release()
}
