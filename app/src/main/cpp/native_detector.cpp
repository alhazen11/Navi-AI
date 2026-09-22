#include "native_detector.h"

#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <ncnn/net.h>
#include <ncnn/mat.h>

#define LOG_TAG "NaviAI-NCNN"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace {

// This constant is tied to the specific YOLOv12n NCNN export bundled at
// assets/models/yolo_model.ncnn.param. The graph bakes anchor-point and
// stride MemoryData constants sized for a 320x320 letterboxed input
// (40x40 + 20x20 + 10x10 = 2100 anchors at strides 8/16/32 -- confirmed by
// inspecting the .param file, never assumed). Re-exporting the model at a
// different --imgsz requires updating this value and re-verifying the
// anchor count matches.
constexpr int kModelInputSize = 320;
constexpr int kNumClasses = 80;
constexpr int kNumBoxCoords = 4;
constexpr int kNumFeatures = kNumBoxCoords + kNumClasses; // 84, no objectness channel
constexpr float kLetterboxPadValue = 114.0f;

std::mutex g_mutex;
ncnn::Net g_net;
std::vector<std::string> g_labels;
bool g_netInitialized = false;
bool g_vulkanActive = false;
std::string g_lastError;

void setLastError(const std::string &message) {
    g_lastError = message;
    LOGE("%s", message.c_str());
}

struct RawDet {
    float x1, y1, x2, y2, score;
    int classId;
};

float iouOf(const RawDet &a, const RawDet &b) {
    float xx1 = std::max(a.x1, b.x1);
    float yy1 = std::max(a.y1, b.y1);
    float xx2 = std::min(a.x2, b.x2);
    float yy2 = std::min(a.y2, b.y2);
    float w = std::max(0.f, xx2 - xx1);
    float h = std::max(0.f, yy2 - yy1);
    float inter = w * h;
    float areaA = std::max(0.f, a.x2 - a.x1) * std::max(0.f, a.y2 - a.y1);
    float areaB = std::max(0.f, b.x2 - b.x1) * std::max(0.f, b.y2 - b.y1);
    if (areaA <= 0.f || areaB <= 0.f) return 0.f;
    return inter / (areaA + areaB - inter + 1e-6f);
}

std::vector<RawDet> runNms(std::vector<RawDet> input, float iouThreshold) {
    std::sort(input.begin(), input.end(),
              [](const RawDet &a, const RawDet &b) { return a.score > b.score; });
    std::vector<RawDet> out;
    std::vector<bool> removed(input.size(), false);
    for (size_t i = 0; i < input.size(); ++i) {
        if (removed[i]) continue;
        out.push_back(input[i]);
        for (size_t j = i + 1; j < input.size(); ++j) {
            if (!removed[j] && input[i].classId == input[j].classId) {
                if (iouOf(input[i], input[j]) > iouThreshold) removed[j] = true;
            }
        }
    }
    return out;
}

