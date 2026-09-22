package com.apps.naviai.data.calibration

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalibrationRepositoryTest {

    // Shares a Robolectric-simulated DataStore file across @Test methods in
    // this class, so each test must start from a clean slate.
    @Before
    fun resetPersistedState() = runTest {
        newRepository().reset()
    }

    private fun newRepository() = CalibrationRepository(ApplicationProvider.getApplicationContext())

    @Test
    fun `calibrate computes focal length from the known-distance formula`() = runTest {
        val repository = newRepository()

        // focalLength = (pixelHeight * knownDistance) / realHeight
        // person real height = 1.70m; pixelHeight=340px at knownDistance=2m -> focalLength = 340*2/1.70 = 400
        val result = repository.calibrate(referenceLabel = "person", referenceDistanceMeters = 2f, referenceBoxHeightPixels = 340f)

        assertTrue(result is CalibrationResult.Success)
        val calibration = (result as CalibrationResult.Success).calibration
        assertEquals(400f, calibration.focalLengthPixels, 0.5f)
    }

    @Test
    fun `calibrate persists and is readable back from the flow`() = runTest {
        val repository = newRepository()

        repository.calibrate("person", 2f, 340f)
        val stored = repository.calibration.first()

        assertEquals(400f, stored!!.focalLengthPixels, 0.5f)
        assertEquals("person", stored.referenceLabel)
    }

    @Test
    fun `rejects zero or negative distance`() = runTest {
        val repository = newRepository()
        val result = repository.calibrate("person", referenceDistanceMeters = 0f, referenceBoxHeightPixels = 100f)

        assertTrue(result is CalibrationResult.Failure)
    }

    @Test
    fun `rejects zero or negative box height`() = runTest {
        val repository = newRepository()
        val result = repository.calibrate("person", referenceDistanceMeters = 2f, referenceBoxHeightPixels = 0f)

        assertTrue(result is CalibrationResult.Failure)
    }

    @Test
    fun `rejects an unknown reference label`() = runTest {
        val repository = newRepository()
        val result = repository.calibrate("not-a-known-object", 2f, 100f)

        assertTrue(result is CalibrationResult.Failure)
    }

    @Test
    fun `reset clears a previously saved calibration`() = runTest {
        val repository = newRepository()
        repository.calibrate("person", 2f, 340f)

        repository.reset()

        assertNull(repository.calibration.first())
    }
}
