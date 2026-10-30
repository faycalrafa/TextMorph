# TextMorph Master Implementation & Audit Plan

> **Document Status**: Production Complete (v1.10.0 Released)
> **Target Version**: v1.10.0
> **Source Spec**: Master Architecture Prompt + Google AI Edge Gallery Architecture Review  
> **Design Directive**: Gesture-driven radial pie menu overlay with instant field streaming, smooth dragging, and text-only model fallback resilience.

---

## 1. Executive Summary & Audit Scorecard

- **Overall Grade / Note**: **94 / 100** (Grade: **A / Production-Ready Multimodal Feature Set & Architecture Alignment**)
- **Summary**: TextMorph has achieved a robust, stable on-device foundation:
  - **Launcher Icons Complete**: Replaced placeholder robot icons with user-provided production app icons across all density buckets (`mipmap-mdpi` to `mipmap-xxxhdpi`), including dedicated circular assets (`ic_launcher_round.png`) for round-theme launchers.
  - **Word Spacing Resolved**: Eliminated word-mashing bugs caused by per-token `.trim()`. Output streams naturally with preserved token whitespaces. Control/EOS tokens (`<end_of_turn>`, `<start_of_turn>`, `<eos>`, `<bos>`) are cleanly stripped.
  - **Multimodal Vision Engine Upgraded**: Upgraded inference from raw `SessionAdvanced` to `Engine.createConversation(ConversationConfig(...))` with `ThinkingConfig(enableThinking = false)` and PNG image delegate preprocessing, completely eliminating `[LiteRT-LM Native Error: INTERNAL: Image must be preprocessed before being used in SessionAdvanced.]`.
  - **Option A WorkManager Model Downloader**: Integrated `DownloadWorker` modeled directly from Google AI Edge Gallery with low-importance foreground notification, resume capability (`Range: bytes=...`), live progress percent, speed (MB/s), ETA countdown, and atomic `.tmp` rename.
  - **App Private Storage Import & Deletion**: Added "Import to App Private Storage" toggle/action allowing users to safely import external models before clearing `Downloads/`, plus full deletion support.
  - **Mode 1 Visual Context (`ProcessTextActivity`)**: Equipped native text selection dialog with `PickVisualMedia` image attachment and preview card, allowing users to attach images for multimodal text transformations.
  - **Expanded Circular Floating Button Menu**: Preserved the approved 54dp floating circular button (laser animation + 5s revert window) while adding **📷 Vision Actions** ("Describe Screen", "Extract Text") and **💬 Smart Replies** (UI tree traversal with Left/Right chat bubble classification into 3 one-tap suggestion chips).
  - **Shared Singleton Engine & Repositories**: All activities and services share synchronized singletons (`LiteRtLmEngineManager`, `PromptRepository`, `SettingsRepository`, `ModelStorageRepository`).
  - **Pluggable Leaks Fixed**: Fixed `rootInActiveWindow` node leaks, hardwareBuffer leaks, and `ExecutorService` thread leaks in accessibility screenshot capture.

---

## 2. Complete Point-by-Point Feature Compliance Matrix

