package com.apps.naviai.native

/**
 * Raw detection as decoded by the native NCNN pipeline, in upright
 * display-space pixel coordinates (post rotation/mirror correction,
 * post letterbox-padding removal). This is the JNI wire format only --
 * see [com.apps.naviai.detection.detector.Detection] for the domain model
 * consumed by the rest of the app.
 *
 * The constructor signature (order and JVM types) is matched exactly by
 * native_detector.cpp via a cached `jmethodID`; changing it requires
 * updating the native side too.
 */
data class NativeDetection(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val score: Float,
    val classId: Int,
    val label: String
)
