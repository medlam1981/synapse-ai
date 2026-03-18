# Synapse AI - Local LLM Engine & Chat 🧠📱

Synapse AI is a blazing-fast, 100% local Large Language Model (LLM) engine and chat application for Android. It turns your phone into a powerful, private AI server.

No cloud dependencies. No privacy concerns. 100% Local.

📥 **[Download Latest APK (Synapse-AI-Agent-latest.apk)](https://github.com/medlam1981/synapse-ai/raw/main/releases/Synapse-AI-Agent-latest.apk)**

## 🚀 Key Features

### 1. 🧠 Built-in Local AI Engine (Llama.cpp)
Runs powerful GGUF models directly on your Android device hardware.
* **Insanely Fast Inference:** Highly optimized with `mmap` direct disk reading and dynamic batching for instant responses.
* **Dynamic Control:** Absolute control. Configure **Max Tokens**, **Temperature**, **Context Length**, and **Thread Count** dynamically right from the app settings.
* **Massive Context Window:** Configured with up to **32,768 tokens** with `Flash Attention`, allowing the AI to remember long conversation histories without breaking a sweat.
* **Endless Background Mode:** Uses advanced battery optimization bypasses to run continuously in your pocket for hours.

### 2. 🔌 True Ollama Server Compatibility
Acts as a local server on port `11434`.
* **Connect from PC:** You can connect to your phone from your PC or other devices on the same network (`0.0.0.0`) using standard Ollama API requests.
* **Smart Proxy:** Features a built-in proxy that injects valid RFC3339 timestamps, ensuring 100% seamless compatibility with desktop tools like `ollama list` and `ollama run local` without crashing.

### 3. 🎨 Beautiful Slim UI & Seamless Chat
* **Slim Header & Marquee Effect:** Maximum screen real estate with a sleek, minimalist TopAppBar.
* **Instant Word-by-Word Streaming:** Experience ultra-fast typing effects (80ms UI updates) with an intelligent, butter-smooth auto-scrolling system.
* **Integrated Input Bar:** A unified chat box featuring an inline "Stop/Send" button and a File Attachment (📎) button with native Android File Picker integration.
* **Optimized Rendering:** Utilizing the default SansSerif font for the chat UI to maximize scrolling performance and readability.

## ⚙️ Requirements & Setup
- High-end Android device (Flagship recommended for running 4B+ parameter models).
- Place your `.gguf` models inside `/sdcard/Download/` or `/sdcard/Models/`.

## 🛠️ Usage Example
1. Open the app and go to Settings.
2. Select your downloaded `.gguf` model.
3. Chat with the AI directly on your phone, or connect to `http://<your-phone-ip>:11434` from your PC.

---
*Built for the future of on-device AI.*