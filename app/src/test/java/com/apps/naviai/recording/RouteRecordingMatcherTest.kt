package com.apps.naviai.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteRecordingMatcherTest {

    @Test
    fun `recognizes the start command`() {
        assertTrue(RouteRecordingMatcher.isStart("mulai merekam jalan"))
        assertTrue(RouteRecordingMatcher.isStart("Mulai Merekam Jalan"))
        assertTrue(RouteRecordingMatcher.isStart("start recording route"))
    }

    @Test
    fun `recognizes the stop command`() {
        assertTrue(RouteRecordingMatcher.isStop("stop merekam jalan"))
        assertTrue(RouteRecordingMatcher.isStop("berhenti merekam jalan"))
        assertTrue(RouteRecordingMatcher.isStop("stop recording route"))
    }

    @Test
    fun `start and stop do not cross-match each other`() {
        assertFalse(RouteRecordingMatcher.isStart("stop merekam jalan"))
        assertFalse(RouteRecordingMatcher.isStop("mulai merekam jalan"))
    }

    @Test
    fun `unrelated commands match neither`() {
        assertFalse(RouteRecordingMatcher.isStart("jelaskan lingkungan saya"))
        assertFalse(RouteRecordingMatcher.isStop("jelaskan lingkungan saya"))
        assertFalse(RouteRecordingMatcher.isStart(""))
        assertFalse(RouteRecordingMatcher.isStop(""))
    }
}
