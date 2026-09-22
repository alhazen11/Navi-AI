package com.apps.naviai.native

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.apps.naviai.detection.detector.DetectorState
import com.apps.naviai.detection.detector.FrameInput
import com.apps.naviai.detection.detector.NcnnObjectDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real NCNN pipeline end-to-end -- native library load, model
 * load from bundled assets, and one inference pass -- against the actual
 * .so and yolo_model.ncnn.* files packaged in the APK. Requires a connected
 * device or emulator: `./gradlew connectedAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class NcnnObjectDetectorInstrumentedTest {

    @Test
    fun nativeLibraryLoads() {
        assertTrue("libnaviai_ncnn.so failed to load", NcnnJniBridge.isLibraryLoaded)
    }

    @Test
    fun modelInitializesFromBundledAssets() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val detector = NcnnObjectDetector(context, Dispatchers.Default)

        val ok = detector.initialize(useVulkan = false)

        assertTrue("Model failed to initialize: ${NcnnJniBridge.nativeGetLastError()}", ok)
        assertTrue(detector.state is DetectorState.Ready)
        detector.release()
    }

    @Test
    fun detectOnASyntheticFrameNeverReturnsAnOutOfRangeClassId() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val detector = NcnnObjectDetector(context, Dispatchers.Default)
        detector.initialize(useVulkan = false)

        val width = 320
        val height = 240
        // Flat gray frame -- not expected to trigger real detections, this
        // exercises the full preprocess/infer/postprocess path without
        // crashing and validates output shape handling end to end.
        val rgba = ByteArray(width * height * 4) { 128.toByte() }
        val frame = FrameInput(rgba, width, height, rotationDegrees = 0, mirror = false)

        val result = detector.detect(frame, confidenceThreshold = 0.4f, iouThreshold = 0.45f)

        result.detections.forEach { detection ->
            assertTrue("classId ${detection.classId} out of range", detection.classId in 0 until 80)
            assertTrue(detection.confidence in 0f..1f)
        }
        detector.release()
    }

    @Test
    fun rotatedFrameDoesNotCrashTheNativePipeline() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val detector = NcnnObjectDetector(context, Dispatchers.Default)
        detector.initialize(useVulkan = false)

        val width = 240
        val height = 320
        val rgba = ByteArray(width * height * 4) { 64.toByte() }

        for (rotation in intArrayOf(0, 90, 180, 270)) {
            val frame = FrameInput(rgba, width, height, rotationDegrees = rotation, mirror = false)
            val result = detector.detect(frame, confidenceThreshold = 0.4f, iouThreshold = 0.45f)
            assertTrue(result.inferenceTimeMs >= 0.0)
        }
        detector.release()
    }

    @Test
    fun invalidModelPathsFailGracefullyInsteadOfCrashing() {
        val ok = NcnnJniBridge.nativeInit(
            paramPath = "/nonexistent/yolo_model.ncnn.param",
            binPath = "/nonexistent/yolo_model.ncnn.bin",
            labels = Array(80) { "class_$it" },
            useVulkan = false
        )

        assertFalse(ok)
        assertTrue(NcnnJniBridge.nativeGetLastError().isNotBlank())
    }

    @Test
    fun detectBeforeInitReturnsEmptyInsteadOfCrashing() {
        NcnnJniBridge.nativeRelease()
        val rgba = ByteArray(64 * 64 * 4)
        val result = NcnnJniBridge.nativeDetect(rgba, 64, 64, 0, false, 0.4f, 0.45f)
        assertTrue(result.isEmpty())
    }
}
