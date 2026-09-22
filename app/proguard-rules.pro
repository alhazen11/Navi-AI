# Add project specific ProGuard rules here.
# For more details, see https://d.android.com/r/tools/r8/keep-rules

# JNI entry points construct these via reflection (env->FindClass / NewObject),
# so R8 must never rename or strip them.
-keep class com.apps.naviai.native.NativeDetection { *; }
-keep class com.apps.naviai.native.NcnnJniBridge {
    native <methods>;
}

# Keep Detection/domain models that cross the JNI boundary or are used in
# Compose state so field names remain stable for debugging.
-keepclassmembers class com.apps.naviai.domain.model.** { *; }

-keepattributes *Annotation*, InnerClasses
-dontwarn kotlinx.coroutines.**
