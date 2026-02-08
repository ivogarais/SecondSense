# Offline Model Lifecycle

## Current implementation

1. User starts download from app UI.
2. `ModelDownloadManager` downloads the selected GGUF model into `filesDir/models/`.
3. Download supports pause, resume (HTTP range), and cancel.
4. Completed file is verified against expected SHA-256.
5. Verified model is marked as preferred in `filesDir/models/preferred_model.txt`.
6. `ModelSessionManager` loads preferred model into local runtime.
7. If loading fails, app reports clear error and keeps model selection unchanged.

## Default variants

- Preferred: `gemma-3-1b-it-q5_k_m.gguf`
- Fallback: none (same model)

## Native runtime integration

- Runtime interface is `LlamaRuntime`.
- Current concrete runtime is `JniLlamaRuntime`, expecting `llama.cpp` native JNI symbols.
- Native source is fetched from `ggml-org/llama.cpp` via CMake `FetchContent`:
  - `nativeLoadModel`
  - `nativeUnloadModel`
  - `nativeGenerate`
