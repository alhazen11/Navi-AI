package com.apps.naviai.audio

import com.apps.naviai.detection.risk.RiskLevel
import com.apps.naviai.detection.tracking.MovementDirection
import com.apps.naviai.detection.tracking.TrackedObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

data class Announcement(val trackingId: Int, val text: String, val isUrgent: Boolean)

/**
 * Decides *what* to say and *when*, so speech is never triggered on every
 * frame: each track has its own cooldown (shorter for CRITICAL risk so
 * genuinely urgent warnings aren't delayed by the normal cadence), and a
 * track that hasn't persisted for a couple of frames yet is not announced,
 * since a single noisy detection is not worth interrupting the user for.
 */
@Singleton
class AnnouncementManager @Inject constructor(private val tts: Speaker) {

    var normalCooldownMs: Long = 4000L
    var criticalCooldownMs: Long = 1500L
    var minRiskToAnnounce: RiskLevel = RiskLevel.LOW

    /**
     * @param onAnnounced invoked once per object actually spoken, so the
     *   caller can persist the timestamp back onto the track (via
     *   ObjectTracker.markAnnounced) for future cooldown checks.
     */
    fun evaluate(
        trackedObjects: List<TrackedObject>,
        language: AnnouncementLanguage,
        nowMs: Long,
        onAnnounced: (trackingId: Int, atMs: Long) -> Unit
    ): List<Announcement> {
        val toAnnounce = trackedObjects
            .filter { it.riskLevel.priority >= minRiskToAnnounce.priority }
            .filter { it.framesTracked >= MIN_FRAMES_BEFORE_ANNOUNCE }
            .filter { shouldAnnounce(it, nowMs) }
            .sortedByDescending { it.riskLevel.priority }
            // Cap how many objects get spoken about per evaluation so a
            // crowded scene doesn't queue up a wall of speech.
            .take(MAX_ANNOUNCEMENTS_PER_PASS)

        val announcements = toAnnounce.map { tracked ->
            Announcement(
                trackingId = tracked.trackingId,
                text = buildSentence(tracked, language),
                isUrgent = tracked.riskLevel == RiskLevel.CRITICAL
            )
        }

        announcements.forEach { announcement ->
            onAnnounced(announcement.trackingId, nowMs)
            tts.speak(announcement.text, announcement.isUrgent, "track_${announcement.trackingId}_$nowMs")
        }

        return announcements
    }

    private fun shouldAnnounce(tracked: TrackedObject, nowMs: Long): Boolean {
        val last = tracked.lastAnnouncedAtMs ?: return true
        val cooldown = if (tracked.riskLevel == RiskLevel.CRITICAL) criticalCooldownMs else normalCooldownMs
        return nowMs - last >= cooldown
    }

    private fun buildSentence(tracked: TrackedObject, language: AnnouncementLanguage): String {
        val label = localizedLabel(tracked.detection.label, language)
        val distance = tracked.estimatedDistanceMeters
        val hasReliableDistance = distance != null && tracked.distanceConfidence >= 0.25f

        return when {
            tracked.riskLevel == RiskLevel.CRITICAL && !hasReliableDistance ->
                genericCriticalPhrase(language)

            tracked.riskLevel == RiskLevel.CRITICAL ->
                criticalLabeledPhrase(label, language)

            tracked.movementDirection == MovementDirection.MOVING_LEFT_TO_RIGHT ->
                lateralPhrase(label, fromLeft = true, language)

            tracked.movementDirection == MovementDirection.MOVING_RIGHT_TO_LEFT ->
                lateralPhrase(label, fromLeft = false, language)

            else -> aheadPhrase(label, distance, hasReliableDistance, language)
        }
    }