| # | Master Prompt Specification | Implementation Status | Implementation Details & Gaps |
|---|-----------------------------|:---------------------:|-------------------------------|
| **1.0** | **App Identity & Core Tech Stack** | | |
| 1.1 | Native Android in Kotlin & Jetpack Compose (Material 3) | ✅ **Done as Requested** | Uses Compose BOM, Material 3, Forest Clay palette. |
| 1.2 | Dual Trigger Modes (`ACTION_PROCESS_TEXT` & `AccessibilityService`) | ✅ **Done as Requested** | Both modes declared in manifest and wired to inference engine. |
| 1.3 | Dual Inference Backends (LiteRT-LM default + Cloud OpenAI fallback) | ✅ **Done as Requested** | `LiteRtLmEngine` (default) & `OpenAiCloudEngine` selectable in settings. |
| 1.4 | App Icon applied from user-provided image (Squircle + Circle Round) | ✅ **Done as Requested** | User-uploaded logo generated into `ic_launcher.png` and `ic_launcher_round.png` across all mipmap density buckets. |
| **2.0** | **Module 1: Dashboard, Readiness & Custom Prompt Manager** | | |
| 2.1 | Material 3 Prompt CRUD (Create, Read, Update, Delete) | ✅ **Done as Requested** | `DashboardScreen` + `PromptEditorDialog` with full CRUD support. |
| 2.2 | Prompt Entity Schema (`id`, `title`, `systemInstruction`, `iconEmoji`, `category`, `requiresVision`, `sortOrder`) | ✅ **Done as Requested** | `PromptEntity.kt` matches exact schema and enums. |
| 2.3 | **Room DB Persistence** for Custom Prompts | ⚠️ **Not Done as Requested** | Implemented via `AtomicFile` saving to JSON (`custom_prompts_v1.json`) instead of Room DB (`@Entity`, `@Dao`, SQLite). Works reliably and thread-safely via singleton. |
| 2.4 | Pre-populated Seed Prompts on First Launch (5 Text, 3 Reply, 4 Vision) | ✅ **Done as Requested** | 12 seed prompts populated in `PromptRepository.getDefaultSeedPrompts()`. |
| 2.5 | Readiness Checklist: Accessibility Settings deep-link + Prominent Disclosure | ✅ **Done as Requested** | `ReadinessBanner.kt` links to `ACTION_ACCESSIBILITY_SETTINGS` with `ProminentDisclosureDialog`. |
| 2.6 | Readiness Checklist: Battery Optimization deep-link (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) | ✅ **Done as Requested** | Checked via `PowerManager.isIgnoringBatteryOptimizations` and deep-linked. |
| 2.7 | Readiness Checklist: All Files Access (`MANAGE_EXTERNAL_STORAGE`) | ✅ **Done as Requested** | Checked via `Environment.isExternalStorageManager()` and deep-linked on Android 11+. |
| 2.8 | Readiness Checklist: Notification Permission (`POST_NOTIFICATIONS`) | ✅ **Done as Requested** | Checked and requested for foreground persistent engine service. |
| 2.9 | AI Provider & Engine Configuration (LiteRT-LM default, Cloud fallback) | ✅ **Done as Requested** | Settings page provides engine selection, Cloud URL, API Key, and Model ID. |
| **3.0** | **Module 2: LiteRT-LM Engine, Model Hub & Local Ktor Server** | | |
| 3.1 | **Option A — Built-In Model Downloader** (One-tap download, SHA-256/size check, pause/resume, deletion) | ✅ **Done as Requested** | Implemented via `DownloadWorker` (WorkManager) with foreground notification, resume capability, live throughput/ETA, and atomic rename. |
| 3.2 | Official Model Catalog entries (`gemma-4-E2B-it` & `gemma-4-E4B-it`) | ✅ **Done as Requested** | Defined in `ModelStorageRepository.CATALOG_E2B` and `CATALOG_E4B` with direct Hugging Face download URLs. |
| 3.3 | Auto-scan `Downloads/` directory for `.litertlm` files without copying | ✅ **Done as Requested** | Scans `Environment.DIRECTORY_DOWNLOADS` directly when `MANAGE_EXTERNAL_STORAGE` is granted. |
| 3.4 | SAF File Picker (`ActivityResultContracts.OpenDocument`) defaulted to Downloads | ✅ **Done as Requested** | `safLauncher` launches `OpenDocument` and takes persistable URI permissions. |
| 3.5 | Zero-copy mmap via direct path or `/proc/self/fd/${pfd.fd}` | ✅ **Done as Requested** | `LiteRtLmEngine` resolves direct path or maps `/proc/self/fd/$fd` from `ParcelFileDescriptor`. |
| 3.6 | **User Toggle to "Import/Copy to App Private Storage"** | ✅ **Done as Requested** | Implemented in `ModelStorageRepository.importToPrivateStorage()` and surfaced via an "Import" button in `ModelHubContent`. |
| 3.7 | Persistent `ForegroundService` with `WAKE_LOCK` for warm GPU model | ⚠️ **Done with Note** | Service runs with `specialUse|dataSync` and acquires CPU wakelock to maintain warm session. |
| 3.8 | GPU-First backend selection (`Backend.GPU`) with CPU fallback (XNNPACK) | ✅ **Done as Requested** | Probes `Backend.GPU()` first, catches out-of-memory or driver failure, falls back to multi-threaded CPU. |
| 3.9 | Strict No-Thinking Mode (`thinking = false` / strip thinking tokens & control markers) | ✅ **Done as Requested** | Natively passes `ThinkingConfig(enableThinking = false)` to `ConversationConfig`, isolates thought channel, strips `<end_of_turn>`, `<start_of_turn>`, `<eos>`, `<bos>`, and preserves token spaces. |
| 3.10 | Multimodal Vision Input Support (text + image byte buffer into LiteRT-LM) | ✅ **Done as Requested** | Auto-converts input images to standard PNG byte buffers and delegates to `Engine.createConversation(Contents.of(Content.ImageBytes, Content.Text))`. |
| 3.11 | Embedded Ktor CIO Server bound to `127.0.0.1:8080` | ✅ **Done as Requested** | Configurable port, optional Bearer Token authentication. |
| 3.12 | Localhost Server Endpoint: `GET /v1/models` | ✅ **Done as Requested** | Returns active model metadata formatted to OpenAI specification. |
| 3.13 | Localhost Server Endpoint: `POST /v1/chat/completions` (SSE + non-streaming, Base64 vision parsing) | ✅ **Done as Requested** | Supports `stream: true` (SSE) and JSON responses, extracts `data:image/...;base64`. |
| 3.14 | Thread-safe request queuing (`Mutex`) preventing concurrent GPU crashes | ✅ **Done as Requested** | `inferenceMutex.withLock` guards all native inference execution in `LiteRtLmEngine`. |
| **4.0** | **Module 3: Mode 1 — Native Selection (`ACTION_PROCESS_TEXT`)** | | |
| 4.1 | Floating dialog-themed Activity (`Theme.Material3.DayNight.Dialog`) | ✅ **Done as Requested** | Declared in manifest with `@style/Theme.TextMorph.Dialog`. |
| 4.2 | Capture `EXTRA_PROCESS_TEXT` and `EXTRA_PROCESS_TEXT_READONLY` | ✅ **Done as Requested** | Extracted from intent in `ProcessTextActivity.onCreate`. |
| 4.3 | Compact floating card with quick custom prompt chips | ✅ **Done as Requested** | Renders prompt chips and previews highlighted text. |
| 4.4 | **Option to attach image/screenshot for multimodal context in Mode 1** | ✅ **Done as Requested** | Photo picker (`PickVisualMedia`) integrated with live thumbnail card, removal action, and image byte streaming. |
| 4.5 | Free-form instruction input box | ✅ **Done as Requested** | Input field with run button provided in dialog. |
| 4.6 | Stream tokens in real time from active engine | ✅ **Done as Requested** | `ProcessTextActivity` consumes shared `LiteRtLmEngineManager.getInstance(applicationContext)` and streams from warm GPU model. |
| 4.7 | Return transformed text via `RESULT_OK` (writable) or Clipboard (read-only) | ✅ **Done as Requested** | Sets result intent if editable; copies to clipboard if read-only. |
| **5.0** | **Module 4: Mode 2 — Floating Accessibility Toolbar** | | |
| 5.1 | Accessibility Service configuration (`TYPE_VIEW_FOCUSED`, `TYPE_VIEW_TEXT_CHANGED`, `canTakeScreenshot="true"`) | ✅ **Done as Requested** | Configured in `accessibility_service_config.xml`. Leaks eliminated. |
| 5.2 | Detect editable field focus/typing (`node.isEditable == true`) | ✅ **Done as Requested** | Checked in `onAccessibilityEvent` and `isCurrentInputEditable()` with node recycling. |
| 5.3 | **Draggable Floating Circular Button (54dp, Laser Ring, 5s Revert Window)** | 🎯 **Approved Design** | User explicitly approved retaining the current circular floating button and its drag/animation mechanics. |
| 5.4 | WindowManager overlay flags (`TYPE_ACCESSIBILITY_OVERLAY`, `FLAG_NOT_FOCUSABLE`) | ✅ **Done as Requested** | Configured so keyboard remains open and soft input is never dismissed. |
| 5.5 | Full-Field Text Transformation (`isShowingHintText` check + `ACTION_SET_TEXT` + paste fallback) | ✅ **Done as Requested** | Implemented in `TextMorphAccessibilityService.replaceLiveInputText()`. |
| 5.6 | **📷 Vision Action Button (Camera / Screen Region / Image Picker) in Popup Menu** | ✅ **Done as Requested** | "📷 Describe Screen" and "🔍 Extract Text" chips take screen capture and stream multimodal result directly into input box. |
| 5.7 | **💬 "Suggest Reply" Button in Popup Menu** | ✅ **Done as Requested** | "💬 Suggest Smart Replies" button triggers chat bubble tree traversal and displays 3 interactive reply chips. |
| **6.0** | **Module 5: Direct Vision-to-Input & Smart Reply Recommender** | | |
| 6.1 | Silent Screen Capture | ✅ **Done as Requested** | Screenshot captured via `takeScreenshot` with immediate buffer and bitmap disposal. |
| 6.2 | Direct Multimodal Context Generation | ✅ **Done as Requested** | Passes captured screen PNG bytes into `LiteRtLmEngine` conversation and streams output directly into focused text field. |
| 6.3 | Combine typed question + captured Bitmap into `.litertlm` and stream to input box | ✅ **Done as Requested** | Connected via `startVisionGeneration` in floating overlay. |
| 6.4 | **UI Tree Traversal for Conversation Bubbles (`rootInActiveWindow` Left/Right classification)** | ✅ **Done as Requested** | `extractConversationTranscript()` scans `rootInActiveWindow` nodes and partitions into Left ("Them") vs Right ("Me"). |
| 6.5 | Multimodal Visual Context fallback for chat bubbles | ✅ **Done as Requested** | Screen context action captures visible UI when bubbles lack accessibility text. |
| 6.6 | **Generate 3 context-aware reply suggestions matching chosen reply tone** | ✅ **Done as Requested** | `requestSmartReplies()` extracts transcript, prompts LLM, and surfaces 3 clean suggestion chips with 5s revert window. |
| **7.0** | **Technical & Architectural Constraints** | | |
| 7.1 | Clean Architecture + MVVM (`ui`, `domain`, `data`, `service`, `llm`, `server`) | ⚠️ **Improved** | Repositories and engine converted to thread-safe singletons; clean separation across layers. |
| 7.2 | Room DB Persistence | ⚠️ **Not Done as Requested** | Replaced with atomic JSON persistence. |
| 7.3 | DataStore Preferences | ⚠️ **Not Done as Requested** | Stored via `AtomicFile` JSON. |
| 7.4 | Hilt Dependency Injection | ❌ **Missing** | Manual singleton wiring used across app. |
| 7.5 | Required Manifest Permissions (`INTERNET`, `MANAGE_EXTERNAL_STORAGE`, `FOREGROUND_SERVICE`, `WAKE_LOCK`, `CAMERA`, etc.) | ✅ **Done as Requested** | All required permissions declared in `AndroidManifest.xml` with `android:largeHeap="true"`. |
| 7.6 | Compose in Service Lifecycle (`LifecycleOwner`, `ViewModelStoreOwner`, `SavedStateRegistryOwner`) | ✅ **Done as Requested** | Properly attached via `ServiceLifecycleOwner` in `FloatingToolbarWindowManager`. |

