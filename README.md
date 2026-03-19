# Synapse AI - Android Neural Agent & Local LLM Server 🧠🤖📱

Synapse AI is a next-generation, fully autonomous on-device AI agent for Android. It combines the raw power of local Large Language Models (LLMs) with deep system integration, allowing the AI to actually **see, think, plan, and interact** with your phone just like a human would.

No cloud dependencies. No privacy concerns. 100% Local.

📥 **[Download Latest APK (Synapse-AI-Agent-latest.apk)](https://github.com/medlam1981/synapse-ai/raw/main/releases/Synapse-AI-Agent-latest.apk)**

## 🚀 Key Features & Major Upgrades

### 1. 🦾 Autonomous Device Control (The Agent is Back!)
The agent has been fully restored with its legendary capabilities to operate your Android device:
* **Visual Perception:** Uses Android Accessibility Services to dump the screen state into a highly optimized, compact JSON tree.
* **Auto-Execution Loop:** Executes multi-step actions (tap, scroll, swipe, open app, type) and waits for UI transitions before deciding the next move.
* **Auto-Return (NEW):** Once a task is marked as complete, the agent automatically returns to the main chat interface to report the results.
* **Synapse Keyboard:** Injects text flawlessly into any app's input fields using its dedicated custom IME.

### 2. 📊 Advanced System Dashboard (Real-time Stats)
A fixed, high-tech dashboard sits below the banner, featuring precision Canvas-drawn icons and live hardware monitoring:
* 🔋 **PWR:** Real-time battery percentage.
* 🧠 **RAM:** Live available memory tracking for the model.
* 💾 **DISK:** Actual free storage space on the device.
* 🔥 **TEMP:** Live CPU thermal monitoring.
* ⚡ **CPU:** Processing state indicator (IDLE/HIGH).

### 3. 🎨 Masterpiece Slim UI
* **Slim Multi-Colored Banner:** A minimalist header featuring "SYNAPSE" in signature purple and a scrolling marquee for the **actual model name**.
* **Animated Atom Core:** A custom-coded, living atom logo with orbiting quarks and a glowing AI nucleus that spins dynamically during thought processes.
* **Elegant <think> Parsing:** Automatically detects model reasoning and wraps it in a beautiful, translucent "Thought Process" box with an animated icon.
* **Word-by-Word Streaming:** Ultra-smooth 80ms UI updates for a natural typing effect without screen jitter.

### 4. 🧠 High-Performance LLM Engine
* **Blazing Fast Inference:** Optimized with `mmap` and dynamic batching to handle models up to 8B parameters on mobile hardware.
* **32K Context Window:** Equipped with a massive context window and **Flash Attention (-fa auto)** for handling extremely long tasks and histories.
* **Endless Background Mode:** Bypasses Android battery optimizations to run continuously for hours.

### 5. 🔌 True Ollama Compatibility
* Acts as a full Ollama-compatible server on port `11434`.
* **Smart Proxy Fix:** Injects valid RFC3339 timestamps into `/api/tags` responses, fixing the dreaded "parsing time" error in official Ollama/OpenClaw CLI tools.

## ⚙️ Requirements & Setup
- High-end Android device (Flagship recommended).
- **Root access** is required for automated keyboard switching and deep UI interactions.
- Place your `.gguf` models in `/sdcard/Download/` or `/sdcard/Models/`.

---
*Built for the future of on-device AI automation.*
