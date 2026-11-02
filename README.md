# TextMorph ✨🪄

> System-wide, privacy-first on-device multimodal AI companion, autonomous mobile agent, and smart assistant for Android.

TextMorph combines **On-Device LiteRT-LM (Gemma 4)** and **llama.cpp (GGUF)** with deep Android system accessibility, providing intelligent real-time text transformations, visual screen perception, autonomous device actions, and context-aware suggestions directly inside any app on your phone.

---

## Key Features

- **Dual On-Device AI Runtimes**: Hardware-accelerated GPU inference via Google LiteRT-LM (`.litertlm`) and ARM64 NEON via llama.cpp (`.gguf`) with zero mandatory cloud dependency.
- **Decoupled Autonomous Agent Daemon**: High-reliability foreground daemon (`AgentDaemonService`) executing durable single physical steps, surviving Activity backgrounding and screen rotations.
- **Write-Ahead Claim Leasing & Zero-Loss Recovery**: 45-second execution leases in SQLite (`agent_execution_claims`) with startup crash sweepers (`CrashRecoveryManager`) protecting against Android Low Memory Killer (LMK) process death.
- **Durable Session Inbox & Real-Time Steering**: Prompts enter `agent_session_inbox` with explicit `AdmissionMode` (`STEER`, `QUEUE`, `ABORT`), allowing real-time user steering directives to inject at active step boundaries.
- **Event-Sourced SQLite WAL Architecture**: Append-only event store (`AgentEventStore`) with reactive materialized views (`ChatMessageProjector`) and throttled token buffers for 60 FPS Compose rendering.
- **Mobile CodeMode Sandbox**: Sandboxed WebView runtime (`MobileCodeModeSandbox`) exposing native tools under `window.TextMorph`, enabling models to batch multi-tool logic in a single turn and saving up to 70% latency and battery.
- **Content-Addressed Screen Tree Diffing**: Semantic diffing (`ScreenDiffer`) and SHA-256 layout hashing reducing perception prompt tokens by 65–80%.
- **Offline Wake Word Engine**: Completely offline Keyword Spotting (KWS) powered by Sherpa-ONNX with continuous 16kHz audio capture and hands-free voice execution.
- **Zero-Copy Downloads/ Loader**: Memory-maps models directly from your `Downloads/` directory via `ParcelFileDescriptor` mmap and zero-copy symlink resolution — no duplicate storage wasted.
- **Mode 1: Zero-Permission Selection (`ACTION_PROCESS_TEXT`)**: Highlight text anywhere and transform it instantly via Android's native selection menu, with optional photo and screenshot attachment.
- **Mode 2: Floating Companion & Gesture Radial Menu**: Non-intrusive 54dp floating bubble anchored above editable text fields with press-drag-release radial quick-actions and live field token streaming.
- **Extensible JavaScript Tool Sandbox & Web Tools**: Execute user-created JS tools in an isolated sandbox, plus perform web searches and scrape webpages via OkHttp and Jsoup.
- **Interactive HTML Chat Sandboxing**: Multi-turn chat with sliding-window compaction, live interactive HTML/CSS/JS preview, and animated GIF recording export.
- **Embedded Localhost OpenAI Server & EventFeed SSE**: Embedded Ktor HTTP server on `127.0.0.1:8080` exposing standard `/v1/models`, streaming `/v1/chat/completions`, and live EventFeed `/api/v1/sessions/{id}/events` (SSE).
- **Charcoal Slate Design System**: Crafted with Jetpack Compose Material 3 using the Charcoal Slate palette, responsive full-width dialogs, and morphing top bar search.

---

## Architecture & Engineering Guidelines

- **Engineering Guidelines & Architectural Contracts**: See [`GEMINI.md`](GEMINI.md).
- **Design System & UX Specification**: See [`design.md`](design.md).
- **Project Roadmap & Feature Tracker**: See [`PROJECT.md`](PROJECT.md).

---

## Building from Source

```bash
cd android-app
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

For release APK generation:
```bash
./gradlew assembleRelease
```
