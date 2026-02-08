# SecondSense

An Android prototype exploring whether an on-device LLM can operate the phone through the Accessibility API (observe UI state, decide, and execute actions).

## Project status
This repo contains scaffolding for:
- An offline model lifecycle (catalog, download, verify, storage, selection)
- A local-model runner interface and JNI bridge placeholder for `llama.cpp`
- An Accessibility capture/execution loop with structured action validation

Current conclusion: a built-in, fully on-device LLM is not currently successful at reliably operating a phone through the Accessibility API, primarily due to model size/compute constraints on mobile and limited training data aligned to real UI interaction.

Native build requires Android NDK/CMake. See `docs/setup-llama-android.md`.
