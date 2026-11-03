# Changelog

All notable changes to **TextMorph** are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [5.7.0] - 2026-11-03

### Added
- **Iterative `/builder` Mode & Loopback State Machine**:
  - Implemented `/builder` slash command for step-by-step code synthesis, application creation, and incremental file modifications.
  - Step-by-step execution loop with scratchpad thinking (`<think>...</think>`), live file mutation, and self-correction retries.
  - Emits real-time code updates into `HtmlSandboxPreviewCard` for instant live preview and testing of HTML/JS/CSS applications.
- **Tiny-LLM File Editing Engine (`FileEditingEngine` & `ProgressiveMatcher`)**:
  - Designed specifically for 1.5B–4B on-device models with a zero-JSON Plain-Text Block Protocol (`<read ...>`, `WRITE`, `EDIT`).
  - **4-Tier Progressive Matching Engine**: Tier 1 (Exact Substring), Tier 2 (Trimmed Line ignoring trailing whitespace), Tier 3 (Indentation-Insensitive with relative base indentation recalculation), and Tier 4 (Fuzzy Anchor Match with sliding window similarity $\ge 0.88$).
  - **Actionable Error Feedback**: Returns closest fuzzy candidate line snippets on 0-matches and requests additional context on ambiguous multi-matches.
  - **Lazy Placeholder Comment Guardrails**: Detects and rejects abbreviations (`// ... existing code ...`, `# rest of code here`, etc.).
  - **In-Memory Syntax Validation & Rollback**: Validates bracket/tag balancing and JSON formatting before saving, reverting memory and providing compiler diagnostics on error.
- **Interactive Step Details Full Dialog (`StepDetailsDialog`)**:
  - Tapping any step item in `StepTraceAccordion` (tool call, search scout, plan worker, builder step, or device automation) opens a full-screen Charcoal Slate dialog.
  - Displays the complete LLM command/input, reasoning thought trace, complete untruncated output, diagnostic failure traces, and execution telemetry with one-tap copy actions.
- **Interactive `[Build Project]` Action in Plan Cards**:
  - Upgraded `PlanExecutionActionCard` with a primary `[Build Project]` action button in responsive `FlowRow` anti-squish styling.

### Changed
- **Modularized Architecture**:
  - Decomposed builder matching into `FileEditingEngine.kt` and `ProgressiveMatcher.kt`, keeping all source files strictly under 500 lines of code.

---

## [5.6.0] - 2026-11-03

### Added
- **Two-Stage Plan-and-Execute Engine (`/plan`)**:
  - Implemented decoupled Stage 1 (Decomposition & Review) and Stage 2 (Multi-Agent Parallel Execution).
  - Decomposes user goals into discrete, modular steps assigning specialized worker roles (`Asset Harvester`, `Research Scout`, `Logic Engineer`, `QA Validator`) with explicit rationales detailing *what* will be done and *why*.
- **Interactive Plan Review & In-Place Edit Card**:
  - Upgraded `PlanExecutionActionCard` with primary **[Execute Plan]** and secondary **[Edit Plan]** actions in responsive `FlowRow` styling.
  - Added an in-place revision expansion input allowing users to describe desired plan additions, modifications, or pruning without abandoning the active session.
  - Connected `revisePlan` in `DeepPlanEngine` to dynamically update target steps and bump plan versions (`v1`, `v2`, etc.) while preserving unchanged work units.
- **Two-Stage DeepSearch Pipeline (`/deepsearch`)**:
  - Decomposed research queries into orthogonal investigation angles for user inspection before launching web discovery.
  - Integrated research plan review and editing directly into `AiChatDeepSearchHandler`, with parallel web crawling, grounded fact staging, and source citation compilation upon execution.
- **Robust Worker Output Sanitization**:
  - Added `sanitizeSectionContent` in `BoundedWorkerPool` to strip model scratchpads (`<think>...</think>`), pseudo-tool tags (`write_section(...)`, `<execute_tool>`), and raw network/socket error dumps.
  - Added resilient fallback on network timeouts and socket drops, preventing connection crashes from corrupting synthesized markdown deliverables.

### Changed
- **Decomposed Handlers Architecture**:
  - Split plan and deep search orchestration into dedicated `< 500 lines` modules: `AiChatPlanExecutionHandler` and `AiChatDeepSearchHandler`.
- **Enhanced Code-as-Action Parsing**:
  - Added thinking block removal and `<execute_tool>` tag stripping in `CodeActionParser.stripControlTokens` to prevent internal scratchpads from being parsed as executable actions.

