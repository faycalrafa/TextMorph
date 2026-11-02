# Changelog

All notable changes to **TextMorph** are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [5.3.0] - 2026-11-02

### Added
- **Web-Wide Image Scraping Engine**: `ImageSearchTool` dynamically scrapes relevant image URLs across top organic web results using Jsoup and DuckDuckGo, inspecting OpenGraph metadata (`og:image`) and semantic body `<img>` tags while intelligently filtering noise, tracking pixels, SVG logos, and ad banners.
- **Parallel Image Health Probing**: Added asynchronous pre-flight HTTP HEAD/GET probes (`probeImageHealth`) validating Content-Type and HTTP 200 OK liveness in parallel before presenting images to models or users, automatically discarding 403, 404, 429, and dead URLs.
- **Balanced Parenthesis AST Action Parser**: Added recursive balanced parenthesis extraction in `ChatToolCallExtractor` to effortlessly extract unquoted nested tool actions like `suggest_next_actions(search_web("..."), search_images("..."))` into actionable follow-up suggestion chips (`[⚡ Web Search]`, `[⚡ Image Search]`) without trailing syntax fragments leaking into chat.
- **Single-Argument and Positional User Approvals**: Added robust support for simplified 2B model tool calls like `request_user_approval("Proceed with search?")` defaulting to `["Yes", "No"]`, and positional parameter approvals.

### Changed
- **Wikimedia Edge Thumbnail Optimization**: Switched Wikimedia Commons queries from uncompressed multi-megabyte master files (`iiprop=url`) to fast, edge-cached 1024px web thumbnails (`thumb.wikimedia.org` via `&iiurlwidth=1024`), dramatically reducing bandwidth and preventing Wikimedia Envoy 429 rate limits.
- **Tiny 2B Model Action Prompts**: Streamlined system prompts and observation examples for `suggest_next_actions` and `request_user_approval`, providing clean single-turn patterns that eliminate syntax confusion on small on-device SLMs.

### Fixed
- **Silent Broken Image Card Degradation**: If an image URL fails to load, `MarkdownImageCard` automatically tries registered fallback mirrors or cleanly collapses without rendering dark, jarring "Preview unavailable" placeholder boxes.
- **Chat Code Fragment Leakage**: Prevented unparsed trailing action text (such as `, search_images("..."))`) from remaining stranded in the conversation text when tiny models output nested actions without string quotation.

---

## [5.2.0] - 2026-11-02

### Added
- **Real-Time Sub-Test Streaming Console**: The Agentic Readiness Suite now displays live sub-test questions, real-time token streaming, per-test latency, and evaluator verdict explanations as each scenario executes.
- **Deep Inspection Accordions**: Added expandable sub-test cards to scenario results with one-click copying for both the exact test prompt and the model's raw unedited response.
- **Persistent Benchmark Storage**: Created `BenchmarkRepository` using thread-safe `AtomicFile` JSON storage (`benchmark_reports_v1.json`) to persist scores and reports across app restarts.
- **Automatic Model Activation Prompt**: Added `EngineActivationCoordinator` event flow that prompts users to benchmark newly loaded LiteRT-LM models, llama.cpp weights, or Cloud API configurations with "Benchmark Now" or "Maybe Later".
- **Chat Header Circular Score Badge**: Added a tier-colored circular score badge to the right of the active model name in the Chat top bar. The model name uses adaptive text weighting and ellipsis truncation so long model names yield space without pushing the badge off-screen. Untested models display a speedometer icon; tapping navigates directly to the benchmark suite.
- **Automated Release Notes Pipeline**: Automated release notes extraction directly from `CHANGELOG.md` in GitHub Actions so that In-App Update dialogs and GitHub Releases display accurate, dynamic release notes.

### Changed
- **Purged All Simulation & Standby Mocks**: Completely deleted synthetic fallback generators, artificial 16ms token delays, and standby mock delegates from `LiteRtLmEngine`, `LiteRtLmSessionDelegate`, and `LlamaCppEngine`. Unloaded engines now set backend to `"None"` and return honest error states.
- **Scenario 4 Template Anti-Echo Guard**: Disqualifies models that echo prompt placeholder templates (e.g. `[Section Title]`), preventing them from scoring points without generating distinct plan sections.
- **Scenario 5 Cloud API Latency Support**: Telemetry scoring now accounts for remote API network round-trip time (RTT + TTFT up to 1500–3000ms) separately from local on-device GPU execution.
- **Architectural Modularity (< 500 lines)**: Decomposed `AgenticReadinessSuite` and `AgenticReadinessScreen` into dedicated modular delegates (`AgenticReadinessScenarios`, `AgenticReadinessReportComponents`) strictly adhering to the 500-line limit.

### Fixed
- **Fixed Brittle Benchmark 50/100 Lock**: Resolved the issue where Cloud API models consistently scored 50/100 due to rigid Code-as-Action regex parsing and prompt echoing over unloaded fallback engines.

---

## [5.1.0] - 2026-10-30

### Added
- **Centralized Model Hub**: Unified model selection, format auto-detection, preset management, and engine switching into a single dedicated route.
- **On-Demand Engine Lifecycle Controls**: Immediate Start/Stop buttons in the top navigation bar and Model Hub to release VRAM and memory on demand.
- **Rolling 1MB Disk Logger**: Buffered diagnostic logs surviving process termination and restoring across app launches.

### Changed
- **Private Internal Model Storage Isolation**: Migrated model storage strictly into app-private internal storage (`filesDir/models/`) to prevent Android 14+ SELinux permission revocations and native mmap crashes.

---

## [5.0.0] - 2026-10-28

### Added
- **Event-Sourced Agentic Engine**: Autonomous mobile agent loop powered by an append-only SQLite WAL event store and reactive Compose state projections.
- **Autonomous Daemon Service**: Background `AgentDaemonService` with sticky notifications and partial wake locks surviving process backgrounding.
- **Session Inbox Steering**: Real-time steer directives (`STEER`, `QUEUE`, `ABORT`) allowing user steering mid-task without aborting.
- **Mobile CodeMode Sandbox**: Sandboxed WebView executing batched multi-tool JavaScript operations in a single LLM turn.

---

## [4.8.0] - 2026-10-26

### Added
- **DeepSearch Coordinator**: Multi-hop web search and synthesis with human-in-the-loop clarification chips.
- **Persistent Cloud API Presets**: Per-preset storage of API keys and endpoint configurations preserving tokens across switches.

---

## [4.5.0] - 2026-10-24

### Changed
- **Pure Code-as-Action Standardization**: Eradicated legacy JSON prompting in favor of pure Code-as-Action syntax (`click_text()`, `type_text()`, `open_app()`, `done()`), reducing token overhead by up to 60% on 1B–2B on-device models.

---

## [2.4.0] - 2026-10-22

### Added
- **Native Flight Breadcrumbs**: Persistent crash recorder capturing native C++ / GPU driver failures (SIGSEGV/Vulkan OOM) across app restarts.
- **Architectural Modularization**: Decomposed monolithic screens into modular feature packages under `ui/`.

---

## [2.0.0] - 2026-10-21

### Added
- **Native GGUF Support via llama.cpp**: Dual on-device runtimes supporting quantized GGUF models alongside LiteRT-LM.
- **Multimodal Vision Projector (mmproj)**: Attached image projection weights for local visual understanding.

---

## [1.0.0] - 2026-10-20

### Added
- **Initial Release**: On-Device LiteRT-LM inference, system-wide floating AI toolbar via Android Accessibility Service, zero-permission `ACTION_PROCESS_TEXT` selection mode, and embedded localhost OpenAI server on `127.0.0.1:8080`.
