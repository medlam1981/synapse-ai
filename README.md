<div align="center">

# 🧠 Synapse AI

**Android Local AI Chat Application**

*Run AI models locally on your device — no cloud, no API keys, complete privacy*

[![Android](https://img.shields.io/badge/Platform-Android-green?logo=android)](https://developer.android.com)
[![API 26+](https://img.shields.io/badge/API-26%2B-blue)](https://developer.android.com/about/versions/oreo)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.20-purple?logo=kotlin)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-blue?logo=jetpackcompose)](https://developer.android.com/jetpack/compose)

</div>

---

## 📖 Overview

Synapse AI is an Android application that enables fully local, offline AI chat on your device. It supports two model formats — **GGUF** (via llama.cpp HTTP server) and **LiteRT-LM** (Google's on-device inference) — giving you flexibility to choose the model that fits your hardware.

All inference runs on-device. No data leaves your phone.

---

## ✨ Features

- **🔒 Fully Local & Private** — All AI inference runs on-device, no cloud APIs required
- **📦 Dual Model Support** — Load GGUF models (Llama, Qwen, Mistral, etc.) or LiteRT-LM models (Gemma)
- **⚡ Streaming Responses** — Real-time token-by-token streaming via SSE (Server-Sent Events)
- **💭 Thinking Models** — Supports reasoning/thinking models that show their thought process before answering
- **🎨 Cyberpunk UI** — Neon-themed dark interface with animated brain logo
- **📂 Model Scanner** — Automatically scans device storage for compatible model files
- **🔄 Auto-scroll** — Chat auto-scrolls during streaming responses
- **📱 Material 3** — Built with Jetpack Compose and Material Design 3

---

## 📸 Screenshots

| Chat Screen | Settings / Model Selection |
|:-----------:|:--------------------------:|
| Neon cyberpunk themed chat with streaming AI responses | Scan and select GGUF or LiteRT-LM models from storage |

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────┐
│              Synapse AI App             │
│  ┌───────────┐     ┌────────────────┐  │
│  │ MainActivity│    │ SettingsScreen │  │
│  │ (Compose UI)│    │ (Model Select) │  │
│  └─────┬───────┘    └───────┬────────┘  │
│        │                    │           │
│  ┌─────▼────────────────────▼────────┐  │
│  │         AgentViewModel            │  │
│  │  (Dual model routing & state)     │  │
│  └──┬──────────────────────────┬─────┘  │
│     │                          │        │
│  ┌──▼──────────┐    ┌─────────▼──────┐  │
│  │ LocalModel  │    │  GGUFModel     │  │
│  │ Client      │    │  Client        │  │
│  │ (LiteRT-LM) │    │  (HTTP/SSE)   │  │
│  └──────┬──────┘    └───────┬────────┘  │
│         │                   │           │
└─────────┼───────────────────┼───────────┘
          │                   │
  ┌───────▼──────┐   ┌───────▼──────────┐
  │  LiteRT-LM   │   │  llama-server    │
  │  Runtime      │   │  (localhost:8080)│
  │  (in-process) │   │  (HTTP API)     │
  └──────────────┘   └──────────────────┘
```

### Components

| Component | File | Description |
|-----------|------|-------------|
| **MainActivity** | `MainActivity.kt` | Jetpack Compose UI with chat interface, brain logo header, auto-scroll |
| **SettingsScreen** | `SettingsScreen.kt` | Model file scanner, type badges (GGUF/LiteRT-LM), path selection |
| **AgentViewModel** | `AgentViewModel.kt` | Central business logic, dual model routing, state management |
| **GGUFModelClient** | `GGUFModelClient.kt` | HTTP streaming client for llama-server, SSE parsing, thinking token support |
| **LocalModelClient** | `LocalModelClient.kt` | LiteRT-LM wrapper for Google's on-device inference engine |

---

## 📋 Specifications

### App Details

| Property | Value |
|----------|-------|
| **App Name** | Synapse AI |
| **Package** | `com.droidrun.agent` |
| **Version** | 1.0-SYNAPSE |
| **Min SDK** | 26 (Android 8.0 Oreo) |
| **Target SDK** | 34 (Android 14) |
| **Compile SDK** | 34 |
| **Language** | Kotlin 1.9.20 |
| **UI Framework** | Jetpack Compose + Material 3 |
| **Build System** | Gradle 8.13 + AGP 8.11.0 |

### Supported Model Formats

| Format | Extension | Engine | Inference Method |
|--------|-----------|--------|-----------------|
| **GGUF** | `.gguf` | llama.cpp (llama-server) | HTTP API on localhost:8080 |
| **LiteRT-LM** | `.litertlm` | Google LiteRT-LM | In-process native inference |

### Tested Models

| Model | Format | Size | Performance |
|-------|--------|------|-------------|
| Qwen3.5-0.8B-UD-Q4_K_XL | GGUF | 558 MB | ~4 tok/s, ~10s load time |
| Gemma 3 1B | LiteRT-LM | 584 MB | Native speed via LiteRT |

### Permissions

| Permission | Purpose |
|------------|---------|
| `READ_EXTERNAL_STORAGE` | Access model files from device storage |
| `WRITE_EXTERNAL_STORAGE` | Legacy storage access |
| `MANAGE_EXTERNAL_STORAGE` | All-files access for model scanning |
| `INTERNET` | Communicate with local llama-server on localhost |

### Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| `androidx.core:core-ktx` | 1.12.0 | Kotlin extensions for Android |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.7.0 | Coroutine-aware lifecycle |
| `androidx.activity:activity-compose` | 1.8.2 | Compose activity integration |
| `androidx.compose:compose-bom` | 2024.02.00 | Compose Bill of Materials |
| `androidx.compose.material3` | (BOM) | Material Design 3 components |
| `kotlinx-coroutines-android` | 1.7.3 | Async/streaming support |
| `litertlm-android` | 0.8.0 | Google's on-device LLM runtime |

---

## 🚀 Getting Started

### Prerequisites

- Android device with **arm64-v8a** architecture
- Android 8.0+ (API 26+)
- At least **4 GB RAM** recommended
- A compatible AI model file (GGUF or LiteRT-LM)

### Install the APK

1. Download `app-debug.apk` from the [Releases](../../releases) section
2. Enable "Install from unknown sources" on your device
3. Install the APK
4. Grant storage permissions when prompted

### Using LiteRT-LM Models (Easiest)

1. Download a `.litertlm` model (e.g., Gemma 3 1B) to your device's `Download` folder
2. Open Synapse AI → Settings (⚙️)
3. Tap "Scan for Models" — your model will appear with a blue badge
4. Select the model and tap "Load Model"
5. Start chatting!

### Using GGUF Models (Advanced)

GGUF models require running `llama-server` on the device:

1. Download a `.gguf` model to your device's `Download` folder
2. Build or obtain `llama-server` for arm64 (see [Building llama-server](#building-llama-server))
3. Push the server binary to device:
   ```bash
   adb push llama-server /data/local/tmp/
   adb shell chmod +x /data/local/tmp/llama-server
   ```
4. Start the server:
   ```bash
   adb shell "/data/local/tmp/llama-server \
     -m /sdcard/Download/YOUR_MODEL.gguf \
     -c 512 -t 2 \
     --host 127.0.0.1 --port 8080"
   ```
5. Open Synapse AI → Settings → Select the GGUF model → Load
6. Start chatting!

### Building llama-server

Build a static arm64 binary from [llama.cpp](https://github.com/ggerganov/llama.cpp):

```bash
git clone https://github.com/ggerganov/llama.cpp
cd llama.cpp
mkdir build && cd build
cmake .. \
  -DCMAKE_EXE_LINKER_FLAGS="-static" \
  -DGGML_OPENMP=OFF \
  -DLLAMA_OPENSSL=OFF
cmake --build . --target llama-server -j$(nproc)
```

The binary will be at `build/bin/llama-server` (~14 MB).

---

## 🎨 UI Theme

The app features a **cyberpunk neon** theme:

| Element | Color | Hex |
|---------|-------|-----|
| Background | Dark Navy | `#0A0E1A` |
| Primary | Neon Cyan | `#00D4FF` |
| Secondary | Neon Purple | `#BB44FF` |
| Accent | Neon Magenta | `#FF44DD` |
| Text | White | `#FFFFFF` |

---

## 🛠️ Build from Source

### Prerequisites

- JDK 17+
- Android SDK (API 34)
- Gradle 8.13+

### Build

```bash
git clone https://github.com/medlam1981/synapse-ai.git
cd synapse-ai
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📁 Project Structure

```
synapse-ai/
├── app/
│   ├── build.gradle.kts          # App build config & dependencies
│   ├── src/main/
│   │   ├── AndroidManifest.xml   # Permissions & app config
│   │   ├── java/com/droidrun/agent/
│   │   │   ├── MainActivity.kt       # Compose UI & chat screen
│   │   │   ├── AgentViewModel.kt      # ViewModel & business logic
│   │   │   ├── GGUFModelClient.kt     # GGUF HTTP streaming client
│   │   │   ├── LocalModelClient.kt    # LiteRT-LM inference client
│   │   │   └── SettingsScreen.kt      # Model selection UI
│   │   └── res/
│   │       ├── drawable/synapse_logo.png
│   │       ├── mipmap-*/ic_launcher*.png
│   │       └── values/
│   │           ├── colors.xml
│   │           ├── strings.xml
│   │           └── themes.xml
├── build.gradle.kts              # Root build config
├── settings.gradle.kts           # Project settings
├── gradle/
│   ├── libs.versions.toml        # Version catalog
│   └── wrapper/                  # Gradle wrapper
└── README.md
```

---

## ⚠️ Known Limitations

- **GGUF server must be started manually** via `adb shell` or a terminal app (e.g., Termux)
- **GGUF inference is slower** than LiteRT-LM due to HTTP overhead and static binary limitations
- Requires **arm64-v8a** device architecture
- Large models (>1GB) may not fit in memory on devices with <4GB RAM
- The app cannot start `llama-server` internally due to Android's seccomp security restrictions

---

## 📄 License

This project is provided as-is for educational and personal use.

---

<div align="center">

**Built with ❤️ using Kotlin, Jetpack Compose, llama.cpp, and LiteRT-LM**

</div>
