package com.apps.naviai.detection.postprocessing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YoloOutputParserTest {

    private fun singleAnchor(cx: Float, cy: Float, w: Float, h: Float, classScores: FloatArray): FloatArray {
        require(classScores.size == YoloOutputParser.NUM_CLASSES)
        return floatArrayOf(cx, cy, w, h, *classScores)
    }

    @Test
    fun `decodes a single confident anchor into a center-form box`() {
        val scores = FloatArray(YoloOutputParser.NUM_CLASSES)
        scores[5] = 0.92f // class id 5 = "bus" in COCO ordering, arbitrary here
        val output = singleAnchor(cx = 160f, cy = 160f, w = 40f, h = 80f, classScores = scores)

        val candidates = YoloOutputParser.parse(output, numAnchors = 1, confidenceThreshold = 0.25f)

        assertEquals(1, candidates.size)
        val c = candidates[0]
        assertEquals(5, c.classId)
        assertEquals(0.92f, c.score, 1e-4f)
        assertEquals(140f, c.box.left, 1e-3f)  // 160 - 40/2
        assertEquals(120f, c.box.top, 1e-3f)   // 160 - 80/2
        assertEquals(180f, c.box.right, 1e-3f)
        assertEquals(200f, c.box.bottom, 1e-3f)
    }

    @Test
    fun `rejects anchors below the confidence threshold`() {
        val scores = FloatArray(YoloOutputParser.NUM_CLASSES)
        scores[0] = 0.1f
        val output = singleAnchor(50f, 50f, 20f, 20f, scores)

        val candidates = YoloOutputParser.parse(output, numAnchors = 1, confidenceThreshold = 0.25f)

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `rejects degenerate zero-size boxes`() {
        val scores = FloatArray(YoloOutputParser.NUM_CLASSES)
        scores[0] = 0.9f
        val output = singleAnchor(50f, 50f, 0f, 0f, scores)

        val candidates = YoloOutputParser.parse(output, numAnchors = 1, confidenceThreshold = 0.25f)

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `never produces a classId outside 0 until 80`() {
        val scores = FloatArray(YoloOutputParser.NUM_CLASSES) { i -> if (i == 79) 0.99f else 0f }
        val output = singleAnchor(10f, 10f, 5f, 5f, scores)

        val candidates = YoloOutputParser.parse(output, numAnchors = 1, confidenceThreshold = 0.25f)

        assertEquals(1, candidates.size)
        assertTrue(candidates[0].classId in 0 until YoloOutputParser.NUM_CLASSES)
    }

    @Test
    fun `picks the highest scoring class, not objectness times class score`() {
        // Regression test for the exact bug fixed in native_detector.cpp: there is
        // no separate objectness channel, confidence is simply the best class score.
        val scores = FloatArray(YoloOutputParser.NUM_CLASSES)
        scores[2] = 0.7f
        scores[10] = 0.6f
        val output = singleAnchor(30f, 30f, 10f, 10f, scores)

        val candidates = YoloOutputParser.parse(output, numAnchors = 1, confidenceThreshold = 0.1f)

        assertEquals(1, candidates.size)
        assertEquals(2, candidates[0].classId)
        assertEquals(0.7f, candidates[0].score, 1e-4f)
    }

    @Test
    fun `throws when output buffer is too small for the declared anchor count`() {
        val tooSmall = FloatArray(10)
        try {
            YoloOutputParser.parse(tooSmall, numAnchors = 5, confidenceThreshold = 0.25f)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