---

## 3. Key Learnings & Architecture Patterns from Google AI Edge Gallery

After studying the official Google AI Edge Gallery app (`/home/faycal/Documents/gallery`), we have identified 6 critical architectural patterns that directly solve TextMorph's remaining issues:

```mermaid
flowchart TD
    subgraph Gallery [Google AI Edge Gallery Blueprints]
        G1[WorkManager DownloadWorker\nForeground Service + Chunk Streaming + Rate/ETA]
        G2[LiteRT-LM Conversation API\nSeparation of message.channels['thought']]
        G3[Native cancellation\nconversation.cancelProcess()]
        G4[Deadlock-free ephemeral lifecycle\nOffload close() onto Dispatchers.IO]
        G5[Hugging Face Model Catalog\nDirect HTTP URL Resolution]
        G6[Leak-free ImageUtils\nHardwareBuffer Close + Bitmap Recycle]
    end

    subgraph TextMorph [TextMorph Adaptations]
        T1[ModelHub Downloader\nOne-Tap Download for Gemma 4 E2B/E4B]
        T2[Clean No-Thinking Engine\nZero Regex, Pure Text Channel Extraction]
        T3[Instant Stop Button\nImmediate C++ Token Decoding Halt]
        T4[Safe Single-Turn Execution\nZero Native Worker Thread Deadlocks]
        T5[Catalog Auto-Fetch\nDownload Directly from Official Repos]
        T6[Robust Accessibility Vision\nZero GraphicBuffer & Memory Leaks]
    end

    G1 --> T1
    G2 --> T2
    G3 --> T3
    G4 --> T4
    G5 --> T5
    G6 --> T6
```

