package com.apps.naviai.detection.tracking

import android.graphics.PointF
import android.graphics.RectF
import com.apps.naviai.detection.detector.Detection
import com.apps.naviai.detection.distance.DistanceEstimate
import com.apps.naviai.detection.distance.DistanceSmoother
import com.apps.naviai.detection.postprocessing.NmsProcessor
import com.apps.naviai.detection.risk.RiskLevel
import java.util.ArrayDeque

/**
 * Lightweight multi-object tracker: greedy IoU/centroid matching between
 * frames, stable integer ids, per-track distance smoothing, and a coarse
 * approaching/receding/lateral movement classification derived from recent
 * history. Not a Kalman filter or a full MOT solution -- it is deliberately
 * simple so it stays cheap enough to run every frame on a mid-range phone.
 */
class ObjectTracker(
    private val iouMatchThreshold: Float = 0.3f,
    private val maxCentroidDistancePx: Float = 150f,
    private val trackTimeoutMs: Long = 1000L,
    private val historySize: Int = 8,
    private val distanceSmoother: DistanceSmoother = DistanceSmoother()
) {
    private class Track(
        val id: Int,
        var lastDetection: Detection,
        var lastRawDistance: DistanceEstimate?,
        var smoothedDistanceMeters: Float?,
        val positionHistory: ArrayDeque<PointF>,
        val distanceHistory: ArrayDeque<Float>,
        var lastSeenAtMs: Long,
        var framesTracked: Int,
        var lastAnnouncedAtMs: Long?
    )

    private val tracks = LinkedHashMap<Int, Track>()
    private var nextId = 1

    /**
     * @param observations this frame's raw detections paired with their
     *   (unsmoothed) per-frame distance estimate, if any.
     */
    fun update(observations: List<Pair<Detection, DistanceEstimate?>>, nowMs: Long): List<TrackedObject> {
        val unmatchedTrackIds = tracks.keys.toMutableSet()
        val matchedObservationIndices = mutableSetOf<Int>()

        // Greedy best-score-first matching, restricted to same-class pairs.
        data class Candidate(val trackId: Int, val obsIndex: Int, val score: Float)

        val candidates = mutableListOf<Candidate>()
        for (trackId in unmatchedTrackIds) {
            val track = tracks.getValue(trackId)
            observations.forEachIndexed { index, (detection, _) ->
                if (detection.classId != track.lastDetection.classId) return@forEachIndexed
                val iou = NmsProcessor.iou(track.lastDetection.boundingBox, detection.boundingBox)
                val centroidDist = centroidDistance(track.lastDetection.boundingBox, detection.boundingBox)
                val eligible = iou > iouMatchThreshold || centroidDist < maxCentroidDistancePx
                if (eligible) {
                    // Combine both signals into one score so IoU-heavy and
                    // motion-heavy matches both rank sensibly.
                    val score = iou + (1f - (centroidDist / maxCentroidDistancePx).coerceIn(0f, 1f))
                    candidates += Candidate(trackId, index, score)
                }
            }
        }

        candidates.sortByDescending { it.score }
        val usedTracks = mutableSetOf<Int>()
        val usedObservations = mutableSetOf<Int>()
        val assignments = mutableListOf<Candidate>()
        for (c in candidates) {
            if (c.trackId in usedTracks || c.obsIndex in usedObservations) continue
            usedTracks += c.trackId
            usedObservations += c.obsIndex
            assignments += c
        }

        for (assignment in assignments) {
            val track = tracks.getValue(assignment.trackId)
            val (detection, distanceEstimate) = observations[assignment.obsIndex]
            applyObservation(track, detection, distanceEstimate, nowMs)
            unmatchedTrackIds -= assignment.trackId
            matchedObservationIndices += assignment.obsIndex
        }

        // New tracks for anything unmatched.
        observations.forEachIndexed { index, (detection, distanceEstimate) ->
            if (index in matchedObservationIndices) return@forEachIndexed
            val id = nextId++
            val track = Track(
                id = id,
                lastDetection = detection,
                lastRawDistance = distanceEstimate,
                smoothedDistanceMeters = distanceSmoother.smooth(id, distanceEstimate?.distanceMeters),
                positionHistory = ArrayDeque<PointF>().apply { addLast(centroidOf(detection.boundingBox)) },
                distanceHistory = ArrayDeque<Float>().apply {
                    distanceEstimate?.distanceMeters?.let { addLast(it) }
                },
                lastSeenAtMs = nowMs,
                framesTracked = 1,
                lastAnnouncedAtMs = null
            )
            tracks[id] = track
        }

        // Age out tracks we haven't seen in a while.
        val staleIds = tracks.values
            .filter { nowMs - it.lastSeenAtMs > trackTimeoutMs }
            .map { it.id }
        staleIds.forEach { id ->
            tracks.remove(id)
            distanceSmoother.reset(id)
        }

        return tracks.values.map { it.toTrackedObject() }
    }

    fun markAnnounced(trackingId: Int, atMs: Long) {
        tracks[trackingId]?.lastAnnouncedAtMs = atMs
    }

    fun reset() {
        tracks.clear()
        distanceSmoother.clear()
        nextId = 1
    }

    private fun applyObservation(
        track: Track,
        detection: Detection,
        distanceEstimate: DistanceEstimate?,
        nowMs: Long
    ) {
        track.lastDetection = detection
        track.lastRawDistance = distanceEstimate
        track.smoothedDistanceMeters = distanceSmoother.smooth(track.id, distanceEstimate?.distanceMeters)
        track.lastSeenAtMs = nowMs
        track.framesTracked += 1

        track.positionHistory.addLast(centroidOf(detection.boundingBox))
        if (track.positionHistory.size > historySize) track.positionHistory.removeFirst()

        track.smoothedDistanceMeters?.let { track.distanceHistory.addLast(it) }
        if (track.distanceHistory.size > historySize) track.distanceHistory.removeFirst()
    }

    private fun Track.toTrackedObject(): TrackedObject {
        val movement = classifyMovement(this)
        return TrackedObject(
            trackingId = id,
            detection = lastDetection,
            estimatedDistanceMeters = smoothedDistanceMeters,
            distanceConfidence = lastRawDistance?.confidence ?: 0f,
            movementDirection = movement,
            riskLevel = RiskLevel.SAFE, // finalized downstream by RiskAssessmentEngine
            framesTracked = framesTracked,
            lastAnnouncedAtMs = lastAnnouncedAtMs
        )
    }

    private fun classifyMovement(track: Track): MovementDirection {
        if (track.framesTracked < MIN_FRAMES_FOR_MOVEMENT) return MovementDirection.UNKNOWN

        if (track.distanceHistory.size >= 3) {
            val oldest = track.distanceHistory.first()
            val newest = track.distanceHistory.last()
            val delta = newest - oldest
            when {
                delta <= -APPROACH_THRESHOLD_METERS -> return MovementDirection.APPROACHING
                delta >= APPROACH_THRESHOLD_METERS -> return MovementDirection.RECEDING
            }
        }

        if (track.positionHistory.size >= 3) {
            val oldest = track.positionHistory.first()
            val newest = track.positionHistory.last()
            val dx = newest.x - oldest.x
            if (kotlin.math.abs(dx) >= LATERAL_THRESHOLD_PX) {
                return if (dx > 0) MovementDirection.MOVING_LEFT_TO_RIGHT else MovementDirection.MOVING_RIGHT_TO_LEFT
            }
        }

        return MovementDirection.STATIONARY
    }

    private fun centroidOf(box: RectF): PointF = PointF(box.centerX(), box.centerY())

    private fun centroidDistance(a: RectF, b: RectF): Float {
        val dx = a.centerX() - b.centerX()
        val dy = a.centerY() - b.centerY()
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private companion object {
        const val MIN_FRAMES_FOR_MOVEMENT = 3
        const val APPROACH_THRESHOLD_METERS = 0.15f
        const val LATERAL_THRESHOLD_PX = 40f
    }
}
