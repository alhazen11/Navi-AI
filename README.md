# NAVI AI

On-device real-time object detection and approximate monocular distance estimation for blind and visually impaired users, built on YOLO + NCNN. Detection, tracking, distance estimation, and risk assessment are fully offline -- no cloud API involved. The two deliberate exceptions are voice commands (section 9), which send audio to AssemblyAI's speech-to-text API, and the LLM vision features (Scene Understanding, Text Reading, Hazard Awareness, Object Search -- section 9), which call a user-configured chat-completions endpoint; both need the user's own credentials/setup. Settings > Offline mode (section 15) lets a user explicitly avoid the network entirely: it switches voice commands to a built-in on-device speech engine (Android 12+) and blocks the LLM features unless they're pointed at a local server, and documents exactly which two features that setting touches.

## 1. Model selection

The spec asked for an explicit comparison of YOLOv8n / YOLO11n / YOLOv12n before implementation, rather than defaulting to the newest model. Here is that comparison, and an honest note on how this particular repo ended up where it did.

| | YOLOv8n | YOLO11n | YOLOv12n |
|---|---|---|---|
| NCNN export maturity | Most mature; years of community Android demos (`ncnn-android-yolov8`, etc.) | Mature; `ultralytics` export `format=ncnn` covers it directly | Newest; same `ultralytics` export path, but lowers its Area-Attention blocks to `MatMul`/`Reduction`, ops with much less real-world NCNN mileage |
| Verified on-device (this repo) | **Yes** -- 20+ seconds of continuous crash-free inference on a Samsung Galaxy S22, correct output shape every frame | Not tested here (should work: same op set as v8n) | **No** -- reproducible native SIGSEGV inside NCNN's own forward pass on first real inference (see write-up above); the `.param` loads and the Gradle build succeeds either way, so a build passing is not evidence a model actually runs |
| Conversion reliability | Extremely reliable; parses and loads cleanly | Reliable | Parses and loads cleanly too -- the failure is at runtime, in the forward pass, not at export or load time |
| Maintenance | Long-tail community support | Actively maintained | Actively maintained, newest |

**What's actually in this repo now: YOLOv8n**, exported fresh via `scripts/export_yolo_to_ncnn.py` at 320x320 (`app/src/main/assets/models/yolo_model.ncnn.param/.bin`). That was not the starting point -- the project originally shipped a YOLOv12n export, which turned out to crash the app natively on real hardware. The full story, because it's a direct, concrete illustration of the spec's own warning against blindly picking the newest model:

### Why YOLOv12n was replaced: a real, reproducible native crash

Once the preprocessing/decode bugs below were fixed, the YOLOv12n build ran cleanly on a Windows/emulator dry build but **crashed on first real inference on a physical device** (Samsung Galaxy S22, Android 16) with `Fatal signal 11 (SIGSEGV), code 2 (SEGV_ACCERR)` on the inference thread. This was tracked down methodically, not guessed at:

1. Installed the debug APK on a connected device via `adb` and captured a live logcat, rather than speculating from the "couldn't start the on-device detector" error text alone.
2. Symbolicated the tombstone with the NDK's `llvm-addr2line` against the unstripped intermediate `.so` (`app/build/intermediates/cxx/Debug/*/obj/arm64-v8a/libnaviai_ncnn.so`), which pinpointed the crash to `native_detector.cpp`'s `ex.extract("out0", out)` call -- i.e. inside NCNN's own forward pass, not a JNI/decode bug.
3. Ruled out two plausible-looking hypotheses by testing them directly on-device: disabling fp16/packed-layout options (no change), and replacing an auto-inferred-size `Slice` param (`-233,-233`) with explicit sizes (no change).
4. Bisected the actual failure point by extracting a chain of intermediate blobs by name and reading off which one's log line was the last to print before the crash -- narrowing it to YOLOv12's **Area-Attention block**, which lowers to `MatMul` + `Reduction` (manual softmax via max/sub/exp/sum/div) in the exported graph. Blobs computed *before* the attention block extracted fine with sane shapes; the crash triggered the instant a `MatMul`-dependent blob was requested, at the same fault address every run (a reproducible bug, not a race).

`MatMul`/`Reduction`-based attention is newer and far less field-tested in NCNN than the plain-convolution architectures of YOLOv8n/YOLO11n -- exactly the distinction the spec's model-selection guidance was pointing at. Rather than patching around a real incompatibility in a prebuilt, vendored NCNN binary (not something this repo can safely rebuild), the model was swapped: `pip install ultralytics`, downloaded real `yolov8n.pt` weights, ran `scripts/export_yolo_to_ncnn.py --weights yolov8n.pt --imgsz 320`, and verified the resulting graph uses only `Convolution/Concat/Reshape/Softmax/Sigmoid/Pooling/Interp/Slice/BinaryOp` -- no `MatMul`, no `Reduction`. Reinstalled on the same physical device: model initializes, camera binds, and inference runs continuously (20+ seconds, dozens of frames, zero crashes) with the exact output shape expected (`dims=2 w=2100 h=84`). See `app/src/main/assets/models/metadata.yaml` for a condensed version of this note.

If you want to try YOLO11n or re-attempt YOLOv12n instead, `scripts/export_yolo_to_ncnn.py` works for any of the three -- just budget time to repeat this same on-device verification (a clean Gradle build is not sufficient evidence a model actually runs; only a physical device is).

### The decode bugs found before that, and what "never assume the output format" caught

Inspecting `yolo_model.ncnn.param` directly (never assuming) turned up three real correctness bugs in the native inference path, now fixed in `native_detector.cpp`. These applied to (and were fixed against) the original YOLOv12n export, and hold equally for the current YOLOv8n one -- both use the same fused Ultralytics-NCNN output convention:

