# TextMorph Master Implementation & Audit Plan

> **Document Status**: Active Audit & Roadmap  
> **Target Version**: v1.5.0+  
> **Source Spec**: Master Architecture Prompt (LiteRT-LM On-Device Multimodal AI Assistant)

---

## 1. Executive Summary & Audit Scorecard

- **Overall Grade / Note**: **68 / 100** (Grade: **C+ / Promising Foundation with Critical Gaps**)
- **Summary**: The core on-device foundation—native LiteRT-LM C++ JNI runtime, zero-copy `ParcelFileDescriptor` / mmap loader, embedded Ktor localhost OpenAI server, basic accessibility overlay, and Material 3 Forest Clay design system—is solidly implemented and functional. However, several critical subsystems requested in the Master Architecture Prompt were either **never implemented** (Option A Model Downloader, Screen Region Crop, Transparent Camera/Gallery Activity, Conversation Bubble Tree Traversal for Smart Replies, Image attachment in `ACTION_PROCESS_TEXT`) or **diverged significantly** from the prompt (Room DB replaced with JSON `AtomicFile`, Hilt omitted entirely, ViewModels omitted in favor of direct repository calls, and the Floating Toolbar changed from an anchored horizontal bar directly above the input box into a floating middle-right circular pill). Furthermore, dangerous state-duplication issues (non-singleton repositories, `ProcessTextActivity` spawning its own un-warmed `LiteRtLmEngine`) and memory leaks (`GraphicBuffer` and un-closed Executor threads in accessibility screenshots) require immediate architectural remediation.

---

## 2. Complete Point-by-Point Feature Matrix