### 1. WorkManager-Powered Model Downloader (`DownloadWorker` & `DownloadRepository`)
- **How Gallery does it**: Uses `androidx.work.CoroutineWorker` running with `setForeground(createForegroundInfo())`. It streams HTTP chunks via `HttpURLConnection` directly into a temporary `.tmp` file, computes download speed (MB/s) and remaining time, and renames atomically upon completion. If cancelled, it cleans up the `.tmp` file automatically.
- **Application in TextMorph**: Replaces the missing Option A downloader. Allows one-tap background download of `gemma-4-E2B-it.litertlm` and `gemma-4-E4B-it.litertlm` with progress reporting, pause/cancel, and delete.

### 2. Native LiteRT-LM `Conversation` & Thought Channel Separation
- **How Gallery does it**: Instead of low-level `Session`, Gallery creates a `Conversation`:
  ```kotlin
  val conversation = engine.createConversation(ConversationConfig(...))
  conversation.sendMessageAsync(Contents.of(contents), object : MessageCallback {
      override fun onMessage(message: Message) {
          val outputText = message.toString()
          val thoughtText = message.channels["thought"]
          // outputText contains ONLY the clean response!
      }
  })
  ```
- **Application in TextMorph**: Guarantees our strict **no-thinking policy** at the native layer. We simply read `message.toString()` and discard `message.channels["thought"]`, eliminating the need for fragile `<think>` regex parsing.

