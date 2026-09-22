#ifndef NAVIAI_NATIVE_DETECTOR_H
#define NAVIAI_NATIVE_DETECTOR_H

#include <jni.h>

// JNI bridge for com.apps.naviai.native.NcnnJniBridge.
// All entry points are safe to call from a single background inference
// thread; nativeDetect() additionally takes an internal lock so a stray
// concurrent call cannot corrupt in-flight state instead of crashing.

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_apps_naviai_native_NcnnJniBridge_nativeInit(
        JNIEnv *env, jobject thiz,
        jstring paramPath, jstring binPath,
        jobjectArray labels, jboolean useVulkan);

JNIEXPORT jobjectArray JNICALL
Java_com_apps_naviai_native_NcnnJniBridge_nativeDetect(
        JNIEnv *env, jobject thiz,
        jbyteArray rgba, jint width, jint height,
        jint rotationDegrees, jboolean mirror,
        jfloat confidenceThreshold, jfloat iouThreshold);

JNIEXPORT void JNICALL
Java_com_apps_naviai_native_NcnnJniBridge_nativeRelease(JNIEnv *env, jobject thiz);

JNIEXPORT jboolean JNICALL
Java_com_apps_naviai_native_NcnnJniBridge_nativeIsVulkanSupported(JNIEnv *env, jobject thiz);

JNIEXPORT jstring JNICALL
Java_com_apps_naviai_native_NcnnJniBridge_nativeGetLastError(JNIEnv *env, jobject thiz);

} // extern "C"

#endif // NAVIAI_NATIVE_DETECTOR_H