1. **No real preprocessing resize.** The old code called `from_pixels_resize(..., width, height)` -- target size equal to source size, i.e. no resize at all. The network never actually saw a 320x320 input.
2. **Wrong input-size constant.** Coordinate scale-back assumed a 640-pixel input; the export is 320x320 (confirmed: the graph's `Reshape` layers sum to `1600 + 400 + 100 = 2100` anchors, which is the 320-input arithmetic at strides 8/16/32, not 640's 8400).
3. **Wrong output layout assumption.** The code assumed `[cx, cy, w, h, objectness, 80 class scores]` (85 channels) and multiplied `objectness * class_score`. The actual graph has **no objectness channel** -- it's `[cx, cy, w, h, 80 sigmoid class scores]` (84 channels), with **boxes already decoded to absolute pixel coordinates** in the model's input space (DFL distribution decode + anchor-point + stride multiply are all fused into the graph itself, confirmed via the `MemoryData`/`Slice`/`Softmax`/`Sigmoid`/`Concat` layers at the end of the `.param` file). The old code shifted every class index by one and applied a nonexistent multiply.

Fixed by: a real letterbox resize+pad to 320x320 (`ncnn::copy_make_border`), a `kModelInputSize = 320` constant with a comment tying it to the specific export, and a decode that reads 4 box coords + 80 raw (already-sigmoid) class scores with no objectness term. `YoloOutputParserTest.kt` has a regression test specifically for bug #3.

A fourth gap (not a decode bug, but a coordinate-mapping one): the old code never corrected for camera rotation, so front/rear camera sensor rotation would have produced boxes that didn't line up with what's shown on screen. Fixed via `ncnn::kanna_rotate_c4` in `native_detector.cpp`, driven by `ImageProxy.imageInfo.rotationDegrees` and a `mirror` flag for the front camera.

### Two more bugs found only by running on a physical device

Neither of these showed up in a Gradle build or in the emulator-free dry run -- both needed a real device and a real `adb logcat`:

- **Model assets stored compressed in the APK.** `AssetManager.openFd()` (used to size-check whether the model was already extracted to app storage) throws `FileNotFoundException: ... probably compressed` for any asset AAPT compresses by default, which it does for the `.ncnn.bin`/`.ncnn.param` extensions. Fixed with `androidResources { noCompress += listOf("ncnn.param", "ncnn.bin") } ` in `app/build.gradle.kts`, plus a defensive fallback in `ModelAssetManager` that copies unconditionally if the size check itself fails, rather than crashing.
- **A per-screen ViewModel released an app-wide singleton.** `DetectionViewModel.onCleared()` used to call `objectDetector.release()` and `ttsManager.shutdown()`. Since Splash and Detection screens each get their own `DetectionViewModel` instance (scoped to their own `NavBackStackEntry`), popping the Splash screen off the back stack tore down the *shared* `@Singleton` detector and TTS engine for the entire app -- so the Detection screen's own (fresh) `DetectionViewModel` instance was always stuck `Uninitialized`. Fixed by removing the release/shutdown calls from `onCleared()` (native/TTS singletons are left for the OS to reclaim on process death) and making `DetectionViewModel`'s `init` block self-sufficient: it now syncs with the shared detector's current state and initializes it itself if needed, instead of assuming Splash already did.

## 2. Output tensor format (as verified, not assumed)

```
Input blob:  "in0"                       -- 320x320x3, RGB, normalized by /255 (no mean subtraction)
Output blob: "out0"                      -- 2100 anchors x 84 features
Per anchor:  [cx, cy, w, h, class_0..class_79]
             cx,cy,w,h  -- already decoded to absolute pixel coords in the 320x320 letterboxed input space
             class_i    -- already has sigmoid applied; NO separate objectness channel
```

Class id validity (`0 <= classId < 80`) is checked at three independent layers: in the native decode loop, again before the label lookup in `native_detector.cpp`, and again in `NcnnObjectDetector.toDomainOrNull()` in Kotlin -- an out-of-range id is dropped, never propagated or used to crash the app.

## 3. Architecture

```
CameraX (YUV_420_888, STRATEGY_KEEP_ONLY_LATEST)
  -> FrameAnalyzer (YUV->RGBA, drops frames while inference is busy)
    -> NcnnObjectDetector (JNI -> native_detector.cpp: rotate/mirror, letterbox, infer, decode, NMS)
      -> DistanceEstimator (per-detection, known-height formula)
        -> ObjectTracker (IoU/centroid matching, EMA distance smoothing, movement classification)
          -> RiskAssessmentEngine (walking-path region + distance + movement + persistence heuristic)
            -> AnnouncementManager (cooldown/dedup, ID/EN sentence construction) -> TextToSpeechManager
            -> DetectionViewModel.uiState -> Compose UI (DetectionOverlay, RiskIndicator, DetectionInfoCard, PerformanceStatsOverlay)
```

Key packages under `app/src/main/java/com/apps/naviai/`:

- `native/` -- JNI surface (`NcnnJniBridge`, `NativeDetection`) over `libnaviai_ncnn.so`.
- `detection/detector/` -- `ObjectDetector` interface + `NcnnObjectDetector` implementation + `Detection` domain model.
- `detection/preprocessing/` -- `Letterbox` (pure, unit-tested coordinate math mirroring the native side).
- `detection/postprocessing/` -- `YoloOutputParser` + `NmsProcessor`: a pure-Kotlin reference decode, used for fast, native-library-free unit tests of the exact format documented above.
- `detection/tracking/` -- `ObjectTracker`, `TrackedObject`, `MovementDirection`.
- `detection/distance/` -- `DistanceEstimator`, `ObjectDimensions` (reference sizes for 40 COCO classes), `DistanceSmoother`.
- `detection/risk/` -- `RiskAssessmentEngine`, `RiskLevel`.
- `camera/` -- `CameraManager`, `FrameAnalyzer`, `ImageUtils` (YUV->RGBA).
- `audio/` -- `TextToSpeechManager`, `Speaker` (interface, for testability), `AnnouncementManager`.
- `data/calibration/`, `settings/` -- DataStore-backed persistence.
- `di/` -- Hilt modules (dispatchers, detector/estimator/speaker bindings).
- `ui/` -- Compose screens, components, and `DetectionViewModel` / `CalibrationViewModel` / `SettingsViewModel`.

## 4. Building

Requirements: Android Studio (or the command line) with **compileSdk 37 / build-tools 37.0.0 / NDK 28.2.13676358 / CMake 3.22.1** installed, JDK 17+ available (the project's Kotlin/Java toolchain is pinned to 17 -- Robolectric's newer SDK shadows require it for unit tests).