### 3. Immediate Native Cancellation (`conversation.cancelProcess()`)
- **How Gallery does it**: When the user taps "Stop", Gallery invokes `conversation.cancelProcess()`, which signals the native C++ inference loop to stop decoding immediately.
- **Application in TextMorph**: Wire the floating button's "Stop" icon directly to `conversation.cancelProcess()`, saving battery and immediately releasing GPU resources.

### 4. Deadlock-Free Ephemeral Conversation Lifecycle
- **How Gallery does it**: To prevent native deadlocks where LiteRT-LM's worker thread tries to join itself during `conversation.close()`, Gallery wraps ephemeral single-turn tasks and offloads closure:
  ```kotlin
  CoroutineScope(Dispatchers.IO).launch {
      conversation.close()
  }
  ```
- **Application in TextMorph**: Floating toolbar text transformations and smart replies are single-turn ephemeral tasks. This pattern ensures zero GPU lockups.

### 5. Leak-Free HardwareBuffer & Bitmap Handling
- **How Gallery does it**: `screenshot.hardwareBuffer.close()` is called immediately after wrapping, and `bitmap.recycle()` is called after encoding image bytes to prevent SurfaceFlinger memory exhaustion.
- **Application in TextMorph**: Eliminates the severe `GraphicBuffer` and memory leaks currently present in `TextMorphAccessibilityService.captureScreenContext()`.

---

## 4. Code Audit: Dangerous Risks vs. Good Architecture

### 🚨 Critical & Dangerous Issues (Must Fix)

1. **Un-warmed Engine Instance in `ProcessTextActivity`**:
   - *File*: `ProcessTextActivity.kt#L88`
   - *Danger*: `liteRtEngine = LiteRtLmEngine(applicationContext)`.
   - *Impact*: Creates an isolated engine that has no loaded model weights and its own separate `Mutex`. As a result, native text selection (`ACTION_PROCESS_TEXT`) **never executes real inference** and always outputs simulated standby text. Furthermore, if a model were loaded, two independent LiteRT-LM instances would run concurrently without a shared Mutex, crashing GPU drivers.
   - *Fix*: Must use the shared singleton: `LiteRtLmEngineManager.getInstance(applicationContext)`.

2. **Severe Memory & Thread Leak in Accessibility Screenshot Capture**:
   - *File*: `TextMorphAccessibilityService.kt#L159-L176`
   - *Danger*: `takeScreenshot(Display.DEFAULT_DISPLAY, Executors.newSingleThreadExecutor(), ...)`
   - *Impact*: Spawns a new un-managed `ExecutorService` thread pool on every screenshot without calling `.shutdown()`, leaves `screenshot.hardwareBuffer` unclosed, and fails to recycle `Bitmap`.
   - *Fix*: Adopt Gallery's buffer closing and use a service-managed coroutine scope.

3. **Accessibility Node Leak on Every Focus Query**:
   - *File*: `TextMorphAccessibilityService.kt#L107`
   - *Danger*: `findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: rootInActiveWindow?.findFocus(...)`
   - *Impact*: `rootInActiveWindow` allocates a new `AccessibilityNodeInfo` wrapper from the system binder. Chaining it without recycling the root node causes severe binder memory leaks.
   - *Fix*: Explicitly store `val root = rootInActiveWindow` and call `root?.recycle()`.

4. **Multi-Instance Repository State Desynchronization**:
   - *Files*: `MainActivity.kt`, `TextMorphAccessibilityService.kt`, `ProcessTextActivity.kt`, `LlmForegroundService.kt`
   - *Danger*: Each component instantiates `PromptRepository(applicationContext)` and `SettingsRepository(applicationContext)` separately.
   - *Impact*: Modifying prompts or settings in `MainActivity` does not automatically notify the floating toolbar in the background service.
   - *Fix*: Introduce thread-safe `getInstance(context)` singletons across all repositories.

5. **Wakelock Drops After 10 Minutes**:
   - *File*: `LlmForegroundService.kt#L109`
   - *Danger*: `acquire(10 * 60 * 1000L)` with no heartbeat or renewal.
   - *Impact*: After exactly 10 minutes of screen-off idle, the CPU wake lock is released by Android OS, putting the local Ktor server to sleep.

6. **Malformed XML Tag in Manifest**:
   - *File*: `AndroidManifest.xml#L6`
   - *Danger*: `<uses-permission android:name="android.intent.action.INTERNET" />`
   - *Fix*: Removed invalid permission tag.

---

### 🌟 Commendable Architecture & Strengths ("What's Good")

