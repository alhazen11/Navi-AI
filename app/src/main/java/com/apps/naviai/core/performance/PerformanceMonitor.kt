package com.apps.naviai.core.performance

data class PerformanceStats(
    val fps: Float = 0f,
    val inferenceTimeMs: Double = 0.0,
    val totalPipelineTimeMs: Double = 0.0,
    val detectionCount: Int = 0
)

/**
 * Rolling-window FPS/latency tracker. Pure and allocation-light so it's
 * cheap to call once per analyzed frame; exposed to the UI via
 * DetectionViewModel so users (and developers) can see actual on-device
 * performance rather than a theoretical number.
 */
class PerformanceMonitor(private val windowSize: Int = 30) {
    private val frameTimestampsMs = ArrayDeque<Long>()
    private val inferenceTimesMs = ArrayDeque<Double>()
    private val pipelineTimesMs = ArrayDeque<Double>()

    fun recordFrame(
        nowMs: Long,
        inferenceTimeMs: Double,
        totalPipelineTimeMs: Double,
        detectionCount: Int
    ): PerformanceStats {
        pushBounded(frameTimestampsMs, nowMs)
        pushBounded(inferenceTimesMs, inferenceTimeMs)
        pushBounded(pipelineTimesMs, totalPipelineTimeMs)

        val fps = if (frameTimestampsMs.size >= 2) {
            val spanSeconds = (frameTimestampsMs.last() - frameTimestampsMs.first()) / 1000.0
            if (spanSeconds > 0) (frameTimestampsMs.size - 1) / spanSeconds else 0.0
        } else {
            0.0
        }

        return PerformanceStats(
            fps = fps.toFloat(),
            inferenceTimeMs = if (inferenceTimesMs.isEmpty()) 0.0 else inferenceTimesMs.average(),
            totalPipelineTimeMs = if (pipelineTimesMs.isEmpty()) 0.0 else pipelineTimesMs.average(),
            detectionCount = detectionCount
        )
    }

    fun reset() {
        frameTimestampsMs.clear()
        inferenceTimesMs.clear()
        pipelineTimesMs.clear()
    }

    private fun <T> pushBounded(deque: ArrayDeque<T>, value: T) {
        deque.addLast(value)
        if (deque.size > windowSize) deque.removeFirst()
    }
}
