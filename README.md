<div align="center">

<img src="https://img.shields.io/badge/ATLAS--AI-AGENT-6366F1.svg?style=for-the-badge&logo=android&logoColor=white" alt="ATLAS-AI"/>

# ATLAS-AI — Autonomous Neural Agent for Android

[![Android](https://img.shields.io/badge/Android-10.0%2B-3DDC84.svg?style=flat&logo=android)](https://github.com/medlam1981/synapse-ai)
[![ARM64](https://img.shields.io/badge/Architecture-ARM64--v8a-0EA5E9.svg?style=flat)](https://github.com/medlam1981/synapse-ai)
[![Vulkan](https://img.shields.io/badge/GPU-Vulkan%201.1-AC162C.svg?style=flat&logo=vulkan)](https://github.com/medlam1981/synapse-ai)
[![llama.cpp](https://img.shields.io/badge/Inference-llama.cpp-F59E0B.svg?style=flat)](https://github.com/ggerganov/llama.cpp)
[![Stable Diffusion](https://img.shields.io/badge/Images-Stable%20Diffusion-EC4899.svg?style=flat)](https://github.com/leejet/stable-diffusion.cpp)
[![Context](https://img.shields.io/badge/Context%20Window-Up%20to%2016K%20Tokens-F59E0B.svg?style=flat)](https://github.com/medlam1981/synapse-ai)
[![Latest APK](https://img.shields.io/badge/Release-Download%20APK-10B981.svg?style=flat&logo=android)](releases/app-debug.apk)

</div>

---

## 📥 Direct APK Download

Download and install the latest compiled release:

👉 **[Download Latest APK (`releases/app-debug.apk`)](https://github.com/medlam1981/synapse-ai/raw/main/releases/app-debug.apk)**

> Requires Android 10+ (API 29+) on ARM64 device. Enable "Install from Unknown Sources" before installing.

---

## 🌟 Executive Overview

**ATLAS-AI AGENT** is a next-generation autonomous AI system built natively for Android (ARM64). It combines:

- **On-device neural inference** via optimized `llama.cpp` native binaries with NEON + Vulkan GPU acceleration
- **Local image generation** via `stable-diffusion.cpp` with CPU/GPU (Vulkan/Adreno) backend
- **Cloud reasoning** via Gemini APIs (Gemini 2.0 Flash / Pro)
- **Full device automation** via accessibility services, shell execution, and root-level integration

ATLAS-AI functions as an autonomous operating system companion that can think, plan, browse, write code, generate images, and execute multi-step workflows directly on-device — with **zero cloud dependency** when running fully local.

---

## 🚀 Key Architectural Pillars

### 1. Dual Cognitive Engine — Local + Cloud

| Mode | Engine | Description |
|------|--------|-------------|
| 🧠 **Local GGUF** | `llama.cpp` JNI | NEON-accelerated ARM64 inference, zero cloud dependency |
| ☁️ **Cloud Gemini** | Gemini 2.0 Flash/Pro | Multi-key auto-rotating pipeline with instant fallback |

- **Complete Engine Isolation**: Switching to Cloud Mode cleanly terminates local `llama-server` background threads to release RAM and preserve battery.
- Supports: **Gemma** (2/4 E2B), **Qwen** (2.5, 3), **Llama** families, and any GGUF-compatible architecture.

---

### 2. Local Image Generation — CPU & GPU (Vulkan)

ATLAS-AI integrates `stable-diffusion.cpp` compiled natively for ARM64 Android with full Vulkan GPU support:

```
Model: /sdcard/Download/sd_turbo-f16.gguf (or any SD GGUF model)
```

| Backend | Flag | Description |
|---------|------|-------------|
| 🖥️ **CPU (SIMD NEON)** | `--backend cpu` | Runs on CPU cores, low memory footprint |
| ⚡ **GPU (Vulkan/Adreno)** | `--backend diffusion=vulkan0,vae=vulkan0` | Hardware GPU acceleration via Vulkan 1.1 |
| 💾 **Zero-RAM Disk Stream** | `--params-backend disk` | Streams weights from disk, minimal RAM usage |
| 🗺️ **RAM MMAP** | `--mmap` | Memory-mapped file loading |

**How it works:**
1. The agent detects image generation intent from the user's message
2. Runs `sd-cli` binary (bundled in `assets/sd-cli`) with the selected backend
3. Displays the generated image directly in the chat

**Settings Card** (accessible from Settings screen):
- Choose compute device: **CPU** or **GPU (Vulkan)**
- RAM Profile selection

---

### 3. Speculative Multi-Token Prediction (MTP)

- Leverages companion draft models (e.g., `mtp-gemma-4-E2B-it-BF16.gguf`) to accelerate generation speeds
- Smart validation protects against zero-KV metadata headers to eliminate native crash vectors
- Manual draft model linking via System Settings

---

### 4. Extended Context Horizon (Up to 16K)

- Configurable context presets: `2K`, `4K`, `8K`, `16K`
- Dynamic KV-cache memory allocation matched to device RAM
- `n_predict = -1` / `max_tokens = -1` for uncapped generation
- Produces complete, production-ready apps, scripts, and documents in a single turn

---

### 5. Reactive Activity Pipeline & Real-Time Phase Reflection

**Live status indicators:**
```
Reading context & memories...
Thinking & formulating architecture...
Searching the web in background...
Writing target file: [file_path]...
Executing device tool: [tool_name]...
Generating image with sd-cli [backend]...
Synthesizing final output...
```

- Reasoning trace (`<think>`) initializes immediately beneath the user query
- Auto-scroll tracking snaps to the live dynamic activity line
- Collapsed auxiliary streams (think blocks, raw code) for clean UI

---

### 6. Hardware Telemetry Capsule

Real-time persistent strip showing:
- 🔋 Battery percentage
- 🧠 Available RAM
- ⚡ CPU load %
- 🌡️ SoC thermal readings
- 📋 Background task queue counter
- 🕒 Digital clock

---

### 7. Bidirectional RTL/LTR Layout & Arabic/Darija Support

- Automatic detection of Arabic and Moroccan Darija scripts
- Strict RTL alignment with proper `html lang="ar" dir="rtl"` in generated content
- Agent identity adapts: `ATLAS >> ` / `أطلس >> `

---

### 8. System Memory Optimization (ZRAM)

- Automated kernel tuning: dynamic ZRAM sizing, `vm.swappiness = 100`, `vm.page-cluster = 0`
- OOM Killer suppression for stable large model weight mapping
- ZRAM compression for efficient swap under constrained RAM conditions

---

### 9. Multi-Worker Orchestration

- Parallel task execution with the `MultiWorkerOrchestrator`
- Background task queue surviving app restarts
- Scheduled tasks via `schedule_task` tool
- Epistemic memory manager for persistent context

---

### 10. Chat Template Engine

- Automatic GGUF chat template detection and parsing
- Support for Gemma, Qwen, Llama, Mistral chat formats
- `ChatTemplateEngine.kt` handles model-specific prompt formatting

---

## 🛠️ Built-in Autonomous Tool Arsenal

| Tool | Functionality |
|------|--------------|
| `web_search(query)` | Live web search and retrieval |
| `write_to_file(path, content)` | Write files directly to device storage |
| `read_file(path)` | Inspect files, scripts, logs |
| `list_directory(path)` | Explore directory structures |
| `execute_shell(command)` | Run native shell/Termux commands |
| `device_navigation` | Android Accessibility — click, scroll, interact |
| `generate_image(prompt)` | Local Stable Diffusion via sd-cli (CPU/GPU) |
| `schedule_task(...)` | Schedule recurring or delayed tasks |
| `add_task(...)` | Queue background follow-up tasks |

---

## 📦 Native Libraries (ARM64)

All libraries compiled natively for `arm64-v8a` without LTO for stability:

| Library | Size | Description |
|---------|------|-------------|
| `libllama_server.so` | 99 MB | Full llama.cpp server with HTTP API |
| `libllama.so` | 33 MB | Core llama inference engine |
| `libggml-vulkan.so` | 65 MB | Vulkan GPU compute backend (Adreno 610+) |
| `libstable-diffusion.so` | 37 MB | Stable Diffusion inference engine |
| `libggml-cpu.so` | 4.5 MB | CPU NEON/SIMD optimized backend |
| `libggml-base.so` | 5 MB | GGML base operations |
| `libggml.so` | 612 KB | GGML unified dispatch layer |
| `libsynapse_bridge.so` | 1.1 MB | JNI bridge layer |
| `libmtmd.so` | 9.9 MB | Multimodal (vision) support |
| `libomp.so` | 1.2 MB | OpenMP multi-threading |
| `libc++_shared.so` | 1.8 MB | C++ standard library |
| `libsd-cli.so` | 901 KB | SD CLI JNI wrapper |
| `sd-cli` (asset) | — | Standalone sd-cli binary |

> All `.so` files stored via **Git LFS** due to large file sizes.

---

## 📁 Model Storage Layout

Place your GGUF models on device storage:

```
/sdcard/atlas-ai/
├── models/
│   ├── gemma-4-E2B-it-qat-UD-Q2_K_XL.gguf
│   ├── Qwen3.8-2B-BF16.gguf
│   └── (any GGUF-compatible model)
├── mtp/
│   └── mtp-gemma-4-E2B-it-BF16.gguf    ← Draft model for MTP
├── sd_models/
│   └── sd_turbo-f16.gguf               ← Stable Diffusion model
├── personas/
│   └── (custom system prompts)
├── webapp/
│   └── (agent-generated web deliverables)
└── documents/
    └── (agent-generated Word .docx and Excel .xlsx files)
```

New files are created under `/sdcard/atlas-ai/`. Generated Word and Excel files appear as previewable conversation artifacts and can be downloaded through Android's document picker. Existing models, plugins, memories, and web apps under `/sdcard/Medlam-Ai/` remain discoverable/readable; the app does not move or delete that existing data.

---

## 📱 System Requirements

| Requirement | Minimum | Recommended |
|-------------|---------|-------------|
| Android | 10.0 (API 29) | 12+ |
| Architecture | ARM64-v8a | ARM64-v8a |
| RAM | 3 GB | 6 GB+ |
| GPU | Any (CPU fallback) | Adreno 610+ (Vulkan 1.1) |
| Storage | 8 GB free | 16 GB+ |

---

## 🔧 Building from Source

### Prerequisites

```
JDK:          OpenJDK 21
Android SDK:  Build Tools 35.0.0, Platform 35
Android NDK:  27.2.12479018 (r27c)
Gradle:       8.13
```

### Build Command

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64
./gradlew assembleDebug
```

Output APK:
```
app/build/outputs/apk/debug/app-debug.apk
```

### Building Native Libraries (sd-cli + Vulkan)

```bash
# Configure
cmake -S /path/to/stable-diffusion.cpp \
      -B build-android-vulkan \
      -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=arm64-v8a \
      -DANDROID_PLATFORM=android-28 \
      -DSD_BUILD_SHARED_LIB=ON \
      -DGGML_VULKAN=ON

# Build (using only 2 cores to preserve RAM)
taskset -c 4,5 cmake --build build-android-vulkan --target stable-diffusion -j2
taskset -c 4,5 cmake --build build-android-vulkan --target sd-cli -j2
```

### ADB Deployment

```bash
adb install -r releases/app-debug.apk
adb shell am start -n com.synapse.localai/.MainActivity
```

---

## ⚙️ Settings Reference

| Setting | Description |
|---------|-------------|
| **Model Path** | Path to primary GGUF model |
| **Draft Model Path** | Companion MTP draft model |
| **Context Size** | 2K / 4K / 8K / 16K tokens |
| **Compute Device** | CPU (SIMD NEON) or GPU (Vulkan Adreno) |
| **RAM Profile** | Standard / Zero-RAM Disk Streaming |
| **Gemini API Keys** | Multi-key rotation pool |
| **API Key Privacy** | Hide key indicators during recordings |
| **ZRAM Optimization** | Auto kernel memory tuning |

---

## 🏗️ Project Architecture

```
app/src/main/
├── java/com/synapse/localai/
│   ├── AgentViewModel.kt            ← Main agent state & logic
│   ├── GGUFModelClient.kt           ← Local llama.cpp client
│   ├── GeminiModelClient.kt         ← Gemini API client
│   ├── DynamicMediaBroker.kt        ← Image generation router (sd-cli)
│   ├── ChatTemplateEngine.kt        ← GGUF chat template parser
│   ├── LlamaNativeEngine.kt         ← JNI inference engine
│   ├── LlamaServerJNI.kt            ← llama-server JNI bindings
│   ├── ToolExecutor.kt              ← Autonomous tool execution
│   ├── WebSearchTool.kt             ← Live web search
│   ├── EpistemicMemoryManager.kt    ← Persistent context memory
│   ├── NeuralPrimingManager.kt      ← Model warm-up and priming
│   ├── MultiWorkerOrchestrator.kt   ← Parallel task execution
│   ├── ZramOptimizer.kt             ← Kernel memory optimization
│   ├── DeviceProfile.kt             ← Hardware telemetry
│   ├── SettingsScreen.kt            ← Full settings UI
│   ├── AgentSettingsRepository.kt   ← Settings persistence
│   ├── audio/                       ← TTS audio engine
│   ├── latex/                       ← LaTeX/Math rendering
│   └── repository/                  ← Image generation repository
├── cpp/
│   ├── jni_bridge.cpp               ← JNI layer
│   ├── medlam_inference_engine.cpp  ← Native inference engine
│   └── llama.h                      ← llama.cpp headers
├── assets/
│   ├── sd-cli                       ← Stable Diffusion CLI binary
│   ├── persona.md                   ← Agent persona & system prompt
│   ├── tts/                         ← TTS model config
│   └── voice/                       ← Voice speaker embeddings
└── jniLibs/arm64-v8a/              ← Pre-compiled native libraries
```

---

## 📄 License & Attribution

- Core LLM inference: [`llama.cpp`](https://github.com/ggerganov/llama.cpp) — MIT License
- Image generation: [`stable-diffusion.cpp`](https://github.com/leejet/stable-diffusion.cpp) — MIT License
- Vulkan shaders: GGML Vulkan backend — MIT License

---

<div align="center">

*ATLAS-AI AGENT — Intelligence Unleashed on Hardware* 🚀🛡️

**Built for Android • Powered by llama.cpp & Stable Diffusion • Vulkan GPU Accelerated**

</div>