| # | Master Prompt Specification | Implementation Status | Implementation Details & Gaps |
|---|-----------------------------|:---------------------:|-------------------------------|
| **1.0** | **App Identity & Core Tech Stack** | | |
| 1.1 | Native Android in Kotlin & Jetpack Compose (Material 3) | **Done as Requested** | Uses Compose BOM 2025.01.01, Material 3, Forest Clay palette. |
| 1.2 | Dual Trigger Modes (`ACTION_PROCESS_TEXT` & `AccessibilityService`) | **Done as Requested** | Both modes declared in manifest and wired to inference engine. |
| 1.3 | Dual Inference Backends (LiteRT-LM default + Cloud OpenAI fallback) | **Done as Requested** | `LiteRtLmEngine` (default) & `OpenAiCloudEngine` selectable in settings. |
| 1.4 | App Icon applied from user-provided image | **Done as Requested** | Cropped launcher icons placed in `drawable/` and all `mipmap-*` buckets. |
| **2.0** | **Module 1: Dashboard, Readiness & Custom Prompt Manager** | | |
| 2.1 | Material 3 Prompt CRUD (Create, Read, Update, Delete) | **Done as Requested** | `DashboardScreen` + `PromptEditorDialog` with full CRUD support. |
| 2.2 | Prompt Entity Schema (`id`, `title`, `systemInstruction`, `iconEmoji`, `category`, `requiresVision`, `sortOrder`) | **Done as Requested** | `PromptEntity.kt` matches exact schema and enums. |
| 2.3 | **Room DB Persistence** for Custom Prompts | ⚠️ **Not Done as Requested** | Implemented via `AtomicFile` saving to JSON (`custom_prompts_v1.json`) instead of Room DB (`@Entity`, `@Dao`, SQLite). |
| 2.4 | Pre-populated Seed Prompts on First Launch (5 Text, 3 Reply, 4 Vision) | **Done as Requested** | 12 seed prompts populated in `PromptRepository.getDefaultSeedPrompts()`. |
| 2.5 | Readiness Checklist: Accessibility Settings deep-link + Prominent Disclosure | **Done as Requested** | `ReadinessBanner.kt` links to `ACTION_ACCESSIBILITY_SETTINGS` with `ProminentDisclosureDialog`. |
| 2.6 | Readiness Checklist: Battery Optimization deep-link (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) | **Done as Requested** | Checked via `PowerManager.isIgnoringBatteryOptimizations` and deep-linked. |
| 2.7 | Readiness Checklist: All Files Access (`MANAGE_EXTERNAL_STORAGE`) | **Done as Requested** | Checked via `Environment.isExternalStorageManager()` and deep-linked on Android 11+. |
| 2.8 | Readiness Checklist: Notification Permission (`POST_NOTIFICATIONS`) | **Done as Requested** | Checked and requested for foreground persistent engine service. |
| 2.9 | AI Provider & Engine Configuration (LiteRT-LM default, Cloud fallback) | **Done as Requested** | Settings page provides engine selection, Cloud URL, API Key, and Model ID. |
| **3.0** | **Module 2: LiteRT-LM Engine, Model Hub & Local Ktor Server** | | |
| 3.1 | **Option A — Built-In Model Downloader** (One-tap download, SHA-256/size check, pause/resume, deletion) | ❌ **Missing** | Model Hub displays catalog cards, but clicking them attempts to initialize local path without downloading. No download engine, SHA-256 verification, pause/resume, or delete action. |
| 3.2 | Official Model Catalog entries (`gemma-4-E2B-it` & `gemma-4-E4B-it`) | **Done as Requested** | Defined in `ModelStorageRepository.CATALOG_E2B` and `CATALOG_E4B`. |
| 3.3 | Auto-scan `Downloads/` directory for `.litertlm` files without copying | **Done as Requested** | Scans `Environment.DIRECTORY_DOWNLOADS` directly when `MANAGE_EXTERNAL_STORAGE` is granted. |
| 3.4 | SAF File Picker (`ActivityResultContracts.OpenDocument`) defaulted to Downloads | **Done as Requested** | `safLauncher` launches `OpenDocument` and takes persistable URI permissions. |
| 3.5 | Zero-copy mmap via direct path or `/proc/self/fd/${pfd.fd}` | **Done as Requested** | `LiteRtLmEngine` resolves direct path or maps `/proc/self/fd/$fd` from `ParcelFileDescriptor`. |
| 3.6 | **User Toggle to "Import/Copy to App Private Storage"** | ❌ **Missing** | Not implemented in `ModelStorageRepository` or `ModelHubContent`. |
| 3.7 | Persistent `ForegroundService` with `WAKE_LOCK` for warm GPU model | ⚠️ **Not Done as Requested** | Service exists with `specialUse|dataSync`, but `WAKE_LOCK` has a hardcoded 10-minute timeout and never renews. |
| 3.8 | GPU-First backend selection (`Backend.GPU`) with CPU fallback (XNNPACK) | **Done as Requested** | Tries `Backend.GPU()` first, catches failure, falls back to `Backend.CPU()`. |
| 3.9 | Strict No-Thinking Mode (`thinking = false` / strip thinking tokens) | ⚠️ **Not Done as Requested** | Prompt injection + regex stripping (`sanitizeNoThinking`) is used; lacks native engine config flag or chat template token suppression if supported by runtime. |
| 3.10 | Multimodal Vision Input Support (text + image byte buffer into LiteRT-LM) | **Done as Requested** | `generateStream` accepts `imageBytes: ByteArray?` and wraps as `InputData.Image`. |
| 3.11 | Embedded Ktor CIO Server bound to `127.0.0.1:8080` | **Done as Requested** | Configurable port, optional Bearer Token authentication. |
| 3.12 | Localhost Server Endpoint: `GET /v1/models` | **Done as Requested** | Returns active model metadata formatted to OpenAI specification. |
| 3.13 | Localhost Server Endpoint: `POST /v1/chat/completions` (SSE + non-streaming, Base64 vision parsing) | **Done as Requested** | Supports `stream: true` (SSE) and JSON responses, extracts `data:image/...;base64`. |
| 3.14 | Thread-safe request queuing (`Mutex`) preventing concurrent GPU crashes | **Done as Requested** | `inferenceMutex.withLock` guards all native inference execution in `LiteRtLmEngine`. |
| **4.0** | **Module 3: Mode 1 — Native Selection (`ACTION_PROCESS_TEXT`)** | | |
| 4.1 | Floating dialog-themed Activity (`Theme.Material3.DayNight.Dialog`) | **Done as Requested** | Declared in manifest with `@style/Theme.TextMorph.Dialog`. |
| 4.2 | Capture `EXTRA_PROCESS_TEXT` and `EXTRA_PROCESS_TEXT_READONLY` | **Done as Requested** | Extracted from intent in `ProcessTextActivity.onCreate`. |
| 4.3 | Compact floating card with quick custom prompt chips | **Done as Requested** | Renders prompt chips and previews highlighted text. |
| 4.4 | **Option to attach image/screenshot for multimodal context in Mode 1** | ❌ **Missing** | `ProcessTextActivity` has no image picker, camera, or screenshot attachment. |
| 4.5 | Free-form instruction input box | **Done as Requested** | Input field with run button provided in dialog. |
| 4.6 | Stream tokens in real time from active engine | ⚠️ **Not Done as Requested** | `ProcessTextActivity` creates a **new, un-warmed** `LiteRtLmEngine()` instance instead of using `LiteRtLmEngineManager.getInstance()`. It always falls back to simulated standby text! |
| 4.7 | Return transformed text via `RESULT_OK` (writable) or Clipboard (read-only) | **Done as Requested** | Sets result intent if editable; copies to clipboard if read-only. |
| **5.0** | **Module 4: Mode 2 — Floating Accessibility Toolbar** | | |
| 5.1 | Accessibility Service configuration (`TYPE_VIEW_FOCUSED`, `TYPE_VIEW_TEXT_CHANGED`, `canTakeScreenshot="true"`) | **Done as Requested** | Configured in `accessibility_service_config.xml`. |
| 5.2 | Detect editable field focus/typing (`node.isEditable == true`) | **Done as Requested** | Checked in `onAccessibilityEvent` and `isCurrentInputEditable()`. |
| 5.3 | **Calculate screen coordinates via `node.getBoundsInScreen(Rect)` and anchor directly above input box** | ⚠️ **Not Done as Requested** | Ignored node bounds and hardcoded anchor to middle-right screen `(screenWidth - 200, screenHeight / 2 - 100)` with drag override. |
| 5.4 | WindowManager overlay flags (`TYPE_ACCESSIBILITY_OVERLAY`, `FLAG_NOT_FOCUSABLE`) | **Done as Requested** | Configured so keyboard remains open and soft input is never dismissed. |
| 5.5 | **Overlay States: Collapsed "✨" pill & Expanded horizontal scrollable bar** | ⚠️ **Not Done as Requested** | Built a 54dp circular button with laser animation and a left-expanding popup card instead of a horizontal bar. |
| 5.6 | **📷 Vision Action Button (Camera / Screen Region / Image Picker) in Toolbar** | ❌ **Missing** | Only has a dummy "📷 Screen Context" chip that triggers a screenshot without returning data. No camera, no screen crop, no image picker. |
| 5.7 | **💬 "Suggest Reply" Button in Toolbar** | ❌ **Missing** | No "Suggest Reply" action in `FloatingToolbarContent`. |
| 5.8 | Full-Field Text Transformation (`isShowingHintText` check + `ACTION_SET_TEXT` + paste fallback) | **Done as Requested** | Implemented in `TextMorphAccessibilityService.replaceLiveInputText()`. |
| **6.0** | **Module 5: Direct Vision-to-Input & Smart Reply Recommender** | | |
| 6.1 | **Silent Screen Capture with Drag Crop Region Box** | ❌ **Missing** | Screenshot capture occurs in service, but no crop overlay / drag box UI exists. |
| 6.2 | **Transparent Overlay Activity (`TakePicturePreview` / `PickVisualMedia`)** | ❌ **Missing** | Not implemented anywhere in the project. |
| 6.3 | Combine typed question + captured Bitmap into `.litertlm` and stream to input box | ❌ **Missing** | The pipeline connecting vision capture to active input text stream is completely absent in the overlay. |
| 6.4 | **UI Tree Traversal for Conversation Bubbles (`rootInActiveWindow` Left/Right classification)** | ❌ **Missing** | Not implemented. `rootInActiveWindow` is only queried to find focus. |
| 6.5 | **Multimodal Visual Context fallback for chat bubbles (stickers/memes/canvas)** | ❌ **Missing** | Not implemented. |
| 6.6 | **Generate 3 context-aware reply suggestions matching chosen reply tone** | ❌ **Missing** | Not implemented. |
| **7.0** | **Technical & Architectural Constraints** | | |
| 7.1 | Clean Architecture + MVVM (`ui`, `domain`, `data`, `service`, `llm`, `server`) | ⚠️ **Not Done as Requested** | No `domain` layer, and **zero ViewModels** (`DashboardViewModel`, `ModelHubViewModel` missing). Direct repository coupling in composables. |
| 7.2 | Room DB Persistence | ⚠️ **Not Done as Requested** | Replaced with `AtomicFile` JSON persistence. |
| 7.3 | DataStore Preferences | ⚠️ **Not Done as Requested** | Dependency imported in Gradle, but settings stored in JSON via `AtomicFile`. |
| 7.4 | Hilt Dependency Injection | ❌ **Missing** | Hilt dependencies and annotations completely omitted. Manual wiring everywhere. |
| 7.5 | Required Manifest Permissions (`INTERNET`, `MANAGE_EXTERNAL_STORAGE`, `FOREGROUND_SERVICE`, `WAKE_LOCK`, `CAMERA`, etc.) | **Done as Requested** | All required permissions declared in `AndroidManifest.xml` with `android:largeHeap="true"`. |
| 7.6 | Compose in Service Lifecycle (`LifecycleOwner`, `ViewModelStoreOwner`, `SavedStateRegistryOwner`) | **Done as Requested** | Properly attached via `ServiceLifecycleOwner` in `FloatingToolbarWindowManager`. |