// ncnn's kanna_rotate type codes follow the EXIF-orientation convention
// (1=none, 2=flip-h, 3=rotate180, 4=flip-v, 5=transpose, 6=rot90cw,
// 7=anti-transpose, 8=rot90ccw). CameraX's rotationDegrees is the clockwise
// rotation needed to make the sensor buffer upright; mirror additionally
// flips horizontally, matching how CameraX/PreviewView mirror the front
// camera for display.
int rotateTypeFor(int rotationDegrees, bool mirror) {
    int deg = ((rotationDegrees % 360) + 360) % 360;
    if (!mirror) {
        switch (deg) {
            case 90: return 6;
            case 180: return 3;
            case 270: return 8;
            default: return 1;
        }
    }
    switch (deg) {
        case 90: return 7;
        case 180: return 4;
        case 270: return 5;
        default: return 2;
    }
}

} // namespace

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_apps_naviai_native_NcnnJniBridge_nativeInit(
        JNIEnv *env, jobject /*thiz*/,
        jstring paramPath, jstring binPath,
        jobjectArray labels, jboolean useVulkan) {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (env == nullptr || paramPath == nullptr || binPath == nullptr) {
        setLastError("nativeInit: invalid arguments");
        return JNI_FALSE;
    }

    const char *paramChars = env->GetStringUTFChars(paramPath, nullptr);
    const char *binChars = env->GetStringUTFChars(binPath, nullptr);
    std::string paramStr = paramChars ? paramChars : "";
    std::string binStr = binChars ? binChars : "";
    if (paramChars) env->ReleaseStringUTFChars(paramPath, paramChars);
    if (binChars) env->ReleaseStringUTFChars(binPath, binChars);

    if (paramStr.empty() || binStr.empty()) {
        setLastError("nativeInit: empty model paths");
        return JNI_FALSE;
    }

    g_labels.clear();
    if (labels != nullptr) {
        jsize count = env->GetArrayLength(labels);
        g_labels.reserve(static_cast<size_t>(count));
        for (jsize i = 0; i < count; ++i) {
            auto item = (jstring) env->GetObjectArrayElement(labels, i);
            if (item == nullptr) {
                g_labels.emplace_back("unknown");
                continue;
            }
            const char *text = env->GetStringUTFChars(item, nullptr);
            g_labels.emplace_back(text ? text : "unknown");
            if (text) env->ReleaseStringUTFChars(item, text);
            env->DeleteLocalRef(item);
        }
    }

    if (g_labels.empty()) {
        setLastError("nativeInit: label list is empty");
        return JNI_FALSE;
    }

    g_net.clear();
    g_vulkanActive = false;

#if NCNN_VULKAN
    if (useVulkan && ncnn::get_gpu_count() > 0) {
        g_net.opt.use_vulkan_compute = true;
        g_vulkanActive = true;
        LOGI("Vulkan compute enabled (%d device(s) found)", ncnn::get_gpu_count());
    } else {
        g_net.opt.use_vulkan_compute = false;
        if (useVulkan) LOGI("Vulkan requested but no GPU device found; falling back to CPU");
    }
#else
    g_net.opt.use_vulkan_compute = false;
    if (useVulkan) {
        LOGI("Vulkan requested but this NCNN build was compiled without NCNN_VULKAN; using CPU");
    }
#endif

    unsigned int hwThreads = std::thread::hardware_concurrency();
    g_net.opt.num_threads = hwThreads > 1 ? static_cast<int>(hwThreads - 1) : 1;
    // fp16 storage/arithmetic and SIMD packing are deliberately left OFF.
    // This export's DFL decode head uses an unusual 4D Reshape/Permute/
    // Softmax pattern (see the `11=4` "d" dimension param in the .param
    // file's reshape_256 layer) to reshape the box-distribution logits
    // before the softmax. Enabling packed/fp16 layout on this exact graph
    // reproducibly crashes with SIGSEGV/SEGV_ACCERR deep inside ncnn's
    // internal layer forward pass (confirmed via a symbolicated on-device
    // tombstone -- MTE catches a tag mismatch, i.e. real heap corruption,
    // triggered from ex.extract("out0", out)). Packing/fp16 are safe
    // optimizations in general; they are just not safe for this specific
    // graph shape on this ncnn build. Re-enable only after verifying a
    // representative device does NOT crash.
    g_net.opt.use_fp16_packed = false;
    g_net.opt.use_fp16_storage = false;
    g_net.opt.use_fp16_arithmetic = false;
    g_net.opt.use_packing_layout = false;

    int ret = g_net.load_param(paramStr.c_str());
    if (ret != 0) {
        setLastError("nativeInit: failed to load .param file (ret=" + std::to_string(ret) + "): " + paramStr);
        g_netInitialized = false;
        return JNI_FALSE;
    }

    ret = g_net.load_model(binStr.c_str());
    if (ret != 0) {
        setLastError("nativeInit: failed to load .bin file (ret=" + std::to_string(ret) + "): " + binStr);
        g_net.clear();
        g_netInitialized = false;
        return JNI_FALSE;
    }

    g_lastError.clear();
    g_netInitialized = true;
    LOGI("Model initialized. labels=%zu vulkan=%d threads=%d",
         g_labels.size(), g_vulkanActive, g_net.opt.num_threads);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_apps_naviai_native_NcnnJniBridge_nativeDetect(
        JNIEnv *env, jobject /*thiz*/,
        jbyteArray rgba, jint width, jint height,
        jint rotationDegrees, jboolean mirror,
        jfloat confidenceThreshold, jfloat iouThreshold) {
    std::lock_guard<std::mutex> lock(g_mutex);

    jclass detClass = env->FindClass("com/apps/naviai/native/NativeDetection");
    if (detClass == nullptr) {
        setLastError("nativeDetect: cannot find NativeDetection class");
        env->ExceptionClear();
        return nullptr;
    }
    jmethodID ctor = env->GetMethodID(detClass, "<init>", "(FFFFFILjava/lang/String;)V");
    if (ctor == nullptr) {
        setLastError("nativeDetect: cannot find NativeDetection constructor");
        env->ExceptionClear();
        env->DeleteLocalRef(detClass);
        return nullptr;
    }
    jobjectArray emptyResult = env->NewObjectArray(0, detClass, nullptr);

    if (!g_netInitialized) {
        setLastError("nativeDetect: model not initialized, call nativeInit() first");
        env->DeleteLocalRef(detClass);
        return emptyResult;
    }
    if (rgba == nullptr || width <= 0 || height <= 0) {
        setLastError("nativeDetect: invalid input buffer/dimensions");
        env->DeleteLocalRef(detClass);
        return emptyResult;
    }

    const jsize rgbaLength = env->GetArrayLength(rgba);
    const jsize expectedLength = width * height * 4;
    if (rgbaLength < expectedLength) {
        setLastError("nativeDetect: RGBA buffer too small, expected=" +
                      std::to_string(expectedLength) + " actual=" + std::to_string(rgbaLength));
        env->DeleteLocalRef(detClass);
        return emptyResult;
    }

    std::vector<unsigned char> srcPixels(static_cast<size_t>(expectedLength));
    env->GetByteArrayRegion(rgba, 0, expectedLength, reinterpret_cast<jbyte *>(srcPixels.data()));
    if (env->ExceptionCheck()) {
        setLastError("nativeDetect: failed to read RGBA byte array");
        env->ExceptionClear();
        env->DeleteLocalRef(detClass);
        return emptyResult;
    }

    // ---- Step 1: rotate/mirror the sensor buffer to upright display space ----
    const int rotateType = rotateTypeFor(rotationDegrees, mirror == JNI_TRUE);
    const bool swapDims = (rotateType == 5 || rotateType == 6 || rotateType == 7 || rotateType == 8);
    const int uprightW = swapDims ? height : width;
    const int uprightH = swapDims ? width : height;

    std::vector<unsigned char> uprightPixels;
    const unsigned char *uprightData;
    if (rotateType == 1) {
        uprightData = srcPixels.data();
    } else {
        uprightPixels.resize(static_cast<size_t>(uprightW) * uprightH * 4);
        ncnn::kanna_rotate_c4(srcPixels.data(), width, height,
                               uprightPixels.data(), uprightW, uprightH, rotateType);
        uprightData = uprightPixels.data();
    }

    // ---- Step 2: letterbox-resize into the fixed model input square ----
    const float scale = std::min(static_cast<float>(kModelInputSize) / uprightW,
                                  static_cast<float>(kModelInputSize) / uprightH);
    const int newW = std::max(1, static_cast<int>(std::round(uprightW * scale)));
    const int newH = std::max(1, static_cast<int>(std::round(uprightH * scale)));
    const int padLeft = (kModelInputSize - newW) / 2;
    const int padTop = (kModelInputSize - newH) / 2;
    const int padRight = kModelInputSize - newW - padLeft;
    const int padBottom = kModelInputSize - newH - padTop;

    ncnn::Mat resized = ncnn::Mat::from_pixels_resize(
            uprightData, ncnn::Mat::PIXEL_RGBA2RGB, uprightW, uprightH, newW, newH);
    if (resized.empty()) {
        setLastError("nativeDetect: from_pixels_resize failed");
        env->DeleteLocalRef(detClass);
        return emptyResult;
    }

    ncnn::Mat input;
    ncnn::copy_make_border(resized, input, padTop, padBottom, padLeft, padRight,
                            ncnn::BORDER_CONSTANT, kLetterboxPadValue);
    if (input.empty()) {
        setLastError("nativeDetect: copy_make_border failed");
        env->DeleteLocalRef(detClass);
        return emptyResult;
    }

    const float normVals[3] = {1.0f / 255.0f, 1.0f / 255.0f, 1.0f / 255.0f};
    input.substract_mean_normalize(nullptr, normVals);

    // ---- Step 3: run inference ----
    ncnn::Extractor ex = g_net.create_extractor();
    ex.set_light_mode(true);

    if (ex.input("in0", input) != 0) {
        setLastError("nativeDetect: failed to set input blob 'in0'");
        env->DeleteLocalRef(detClass);
        return emptyResult;
    }

    ncnn::Mat out;
    int extractRet = ex.extract("out0", out);
    if (extractRet != 0 || out.empty()) {
        extractRet = ex.extract(0, out);
    }
    if (extractRet != 0 || out.empty()) {
        setLastError("nativeDetect: failed to extract output blob (ret=" + std::to_string(extractRet) + ")");
        env->DeleteLocalRef(detClass);
        return emptyResult;
    }

    LOGD("Output tensor: dims=%d w=%d h=%d c=%d", out.dims, out.w, out.h, out.c);

    // ---- Step 4: decode. Confirmed layout (inspected from the .param graph,
    // never assumed): each of the 2100 anchors carries 84 values --
    // [cx, cy, w, h] already decoded to absolute pixel coordinates in the
    // 320x320 letterboxed input space (DFL + anchor-point + stride decode is
    // fused into the graph), followed by 80 per-class scores that already
    // have sigmoid applied. There is no separate objectness channel. ----
    std::vector<RawDet> candidates;

    auto decodeAnchor = [&](float cx, float cy, float bw, float bh, const float *classScores) {
        if (bw <= 0.0f || bh <= 0.0f) return;

        int bestClass = -1;
        float bestScore = 0.0f;
        for (int c = 0; c < kNumClasses; ++c) {
            float s = classScores[c];
            if (s > bestScore) {
                bestScore = s;
                bestClass = c;
            }
        }
        if (bestClass < 0 || bestClass >= kNumClasses) return;
        if (bestScore < confidenceThreshold) return;

        float x1 = cx - bw * 0.5f;
        float y1 = cy - bh * 0.5f;
        float x2 = cx + bw * 0.5f;
        float y2 = cy + bh * 0.5f;

        // Remove letterbox padding, then rescale from the 320-space back to
        // the upright display-space image.
        x1 = (x1 - padLeft) / scale;
        y1 = (y1 - padTop) / scale;
        x2 = (x2 - padLeft) / scale;
        y2 = (y2 - padTop) / scale;

        x1 = std::max(0.0f, std::min(x1, static_cast<float>(uprightW)));
        y1 = std::max(0.0f, std::min(y1, static_cast<float>(uprightH)));
        x2 = std::max(0.0f, std::min(x2, static_cast<float>(uprightW)));
        y2 = std::max(0.0f, std::min(y2, static_cast<float>(uprightH)));

        if (x2 <= x1 || y2 <= y1) return;

        candidates.push_back({x1, y1, x2, y2, bestScore, bestClass});
    };

    if (out.dims == 2) {
        // Either [anchors, features] (out.h == anchors) or the transposed
        // [features, anchors] layout -- both are seen across ncnn export
        // versions, so detect orientation from which dimension equals 84.
        if (out.w == kNumFeatures) {
            // w=features(84), h=anchors
            for (int i = 0; i < out.h; ++i) {
                const float *row = out.row(i);
                decodeAnchor(row[0], row[1], row[2], row[3], row + kNumBoxCoords);
            }
        } else if (out.h == kNumFeatures) {
            // w=anchors, h=features(84) -- transposed, read column-wise
            std::vector<float> feat(kNumFeatures);
            for (int i = 0; i < out.w; ++i) {
                for (int f = 0; f < kNumFeatures; ++f) {
                    feat[f] = out.row(f)[i];
                }
                decodeAnchor(feat[0], feat[1], feat[2], feat[3], feat.data() + kNumBoxCoords);
            }
        } else {
            setLastError("nativeDetect: unexpected 2D output shape w=" + std::to_string(out.w) +
                          " h=" + std::to_string(out.h) + " (expected one dim == " +
                          std::to_string(kNumFeatures) + ")");
            env->DeleteLocalRef(detClass);
            return emptyResult;
        }
    } else if (out.dims == 3) {
        // [channels(84), h, w] planar layout.
        if (out.c == kNumFeatures) {
            const int numAnchors = out.w * out.h;
            std::vector<float> feat(kNumFeatures);
            for (int a = 0; a < numAnchors; ++a) {
                for (int f = 0; f < kNumFeatures; ++f) {
                    feat[f] = out.channel(f)[a];
                }
                decodeAnchor(feat[0], feat[1], feat[2], feat[3], feat.data() + kNumBoxCoords);
            }
        } else if (out.w == kNumFeatures) {
            for (int i = 0; i < out.h; ++i) {
                const float *row = out.row(i);
                decodeAnchor(row[0], row[1], row[2], row[3], row + kNumBoxCoords);
            }
        } else {
            setLastError("nativeDetect: unexpected 3D output shape w=" + std::to_string(out.w) +
                          " h=" + std::to_string(out.h) + " c=" + std::to_string(out.c));
            env->DeleteLocalRef(detClass);
            return emptyResult;
        }
    } else {
        setLastError("nativeDetect: unsupported output dims=" + std::to_string(out.dims));
        env->DeleteLocalRef(detClass);
        return emptyResult;
    }

    const size_t candidateCount = candidates.size();
    std::vector<RawDet> finalDets = runNms(std::move(candidates), iouThreshold);
    if (!finalDets.empty()) {
        LOGI("Detected %zu object(s) after NMS (%zu candidates above threshold)", finalDets.size(), candidateCount);
    }

    jobjectArray result = env->NewObjectArray(static_cast<jsize>(finalDets.size()), detClass, nullptr);
    if (result == nullptr) {
        setLastError("nativeDetect: failed to allocate result array");
        env->DeleteLocalRef(detClass);
        return emptyResult;
    }

    for (size_t i = 0; i < finalDets.size(); ++i) {
        const RawDet &d = finalDets[i];
        const char *labelText = "unknown";
        // Defensive bounds check: never index into g_labels with an
        // unvalidated class id, even though decodeAnchor already clamps
        // bestClass to [0, kNumClasses).
        if (d.classId >= 0 && d.classId < static_cast<int>(g_labels.size())) {
            labelText = g_labels[d.classId].c_str();
        }
        jstring label = env->NewStringUTF(labelText);
        if (label == nullptr) continue;

        jobject detObj = env->NewObject(detClass, ctor,
                                         static_cast<jfloat>(d.x1), static_cast<jfloat>(d.y1),
                                         static_cast<jfloat>(d.x2), static_cast<jfloat>(d.y2),
                                         static_cast<jfloat>(d.score), static_cast<jint>(d.classId),
                                         label);
        if (detObj != nullptr) {
            env->SetObjectArrayElement(result, static_cast<jsize>(i), detObj);
            env->DeleteLocalRef(detObj);
        }
        env->DeleteLocalRef(label);
    }

    env->DeleteLocalRef(detClass);
    g_lastError.clear();
    return result;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_apps_naviai_native_NcnnJniBridge_nativeRelease(JNIEnv * /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_net.clear();
    g_labels.clear();
    g_netInitialized = false;
    g_vulkanActive = false;
    LOGI("Native detector released");
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_apps_naviai_native_NcnnJniBridge_nativeIsVulkanSupported(JNIEnv * /*env*/, jobject /*thiz*/) {
#if NCNN_VULKAN
    return ncnn::get_gpu_count() > 0 ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_apps_naviai_native_NcnnJniBridge_nativeGetLastError(JNIEnv *env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return env->NewStringUTF(g_lastError.c_str());
}
