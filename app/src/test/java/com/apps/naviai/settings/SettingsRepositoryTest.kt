package com.apps.naviai.settings

import androidx.camera.core.CameraSelector
import androidx.test.core.app.ApplicationProvider
import com.apps.naviai.audio.AnnouncementLanguage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryTest {

    // The DataStore file lives on Robolectric's simulated filesystem and
    // persists across @Test methods in this class, so each test must start
    // from a clean slate rather than relying on JUnit's undefined method order.
    @Before
    fun resetPersistedState() = runTest {
        newRepository().resetToDefaults()
    }

    private fun newRepository() = SettingsRepository(ApplicationProvider.getApplicationContext())

    @Test
    fun `defaults are returned before anything is written`() = runTest {
        val repository = newRepository()
        val settings = repository.settings.first()

        assertEquals(0.45f, settings.confidenceThreshold, 1e-4f)
        assertEquals(AnnouncementLanguage.INDONESIAN, settings.speechLanguage)
        assertEquals(true, settings.enableVoiceAssistance)
    }

    @Test
    fun `a written value is persisted and read back`() = runTest {
        val repository = newRepository()

        repository.setConfidenceThreshold(0.6f)
        repository.setSpeechLanguage(AnnouncementLanguage.ENGLISH)
        repository.setCameraLensFacing(CameraSelector.LENS_FACING_FRONT)

        val settings = repository.settings.first()
        assertEquals(0.6f, settings.confidenceThreshold, 1e-4f)
        assertEquals(AnnouncementLanguage.ENGLISH, settings.speechLanguage)
        assertEquals(CameraSelector.LENS_FACING_FRONT, settings.cameraLensFacing)
    }

    @Test
    fun `values are clamped to a safe range`() = runTest {
        val repository = newRepository()

        repository.setConfidenceThreshold(5f) // way above 1.0
        repository.setAnnouncementIntervalMs(-100L)

        val settings = repository.settings.first()
        assertEquals(0.95f, settings.confidenceThreshold, 1e-4f)
        assertEquals(500L, settings.announcementIntervalMs)
    }

    @Test
    fun `resetToDefaults reverts every field`() = runTest {
        val repository = newRepository()
        repository.setConfidenceThreshold(0.9f)
        repository.setEnableVoiceAssistance(false)

        repository.resetToDefaults()

        val settings = repository.settings.first()
        assertEquals(0.45f, settings.confidenceThreshold, 1e-4f)
        assertEquals(true, settings.enableVoiceAssistance)
    }
}
