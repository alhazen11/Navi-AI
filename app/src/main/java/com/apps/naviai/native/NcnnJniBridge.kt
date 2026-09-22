package com.apps.naviai.native

/**
 * Thin JNI surface over `libnaviai_ncnn.so` (built from
 * app/src/main/cpp/native_detector.cpp). All calls are safe to invoke from
 * any thread; the native side serializes access with an internal mutex, but
 * callers should still funnel inference through a single dedicated
 * dispatcher (see FrameAnalyzer) to avoid queuing more frames than the
 * device can keep up with.
 */
object NcnnJniBridge {

    var isLibraryLoaded: Boolean = false
        private set

    init {
        isLibraryLoaded = try {
            System.loadLibrary("naviai_ncnn")
            true
        } catch (error: UnsatisfiedLinkError) {
            false
        }
    }

    /**
     * Loads the NCNN network from absolute file paths on disk (assets must
     * be copied out of the APK first, since NCNN's file loader needs a real
     * filesystem path). [labels] must have exactly 80 entries matching the
     * COCO class order the model was trained on.
     */
    external fun nativeInit(
        paramPath: String,
        binPath: String,
        labels: Array<String>,
        useVulkan: Boolean
    ): Boolean

    /**
     * Runs one detection pass.
     *
     * @param rgba raw RGBA8888 bytes, width*height*4 long, in sensor
     *   (un-rotated) orientation.
     * @param width sensor buffer width.
     * @param height sensor buffer height.
     * @param rotationDegrees clockwise rotation needed to make the buffer
     *   upright, from `ImageProxy.imageInfo.rotationDegrees`.
     * @param mirror true for the front camera, to match the mirrored
     *   preview shown to the user.
     * @return detections in upright display-space pixel coordinates,
     *   already NMS-filtered. Empty (not null) when nothing passed
     *   threshold or the model failed to initialize.
     */
    external fun nativeDetect(
        rgba: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        mirror: Boolean,
        confidenceThreshold: Float,
        iouThreshold: Float
    ): Array<NativeDetection>

    external fun nativeRelease()

    external fun nativeIsVulkanSupported(): Boolean

    external fun nativeGetLastError(): String
}
