# Developer & Agent Guideline

Welcome! If you are an AI assistant or developer working on the **Cantonese Voice Keyboard** project, please read and follow these instructions carefully.

---

## 1. Project Context & Status
This project builds a local, on-device Cantonese Voice Input Keyboard on Android using Jetpack Compose, Kotlin Coroutines, and the highly optimized Rust `QwenASR` backend (utilizing the INT8 pre-quantized K4 SDOT prefill baseline).

To understand our target architecture, memory optimizations, and step-by-step progress, **you must read the following two files immediately**:

1.  **Implementation Design**: Read [implementation_plan.md](file:///Users/tony/Documents/keyboard/cantonese-voice-keyboard/implementation_plan.md) to understand:
    *   **Zero-Copy JNI Buffer Bindings**: Passing JVM audio data via direct `ByteBuffer` pointers mapped to `GetDirectBufferAddress` in Rust to eliminate copies.
    *   **Modern Android Development (MAD)**: Compose state hoisting, Unidirectional Data Flow, and reactive Coroutine Flows.
    *   **Visibility-Based Memory Optimization**: Loading the ASR engine in `onStartInputView` and calling `.free()` immediately in `onFinishInputView` to keep the background memory footprint close to zero.
    *   **Deployment Script**: Building and running the app using the automated `deploy.sh` script.
2.  **Task Tracker & Checklists**: Read [task.md](file:///Users/tony/Documents/keyboard/cantonese-voice-keyboard/task.md) to view the granular checklist of tasks.

---

## 2. Dynamic Working Protocol

> [!IMPORTANT]
> **Strict Checkbox Rule**: The user directs the implementation **one checkbox at a time**.
> - Do NOT attempt to implement multiple tasks or checkboxes at once.
> - Only focus on the specific active checkbox requested by the user.
> - When a task is completed, mark it as checked `[x]` in `task.md`, commit your changes to Git, and wait for instructions before proceeding to the next checkbox.