---

## 3. Code Audit: Dangerous Risks vs. Good Architecture

### 🚨 Critical & Dangerous Issues (Must Fix)

1. **Un-warmed Engine Instance in `ProcessTextActivity`**:
   - *File*: `ProcessTextActivity.kt#L88`
   - *Danger*: `liteRtEngine = LiteRtLmEngine(applicationContext)`.
   - *Impact*: Creates an isolated engine that has no loaded model weights and its own separate `Mutex`. As a result, native text selection (`ACTION_PROCESS_TEXT`) **never executes real inference** and always outputs simulated standby text. Furthermore, if a model were loaded, two independent LiteRT-LM instances would run concurrently without a shared Mutex, crashing GPU drivers.
   - *Fix*: Must use the shared singleton: `LiteRtLmEngineManager.getInstance(applicationContext)`.

2. **Severe Memory & Thread Leak in Accessibility Screenshot Capture**:
   - *File*: `TextMorphAccessibilityService.kt#L159-L176`
   - *Danger*: 
     ```kotlin
     takeScreenshot(Display.DEFAULT_DISPLAY, Executors.newSingleThreadExecutor(), ...)
     ```
   - *Impact*:
     - Spawns a new un-managed `ExecutorService` thread pool on every screenshot without calling `.shutdown()`.
     - `screenshot.hardwareBuffer` is never closed (`screenshot.hardwareBuffer.close()`), causing direct hardware buffer leaks in SurfaceFlinger.
     - `bitmap.recycle()` is never called, leaking megabytes of uncompressed bitmap memory on the native heap.
     - The captured JPEG bytes are logged and discarded without ever reaching the inference engine!

