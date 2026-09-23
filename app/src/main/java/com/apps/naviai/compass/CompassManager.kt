package com.apps.naviai.compass

import kotlinx.coroutines.flow.Flow

data class HeadingReading(
    /** 0-360, 0 = magnetic north, clockwise. */
    val azimuthDegrees: Double,
    /** False while the OS reports low sensor accuracy (e.g. magnetic interference) -- callers should not trust this reading for turn guidance while false. */
    val isReliable: Boolean
)

interface CompassManager {
    /** Emits smoothed compass headings while collected; sensor listening stops when the collector cancels. */
    fun headingUpdates(): Flow<HeadingReading>
}