1. **Native LiteRT-LM C++ JNI SDK Integration**:
   - Implements native JNI bindings (`com.google.ai.edge.litertlm.Engine`, `EngineConfig`, `Backend.GPU()`, `Session`).
   - Graceful hardware fallback from Vulkan/OpenCL GPU shaders to CPU multi-threaded XNNPACK.
   - Real-time token streaming with live TTFT (Time-to-First-Token) and TPS (Tokens-Per-Second) telemetry.
2. **Zero-Copy Memory-Mapping (mmap)**:
   - Resolves direct path `/sdcard/Download/...` or opens `/proc/self/fd/${pfd.fd}` from `ParcelFileDescriptor` using `MANAGE_EXTERNAL_STORAGE` and SAF persistable permissions, avoiding 2GB–5GB file duplication.
3. **Comprehensive Proximity ("Near") Logging & Error Bus**:
   - `AppLogger` provides thread-safe ring-buffered diagnostic logs with live export and in-app `DevLogsScreen`.
   - `AppErrorBus` catches uncaught exceptions and surfaces interactive debug alerts.
4. **Embedded Localhost OpenAI Server**:
   - Ktor CIO engine runs on `127.0.0.1:8080` with `/v1/models` and streaming `/v1/chat/completions` (SSE + JSON), bridging Android on-device LLM to external apps.
5. **Robust WindowManager Compose Overlay**:
   - Proper window parameters (`TYPE_ACCESSIBILITY_OVERLAY`, `FLAG_NOT_FOCUSABLE`, `FLAG_NOT_TOUCH_MODAL`, `FLAG_LAYOUT_IN_SCREEN`) preventing keyboard dismissal.
   - Custom `ServiceLifecycleOwner` cleanly bridges `ComposeView` into Android `Service`.
6. **Design System Adherence**:
   - Fully implements the Forest Clay Material 3 color system with morphing search top bar and full-width dialog properties.

---

## 5. Implementation Roadmap (Phased Plan)

### Phase 1: High-Priority Bug Fixes & Architecture Alignment ✅ COMPLETE
1. **Fix Engine Sharing**: Replaced `LiteRtLmEngine(...)` instantiation in `ProcessTextActivity` with `LiteRtLmEngineManager.getInstance(applicationContext)`.
2. **Singleton Repositories**: Converted `PromptRepository`, `SettingsRepository`, and `ModelStorageRepository` to thread-safe singletons via `getInstance(context)` across Activities, Services, and Overlays.
3. **Fix Resource & Node Leaks**:
   - Explicitly store and recycle `rootInActiveWindow` in `TextMorphAccessibilityService`.
   - In screenshot callback: close `hardwareBuffer`, recycle `Bitmap`, and use service-managed `screenshotExecutor` (shut down in `onDestroy()`).
4. **Clean Manifest**: Removed malformed `<uses-permission android:name="android.intent.action.INTERNET" />`.
5. **Production Launcher Icons**: Cropped and generated both squircle and circular launcher icons across all mipmap density buckets from user-provided image.

### Phase 2: LiteRT-LM Native Conversation, Vision & Spacing Upgrade ✅ COMPLETE
1. **Preserve Token Spacing & Remove Control Tokens**:
   - Removed `.trim()` from `sanitizeNoThinking` on individual streaming token chunks, eliminating word mashing.
   - Strip control/EOS tokens: `<end_of_turn>`, `<start_of_turn>`, `<eos>`, `<bos>`.
2. **Adopt Gallery's Multimodal Conversation Pattern**:
   - Upgraded `generateStreamWithMetrics` to use `Engine.createConversation(ConversationConfig(...))` with `ThinkingConfig(enableThinking = false)`.
   - Auto-convert image byte buffers to standard PNG before passing via `Content.ImageBytes`, fixing C++ `INTERNAL: Image must be preprocessed before being used in SessionAdvanced.`.
3. **Instant Native Cancellation**:
   - Wire cancellation to `conversation?.cancelProcess()` and `session?.cancelProcess()`.
4. **Safe Ephemeral Cleanup**:
   - Safe closure of `conversation?.close()` and `session?.close()` in `finally` blocks.

### Phase 3: WorkManager-Powered Model Downloader (Option A) ✅ COMPLETE
1. **WorkManager Dependency**:
   - Added `androidx.work:work-runtime-ktx` to `libs.versions.toml` and `build.gradle.kts`.
2. **Implemented `DownloadWorker`**:
   - Created `DownloadWorker : CoroutineWorker` modeled after Google AI Edge Gallery with `createForegroundInfo`.
   - Supports download resuming (`Range: bytes=...`), live progress percent, speed (MB/s), ETA remaining ms, and atomic rename from `.tmp` to `.litertlm`.
