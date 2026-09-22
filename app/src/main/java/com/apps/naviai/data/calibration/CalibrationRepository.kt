package com.apps.naviai.data.calibration

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.apps.naviai.data.local.calibrationDataStore
import com.apps.naviai.detection.distance.ObjectDimensions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

sealed interface CalibrationResult {
    data class Success(val calibration: CalibrationData) : CalibrationResult
    data class Failure(val reason: String) : CalibrationResult
}

/**
 * Persists a user-performed known-distance calibration:
 * `focalLengthPixels = (pixelObjectHeight * knownDistanceMeters) / realObjectHeightMeters`.
 *
 * Loaded once at app startup and combined with the live frame size to build
 * [com.apps.naviai.domain.model.CameraParameters] for distance estimation.
 */
@Singleton
class CalibrationRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private object Keys {
        val FOCAL_LENGTH = floatPreferencesKey("focal_length_pixels")
        val REFERENCE_LABEL = stringPreferencesKey("reference_label")
        val REFERENCE_DISTANCE = floatPreferencesKey("reference_distance_meters")
        val REFERENCE_BOX_HEIGHT = floatPreferencesKey("reference_box_height_pixels")
        val CALIBRATED_AT = longPreferencesKey("calibrated_at_ms")
    }

    val calibration: Flow<CalibrationData?> = context.calibrationDataStore.data.map { prefs ->
        val focalLength = prefs[Keys.FOCAL_LENGTH] ?: return@map null
        CalibrationData(
            focalLengthPixels = focalLength,
            referenceLabel = prefs[Keys.REFERENCE_LABEL] ?: return@map null,
            referenceDistanceMeters = prefs[Keys.REFERENCE_DISTANCE] ?: return@map null,
            referenceBoxHeightPixels = prefs[Keys.REFERENCE_BOX_HEIGHT] ?: return@map null,
            calibratedAtMs = prefs[Keys.CALIBRATED_AT] ?: return@map null
        )
    }

    /**
     * @param referenceLabel a COCO class present in [ObjectDimensions] (e.g. "person").
     * @param referenceDistanceMeters actual measured distance to that object when captured.
     * @param referenceBoxHeightPixels the detected bounding-box height at that moment.
     */
    suspend fun calibrate(
        referenceLabel: String,
        referenceDistanceMeters: Float,
        referenceBoxHeightPixels: Float
    ): CalibrationResult {
        if (referenceDistanceMeters <= 0f) {
            return CalibrationResult.Failure("Reference distance must be greater than zero.")
        }
        if (referenceBoxHeightPixels <= 0f) {
            return CalibrationResult.Failure("Detected object height must be greater than zero.")
        }
        val realHeight = ObjectDimensions.forLabel(referenceLabel)?.typicalHeightMeters
            ?: return CalibrationResult.Failure("Unknown reference object: $referenceLabel")

        val focalLength = (referenceBoxHeightPixels * referenceDistanceMeters) / realHeight
        if (!focalLength.isFinite() || focalLength <= 0f) {
            return CalibrationResult.Failure("Calibration produced an invalid focal length; try again with a clearer view.")
        }

        val data = CalibrationData(
            focalLengthPixels = focalLength,
            referenceLabel = referenceLabel,
            referenceDistanceMeters = referenceDistanceMeters,
            referenceBoxHeightPixels = referenceBoxHeightPixels,
            calibratedAtMs = System.currentTimeMillis()
        )

        context.calibrationDataStore.edit { prefs ->
            prefs[Keys.FOCAL_LENGTH] = data.focalLengthPixels
            prefs[Keys.REFERENCE_LABEL] = data.referenceLabel
            prefs[Keys.REFERENCE_DISTANCE] = data.referenceDistanceMeters
            prefs[Keys.REFERENCE_BOX_HEIGHT] = data.referenceBoxHeightPixels
            prefs[Keys.CALIBRATED_AT] = data.calibratedAtMs
        }
        return CalibrationResult.Success(data)
    }

    suspend fun reset() {
        context.calibrationDataStore.edit { it.clear() }
    }
}
