package com.apps.naviai.detection.postprocessing

import android.graphics.RectF

/** Anything NMS needs to compare and rank -- kept generic so it works for
 *  both the Kotlin-side decode path (tests) and any future candidate type. */
interface NmsCandidate {
    val box: RectF
    val score: Float
    val classId: Int
}

/**
 * Class-aware greedy NMS: identical algorithm to the one running natively
 * in native_detector.cpp (highest score first, suppress same-class boxes
 * above the IoU threshold). Kept as a pure Kotlin implementation so it has
 * a fast, native-library-free unit test surface.
 */
object NmsProcessor {
    fun <T : NmsCandidate> apply(candidates: List<T>, iouThreshold: Float): List<T> {
        val sorted = candidates.sortedByDescending { it.score }
        val removed = BooleanArray(sorted.size)
        val kept = mutableListOf<T>()

        for (i in sorted.indices) {
            if (removed[i]) continue
            val a = sorted[i]
            kept += a
            for (j in i + 1 until sorted.size) {
                if (removed[j]) continue
                val b = sorted[j]
                if (a.classId == b.classId && iou(a.box, b.box) > iouThreshold) {
                    removed[j] = true
                }
            }
        }
        return kept
    }

    fun iou(a: RectF, b: RectF): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)

        val interW = (right - left).coerceAtLeast(0f)
        val interH = (bottom - top).coerceAtLeast(0f)
        val inter = interW * interH

        val areaA = (a.right - a.left).coerceAtLeast(0f) * (a.bottom - a.top).coerceAtLeast(0f)
        val areaB = (b.right - b.left).coerceAtLeast(0f) * (b.bottom - b.top).coerceAtLeast(0f)
        if (areaA <= 0f || areaB <= 0f) return 0f

        return inter / (areaA + areaB - inter + 1e-6f)
    }
}
