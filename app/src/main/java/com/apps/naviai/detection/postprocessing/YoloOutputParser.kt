package com.apps.naviai.detection.postprocessing

import android.graphics.RectF
import com.apps.naviai.detection.preprocessing.LetterboxParams

/**
 * Decodes the raw YOLO output tensor into candidate boxes in *model input*
 * (letterboxed 320x320) pixel space -- caller applies
 * [LetterboxParams.restoreToSource] afterwards to map into source-image
 * coordinates.
 *
 * This mirrors, feature-for-feature, the layout that native_detector.cpp
 * decodes on-device -- confirmed by inspecting the exported
 * yolo_model.ncnn.param graph rather than assumed:
 *
 *   Per anchor: [cx, cy, w, h, class_0 .. class_79]  (84 values, row-major)
 *
 *   - cx, cy, w, h are ALREADY decoded to absolute pixel coordinates in the
 *     320x320 letterboxed input (the DFL distribution decode, anchor-point
 *     offset, and stride multiply are fused into the ncnn graph itself).
 *   - class_0..class_79 already have sigmoid applied.
 *   - There is NO separate objectness channel -- confidence is simply the
 *     best class score, not objectness * class_score.
 *
 * This is deliberately kept as a pure, native-library-free implementation
 * so output-format regressions (wrong channel count, wrong class range,
 * wrong box decode) are caught by a fast JVM unit test instead of only
 * being discoverable on-device.
 */
object YoloOutputParser {
    const val NUM_BOX_COORDS = 4
    const val NUM_CLASSES = 80
    const val NUM_FEATURES = NUM_BOX_COORDS + NUM_CLASSES

    data class Candidate(
        override val box: RectF,
        override val score: Float,
        override val classId: Int
    ) : NmsCandidate

    /**
     * @param output row-major [numAnchors][NUM_FEATURES] flattened array.
     */
    fun parse(
        output: FloatArray,
        numAnchors: Int,
        confidenceThreshold: Float
    ): List<Candidate> {
        require(output.size >= numAnchors * NUM_FEATURES) {
            "output too small: expected >= ${numAnchors * NUM_FEATURES}, got ${output.size}"
        }

        val candidates = mutableListOf<Candidate>()
        for (anchor in 0 until numAnchors) {
            val base = anchor * NUM_FEATURES
            val cx = output[base]
            val cy = output[base + 1]
            val w = output[base + 2]
            val h = output[base + 3]
            if (w <= 0f || h <= 0f) continue

            var bestClass = -1
            var bestScore = 0f
            for (c in 0 until NUM_CLASSES) {
                val s = output[base + NUM_BOX_COORDS + c]
                if (s > bestScore) {
                    bestScore = s
                    bestClass = c
                }
            }
            if (bestClass < 0 || bestClass >= NUM_CLASSES) continue
            if (bestScore < confidenceThreshold) continue

            val box = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
            candidates += Candidate(box, bestScore, bestClass)
        }
        return candidates
    }
}
