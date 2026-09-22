package com.apps.naviai.detection.preprocessing

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LetterboxTest {

    @Test
    fun `wide image pads top and bottom`() {
        // 1280x720 -> 320x320: scale = 320/1280 = 0.25, resized = 320x180, pad top+bottom = 70 each
        val params = Letterbox.compute(srcWidth = 1280, srcHeight = 720, targetSize = 320)

        assertEquals(0.25f, params.scale, 1e-4f)
        assertEquals(320, params.resizedWidth)
        assertEquals(180, params.resizedHeight)
        assertEquals(0, params.padLeft)
        assertEquals(70, params.padTop)
    }

    @Test
    fun `tall image pads left and right`() {
        val params = Letterbox.compute(srcWidth = 720, srcHeight = 1280, targetSize = 320)

        assertEquals(0.25f, params.scale, 1e-4f)
        assertEquals(180, params.resizedWidth)
        assertEquals(320, params.resizedHeight)
        assertEquals(70, params.padLeft)
        assertEquals(0, params.padTop)
    }

    @Test
    fun `square image needs no padding`() {
        val params = Letterbox.compute(srcWidth = 320, srcHeight = 320, targetSize = 320)

        assertEquals(1f, params.scale, 1e-4f)
        assertEquals(0, params.padLeft)
        assertEquals(0, params.padTop)
    }

    @Test
    fun `restoreToSource maps a full-canvas box back to the full source image`() {
        val params = Letterbox.compute(srcWidth = 1280, srcHeight = 720, targetSize = 320)
        // A box spanning the entire resized (non-padded) region in model space.
        val modelSpaceBox = RectF(0f, 70f, 320f, 250f)

        val restored = params.restoreToSource(modelSpaceBox, srcWidth = 1280, srcHeight = 720)

        assertEquals(0f, restored.left, 0.5f)
        assertEquals(0f, restored.top, 0.5f)
        assertEquals(1280f, restored.right, 0.5f)
        assertEquals(720f, restored.bottom, 0.5f)
    }

    @Test
    fun `restoreToSource clamps coordinates that fall inside the padding`() {
        val params = Letterbox.compute(srcWidth = 1280, srcHeight = 720, targetSize = 320)
        // A box that starts inside the top padding band (before the real image begins).
        val modelSpaceBox = RectF(10f, 0f, 100f, 300f)

        val restored = params.restoreToSource(modelSpaceBox, srcWidth = 1280, srcHeight = 720)

        assertEquals(0f, restored.top, 1e-3f)
        assertTrue(restored.bottom <= 720f)
    }
}
