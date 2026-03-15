# Synapse AI - Android Neural Agent 🧠📱

Synapse AI is a next-generation, fully autonomous on-device AI agent for Android. It combines the raw power of local Large Language Models (LLMs) with deep system integration, allowing the AI to actually **see, think, plan, and interact** with your phone just like a human would.

No cloud dependencies. No privacy concerns. 100% Local.

## 🚀 Key Features

### 1. 🧠 Built-in Local AI Engine (Llama.cpp)
Runs powerful GGUF models directly on your Android device hardware.
* **Insanely Fast Inference:** Highly optimized with `mmap` direct disk reading, max thread allocation, and dynamic batching (`-ub 512`, `-b 512`) for instant responses.
* **Massive Context Window:** Configured with up to **8192 tokens**, allowing the agent to remember long conversation histories and parse complex UI trees without breaking a sweat.
* **Ollama-Compatible API:** Acts as a local server on port `11434`, meaning you can connect to it from your PC or other devices on the same network (`0.0.0.0`) using standard API requests (`/completion`).

### 2. 👁️ Advanced UI Perception (Android Portal)
Synapse AI doesn't just guess; it actually *sees* your screen.
* **Native UI Tree Parsing:** Uses Android Accessibility Services to dump the screen state into a highly optimized, compact JSON tree in milliseconds.
* **Element Tracking:** Assigns a unique `[idx]` to every clickable, scrollable, and editable element on the screen for pinpoint precision.
* **Built-in Portal Server:** Hosts an internal TCP server on port `8081` that acts as a bridge between the AI logic and the Android OS.

### 3. 🤖 Autonomous Task Execution
Give it a goal, and watch it work. Synapse AI uses an advanced multi-step reasoning loop:
* **`<thought>` & `<plan>`:** Before acting, the agent analyzes the screen, formulates a strategy, and updates its plan.
* **`<add_memory>`:** The agent dynamically stores information it finds on the screen (like prices, names, or messages) to use in later steps.
* **Continuous Looping:** Once given a task, the agent will execute an action, wait for the screen to update, re-analyze, and continue firing actions until it outputs `{"action": "complete"}`.

### 4. 🖐️ Deep System Interaction
Executes commands directly on the Android OS using native integrations:
* **Synapse Keyboard IME:** Automatically switches to its own dedicated keyboard to inject text flawlessly into any app's input fields.
* **Gestures & Navigation:** Supports precise coordinates or index-based tapping, swiping, scrolling, and system navigation (Home, Back, Recents).
* **Auto-Minimization:** Intelligently hides itself into the background the moment it needs to interact with another app, giving you a clear view of its actions.

## ⚙️ Requirements & Setup
- High-end Android device (Flagship recommended for running 2B+ parameter models).
- Root access is heavily recommended for seamless keyboard switching and deep UI automation.
- Place your `.gguf` models inside `/sdcard/Download/` or `/sdcard/Models/`.

## 🛠️ Usage Example
Simply open the app, load your model, and type a command:
> *"Open YouTube, search for funny cats, and play the first video."*

Synapse will handle the rest autonomously!

---
*Built for the future of on-device AI automation.*
