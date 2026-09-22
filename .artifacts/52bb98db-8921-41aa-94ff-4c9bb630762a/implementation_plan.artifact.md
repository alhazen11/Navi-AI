# Fix UnsatisfiedLinkError for libnaviai_ncnn.so

The application fails to load `libnaviai_ncnn.so` because the native build is not registered in the project's Gradle configuration. Additionally, even if built, the JNI functions would fail to link due to a package name mismatch between the Kotlin class and the C++ implementation.

## Proposed Changes

### Build Configuration

#### [MODIFY] [app/build.gradle.kts](file:///D:/Project/NaviAI/app/build.gradle.kts)
- Add `externalNativeBuild` block to the `android` section to link `CMakeLists.txt`.

### JNI Implementation

#### [MODIFY] [ncnn_jni.cpp](file:///D:/Project/NaviAI/app/src/main/cpp/ncnn_jni.cpp)
- Update JNI function names to match the Kotlin package `com.apps.naviai.libs.detection`.
- Fix the function signatures to use the correct `JNIEXPORT` and `JNICALL` macros with the updated names.

## Verification Plan

### Automated Tests
- Run `gradlew :app:assembleDebug` to verify the native library builds and is included in the APK.
- Note: This might fail initially if the `ncnn` third-party library is not present in the expected path.

### Manual Verification
- Deploy the app to a device and check if `NcnnDetector` initializes without `UnsatisfiedLinkError`.