```bash
./gradlew :app:assembleDebug          # builds the APK, including native compilation for 4 ABIs
./gradlew :app:testDebugUnitTest      # 67 unit tests (pure logic + Robolectric-backed DataStore/RectF tests)
./gradlew :app:lintDebug
./gradlew :app:assembleRelease        # R8-minified release build
./gradlew :app:assembleDebugAndroidTest   # compiles instrumentation tests (run on-device, see below)
```

All of the above were run in this environment and pass. `assembleRelease` was also verified: R8 shrinking does not break the JNI-facing classes (see `proguard-rules.pro` for the `-keep` rules on `NativeDetection`/`NcnnJniBridge`'s native methods).

ABIs built: `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` (the vendored NCNN prebuilt also has a `riscv64` slice under `app/src/main/cpp/third_party/ncnn/riscv64/` if you want to add it back to `abiFilters`).

## 5. Model conversion pipeline

See `scripts/export_yolo_to_ncnn.py` and `scripts/requirements.txt`. Summary:

```bash
pip install -r scripts/requirements.txt
python scripts/export_yolo_to_ncnn.py --weights yolo12n.pt --imgsz 320
```

This is the exact process (via `ultralytics`' built-in `format="ncnn"` exporter, which runs PNNX internally) that produced the bundled model. The script also prints the exported graph's input/output layers and anchor-count-contributing `Reshape` sizes so you can verify the format matches what `native_detector.cpp` expects, instead of assuming it does after a re-export. **If you export at an `--imgsz` other than 320, you must update `kModelInputSize` in `app/src/main/cpp/native_detector.cpp` to match** -- the anchor/stride constants are baked into the graph for one fixed resolution.

FP16: pass `--half` (the native side already enables `use_fp16_packed/storage/arithmetic`, so an FP16 `.bin` is used automatically when present). INT8: not included -- NCNN's INT8 path (`ncnn2int8`) needs a calibration image set that isn't part of this repo; see the [ncnn quantization docs](https://github.com/Tencent/ncnn/wiki/quantized-int8-inference) if you want to add it.

## 6. Distance estimation and calibration

`distance = (realObjectHeightMeters * focalLengthPixels) / boundingBoxPixelHeight`, using `ObjectDimensions`' reference heights (40 COCO classes, each tagged HIGH/MEDIUM/LOW reliability with a note on why). Every `DistanceEstimate` carries a `confidence` (penalized for: uncalibrated camera, small/far boxes, frame-edge clipping, unusual aspect ratio, low detector confidence) and a `method` (calibrated vs. uncalibrated-default focal length) -- **this is always presented as approximate**, both in the UI ("~2.4 m") and in TTS ("sekitar dua meter" / "about 2 meters"), never as a precise measurement.

Calibration (`CalibrationScreen`): point the camera at a known reference object (e.g. yourself, at a measured distance), capture its detected box height, enter the real distance, and the app solves `focalLength = (pixelHeight * knownDistance) / realHeight`. This replaces the rough FOV-based default (`CameraParameters.defaultFor`) with a per-device value.

## 7. Testing

- **Unit tests** (`app/src/test/`, 67 tests, all passing): YOLO output parsing (including a regression test for the objectness-channel bug), class-id validation, NMS, letterbox coordinate restoration, distance calculation (including edge cases: unknown class, degenerate box, frame-edge clipping, low-reliability classes), distance smoothing, calibration (formula + persistence + validation), object tracking (id stability, class separation, timeout, movement classification), risk assessment (all listed scenarios: close/centered/approaching/uncertain-distance/persistence-damping/sensitivity), TTS cooldown/dedup/priority/sentence construction, settings persistence. RectF/PointF-touching tests run under Robolectric (`@Config(sdk = [34])`).
- **Instrumentation tests** (`app/src/androidTest/`): Compose semantics/accessibility content descriptions, camera-permission state, and a native integration suite (`NcnnObjectDetectorInstrumentedTest`) that loads the real bundled model on-device, runs real inference, and asserts class-id validity, rotation handling (0/90/180/270), and graceful failure on missing/invalid model paths. **Run and verified on a physical Samsung Galaxy S22 (Android 16)** via `./gradlew connectedDebugAndroidTest`: all 6 `NcnnObjectDetectorInstrumentedTest` cases pass, confirming the real bundled model loads and runs real inference on-device without crashing. All 4 Compose-rendering tests failed in that same run purely because the device's lock screen was covering the test activity (`IllegalStateException: No compose hierarchies found in the app`) -- an environmental condition, not a code defect; they use no device/native state and should pass once run with the screen unlocked.

## 8. Physical device testing

1. Enable Developer Options + USB debugging on an Android 8.0+ (API 26+) device, connect via USB.
2. `./gradlew installDebug` (or run from Android Studio).
3. Grant the camera permission when prompted (splash screen requests it automatically).
4. Point the camera at a person, chair, bottle, etc. -- you should see a colored bounding box (color = risk level), a label with confidence and approximate distance, and hear a spoken announcement after ~2 frames of persistence (respecting the cooldown).
5. Recommended: run calibration (Settings -> Calibrate distance) with a tape-measured reference before trusting distance numbers for anything beyond a rough sense of scale.
6. Toggle the front camera to verify mirroring: the overlay boxes should track the mirrored preview, not be flipped relative to it.
7. Check `adb logcat -s NaviAI-NCNN` for native-side diagnostic logs (init failures, output-shape logs, per-frame candidate counts).

Mid-range-device expectations: at 320x320 fixed input, CPU-only, expect roughly 80-150ms inference on a mid-range ARM64 SoC (varies a lot by device); Vulkan is not available in the vendored NCNN build (see below), so there's no GPU path to fall back to on this build.

## 9. Voice command interface

Always-on, hands-free voice capture, addressed with a "NAVI" wake word. This phase only captures and displays what was heard -- it does not act on commands yet (that's future work).

### Speech-to-text engine: AssemblyAI Universal-Streaming (cloud)

Voice command transcription runs on [AssemblyAI's realtime streaming API](https://www.assemblyai.com/docs/api-reference/streaming-api/streaming-api), not Android's on-device `SpeechRecognizer` (an earlier iteration of this feature used the latter; it's been fully replaced). Verified against AssemblyAI's own API reference rather than assumed:

- **Connection**: `AssemblyAiRealtimeTranscriber` opens a WebSocket to `wss://streaming.assemblyai.com/v3/ws?sample_rate=16000&encoding=pcm_s16le&format_turns=true&speech_model=universal-streaming-multilingual`, authenticated via an `Authorization: <api-key>` header (no "Bearer" prefix).
- **Audio**: `android.media.AudioRecord` captures raw 16kHz mono 16-bit PCM (`MediaRecorder.AudioSource.VOICE_RECOGNITION`) in ~100ms chunks, sent as binary WebSocket frames -- matching AssemblyAI's required format exactly, no transcoding needed.
- **Messages**: `Begin` confirms the session started (used to send an `UpdateConfiguration` message with a `prompt` biasing recognition toward the wake word); `Turn` carries partial and, when `end_of_turn: true`, finalized transcripts (only finalized turns are acted on); `Termination` confirms a clean close. `{"type":"Terminate"}` is sent to close gracefully rather than just dropping the socket.
- **Why cloud instead of on-device**: this replaced the original `SpeechRecognizer`-based implementation specifically because it was unreliable (see the "Navy" bug below) and had no way to bias recognition toward the wake word. AssemblyAI's `UpdateConfiguration` `prompt` field provides exactly that lever.
- **No restart-loop needed.** Because the WebSocket stays open continuously, there's no per-utterance restart gap that could clip the start of speech (a real risk with `SpeechRecognizer`'s one-call-per-utterance model) -- reconnection (with exponential backoff) only happens on an actual disconnect/error.

### Wake-word matching (unchanged by the STT swap -- same `VoiceCommandParser`)

- **Auto-listening, no push button.** `VoiceCommandPanel` requests microphone permission automatically when the Detection screen appears, and starts listening as soon as it's granted (and an API key is configured). The mic icon is a pause/resume toggle, not a press-to-talk trigger.
- **Only "NAVI ..." is treated as a command.** `VoiceCommandParser.extractCommand()` requires the recognized text to *start* with a wake word (case-insensitive, tolerates a following comma/colon). Ordinary ambient conversation that never mentions the wake word is ignored silently and listening just continues -- it is not treated as a failed command, so NAVI never nags during normal silence.
- **"Navy" is accepted as an alias for "NAVI".** Found via a real on-device bug (pre-dating the AssemblyAI switch, still relevant since the same ambiguity can occur with any recognizer): the recognized command was never showing on screen at all. Logging every recognized hypothesis (not just errors) showed exactly why -- for a genuine "NAVI, ..." utterance, the recognizer's top-ranked hypothesis was the literal text `"Navy bagaimana hari ini"`, not "Navi". Speech recognizers reliably normalize "NAVI" to the real English word "Navy", especially in non-English locales. `VoiceCommandParser.DEFAULT_WAKE_WORDS = listOf("navi", "navy")` fixes it; users still say "NAVI", the alias is purely an ASR-output-matching concern. The AssemblyAI `prompt` bias above is a second, complementary mitigation for the same root problem.
- **"Please repeat" is reserved for genuinely garbled attempts.** Silence and ambient speech restart/continue quietly with no message. A spoken repeat request -- shown on screen *and* spoken aloud via the existing TTS pipeline, since a spoken prompt is what "NAVI asks you to repeat" means for a blind/low-vision audience -- only fires when the recognized speech mentions a wake word somewhere but a clean command couldn't be parsed from it (`VoiceCommandParser.mentionsWakeWord()`), e.g. noise mangling the prefix. While speaking the prompt, `AssemblyAiRealtimeTranscriber.setMuted(true)` stops forwarding mic audio (not a true "TTS finished" callback, just a ~3s fixed window) to reduce -- not eliminate, there's no real echo cancellation here -- the chance of the mic picking up NAVI's own voice as new input.
- Recognition locale follows the existing `speechLanguage` setting (Indonesian/English), same as TTS.

### Setup

1. Get an API key at [assemblyai.com](https://www.assemblyai.com/).
2. Settings -> Voice commands -> paste it into "AssemblyAI API key". Stored locally via DataStore Preferences (plain text, not a secure keystore -- see the security note in `AssemblyAiRealtimeTranscriber.kt`: this app has no backend to mint short-lived tokens, so the key is sent directly from the device on every connection, which AssemblyAI's own docs call out as acceptable for non-public use only).
3. Without a key configured, the voice panel shows "Unavailable" with a message telling the user to add one -- verified on an emulator (graceful, no crash) since a real key wasn't available in this environment to test the live streaming path end-to-end.

## 10. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `UnsatisfiedLinkError: libnaviai_ncnn.so not found` | Check `abiFilters` in `app/build.gradle.kts` includes the device's ABI; check `app/src/main/cpp/third_party/ncnn/<ABI>/lib/libncnn.so` exists for that ABI. |
| Splash screen stuck on "Loading detection model..." | Check `adb logcat -s NaviAI-NCNN`; `NcnnJniBridge.nativeGetLastError()` surfaces the exact native failure (missing/corrupt asset, param/bin mismatch). |
| Boxes look shifted/rotated relative to the preview | Confirm `rotationDegrees`/`mirror` are being passed through `FrameAnalyzer` -> `FrameInput`; this is exactly what `native_detector.cpp`'s `rotateTypeFor()` + `kanna_rotate_c4` handles. |
| Distances feel very wrong | Uncalibrated default focal length is a rough FOV guess -- run calibration. Also check the object's `ObjectDimensions` reliability tier; LOW-reliability classes (bicycle, dog, backpack, etc.) are inherently noisy. |
| Vulkan toggle has no effect | This vendored NCNN build has `NCNN_VULKAN = 0` (confirmed in `platform.h`) -- CPU-only. `nativeIsVulkanSupported()` correctly reports `false`; the Settings toggle is disabled and shows why. To get GPU acceleration, rebuild NCNN from source with `-DNCNN_VULKAN=ON` and replace the vendored `.so`/headers per ABI. |
| Gradle: "Cannot add extension with name 'kotlin'" | Don't apply `org.jetbrains.kotlin.android` -- AGP 9.x's **built-in Kotlin support** applies it implicitly; see https://developer.android.com/r/tools/built-in-kotlin. |
| Robolectric tests fail with "SDK 34 requires Java 17" | Run under JDK 17+ (`kotlin { jvmToolchain(17) }` is already set; make sure `JAVA_HOME` points at a 17+ JDK when invoking Gradle from the command line). |
| `FileNotFoundException: ... probably compressed` on first launch | Model assets must be stored uncompressed in the APK; check `androidResources.noCompress` in `app/build.gradle.kts` still lists `ncnn.param`/`ncnn.bin`. |
| Detector initializes on Splash but Detection screen never detects anything | Check `DetectionViewModel.onCleared()` hasn't regained a `objectDetector.release()`/`ttsManager.shutdown()` call -- those are app-wide `@Singleton`s shared across every screen's own ViewModel instance; releasing them from one screen's teardown breaks every other screen. See section 1's write-up. |
| A model change builds fine but you're not sure it actually runs | **A successful Gradle build is not evidence a model runs correctly.** Install on a real device and watch `adb logcat -s NaviAI-NCNN:* DEBUG:*` through first inference -- this is exactly how the YOLOv12n MatMul crash in section 1 was found; it never showed up in the build. |

## 11. Performance optimization notes

- Inference input is fixed at 320x320 for the bundled model (see section 5 for why 416/640 aren't just a settings toggle for this specific export).
- `FrameAnalyzer` drops (not queues) any frame that arrives while a previous one is mid-inference, paired with `STRATEGY_KEEP_ONLY_LATEST` -- this is what keeps the camera preview smooth independent of inference speed.
- `NcnnObjectDetector` funnels every native call through a single dedicated `@InferenceDispatcher` thread (see `di/DispatcherModule.kt`), so inference is never accidentally run concurrently.
- `net.opt.num_threads` is set to `hardwareThreads - 1` at init, leaving a core free for the camera/UI pipeline.
- FP16 packing/storage/arithmetic and SIMD packing layout are currently **disabled** in `native_detector.cpp` (`use_fp16_*`/`use_packing_layout = false`). They were disabled while chasing the YOLOv12n crash in section 1 and, although that crash turned out to be the `MatMul`/`Reduction` ops (which YOLOv8n doesn't use), they were never re-verified as safe to re-enable on-device afterward. Re-enabling them is a reasonable performance follow-up, but only after the same kind of on-device verification described above -- don't just flip the flags and assume it's fine.
- `PerformanceStatsOverlay` shows live FPS / inference time / total pipeline time / detection count on-device -- use it rather than assuming a number.

## 12. Known limitations

- **Vulkan is not available** in the vendored NCNN build (CPU-only); see the troubleshooting table.
- **Model input is fixed at 320x320** for the bundled YOLOv8n export; the "model input resolution" Settings field is informational only unless you re-export and update the native constant.
- **Distance estimates are approximate**, sensitive to pose (side-on vs. frontal), occlusion, and per-class real-world size variance (see each `ObjectDimension.reliability`/`.notes`). They are never presented as precise measurements, and the app makes no collision-avoidance guarantee.
- **Object tracking is a lightweight greedy IoU/centroid matcher**, not a Kalman filter or full MOT solution -- fast crossing motion or heavy occlusion can lose or re-ID a track.
- **INT8 quantization is not included** (needs a calibration dataset this repo doesn't have); see section 5.
- **The 4 Compose-rendering instrumentation tests need an unlocked, awake screen to pass** -- they failed in this session's run because the test device's lock screen covered the test activity (see section 7). Re-run `./gradlew connectedDebugAndroidTest` with the device unlocked to confirm.
- Indonesian/English label translation in `AnnouncementManager` covers the ~40 classes in `ObjectDimensions` (the ones distance estimation actually applies to); the remaining COCO classes fall back to a capitalized English label even in Indonesian mode.
- **Voice commands are display-only** (section 9): "NAVI, ..." speech is captured and shown/echoed, but no command is actually dispatched/executed yet.
- **Voice commands require internet and the user's own AssemblyAI API key** -- this is the one part of the app that isn't fully offline (see the note at the top of this README). No key configured means no voice commands, shown clearly rather than failing silently.
- **The API key is stored in plain DataStore Preferences, not a secure keystore**, and is sent directly from the device on every connection since this app has no backend to mint short-lived tokens. Acceptable for personal use with your own key; do not ship a build with a shared key embedded.
- Continuous listening has no true echo cancellation against NAVI's own spoken "please repeat" prompt -- see section 9's notes on the fixed mute window used to reduce (not eliminate) that risk.
- **The AssemblyAI integration was verified only up to the "no API key configured" graceful-degradation path** (confirmed on an emulator: correct localized message, no crash) and via a code-level review against AssemblyAI's published protocol -- this environment had no AssemblyAI API key available to test the live audio-streaming path end-to-end. Get a key and test with real speech before relying on it.
- **Section 9 above (voice commands) predates command dispatch.** Voice commands are no longer display-only -- Scene Understanding, Text Reading (OCR), Hazard Awareness, Object Search, and now Route Recording/Navigation (section 13) all actually execute. That section's "Voice commands are display-only" framing is stale and kept as-is here rather than rewritten, since rewriting it wasn't part of this change; `VoiceCommandParser`/wake-word matching itself is still accurate.

## 13. Route recording & navigation (GPS + compass)

Two features: record a walked route as a sequence of GPS points, then later navigate it again with spoken turn-by-turn guidance -- no maps, just "walk this path again," aimed at a blind/low-vision user retracing a familiar route (e.g. home to a bus stop).

### Feature 1: Record Walking Route

- **Voice**: "NAVI, mulai merekam jalan" starts; "NAVI, stop merekam jalan" stops and asks "Sebutkan nama rute yang ingin disimpan." -- whatever is said *next* is saved as the name, without needing to repeat "NAVI" first (`VoiceCommandManager.awaitNextRawUtterance()`, a one-shot raw-dictation mode added specifically for this). A manual Start/Stop button plus a text-field fallback for the name (in case the name capture mishears) are on `RecordingScreen` too.
- **What's recorded per point**: latitude, longitude, altitude, GPS accuracy, speed, bearing, timestamp (`RoutePointEntity`), at ~1s intervals via `FusedLocationProviderClient` (`FusedLocationProvider`).
- **Storage**: Room (`NaviRoomDatabase`/`RouteDao`/`RouteRepository`) -- a `RouteEntity` (name, createdAt, total distance, point count) with a one-to-many `RoutePointEntity` list, ordered by an explicit `sequenceNumber` (not insertion/id order, which Room's `@Relation` doesn't guarantee). Total distance is computed from consecutive-point Haversine deltas (`GeoMath`) at save time.
- **Recording itself is a singleton (`RouteRecorder`), not view-model-scoped** -- it keeps running (and keeps its in-progress points) if the user briefly leaves `RecordingScreen`, e.g. to check Settings.

### Feature 2: Voice-Based Route Navigation

- **Voice**: "NAVI, mulai navigasi <nama rute>" (or "navigasi ke <rute>" / English "navigate to <name>") looks the route up by name and starts; "NAVI, stop navigasi" stops. The route-name extraction is regex-based (`NavigationCommandMatcher`), same pragmatic approach as this app's other free-form-argument voice commands (e.g. Object Search).
- **`NavigationEngine`** (`routenav/NavigationEngine.kt`) is the actual turn-by-turn decision logic, and is deliberately framework-free (no `android.location.Location`, just plain lat/lon doubles) specifically so it's unit-testable without a device/emulator/Robolectric -- GPS/compass behavior genuinely can't be exercised live in this environment. `GeoMath` (Haversine distance/bearing, also framework-free) backs it. Both have full unit test coverage (`NavigationEngineTest`, `GeoMathTest`).
  - Advances through route points by proximity, detects arrival at the final point, flags deviation as "too far from every recorded point" (an approximation of true path-deviation, not a precise perpendicular-distance-to-polyline calculation), and classifies the heading-difference-to-next-point into 7 turn buckets (straight / slight / normal / sharp, left or right).
  - All the thresholds the spec calls out as "must be configurable" (route-point proximity, arrival radius, deviation distance, minimum GPS accuracy) are constructor parameters (`NavigationThresholds`), not hardcoded.
- **`NavigationController`** (singleton) wires `NavigationEngine` to real GPS (`LocationProvider`/`FusedLocationProvider`) and compass (`CompassManager`/`DeviceCompassManager`) updates, applies announcement cooldowns (repeat the same turn instruction at most every 8s unless it changed; periodic distance callouts every 15s; deviation/arrival always speak immediately and flush the queue -- "prioritize emergency warnings" from the spec), and also owns voice listening for "stop navigasi" *while navigation is active*, independent of which screen (if any) is visible.
- **`RouteNavigationService`** is a real Android foreground service (`foregroundServiceType="location"`) whose only job is keeping the process alive with a persistent notification while navigating -- it does not contain navigation logic itself (that's `NavigationController`, a plain singleton, so navigation state survives even if the OS were to recreate the Service). This is what makes "stop navigasi" and turn-by-turn guidance keep working with the screen off / app backgrounded, per the spec's "work with the screen turned off where Android permits."
- **Compass** (`DeviceCompassManager`): prefers the fused rotation-vector sensor where available, falls back to manual accelerometer+magnetometer combination via `SensorManager.getRotationMatrix`/`getOrientation`. Circular-aware low-pass smoothing (blends via the shortest angular path, so it doesn't spike crossing the 0°/360° wraparound). `HeadingReading.isReliable` mirrors the OS's own sensor-accuracy report (drops under magnetic interference); low-reliability readings are treated as "no heading" (`NavigationEngine` still reports distance, just without a turn direction) rather than trusted. See the class doc in `DeviceCompassManager.kt` for why a phone held vertically in a pocket is actually a *fine* orientation for this -- the genuinely bad case is the phone lying flat.
- **Camera**: `NavigationScreen` activates the rear camera for a live preview only (via a new `CameraManager.bindPreviewOnly()`), per the spec's "current implementation may focus on camera activation and preview." No `ImageAnalysis` use case is bound for navigation -- this app already has a complete YOLO/NCNN object-detection pipeline (`ObjectDetector`/`FrameAnalyzer`, see section 1/3) for the Detection screen; wiring live obstacle detection into navigation later means reusing that pipeline the same way `DetectionScreen` already does, not building a second one from a placeholder interface that doesn't actually detect anything.
- **Entry points**: `DetectionScreen` -- this app's single screen for every voice-triggered feature (see the note at the top of section 14) -- handles both voice commands directly, so "mulai merekam jalan" / "mulai navigasi X" work without navigating anywhere first; `SavedRoutesScreen` lists routes (name, created date, distance, point count) with per-route Navigate/Delete buttons as the non-voice path.

### Known limitations specific to this feature

- **Not tested on a real device.** GPS, compass sensors, a real foreground-service background run, and actual walking can't be exercised in this environment -- everything here was verified by (a) a full `assembleDebug` build succeeding (manifest, resources, Room/KSP codegen, native build all still link correctly) and (b) unit tests for every framework-independent piece of logic (`GeoMath`, `NavigationEngine`, `RouteRecordingMatcher`, `NavigationCommandMatcher`). The Android-framework-heavy pieces (`FusedLocationProvider`, `DeviceCompassManager`, `RouteNavigationService`, Room DAOs) compile and wire together correctly but have no automated coverage of their own -- please verify GPS accuracy/update cadence, compass responsiveness while pocketed, and the foreground notification/background survival behavior on an actual device before relying on this outdoors.
- **Route deviation is an approximation** ("too far from every recorded point"), not a true perpendicular-distance-to-polyline calculation -- fine for a path that was walked once and is being retraced, less precise for routes with sections that loop back near each other.
- **The route-name voice capture has no confirmation step** -- whatever utterance comes right after "stop merekam jalan" is saved verbatim (trimmed). A misheard name is silently wrong until the user notices it in Saved Routes; the manual text-field fallback on `RecordingScreen` exists specifically for this.
- **POST_NOTIFICATIONS is declared but never requested at runtime** -- on Android 13+, without the user granting it, the navigation foreground service still runs (GPS/compass/TTS all still work), but its persistent notification may not actually be visible. Not currently blocking since the notification is a convenience, not how any guidance is delivered (that's all spoken via TTS), but worth adding a runtime request for a polished release.
- **Cross-screen voice listening**: voice-command dispatch lives entirely in `DetectionViewModel` now (see section 14's note -- there used to be a separate HomeScreen/HomeViewModel hub; removed so every voice feature is heard from one screen instead of split across two). `DetectionScreen` is also the app's landing screen after Splash.

## 14. Conversation memory

A simple, local memory NAVI can save/recall/forget on request -- e.g. "ingat bahwa rumah saya berada di Depok" now, "di mana rumah saya?" later.

**One screen for every voice feature.** This app originally had a separate `HomeScreen`/`HomeViewModel` that owned the route-recording/navigation voice commands, alongside `DetectionScreen`/`DetectionViewModel` owning Scene Understanding/Text Reading/Hazard Awareness/Object Search. Both have since been merged into `DetectionViewModel.handleVoiceCommand()` -- HomeScreen is gone, `DetectionScreen` is the landing screen and handles every voice command this app has. Merging surfaced a real ambiguity worth calling out: [`MemoryCommandMatcher.extractRecallQuery`](../app/src/main/java/com/apps/naviai/memory/MemoryCommandMatcher.kt) and [`ObjectSearchMatcher.extractQuery`](../app/src/main/java/com/apps/naviai/scene/ObjectSearchMatcher.kt) both match the exact same "di mana X" / "where is X" phrasing (memory asking where something *is*, Object Search asking whether it's *visible right now* -- genuinely the same sentence shape for two different intents). `MemoryManager.handleCommand()` is now `suspend` and resolves this by only "claiming" a bare question if it actually finds a matching memory first; otherwise it returns `false` so `DetectionViewModel` falls through to Object Search. See `handleVoiceCommand`'s doc comment in `DetectionViewModel.kt` for the full dispatch priority order.

- **Voice commands** (all handled by `memory/MemoryManager.kt`, dispatched from `DetectionViewModel` -- see the note above): "ingat bahwa X" / "remember that X" saves; "apa yang kamu ingat tentang saya?" / "what do you remember about me" reads back everything saved; a plain question ("di mana rumah saya?", "where do I live?") searches saved memories by keyword overlap and speaks the best match *if one is found* (see above); "lupakan X" / "forget X" deletes the best-matching memory; "hapus semua ingatan saya" / "clear all memories" asks for spoken confirmation ("say 'yes' to confirm") before actually deleting everything, reusing the same one-shot raw-dictation mode (`VoiceCommandManager.awaitNextRawUtterance()`) Route Recording's naming step introduced.
- **Storage**: `ConversationMemoryEntity` (content, category, createdAt, updatedAt, isImportant) in the same Room database as routes (`NaviRoomDatabase`, bumped to schema version 2 with `fallbackToDestructiveMigration()` -- see the version-bump comment there for why that's fine now but not for a shipped release). A `Converters` class persists the `MemoryCategory` enum as its name string.
- **Category classification** (`MemoryCategoryClassifier`) and **importance flagging** (`MemoryImportanceClassifier`) are both plain keyword heuristics, not ML -- checked in priority order (a concrete location wins over a vague preference match) and fully unit tested. **Recall/forget matching** (`MemorySearch`) tokenizes both the query and each memory's content, strips a stop-word list (ID+EN), and scores by shared-keyword count -- not embeddings/semantic search, since this app has no on-device NLU model for that.
- **Modularity**: every piece of "intelligence" here (`MemoryCommandMatcher`, `MemoryCategoryClassifier`, `MemoryImportanceClassifier`, `MemorySearch`) is a pure, dependency-free function/object -- `MemoryManager` only orchestrates them plus TTS/Room. Swapping any of them for something smarter later (e.g. an LLM-backed classifier or semantic search) doesn't require touching `MemoryManager`'s control flow, and `ConversationMemoryRepository` is an interface so the storage backend is swappable too -- this is the "modular and extensible for future AI-powered conversation features" requirement.
- **`MemoryScreen`** (reached from a top-bar button on `DetectionScreen`) lists all saved memories with their category chip and an importance star, and supports manual add/edit/delete plus a "Clear all" button with an `AlertDialog` confirmation (the same "ask for confirmation before deleting all" requirement, enforced on both the voice and UI paths).
- **Remaining ambiguity**: the memory-vs-search fix above only prevents memory from wrongly swallowing a *search* question. It doesn't prevent the reverse in every case, and it doesn't disambiguate Scene Understanding questions that happen to share a keyword with a stored memory (e.g. asking to describe a room when a memory happens to mention "kantor") -- see this section's "Known limitations" below.
- **Privacy**: memories are stored only in the local Room database -- nothing here uploads them anywhere. (Separately, and unrelated to this feature: this app's *voice transcription* already goes to AssemblyAI's cloud API per section 9, so the audio the user speaks a memory command in still leaves the device the same way any other voice command's audio does -- but the resulting *memory record itself* is never sent anywhere once transcribed.) Nothing here auto-detects or blocks "sensitive" content -- per the spec, only what the user explicitly asks to be remembered gets saved.

### Known limitations specific to this feature

- **No update-by-voice / de-duplication.** Saying "ingat bahwa X" always inserts a new row, even if a similar memory already exists -- there's no automatic merge (detecting "this is an update to memory #3" from free text is genuine NLU this app doesn't have). Editing an existing memory is a `MemoryScreen` UI action, not a voice one; the spec's "allow users to update existing memories" is satisfied there, not via voice.
- **Recall speaks only the single best-matching memory** for a specific question (not a ranked list) -- if two memories are both about "kantor", only the top-scoring one is read out.
- **Keyword search, not semantic search**: "di mana saya bekerja" (where do I work) won't match a memory that only says "kantor saya di Sudirman" (my office is at Sudirman) unless a literal token overlaps -- there's no synonym awareness ("bekerja" != "kantor").
- **Memory-vs-Object-Search disambiguation is one-directional and keyword-based, not true intent classification.** The fix only stops memory from claiming a search question it has no answer for; it does NOT stop memory from claiming a search question it *coincidentally* has a keyword-matching (but semantically wrong) memory for -- e.g. a stored memory "kantor saya di Sudirman" would hijack "di mana kantor pos terdekat?" (where's the nearest post office?) since both share the token "kantor". Likewise, nothing here disambiguates Scene Understanding-shaped questions from memory recall if they happen to share a keyword. A real fix needs actual intent classification (e.g. LLM-based), not keyword overlap -- out of scope for this pass.
- **Same "untested on a real device" caveat as section 13** -- Room/DAO codegen and the full app compile/link correctly (`assembleDebug` succeeded, KSP generated the new schema without error), and every pure-logic piece has full unit test coverage, but the actual voice round-trip (say a memory, hear it recalled) hasn't been exercised live.

## 15. Offline mode

Settings > Offline mode is a single switch (`AppSettings.offlineModeEnabled`, `SettingsRepository`) that makes explicit what's already true of most of this app: everything below except two features runs entirely on-device, with no network call of any kind. For the two that don't, it does the best available thing rather than a blanket "disabled": voice commands switch to a real on-device speech engine, and the LLM vision features keep working if (and only if) they're already pointed at a local server.

### Fully offline, unaffected by this setting

These never touch the network, on or off:

- **Object detection, distance estimation, tracking, risk assessment** (sections 1-3, 6) -- YOLOv8n/NCNN inference, `DistanceEstimator`, `ObjectTracker`, `RiskAssessmentEngine` all run on-device.
- **Spoken announcements** (`AnnouncementManager`, `TextToSpeechManager`) -- Android's on-device TTS engine.
- **Route recording & navigation** (section 13) -- GPS (`FusedLocationProviderClient`) and the compass sensors work without internet (they don't need a cellular/Wi-Fi data connection, just the location/sensor hardware); routes are stored locally in Room. The *manual* Start/Stop/Navigate/Delete/Rename controls on `RecordingScreen`/`SavedRoutesScreen` need no voice recognition at all.
- **Conversation memory** (section 14) -- stored and searched locally in Room; the `MemoryScreen` manual add/edit/delete/clear-all UI needs no voice recognition either.

### What Offline mode does to the two network-dependent features

| Feature | Why it normally needs the internet | Behavior with Offline mode on |
|---|---|---|
| **Voice commands** (wake-word capture, all of section 9's "NAVI, ..." commands) | Speech-to-text normally runs on AssemblyAI's cloud API (`AssemblyAiBatchTranscriber`). | **Switches engines** to `AndroidSpeechRecognizerTranscriber` -- Android's built-in on-device speech recognizer (`SpeechRecognizer.createOnDeviceSpeechRecognizer`) -- instead of just disabling voice commands. `VoiceCommandManager.startContinuousListening()` picks the engine based on this setting and always stops whichever one was previously active before switching (only one may hold the mic). Wake-word matching (`VoiceCommandParser`) is identical either way, so "NAVI, ..." commands behave the same regardless of engine. See "On-device recognition" below for its requirements and limitations. |
| **Scene Understanding, Text Reading (OCR), Hazard Awareness's LLM check, Object Search** (section 9's LLM-backed features, all going through `LlmClient.chatWithImage`) | These need a vision-capable chat-completions endpoint. `Settings > LLM` lets that endpoint be a **local** server (e.g. Ollama/LM Studio on your own LAN) or a **hosted** one (OpenRouter, Groq, etc.) -- only the hosted case actually needs the internet. | `LocalEndpoint.isLocal()` checks the configured base URL: loopback (`localhost`/`127.0.0.1`) and private-LAN hosts (`10.x`, `172.16-31.x`, `192.168.x`) are treated as still-offline and keep working; anything else is blocked with "This feature needs the internet and is disabled while Offline Mode is on." A LAN mDNS hostname (`ollama.local`) is deliberately **not** recognized (no DNS resolution is attempted) -- use the server's literal IP if you want it to count as local. |

### On-device voice recognition (`AndroidSpeechRecognizerTranscriber`)

- **Requires Android 12 (API 31)+.** `SpeechRecognizer.createOnDeviceSpeechRecognizer()` is the only API that actually *guarantees* on-device-only recognition -- the older `createSpeechRecognizer()` + `EXTRA_PREFER_OFFLINE` combination is merely a *hint* that some recognizer implementations silently ignore, falling back to a network call with no offline language model downloaded. That would defeat the entire point of this setting, so on API < 31 (or if `SpeechRecognizer.isRecognitionAvailable()` is false), `isSupported()` returns false and voice commands are left unavailable instead, with a message saying so -- never a silent network call.
- **Defense in depth even on 31+**: if the on-device recognizer ever reports `ERROR_NETWORK`/`ERROR_NETWORK_TIMEOUT` anyway, that's treated as a fatal error (stops listening, surfaces a message) rather than retried, on the theory that a recognizer attempting the network at all under Offline Mode is a bug worth surfacing, not papering over.
- **No true "always listening" API.** Unlike AssemblyAI's continuous capture, `SpeechRecognizer.startListening()` captures at most one utterance per call. Continuous listening here is a restart-loop (start again after every result/silence/error) -- the same approach this app's very first voice implementation used before being replaced by AssemblyAI specifically because that loop's restart gap could clip the start of the next utterance (see section 9). This tradeoff is accepted here because it's the only way to get always-on voice commands that are also genuinely offline.
- **Language support isn't guaranteed.** If the on-device recognizer doesn't support the selected language on a given device, `ERROR_LANGUAGE_NOT_SUPPORTED`/`ERROR_LANGUAGE_UNAVAILABLE` surface as a clear message rather than a silent failure -- but which languages are actually available on-device varies by device/OS build and isn't something this app controls.
- **Wake-word biasing** (`RecognizerIntent.EXTRA_BIASING_STRINGS`, biasing toward "navi"/"navy") only takes effect on API 33+; below that, recognition runs without it, same as it would if AssemblyAI's `word_boost` were unavailable.
- **Not verified on a physical device** -- like section 13's GPS/compass code, this compiles and the unit-testable pieces (`LocalEndpoint`) are covered, but the actual on-device recognizer round trip needs a real Android 12+ device to confirm.

### Known limitations of this feature

- **`LocalEndpoint.isLocal()` is a literal-host check, not a live reachability check.** It doesn't verify the endpoint actually responds, only that its host looks local -- a wrong or unreachable local IP still "passes" and then fails at request time with the normal connection-error message, same as it would with Offline mode off.
- **No airplane-mode/connectivity auto-detection.** This is a manual switch the user turns on/off, not something that reacts automatically to the device losing signal -- turning it on is a deliberate "don't even try the network" choice, not a network-state indicator.
- **Hazard Awareness's LLM supplement is silent either way** (see section 9): its fast on-device announcement path is unaffected by this setting; only the extra LLM-described warning is skipped when blocked, with no spoken message about it (consistent with how it already handles a missing LLM config).