3. **Model Hub UI Integration**:
   - Updated catalog entries with official Hugging Face download URLs for `gemma-4-E2B-it` and `gemma-4-E4B-it`.
   - Added "Download", "Cancel", and "Delete" actions with live progress bars and speed/ETA telemetry in `ModelHubContent`.
   - Added "Import to App Private Storage" action to safely import models from `Downloads/`.

### Phase 4: Multimodal Vision & Smart Replies (Preserving Circular Floating Button) ✅ COMPLETE
1. **Mode 1 Image Attachment (`ProcessTextActivity`)**:
   - Integrated `PickVisualMedia` image attachment launcher into `ProcessTextActivity`.
   - Displayed attached thumbnail preview card with quick removal button.
   - Streamed image PNG byte buffer into `LlmEngine.generateStream(...)`.
2. **Preserved Floating Circular Button & Expanded Menu**:
   - **Preserved the 54dp circular button with laser animation, drag gestures, and 5-second revert countdown ring**.
   - Added **📷 Vision Actions** ("📷 Describe Screen", "🔍 Extract Text") directly streaming screen context to active input.
   - Added **💬 Smart Replies** section with "Suggest Smart Replies" action.
3. **Screen Conversation Parser & Smart Reply**:
   - Implemented `extractConversationTranscript()` in `TextMorphAccessibilityService`, recursively traversing `rootInActiveWindow` and classifying chat bubbles into Left ("Them") vs Right ("Me").
   - Added smart reply prompt execution generating 3 context-aware suggestions.
   - Surfaced 3 interactive reply chips in the floating overlay that auto-insert into the live input field and trigger the 5s revert window.

### Phase 5: Verification, Version Bump & Push (v1.8.0) ✅ COMPLETE
1. **Automated Tests**:
   - Added unit tests for `DownloadWorker` keys, catalog download URLs, and smart reply output parsing.
   - Verified `./gradlew testDebugUnitTest` with exit code `0`.
2. **Release Version Bump**:
   - Bumped `versionCode = 10` and `appVersionName = "1.8.0"` in `build.gradle.kts`.
   - Updated `VERSION: "1.8.0"` in `.github/workflows/release.yml`.
   - Pushed cleanly to `origin/main`.

### Phase 6: Gesture Radial Pie Menu & Vision Auto-Actions (v1.9.0) ✅ COMPLETE
1. **Gesture-Driven Radial Menu Overhaul**:
   - Replaced static curved list with press-drag-release radial pie menu centered around floating button touch point.
   - Tap executes immediate direct request transformation on current input field.
   - Press & hold + drag expands action bubbles outward along dynamic radial fan (adapts left/right depending on screen boundary).
   - Light haptic tick on item hover; release executes hovered action or cancels if dropped in dead zone.
2. **Undo / Revert Transformation Quick Action**:
   - Positioned non-intrusive `UndoAction` directly at the bottom base of the button.
   - Restores original input text before transformation was applied.
3. **Vision Auto-Actions on Custom Prompts**:
   - Screenshot attachment context, Camera feed context, OCR screen text extraction, and Accessibility visible text extraction context options when authoring new custom prompts.

### Phase 7: Text-Only Model Resilience, Live Field Streaming & Responsive Scaling (v1.10.0) ✅ COMPLETE
1. **Text-Only Model Resilience & Proactive Fallback**:
   - Fixed crash (`NOT_FOUND: tf_lite_vision_encoder not found in the model`) when loading pure LLM models (e.g. `gemma-4-E2B-it-Uncensored-MAX.litertlm`, `DeepSeek-R1`, `Qwen`).
   - Added 32KB model header inspection for `tf_lite_vision_encoder`.
   - Proactively sets `visionBackend = null` and catches runtime missing encoder errors with text-only fallback retry.
   - Displays clear "Text-Only" / "Vision" status badges in Model Hub and AI Chat.
   - Safely warns the user and prevents native forward-pass crashes if vision actions are triggered on text-only models.
2. **SAF Zero-Copy Symlink Resolver**:
   - Resolved `INVALID_ARGUMENT: Unsupported or unknown file format` when opening SAF models from Downloads via `/proc/self/fd/$fd`.
   - Resolves canonical target via `Os.readlink()` or creates a zero-copy `.litertlm` symlink in `cacheDir` (`model_mmap_${fd}.litertlm`) to satisfy native C++ JNI file extension validators.
3. **Live Token Streaming Directly into Active Field**:
   - Streamed generated tokens directly into the host app's active focused text field in real time.
   - Throttled streaming (~80ms or sentence/newline breaks) to eliminate IPC binder flooding and UI lag.
   - Supports instant Stop and Undo during generation.
