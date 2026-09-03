# MEDLAM-AI AGENT 🧠⚡
> **Autonomous On-Device Neural Core & Hybrid Cognitive Workspace for Android**

[![Platform](https://img.shields.io/badge/Platform-Android%20ARM64-3DDC84.svg?style=flat&logo=android)](https://github.com/medlam1981/synapse-ai)
[![Engine](https://img.shields.io/badge/Inference-llama.cpp%20JNI%20%2B%20Gemini%20Cloud-0070F3.svg?style=flat)](https://github.com/medlam1981/synapse-ai)
[![Architecture](https://img.shields.io/badge/Architecture-Gemma%20%7C%20Qwen%20%7C%20Llama-8A2BE2.svg?style=flat)](https://github.com/medlam1981/synapse-ai)
[![Context](https://img.shields.io/badge/Context%20Window-Up%20to%2016K%20Tokens-F59E0B.svg?style=flat)](https://github.com/medlam1981/synapse-ai)
[![Latest APK](https://img.shields.io/badge/Release-Download%20APK-10B981.svg?style=flat&logo=android)](releases/app-debug.apk)

---

## 📥 Direct APK Download

Download and install the latest compiled release:
👉 **[Download Latest APK (`releases/app-debug.apk`)](https://github.com/medlam1981/synapse-ai/raw/main/releases/app-debug.apk)**

---

## 🌟 Executive Overview

**MEDLAM-AI AGENT** (formerly Synapse AI) is a next-generation autonomous AI system built natively for Android (ARM64). It bridges on-device neural model execution via optimized `llama.cpp` native binaries with cloud-scale reasoning via Gemini APIs. Designed for complete privacy, unconstrained code generation, and deep device automation, MEDLAM-AI functions as an autonomous operating system companion that can think, plan, browse, code, and execute multi-step workflows directly on hardware.

---

## 🚀 Key Architectural Pillars

### 1. Dual Cognitive Engine & Complete Decoupling
- **On-Device Neural Core (Local GGUF)**:
  - High-performance native inference through ARM64 NEON-accelerated `llama.cpp` JNI bindings.
  - Broad universal architecture ingestion: zero-friction loading of **Gemma** (e.g., Gemma 2/4 E2B), **Qwen** (Qwen 2.5, Qwen 3), and **Llama** families without tensor mapping bottlenecks.
  - Zero cloud dependency for total data privacy and air-gapped productivity.
- **Cloud Cognitive Mode (Gemini)**:
  - Multi-key auto-rotating Gemini pipeline (Gemini 2.0 Flash / Pro) with instant fallback.
  - **Complete Engine Isolation**: Activating Cloud Mode cleanly terminates local `llama-server` background threads to release RAM and preserve battery life.

### 2. Speculative Multi-Token Prediction (MTP) & Manual Pairing
- **High-Velocity Speculative Decoding**:
  - Leverages companion draft models (e.g., `mtp-gemma-4-E2B-it-BF16.gguf`) to accelerate generation speeds.
  - Smart validation protects against zero-KV metadata headers to eliminate native crash vectors.
- **User-Driven Companion Draft Linking**:
  - Seamless manual selection in System Settings: link any companion draft model or operate in standalone autoregressive mode with automatic fallback.

### 3. Extended Context Horizon (Up to 16K) & Unconstrained Output
- **16,384 Token Context Window**:
  - Configurable context presets (`2K`, `4K`, `8K`, `16K`) with dynamic KV-cache memory allocation matched to device RAM.
- **Uncapped Generation Horizons**:
  - Elimination of completion token bottlenecks (`n_predict = -1`, `max_tokens = -1`), allowing the agent to synthesize full, production-ready web apps, scripts, and documents in a single turn.

### 4. Reactive Activity Pipeline & Real-Time Phase Reflection
- **Immediate Post-Prompt Reasoning Anchor**:
  - The reasoning tray (`<think>` stream) initializes immediately beneath the user query (`USER >> [query]`) as the very first response component.
- **Persistent Dynamic Activity Line**:
  - Anchored directly below the active thinking block or emerging artifacts at the absolute bottom edge of the response, acting as the live operational boundary.
  - Granular real-time phase reflection matching elite agentic platforms:
    * `Reading context & memories...`
    * `Thinking & formulating architecture...`
    * `Searching the web in background...`
    * `Writing target file: [file_path]...`
    * `Executing device tool: [tool_name]...`
    * `Synthesizing final output...`
- **Default-Collapsed Auxiliary Streams**:
  - Internal thought traces (`<think>`) and raw code blocks initialize collapsed into sleek, low-profile preview chips to maintain high visual signal-to-noise ratio. Tap to inspect full logs on demand.

### 5. Viewport Geometry & Adaptive Floating Layout
- **Dynamic Bottom Inset Safeguard**:
  - Enforced bottom insets within `LazyColumn` calculated strictly above the floating message input capsule to eliminate content clipping.
  - Complete software keyboard compatibility with `Modifier.imePadding()`.
- **Auto-Scroll Tracking Anchor**:
  - Viewport automatically snaps and tracks the live dynamic activity line as new tokens and status events stream in.

### 6. Hardware Telemetry Capsule & API Privacy
- **Persistent Telemetry Strip**:
  - Real-time hardware telemetry: Battery percentage, available RAM, CPU load percentage, and SoC thermal readings.
  - Dedicated background task queue counter (`tsk [count]`) and synchronized digital clock.
- **API Key Privacy Toggle**:
  - System setting switch to conceal active API key rotation indicators during screen recordings, live demos, and screenshots.

### 7. Bidirectional Layout & Arabic/Darija RTL Typography
- **Automatic Script Detection**:
  - Real-time detection of Arabic and Moroccan Darija scripts in user prompts and assistant outputs.
- **Strict RTL Alignment**:
  - Optical baseline alignment across both LTR and RTL layouts using standardized forward-arrow indicators:
    * User: `USER >> ` / `المستخدم >> `
    * Agent: `MEDLAM >>` / `ميدلام >>`

### 8. System Memory Optimization (ZRAM & Low-Memory Bypass)
- **ZRAM Compression Tuning**:
  - Automated kernel memory optimization: dynamic zram device sizing, swappiness acceleration (`vm.swappiness = 100`), and page clustering (`vm.page-cluster = 0`).
- **OOM Killer Suppression**:
  - Disables aggressive low-memory process termination (`/proc/sys/vm/oom_kill_allocating_task`), allowing large model weights to be mapped safely without sudden app termination.

---

## 🛠️ Built-in Autonomous Tool Arsenal

The agent can invoke internal and device-level tools autonomously during task execution:

| Tool | Functionality |
| :--- | :--- |
| `web_search(query)` | Live web search and retrieval of current documentation, facts, and code examples. |
| `write_to_file(path, content)` | Writes source code, configuration files, and deliverables directly to device storage. |
| `read_file(path)` | Inspects files, scripts, and logs on device. |
| `list_directory(path)` | Explores directory structures and manages files. |
| `execute_shell(command)` | Runs native shell and termux commands in the background. |
| `device_navigation` | Uses Android Accessibility Services to click, scroll, and interact with Android apps. |
| `synapse_keyboard_ime` | Custom native Input Method for fast, precision text injection. |

---

## 📱 System Requirements & Model Placement

### Requirements
- **OS**: Android 10.0+ (API Level 29+)
- **Architecture**: ARM64-v8a
- **RAM**:
  - 4GB+ for 2B models (e.g., Gemma-4-E2B, Qwen3.8-2B Q2/Q4)
  - 8GB+ for 8K–16K extended context windows

### Recommended Storage Layout
Place your GGUF models on device storage:
```text
/sdcard/Medlam-Ai/
├── models/
│   ├── gemma-4-E2B-it-qat-UD-Q2_K_XL.gguf
│   └── Qwen3.8-2B-BF16.gguf
├── mtp/
│   └── mtp-gemma-4-E2B-it-BF16.gguf
├── personas/
│   └── (custom system prompts and agent personas)
└── webapp/
    └── (agent-generated deliverables and codebases)
```

---

## 🔧 Building from Source

### Prerequisites
- **JDK**: OpenJDK 21
- **Android SDK**: Build Tools 35.0.0, Platform 35
- **Android NDK**: 26.1.10909125
- **Gradle**: 8.13

### Build Command
```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64
./gradlew assembleDebug
```
The output APK will be generated at:
```text
app/build/outputs/apk/debug/app-debug.apk
```

### Fast ADB Deployment
```bash
adb install -r releases/app-debug.apk
adb shell am start -n com.synapse.localai/.MainActivity
```

---

## 📄 License & Attribution

- Core inference powered by [`llama.cpp`](https://github.com/ggerganov/llama.cpp) (MIT License).
- Designed and engineered for high-performance autonomous edge computing.

---
*MEDLAM-AI AGENT — Intelligence Unleashed on Hardware.* 🚀🛡️
