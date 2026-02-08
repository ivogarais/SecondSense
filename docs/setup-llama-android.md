# Llama.cpp Android Native Setup

This project now includes Kotlin/JNI bridge and native CMake wiring in:

- `app/src/main/java/com/secondsense/llm/JniLlamaRuntime.kt`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/cpp/secondsense-llama-jni.cpp`

Native source is fetched from `ggml-org/llama.cpp` during CMake configure using `FetchContent`.

## Required JNI symbols

- `nativeLoadModel(modelPath: String, contextSize: Int, threads: Int): Long`
- `nativeUnloadModel(handle: Long): Unit`
- `nativeGenerate(handle: Long, prompt: String, maxTokens: Int, temperature: Float, topP: Float): String`

## Build notes

1. Ensure Android NDK + CMake are installed in Android Studio SDK manager.
2. Build from Gradle (`assembleDebug` / `installDebug`); native `secondsense_llama` is produced automatically.
3. Current ABI filter is `arm64-v8a` in `app/build.gradle.kts`.

## ABI targets

Recommended minimum:

- `arm64-v8a`

Optional:

- `armeabi-v7a`
- `x86_64` (emulator)

## Validation

1. Build/install debug app.
2. Download model from in-app controls.
3. Tap `Load Local Model`.
4. Confirm no `llama.cpp native library not loaded` error in UI/logcat.