3. **Accessibility Node Leak on Every Focus Query**:
   - *File*: `TextMorphAccessibilityService.kt#L107`
   - *Danger*: `findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: rootInActiveWindow?.findFocus(...)`
   - *Impact*: `rootInActiveWindow` allocates a new `AccessibilityNodeInfo` wrapper from the system binder. Because it is invoked via chained call, the root node is **never recycled**, creating severe binder exhaustion and Android system service memory leaks.

4. **Multi-Instance Repository State Desynchronization**:
   - *Files*: `MainActivity.kt`, `TextMorphAccessibilityService.kt`, `ProcessTextActivity.kt`, `LlmForegroundService.kt`
   - *Danger*: Each component calls `PromptRepository(applicationContext)` and `SettingsRepository(applicationContext)` separately.
   - *Impact*: Each instance maintains its own in-memory `MutableStateFlow` and its own `Mutex`. When a user creates/edits a prompt in `MainActivity`, the instance in `TextMorphAccessibilityService` or `ProcessTextActivity` does not automatically update until the app is restarted or reloaded from disk. File writes can also experience race conditions.

5. **Wakelock Drops After 10 Minutes**:
   - *File*: `LlmForegroundService.kt#L109`
   - *Danger*: `acquire(10 * 60 * 1000L)` with no heartbeat or renewal.
   - *Impact*: After exactly 10 minutes of device idle/screen-off, the CPU wake lock is released by Android OS, putting the local Ktor server and warm model to sleep.

