package com.apps.naviai.data.calibration

/** Result of a user-performed focal-length calibration. */
data class CalibrationData(
    val focalLengthPixels: Float,
    val referenceLabel: String,
    val referenceDistanceMeters: Float,
    val referenceBoxHeightPixels: Float,
    val calibratedAtMs: Long
)
