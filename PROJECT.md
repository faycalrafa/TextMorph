# Project: TextMorph — System-Wide Privacy-First Multimodal AI Companion & Mobile Agent

## Architecture
TextMorph is built with **Modern Android Architecture (MVVM)**, **Jetpack Compose (Material 3)**, **LiteRT-LM & llama.cpp (Dual On-Device AI Runtimes)**, **Sherpa-ONNX (Offline Keyword Spotting)**, **Android Accessibility Service**, and an embedded **Ktor Localhost Server**.

Key architectural boundaries:
- **Navigation & Routing**: In `MainActivity.kt`, managed via `MainAppScaffold.kt` with modal sidebar drawer, morphing search top bar, and hierarchical back press interception.
- **Inference Subsystem (`llm`)**:
  - `LiteRtLmEngine`: GPU-first hardware acceleration (Vulkan/OpenCL) for Google Gemma 4 `.litertlm` models with zero-copy memory mapping from `Downloads/`.
  - `LlamaCppEngine`: ARM64 NEON hardware-accelerated GGUF runtime with optional multimodal projector (`mmproj`) support.
  - `OpenAiCloudEngine`: High-performance remote client supporting OpenAI, OpenRouter, and local network models (Ollama, LM Studio, vLLM).
  - `ChatCompactor`: Sliding-window multi-turn inference prompt assembly with artifact stripping and token budget estimation.
- **Mobile Autonomous Agent Subsystem (`agent`)**:
  - `AgentEngine` & `TaskExecutor`: Autonomous task planning and execution loop.
  - Tri-modal perception: Accessibility tree dump, silent hardware screenshot capture, and query-targeted semantic mapping.
  - 4-pass click disambiguation (`TargetClickResolver`) protecting input boxes from erroneous search result clicks.
  - Pure Code-as-Action syntax (`click_text()`, `type_text()`, `press_enter()`, `scroll()`, `open_app()`, `done()`, `search_web()`, `fetch_page()`, `write_section()`) parsed via `CodeActionParser` and `CodeActionMapper`.
  - Unified single Chat mode with pre-inference command routing (`ChatCommandRouter`), 0-tool fast exit for casual chit-chat, and dynamic phase tool filtering (`ToolFilter`).
  - Pre-flight schema validation (`ToolValidator`) and loop-breaker execution guardrails (`ExecutionGuardrail`).
  - Bounded Plan-Execute-Append pipeline: `BoundedWorkerPool` (Semaphore concurrency = 2), `GpuInferenceGate` (two-tier GPU locking), and `ArtifactAssemblyStore` (temp disk staging & Kotlin quality gate).
  - Controlled Agentic Readiness Suite: 5-scenario offline capability and telemetry test suite (`AgenticReadinessSuite`) and Charcoal Slate UI (`AgenticReadinessScreen`).
  - Extensible tool execution: In-app device actions, JavaScript sandboxing via `JsToolRunner`, and web search/scraping via `WebTools`.
  - Self-healing recovery: anti-loop caps, keyboard dismissals, loading spinner detection, and navigation back-out.
- **Voice & Wake Word Subsystem (`voice`)**:
  - `WakeWordSpotter`: Offline keyword spotting via Sherpa-ONNX runtime and continuous 16kHz `AudioRecord` capture loop.
  - `WakeWordForegroundService`: Dedicated microphone foreground service for hands-free activation.
  - `WakeWordCalibrationTester`: In-app threshold calibration with real-time audio level feedback.
- **Service & Overlay Subsystem (`service.overlay`)**:
  - `TextMorphAccessibilityService`: Monitors window changes, input focus, and delivers gesture dispatches (`ScreenAutomationController`).
  - `FloatingToolbarWindowManager`: 54dp draggable bubble companion, dynamic gesture-driven radial pie menu, and smooth coordinate gliding.
  - `LlmForegroundService`: Keeps on-device models warm in GPU memory with CPU wake lock.
- **Data Subsystem (`data`)**:
  - `PromptRepository`, `SettingsRepository`, `ChatSessionRepository`, `AutomationRepository`, `WakeWordRepository`, `JsToolRepository`, `SlashCommandRepository`: Atomic JSON persistence via `AtomicFile` with synchronized singletons (`getInstance(context)`).
  - `ModelStorageRepository`: Auto-scans `/sdcard/Download/` and manages SAF zero-copy symlink loading.
  - `DownloadWorker`: WorkManager foreground downloader with HTTP resume, rate (MB/s), ETA calculation, and atomic `.tmp` rename.