6. **Malformed XML Tag in Manifest**:
   - *File*: `AndroidManifest.xml#L6`
   - *Danger*: `<uses-permission android:name="android.intent.action.INTERNET" />`
   - *Impact*: `android.intent.action.INTERNET` is an intent action string, not a permission name (valid permission is `android.permission.INTERNET` on line 7).

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

## 4. Implementation Roadmap to 100% Completion

### Phase 1: High-Priority Bug Fixes & Architecture Alignment
1. **Fix Engine Sharing**: Replace `LiteRtLmEngine(...)` instantiation in `ProcessTextActivity` with `LiteRtLmEngineManager.getInstance(applicationContext)`.
2. **Singleton Repositories**: Convert `PromptRepository`, `SettingsRepository`, and `ModelStorageRepository` to thread-safe singletons (or migrate to Hilt DI).
3. **Fix Resource & Node Leaks**: Properly recycle `rootInActiveWindow` in `TextMorphAccessibilityService`, close `hardwareBuffer`, recycle `Bitmap`, and use a dedicated service coroutine scope for screenshots instead of raw unclosed executors.
4. **Clean Manifest**: Remove `<uses-permission android:name="android.intent.action.INTERNET" />`.

### Phase 2: Missing Vision & Selection Features (Module 3 & 4)
1. **Mode 1 Image Attachment**: Add image/camera picker to `ProcessTextActivity` and pass `imageBytes` to `generateStream()`.
2. **Floating Toolbar Anchor**: Add toggle/logic to anchor above the focused text input using `node.getBoundsInScreen(Rect)`.
3. **Vision Toolbar Buttons**: Add Camera, Gallery, and Screen Crop action triggers to `FloatingToolbarContent`.

### Phase 3: Module 5 — Smart Replies & Conversation Parser
1. **UI Tree Traversal**: Implement recursive traversal of `rootInActiveWindow` in `TextMorphAccessibilityService` to collect text nodes, calculate `Rect.centerX()` vs. screen width, and categorize into Incoming vs. Outgoing chat bubbles.
2. **Smart Reply Generator**: Pass transcript + tone prompt to `LiteRtLmEngine` to produce 3 interactive suggestion chips and insert into the input field on tap.
3. **Visual Context Fallback**: Connect silent `takeScreenshot()` bytes into the smart reply prompt when stickers or memes are detected.

### Phase 4: Module 2 — Built-In Model Downloader (Option A)
1. **Model Downloader Engine**: Implement HTTP file download with pause/resume, SHA-256 validation, and download progress reporting for `gemma-4-E2B-it` and `gemma-4-E4B-it`.
2. **Model Management**: Add model deletion and "Import to App Private Storage" toggle in `ModelHubContent`.

### Phase 5: Technical Debt & Version Bumping
1. **Data Layer Refactor**: Migrate from `AtomicFile` JSON to Room DB and DataStore Preferences.
2. **Unit Tests**: Add tests covering UI tree parsing, singleton engine sharing, and model download integrity.
3. **Release & Push**: Bump version in `build.gradle.kts` and `.github/workflows/release.yml`, run tests, and push to `main`.
