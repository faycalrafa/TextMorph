# TextMorph ✨🪄

> System-wide, privacy-first multimodal AI writing assistant, vision analyzer, and smart reply generator for Android.

TextMorph combines **On-Device LiteRT-LM (Gemma 4)** with native Android system integration, providing intelligent text transformations, visual analysis, and context-aware suggestions directly inside any text field on your phone.

---

## Key Features

- **On-Device Local AI (LiteRT-LM)**: Hardware-accelerated GPU inference with zero cloud dependency.
- **Zero-Copy Downloads/ Loader**: Memory-maps `.litertlm` models directly from your `Downloads/` directory via `ParcelFileDescriptor` mmap — no duplicate gigabytes wasted.
- **Mode 1: Zero-Permission Selection (`ACTION_PROCESS_TEXT`)**: Highlight text anywhere and transform it instantly via Android's native context menu.
- **Mode 2: Floating AI Toolbar (`AccessibilityService`)**: Non-intrusive floating overlay anchored directly above active editable fields.
- **Direct Vision Actions**: Region screenshot crop, camera snapshots, chart/diagram reading, and puzzle solving.
- **Context-Aware Smart Replies**: Analyzes chat bubbles on screen to suggest 3 contextual responses.
- **Embedded Localhost OpenAI Server**: Ktor HTTP server on `127.0.0.1:8080` exposing standard `/v1/chat/completions`.
- **Forest Clay Design System**: Crafted with Jetpack Compose Material 3 using the Forest Clay theme, full-width responsive dialogs, and morphing top bar search.

---

## Architecture & Engineering Guidelines

- **Design System**: See [`design.md`](design.md).
- **Engineering Guidelines**: See [`GEMINI.md`](GEMINI.md).
- **Project Roadmap & Tracking**: See [`PROJECT.md`](PROJECT.md).

---

## Building from Source

```bash
cd android-app
./gradlew assembleDebug
```

For release APK generation:
```bash
./gradlew assembleRelease
```