- **Server Subsystem (`server`)**: `LocalHttpServer` hosts an embedded Ktor CIO server on `127.0.0.1:8080` exposing standard `/v1/models` and streaming `/v1/chat/completions`.
- **UI Subsystem (`ui`)**: Jetpack Compose Material 3 screens adopting the **Charcoal Slate** design system (`ThemeMode.DARK_SLATE`) with full-width responsive dialogs (`DialogProperties(usePlatformDefaultWidth = false)` + `FlowRow`) and morphing top bar search.

---

## Feature Inventory & Status Tracker

| # | Feature | Description | Milestone | Status |
|---|---------|-------------|-----------|--------|
| F1 | Project Foundation & Build System | Gradle version catalog, AGP 9.0.1, dependencies (LiteRT-LM, llama.cpp, Sherpa-ONNX, Ktor, Compose), AndroidManifest, and icon assets | M1 | **DONE** |
| F2 | Engineering Guidelines & Design Spec | `design.md` with Charcoal Slate default palette and `GEMINI.md` architectural contracts | M1 | **DONE** |
| F3 | GitHub Actions CI/CD Pipeline | Automated release APK builds and artifact generation (`release.yml`) | M1 | **DONE** |
| F4 | Custom Prompt Manager & Seeds | Prompt schema (`PromptEntity`), atomic persistence, and 12 seed prompts (Text, Smart Reply, Vision) | M2 | **DONE** |
| F5 | System Readiness & Permission Banner | Checklist cards for Accessibility, Battery Optimizations, Downloads Access, and Notifications | M2 | **DONE** |
| F6 | Main Dashboard & Prompt CRUD UI | Material 3 dashboard, prompt card list, morphing top bar search, and full-width prompt editor dialog | M2 | **DONE** |
| F7 | LiteRT-LM Engine & GPU Backend | Hardware-accelerated `.litertlm` execution with strict `thinking = false` policy and Mutex queuing | M3 | **DONE** |
| F8 | Zero-Copy Downloads/ Model Loader | Auto-scan `/sdcard/Download` and SAF `ParcelFileDescriptor` mmap loading without duplicating files | M3 | **DONE** |
| F9 | Embedded Localhost OpenAI Server | Ktor CIO server on `127.0.0.1:8080` exposing `/v1/models` and streaming `/v1/chat/completions` | M3 | **DONE** |
| F10 | Native Text Selection Handler | Mode 1: `ACTION_PROCESS_TEXT` floating dialog activity with prompt chips, image attachment, and in-place replacement | M4 | **DONE** |
| F11 | Accessibility Floating AI Toolbar | Mode 2: Non-intrusive floating toolbar anchored above editable text fields via `WindowManager` | M5 | **DONE** |
| F12 | Full-Field Text Transformation | Automatic text extraction without manual selection and replacement via `ACTION_SET_TEXT` | M5 | **DONE** |
| F13 | Direct Vision-to-Input Engine | Accessibility screenshot capture (`canTakeScreenshot="true"`), multimodal image bytes handling, and direct insertion | M6 | **DONE** |
| F14 | Smart Reply Recommender | Visual and UI context awareness with contextual affirmative, inquiry, and polite decline suggestions | M6 | **DONE** |
| F15 | In-App Update Engine & Developer Logs | Real-time diagnostic logging (`AppLogger`), GitHub release update dialog, and In-App APK installer | M6 | **DONE** |
| F16 | Gesture Radial Pie Menu | Dynamic press-drag-release radial menu orbiting the floating companion with coordinate tracking | M7 | **DONE** |
| F17 | Live Field Token Streaming | Streaming generated tokens directly into host app input fields with throttled IPC dispatch | M7 | **DONE** |
| F18 | Autonomous Mobile Agent Engine | Multi-step task planning and execution (`TaskExecutor`) across third-party Android apps | M8 | **DONE** |
| F19 | Tri-Modal Screen Perception | Semantic accessibility tree, hardware screenshot bitmap, and linearized targeted hierarchy | M8 | **DONE** |
| F20 | 4-Pass Click Disambiguation | Strict heuristics preventing inadvertent search input clicks over search result items | M8 | **DONE** |
| F21 | Offline Wake Word Engine (Sherpa-ONNX)| Low-latency offline Keyword Spotting with continuous 16kHz audio capture and foreground service | M9 | **DONE** |
| F22 | llama.cpp GGUF Dual Runtime | Integrated `llamacpp-kotlin` runtime for quantized GGUF models on ARM64 NEON with mmproj vision | M10 | **DONE** |
| F23 | JavaScript Tool Sandbox & Web Tools | Rhino/WebView JS runner with network/eval sandbox, plus OkHttp + Jsoup web search & extraction | M11 | **DONE** |
| F24 | Interactive HTML Chat Sandboxing | Live HTML/CSS/JS preview in chat with GIF export and default browser launching | M11 | **DONE** |
| F25 | Visual & Semantic Agent Benchmarks | Automated visual screen generation and multi-step action evaluation engine with GIF recordings | M12 | **DONE** |
| F26 | On-Device Agent Harness & Readiness Suite | Unified chat mode, Code-as-Action standardization, Bounded Plan-Execute-Append pipeline, and 5-scenario Agentic Readiness Suite | M13 | **DONE** |
| F27 | Dedicated Image Search & Short-ID Aliasing | `search_images(query: String)` tool in `PHASE_RESEARCH`, `ImageAssetRegistry` (`IMG_1`, `IMG_2`), `ArtifactAssemblyStore` alias resolution, and Coil Markdown image cards with lightbox and export | M14 | **DONE** |
| F28 | UI/UX & Agent Harness Execution Overhaul | Collapsible Step Trace Accordion with real-time timers and failure diagnostics, slash command autocomplete (`/plan`, `/goal`), executable `/plan` confirmation card, continuous `/goal` loop coordinator, first-class `ArtifactSummaryCard` & `ArtifactReaderScreen` with PDF export, `js_tool` discovery in phase prompts, citations bottom sheet (`Sources (N)`), code block cards with copy button, touch-lock scroll physics with Jump-to-Bottom FAB, and in-chat session renamer/manager | M15 | **DONE** |

