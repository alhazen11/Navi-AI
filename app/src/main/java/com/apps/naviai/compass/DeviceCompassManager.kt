package com.apps.naviai.compass

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.apps.naviai.location.GeoMath
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Compass heading via the rotation vector sensor where available (fuses
 * accelerometer+magnetometer+gyroscope in hardware/OS -- far more stable
 * than a hand-rolled combination), falling back to a manual
 * accelerometer+magnetometer combination via
 * [SensorManager.getRotationMatrix]/[SensorManager.getOrientation] on
 * devices without a rotation vector sensor. Low-pass filtered (circular-
 * aware, so it doesn't spike crossing the 0/360 wraparound) to damp jitter.
 *
 * Orientation limitation (per spec -- phone vertical inside a pocket, not
 * held flat facing up): [SensorManager.getOrientation]'s azimuth is
 * computed relative to the DEVICE's own axes, not the screen, so it stays
 * meaningful for a phone held vertically in any rotation (portrait,
 * pocketed screen-to-leg, etc.) -- that is actually a *good* orientation
 * for this. The orientation that genuinely degrades heading accuracy is
 * the phone lying close to FLAT (screen up or down), where azimuth becomes
 * highly sensitive to small tilt -- not something this app can fully
 * correct for in software; if headings look erratic, check the phone isn't
 * lying flat.
 *
 * Magnetic interference: [HeadingReading.isReliable] mirrors the OS's own
 * [SensorEvent] accuracy report (drops near metal/electronics/magnets) --
 * callers should prompt the user to move away from interference or wave
 * the phone in a figure-8 (the standard magnetometer recalibration
 * gesture) rather than trust a low-accuracy reading for turn guidance; see
 * [com.apps.naviai.routenav.NavigationEngine] callers for where that's
 * surfaced as a spoken prompt.
 */
@Singleton
class DeviceCompassManager @Inject constructor(@ApplicationContext private val context: Context) : CompassManager {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    override fun headingUpdates(): Flow<HeadingReading> = callbackFlow {
        val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        var smoothedAzimuth: Double? = null
        var reliable = true

        fun emitAzimuth(rawAzimuthDegrees: Double) {
            val normalized = (rawAzimuthDegrees + 360) % 360
            val previous = smoothedAzimuth
            val smoothed = if (previous == null) {
                normalized
            } else {
                // Circular low-pass: blend via the shortest angular path,
                // not naive linear interpolation, so smoothing near the
                // 0/360 wraparound doesn't spike (e.g. 359 -> 1 should
                // smooth as a tiny +2 step, not swing through 180).
                val delta = GeoMath.angularDifference(previous, normalized)
                (previous + SMOOTHING_ALPHA * delta + 360) % 360
            }
            smoothedAzimuth = smoothed
            trySend(HeadingReading(smoothed, reliable))
        }

        when {
            rotationVectorSensor != null -> {
                val rotationMatrix = FloatArray(9)
                val orientation = FloatArray(3)
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        SensorManager.getOrientation(rotationMatrix, orientation)
                        emitAzimuth(Math.toDegrees(orientation[0].toDouble()))
                    }

                    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                        reliable = accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
                    }
                }
                sensorManager.registerListener(listener, rotationVectorSensor, SensorManager.SENSOR_DELAY_GAME)
                awaitClose { sensorManager.unregisterListener(listener) }
            }

            accelerometer != null && magnetometer != null -> {
                val gravity = FloatArray(3)
                val geomagnetic = FloatArray(3)
                var haveGravity = false
                var haveGeomagnetic = false
                val rotationMatrix = FloatArray(9)
                val orientation = FloatArray(3)

                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        when (event.sensor.type) {
                            Sensor.TYPE_ACCELEROMETER -> {
                                System.arraycopy(event.values, 0, gravity, 0, 3)
                                haveGravity = true
                            }
                            Sensor.TYPE_MAGNETIC_FIELD -> {
                                System.arraycopy(event.values, 0, geomagnetic, 0, 3)
                                haveGeomagnetic = true
                            }
                        }
                        if (haveGravity && haveGeomagnetic && SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)) {
                            SensorManager.getOrientation(rotationMatrix, orientation)
                            emitAzimuth(Math.toDegrees(orientation[0].toDouble()))
                        }
                    }

                    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                        if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                            reliable = accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
                        }
                    }
                }
                sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
                sensorManager.registerListener(listener, magnetometer, SensorManager.SENSOR_DELAY_GAME)
                awaitClose { sensorManager.unregisterListener(listener) }
            }

            else -> close() // No usable orientation sensors on this device at all.
        }
    }

    private companion object {
        /** Low-pass filter weight for new readings -- lower = smoother but slower to respond to a real turn. */
        const val SMOOTHING_ALPHA = 0.25
    }
}