    private fun aheadPhrase(label: String, distance: Float?, hasReliableDistance: Boolean, language: AnnouncementLanguage): String {
        val distancePhrase = if (hasReliableDistance && distance != null) {
            distancePhrase(distance, language)
        } else null
        return when (language) {
            AnnouncementLanguage.INDONESIAN ->
                if (distancePhrase != null) "$label di depan, jarak $distancePhrase."
                else "$label di depan."
            AnnouncementLanguage.ENGLISH ->
                if (distancePhrase != null) "$label ahead, $distancePhrase."
                else "$label ahead."
        }
    }

    private fun genericCriticalPhrase(language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Hati-hati, ada penghalang dekat."
        AnnouncementLanguage.ENGLISH -> "Caution, obstacle very close."
    }

    private fun criticalLabeledPhrase(label: String, language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "Hati-hati, $label sangat dekat di depan."
        AnnouncementLanguage.ENGLISH -> "Caution, $label very close ahead."
    }

    private fun lateralPhrase(label: String, fromLeft: Boolean, language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> "$label bergerak dari ${if (fromLeft) "kiri" else "kanan"}."
        AnnouncementLanguage.ENGLISH -> "$label moving from the ${if (fromLeft) "left" else "right"}."
    }

    private fun distancePhrase(distanceMeters: Float, language: AnnouncementLanguage): String {
        val rounded = distanceMeters.roundToInt()
        return when (language) {
            AnnouncementLanguage.INDONESIAN ->
                if (distanceMeters < 1f) "kurang dari satu meter"
                else "sekitar ${indonesianNumber(rounded)} meter"
            AnnouncementLanguage.ENGLISH ->
                if (distanceMeters < 1f) "less than one meter away"
                else "about $rounded meter${if (rounded == 1) "" else "s"} away"
        }
    }

    private fun indonesianNumber(n: Int): String = INDONESIAN_NUMBERS[n] ?: n.toString()

    private fun localizedLabel(cocoLabel: String, language: AnnouncementLanguage): String = when (language) {
        AnnouncementLanguage.INDONESIAN -> INDONESIAN_LABELS[cocoLabel] ?: cocoLabel.replaceFirstChar { it.uppercase() }
        AnnouncementLanguage.ENGLISH -> cocoLabel.replaceFirstChar { it.uppercase() }
    }

    private companion object {
        const val MIN_FRAMES_BEFORE_ANNOUNCE = 2
        const val MAX_ANNOUNCEMENTS_PER_PASS = 3

        val INDONESIAN_NUMBERS = mapOf(
            1 to "satu", 2 to "dua", 3 to "tiga", 4 to "empat", 5 to "lima",
            6 to "enam", 7 to "tujuh", 8 to "delapan", 9 to "sembilan", 10 to "sepuluh"
        )

        val INDONESIAN_LABELS = mapOf(
            "person" to "Orang", "bicycle" to "Sepeda", "car" to "Mobil", "motorcycle" to "Sepeda motor",
            "bus" to "Bus", "train" to "Kereta", "truck" to "Truk", "boat" to "Perahu",
            "fire hydrant" to "Hidran", "stop sign" to "Rambu berhenti", "parking meter" to "Meteran parkir",
            "traffic light" to "Lampu lalu lintas", "bench" to "Bangku", "bird" to "Burung", "cat" to "Kucing",
            "dog" to "Anjing", "horse" to "Kuda", "sheep" to "Domba", "cow" to "Sapi",
            "backpack" to "Tas ransel", "umbrella" to "Payung", "handbag" to "Tas tangan",
            "suitcase" to "Koper", "skateboard" to "Papan seluncur", "surfboard" to "Papan selancar",
            "chair" to "Kursi", "couch" to "Sofa", "potted plant" to "Tanaman pot", "bed" to "Tempat tidur",
            "dining table" to "Meja makan", "toilet" to "Toilet", "tv" to "Televisi", "laptop" to "Laptop",
            "refrigerator" to "Kulkas", "oven" to "Oven", "sink" to "Wastafel", "book" to "Buku",
            "vase" to "Vas", "teddy bear" to "Boneka beruang", "wine glass" to "Gelas anggur",
            "cup" to "Cangkir", "bottle" to "Botol"
        )
    }
}