---

## Milestones

| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| **M1** | **Foundation, CI/CD & Scaffolding** | F1, F2, F3 | none | **DONE** |
| **M2** | **Dashboard, Readiness Checklist & Prompt CRUD** | F4, F5, F6 | M1 | **DONE** |
| **M3** | **LiteRT-LM Engine, Downloads mmap & Ktor Server** | F7, F8, F9 | M1 | **DONE** |
| **M4** | **Mode 1: Native Text Selection (`ACTION_PROCESS_TEXT`)** | F10 | M2, M3 | **DONE** |
| **M5** | **Mode 2: Floating Accessibility Toolbar** | F11, F12 | M2, M3 | **DONE** |
| **M6** | **Multimodal Vision, Smart Replies & Testing** | F13, F14, F15 | M4, M5 | **DONE** |
| **M7** | **Radial Pie Menu & Live Field Streaming** | F16, F17 | M5, M6 | **DONE** |
| **M8** | **Autonomous Mobile Agent & Tri-Modal Perception** | F18, F19, F20 | M6, M7 | **DONE** |
| **M9** | **Offline Wake Word & Voice Hotkeys (Sherpa-ONNX)** | F21 | M8 | **DONE** |
| **M10**| **llama.cpp GGUF Dual Engine Integration** | F22 | M3, M8 | **DONE** |
| **M11**| **JavaScript Tool Sandbox & Web Tools** | F23, F24 | M8, M10 | **DONE** |
| **M12**| **Visual Agent Benchmarks & Quality Polish** | F25 | M8, M11 | **DONE** |
| **M13**| **On-Device Agent Harness & Readiness Suite** | F26 | M11, M12 | **DONE** |
| **M14**| **Dedicated Image Search & Short-ID Aliasing** | F27 | M13 | **DONE** |
| **M15**| **UI/UX & Agent Harness Execution Overhaul** | F28 | M14 | **DONE** |

---

## Verification & Build Results
- **Unit Tests**: `testDebugUnitTest` executed and passed with exit code `0` (240 unit tests across 29 test suites).
- **Release Build**: `assembleRelease` executed with signed release APK and native runtime bindings.
- **Application Version**: `v4.4.0` (versionCode `71`).
