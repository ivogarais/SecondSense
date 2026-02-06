# SecondSense

Offline Android assistant skeleton for:
- On-device LLM runtime (`llama.cpp` integration later)
- Accessibility-driven UI automation loop
- Voice input and structured action execution

## Project status
This repository currently contains project structure and build configuration only.
No app logic has been implemented yet.
Gradle wrapper scripts are not generated in this scaffold.

## Suggested next coding files
1. `app/src/main/java/com/secondsense/agent/contract/ActionSchema.kt`
2. `app/src/main/java/com/secondsense/agent/engine/AgentLoopController.kt`
3. `app/src/main/java/com/secondsense/accessibility/SecondSenseAccessibilityService.kt`
4. `app/src/main/java/com/secondsense/llm/runtime/LlamaRuntime.kt`
5. `app/src/main/java/com/secondsense/speech/SpeechInputManager.kt`