---

## [5.5.0] - 2026-11-02

### Added
- **Icon-Only Engine Start/Stop Button**: Added `iconOnly: Boolean` support to `EngineStartStopButton`, rendering a compact 32×32dp rounded surface with an 18dp icon (`Stop` / `PlayArrow` / progress spinner) for cleaner navigation bars without text clutter.
- **Overflow Menu Engine Control**: Moved the Start/Stop local engine toggle into the AI Chat 3-dots overflow menu (`AiChatTopBar`), displaying live engine state, color-coded status, and loading spinner without overcrowding chat header actions.

### Changed
- **Unified Single Import in Model Hub**: Consolidated redundant model import/scan buttons across Model Hub into a single, prominent primary `[Import Model]` action in the Available Models section header.
- **Refined AI Chat Navbar Spacing**: Balanced spacing across `AiChatTopBar` by adding 4dp separation between the drawer menu icon and the active model switcher pill, and 6dp spacing on the right-hand action cluster.
- **Model Hub Top Bar Refactor**: Switched Model Hub navigation bar Start/Stop button to icon-only mode, and refined local engine detection logic so the control reliably displays whenever on-device SLMs are selected.

### Fixed
- **AI Config Dialog Clutter**: Removed the redundant "Model Context Window vs Generation Limit" information card from `AiModelConfigDialog`, freeing up vertical space for immediate parameter tuning without cognitive overhead.
- **Eliminated Duplicate Upload Components**: Removed the duplicate import button from the empty state card and safely purged the obsolete `ModelLoaderCard` component.

---

## [5.4.0] - 2026-11-02

### Added
- **3-Tier AI Thinking Budget Modes**: Added user-selectable thinking modes: `Low` (Fast direct response, 0 budget tokens — default), `Medium` (Balanced reasoning, up to 800 tokens max), and `High` (Deep reasoning, capped strictly at 1500 tokens max).
- **Native Engine Thinking Activation**:
  - *LiteRT-LM*: Passed user-configured budget directly to `ThinkingConfig(enableThinking, thinkingTokenBudget)` in native conversation session initialization.
  - *llama.cpp*: Added dynamic prompt conditioning and streaming token-budget enforcement clamping thinking tokens if budget is exceeded.
  - *Cloud OpenAI API*: Added streaming extraction for `reasoning_content` / `reasoning` from OpenRouter, DeepSeek-R1, and Groq, mapped OpenAI `reasoning_effort` ("low", "medium", "high") for o1/o3 models, and enforced budget token bounds.
- **Collapsible Thinking Process Accordion**: AI internal reasoning (`<think>...</think>`) renders in a sleek, collapsible "Thought Process" card directly above the response bubble in Charcoal Slate styling. Hidden and collapsed by default so users can view or ignore it at their discretion. Only renders when `<think>` blocks are present.
- **Multi-Step AI Response & Intent Visibility**:
  - `AgentStepTrace` now records `stepResponse` and `thought` across every tool call.
  - In chat inference (`AiChatInferenceExecutor`), intermediate AI responses are no longer wiped during tool hops, keeping the model's explanations visible alongside running tool progress.
  - `StepTraceAccordion` displays the AI's explanation and intent directly inside each step item alongside tool parameters and tool output snippets.
  - Autonomous device execution (`SingleStepExecutionEngine` and `TaskExecutor`) captures and projects step reasoning to the agent state machine and event store.
- **Thinking Mode Selector in Model Hub**: Upgraded `AiModelConfigDialog` with a 3-way segmented pill selector (`Low`, `Medium`, `High`) with clear descriptions of reasoning depth and token budgets.

### Changed
- **Preserved Intermediate Tool Context**: Eradicated blank chat bubble states while information tools (Web Search, Image Search, JS tools) are executing.
- **Strict File Modularity (< 500 lines)**: Factored out conversation building into `LiteRtLmSessionDelegate` and prompt assembly into `AiChatInferenceUtils` to ensure every `.kt` file strictly respects the 500-line limit.

### Fixed
- **Cloud API Reasoning Payload Drop**: Fixed issue where reasoning deltas from DeepSeek and OpenRouter models were ignored in SSE streaming chunks.
- **UI Thought Stripping**: Replaced aggressive text regex stripping with structured thought extraction that powers the collapsible thinking accordion while keeping answer bubbles clean.

---

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
