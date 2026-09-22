package com.apps.naviai.audio

import android.graphics.RectF
import com.apps.naviai.detection.detector.Detection
import com.apps.naviai.detection.risk.RiskLevel
import com.apps.naviai.detection.tracking.MovementDirection
import com.apps.naviai.detection.tracking.TrackedObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakeSpeaker : Speaker {
    val spoken = mutableListOf<Triple<String, Boolean, String>>()
    override fun speak(text: String, flushQueue: Boolean, utteranceId: String) {
        spoken += Triple(text, flushQueue, utteranceId)
    }
    override fun stop() { spoken.clear() }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnnouncementManagerTest {

    private fun trackedObject(
        id: Int,
        risk: RiskLevel,
        lastAnnouncedAtMs: Long? = null,
        framesTracked: Int = 5,
        distance: Float? = 2.0f,
        movement: MovementDirection = MovementDirection.STATIONARY,
        label: String = "person"
    ): TrackedObject = TrackedObject(
        trackingId = id,
        detection = Detection(0, label, 0.9f, RectF(0f, 0f, 50f, 100f), 0L),
        estimatedDistanceMeters = distance,
        distanceConfidence = 0.8f,
        movementDirection = movement,
        riskLevel = risk,
        framesTracked = framesTracked,
        lastAnnouncedAtMs = lastAnnouncedAtMs
    )

    @Test
    fun `announces an object above the minimum risk threshold`() {
        val speaker = FakeSpeaker()
        val manager = AnnouncementManager(speaker)

        val announcements = manager.evaluate(
            listOf(trackedObject(1, RiskLevel.MEDIUM)),
            AnnouncementLanguage.ENGLISH,
            nowMs = 1000
        ) { _, _ -> }

        assertEquals(1, announcements.size)
        assertEquals(1, speaker.spoken.size)
    }

    @Test
    fun `never announces a brand new one-frame track`() {
        val speaker = FakeSpeaker()
        val manager = AnnouncementManager(speaker)

        val announcements = manager.evaluate(
            listOf(trackedObject(1, RiskLevel.HIGH, framesTracked = 1)),
            AnnouncementLanguage.ENGLISH,
            nowMs = 1000
        ) { _, _ -> }

        assertTrue(announcements.isEmpty())
    }

    @Test
    fun `does not repeat the same object within the cooldown window`() {
        val speaker = FakeSpeaker()
        val manager = AnnouncementManager(speaker).apply { normalCooldownMs = 4000 }

        val announcements = manager.evaluate(
            listOf(trackedObject(1, RiskLevel.MEDIUM, lastAnnouncedAtMs = 1000)),
            AnnouncementLanguage.ENGLISH,
            nowMs = 2500 // only 1.5s after the last announcement, cooldown is 4s
        ) { _, _ -> }

        assertTrue(announcements.isEmpty())
    }

    @Test
    fun `re-announces after the cooldown window elapses`() {
        val speaker = FakeSpeaker()
        val manager = AnnouncementManager(speaker).apply { normalCooldownMs = 4000 }

        val announcements = manager.evaluate(
            listOf(trackedObject(1, RiskLevel.MEDIUM, lastAnnouncedAtMs = 1000)),
            AnnouncementLanguage.ENGLISH,
            nowMs = 6000
        ) { _, _ -> }

        assertEquals(1, announcements.size)
    }

    @Test
    fun `critical risk uses a shorter cooldown than normal risk`() {
        val speaker = FakeSpeaker()
        val manager = AnnouncementManager(speaker).apply {
            normalCooldownMs = 4000
            criticalCooldownMs = 1000
        }

        // 1.5s since last announcement: too soon for normal, fine for critical.
        val announcements = manager.evaluate(
            listOf(trackedObject(1, RiskLevel.CRITICAL, lastAnnouncedAtMs = 1000, distance = 0.4f)),
            AnnouncementLanguage.ENGLISH,
            nowMs = 2500
        ) { _, _ -> }

        assertEquals(1, announcements.size)
        assertTrue(announcements[0].isUrgent)
    }

    @Test
    fun `critical announcement flushes the speech queue`() {
        val speaker = FakeSpeaker()
        val manager = AnnouncementManager(speaker)

        manager.evaluate(listOf(trackedObject(1, RiskLevel.CRITICAL, distance = 0.3f)), AnnouncementLanguage.ENGLISH, 1000) { _, _ -> }

        assertTrue(speaker.spoken[0].second) // flushQueue = true
    }

    @Test
    fun `non-critical announcement does not flush the speech queue`() {
        val speaker = FakeSpeaker()
        val manager = AnnouncementManager(speaker)

        manager.evaluate(listOf(trackedObject(1, RiskLevel.LOW)), AnnouncementLanguage.ENGLISH, 1000) { _, _ -> }

        assertTrue(speaker.spoken.isEmpty() == false)
        assertEquals(false, speaker.spoken[0].second)
    }

    @Test
    fun `objects below the minimum risk are never announced`() {
        val speaker = FakeSpeaker()
        val manager = AnnouncementManager(speaker).apply { minRiskToAnnounce = RiskLevel.MEDIUM }

        val announcements = manager.evaluate(
            listOf(trackedObject(1, RiskLevel.LOW)),
            AnnouncementLanguage.ENGLISH,
            nowMs = 1000
        ) { _, _ -> }

        assertTrue(announcements.isEmpty())
    }

    @Test
    fun `indonesian sentence for an object ahead matches the expected phrasing style`() {
        val speaker = FakeSpeaker()
        val manager = AnnouncementManager(speaker)

        manager.evaluate(
            listOf(trackedObject(1, RiskLevel.LOW, label = "person", distance = 1.0f)),
            AnnouncementLanguage.INDONESIAN,
            nowMs = 1000
        ) { _, _ -> }

        assertTrue(speaker.spoken[0].first.startsWith("Orang di depan"))
    }

    @Test
    fun `onAnnounced callback fires once per spoken object`() {
        val speaker = FakeSpeaker()
        val manager = AnnouncementManager(speaker)
        val announcedIds = mutableListOf<Int>()

        manager.evaluate(
            listOf(trackedObject(1, RiskLevel.MEDIUM), trackedObject(2, RiskLevel.HIGH)),
            AnnouncementLanguage.ENGLISH,
            nowMs = 1000
        ) { id, _ -> announcedIds += id }

        assertEquals(setOf(1, 2), announcedIds.toSet())
    }
}
