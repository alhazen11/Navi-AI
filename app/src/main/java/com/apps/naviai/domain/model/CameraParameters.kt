package com.apps.naviai.domain.model

/**
 * Approximate camera intrinsics used for monocular distance estimation.
 *
 * [focalLengthPixels] is the single most important value here: it is what
 * calibration solves for (see data/calibration), and everything downstream
 * is only as accurate as this number. Until the user calibrates, a rough
 * default derived from typical smartphone field-of-view is used and the
 * resulting distance estimates are flagged with a lower confidence.
 */
data class CameraParameters(
    val focalLengthPixels: Float,
    val isCalibrated: Boolean,
    val frameWidth: Int,
    val frameHeight: Int
) {
    companion object {
        /**
         * Rough default focal length for a phone's main rear camera at the
         * inference resolution, derived from a ~70 degree horizontal FOV
         * assumption: focalLengthPx = (frameWidth / 2) / tan(FOV / 2).
         * This is a starting point only -- never presented to the user as
         * an accurate measurement until they run calibration.
         */
        fun defaultFor(frameWidth: Int, frameHeight: Int): CameraParameters {
            val horizontalFovRadians = Math.toRadians(70.0)
            val focalLength = (frameWidth / 2.0) / Math.tan(horizontalFovRadians / 2.0)
            return CameraParameters(
                focalLengthPixels = focalLength.toFloat(),
                isCalibrated = false,
                frameWidth = frameWidth,
                frameHeight = frameHeight
            )
        }
    }
}
