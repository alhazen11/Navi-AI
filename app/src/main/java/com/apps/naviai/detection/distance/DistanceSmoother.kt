package com.apps.naviai.detection.distance

/**
 * Exponential-moving-average smoother for per-track distance estimates.
 * Raw single-frame distance readings are noisy (bounding box jitter alone
 * can swing the height-based formula by tens of centimeters), which would
 * otherwise cause jumpy TTS announcements and an unstable risk level.
 *
 * Keyed by tracking id so each object's history is independent; call
 * [reset] when a track is dropped so a stale value never leaks into a
 * different object that's later assigned the same id.
 */
class DistanceSmoother(private val alpha: Float = 0.35f) {
    init {
        require(alpha in 0f..1f) { "alpha must be in [0,1], was $alpha" }
    }

    private val smoothedByTrack = mutableMapOf<Int, Float>()

    /** Feed one raw reading (or null when no estimate is available this frame). */
    fun smooth(trackingId: Int, rawDistanceMeters: Float?): Float? {
        if (rawDistanceMeters == null) return smoothedByTrack[trackingId]

        val previous = smoothedByTrack[trackingId]
        val next = if (previous == null) {
            rawDistanceMeters
        } else {
            previous + alpha * (rawDistanceMeters - previous)
        }
        smoothedByTrack[trackingId] = next
        return next
    }

    fun reset(trackingId: Int) {
        smoothedByTrack.remove(trackingId)
    }

    fun clear() {
        smoothedByTrack.clear()
    }
}