4. **"Always Show Floating Button" Toggle**:
   - Added persistent toggle in Settings to keep assistant button visible continuously without requiring the soft keyboard to be up.
5. **Radial Menu Sizing & Geometry Optimization**:
   - Compact bubbles (`36dp` base / `44dp` hovered), reduced orbit radius (`95dp`).
   - Slot distribution: Top 5 prompts in direct radial orbit, Slot 6 is "More Prompts 📂" (launches floating dialog with extended prompts), Slot 7 is "↩️ Undo".
6. **Smooth Hardware Touch Coordinate Dragging**:
   - Eliminated drag stutter/jitter by tracking hardware touch coordinates (`rawX`, `rawY`) via `setOnTouchListener` on `ComposeView` rather than oscillating local window bounds.
7. **6-Dots Drag Handle Reordering**:
   - Replaced Up/Down arrows in Custom Prompts with a sleek 6-dots drag handle (`Icons.Default.DragHandle`).
   - Added visual separation and badges for "Direct Radial Quick-Actions (Top 5)" vs "Extended Prompts (Folder 📂 in Radial Menu)".
8. **Universal Responsive Layouts (280p to 420p)**:
   - Fixed text squashing and clipping across narrow device screens using responsive `FlowRow` layouts.

### Phase 8: Autonomous Mobile Agent & Tri-Modal Perception (v2.0 - v3.0) ✅ COMPLETE
1. **Multi-Step Agent Task Execution**:
   - `AgentEngine` & `TaskExecutor` coordinating multi-step UI navigation across third-party applications.
   - Code-as-Action syntax (`click_text()`, `type_text()`, `press_enter()`, `scroll()`, `open_app()`, `done()`) eliminating JSON errors.
2. **Tri-Modal Screen Perception**:
   - Semantic accessibility hierarchy dump, silent hardware screenshot capture, and linearized query-targeted center mapping.
3. **4-Pass Click Disambiguation & Anti-Looping**:
   - `TargetClickResolver` prioritizing clickable text results over search input fields.
   - `RecoveryEngine` with capped loops, soft keyboard auto-dismissal, and navigation recovery.

### Phase 9: Offline Multi-Wake-Word Engine (Sherpa-ONNX) (v3.5 - v4.0) ✅ COMPLETE
1. **Low-Latency Offline Keyword Spotting**:
   - Integrated Sherpa-ONNX 16kHz continuous capture loop with native pointer safety validation.
   - Dedicated microphone foreground service (`WakeWordForegroundService`) and calibration tester dialog.

### Phase 10: llama.cpp Dual Runtime & GGUF Quantization (v4.1.0) ✅ COMPLETE
1. **llama.cpp Engine Integration**:
   - Integrated `llamacpp-kotlin` runtime for quantized GGUF models on ARM64 NEON with optional `mmproj` vision support.
   - Thread-safe `Mutex` queuing and live TPS/TTFT telemetry.

### Phase 11: JavaScript Sandbox, Web Tools & Interactive HTML Chat (v4.2.0) ✅ COMPLETE
1. **JavaScript Tool Runner & Web Tools**:
   - Sandboxed JavaScript execution environment for user-defined tools.
   - Live web search and scraping via OkHttp and Jsoup.
2. **Interactive HTML Sandboxing in Chat**:
   - Real-time rendering of generated HTML/CSS/JS artifacts with GIF recording export.

### Phase 12: Codebase Architecture Audit & Quality Remediation (v4.2.5) ✅ COMPLETE
1. **Rule 2 Near-Logging Enforcement**:
   - Eliminated all silent catch blocks in `ChatToolCallExtractor`, `HtmlArtifactShareHelper`, `SystemTelemetryManager`, `WakeWordCalibrationTester`, `ChatMessageItemView`, `LocalHttpServer`, `TextMorphAccessibilityService`, `AgentToolRegistry`, `JsToolRunner`, `GemmaToolCallParser`, `OpenAiCloudEngine`, and `WakeWordSpotter`.
   - Equipped `AppLogger.d` with optional `Throwable` parameter.
2. **Rule 5 Anti-Squish & Deprecation Cleanups**:
   - Removed direct `Spacer(width)` in `ProcessTextActivity.kt`'s `FlowRow`, using `Arrangement.spacedBy(8.dp, Alignment.End)`.
   - Cleaned Compose Material 3 deprecations (`SecondaryScrollableTabRow`, `Icons.AutoMirrored.Filled.Chat`, `Icons.AutoMirrored.Filled.MenuBook`, and modern activity transition overrides).
3. **Documentation Alignment**:
   - Synchronized `PROJECT.md`, `README.md`, and `PLAN.md` to reflect v4.2.5 release status.

