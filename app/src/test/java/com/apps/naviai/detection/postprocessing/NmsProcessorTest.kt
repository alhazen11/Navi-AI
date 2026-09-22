package com.apps.naviai.detection.postprocessing

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NmsProcessorTest {

    @Test
    fun `iou of identical boxes is 1`() {
        val box = RectF(0f, 0f, 10f, 10f)
        assertEquals(1f, NmsProcessor.iou(box, box), 1e-4f)
    }

    @Test
    fun `iou of non-overlapping boxes is 0`() {
        val a = RectF(0f, 0f, 10f, 10f)
        val b = RectF(20f, 20f, 30f, 30f)
        assertEquals(0f, NmsProcessor.iou(a, b), 1e-4f)
    }

    @Test
    fun `suppresses lower-score same-class overlapping box`() {
        val strong = YoloOutputParser.Candidate(RectF(0f, 0f, 10f, 10f), 0.9f, classId = 0)
        val weak = YoloOutputParser.Candidate(RectF(1f, 1f, 11f, 11f), 0.5f, classId = 0)

        val kept = NmsProcessor.apply(listOf(strong, weak), iouThreshold = 0.3f)

        assertEquals(1, kept.size)
        assertEquals(0.9f, kept[0].score, 1e-4f)
    }

    @Test
    fun `keeps overlapping boxes of different classes`() {
        val a = YoloOutputParser.Candidate(RectF(0f, 0f, 10f, 10f), 0.9f, classId = 0)
        val b = YoloOutputParser.Candidate(RectF(1f, 1f, 11f, 11f), 0.85f, classId = 1)

        val kept = NmsProcessor.apply(listOf(a, b), iouThreshold = 0.3f)

        assertEquals(2, kept.size)
    }

    @Test
    fun `keeps both boxes when below iou threshold`() {
        val a = YoloOutputParser.Candidate(RectF(0f, 0f, 10f, 10f), 0.9f, classId = 0)
        val b = YoloOutputParser.Candidate(RectF(9f, 9f, 20f, 20f), 0.8f, classId = 0)

        val kept = NmsProcessor.apply(listOf(a, b), iouThreshold = 0.3f)

        assertTrue(kept.size == 2)
    }
}
