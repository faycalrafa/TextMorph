# Project: TextMorph — System-Wide Privacy-First Multimodal AI Assistant

## Architecture
TextMorph is built with **Modern Android Architecture (MVVM)**, **Jetpack Compose (Material 3)**, **LiteRT-LM (On-Device Multimodal AI Inference)**, **Android Accessibility Service**, and an embedded **Ktor Localhost Server**.

Key architectural boundaries:
- **Navigation & Routing**: In `MainActivity.kt`, managed via `DashboardScreen.kt` with sidebar drawer, morphing search top bar, and back press interception.
- **Inference Subsystem (`llm`)**: `LiteRtLmEngine` provides GPU-first hardware-accelerated inference with zero-copy memory mapping from `Downloads/`. `OpenAiCloudEngine` provides optional fallback.
- **Service & Overlay Subsystem (`service`)**: `TextMorphAccessibilityService` monitors input focus and window changes. `FloatingToolbarWindowManager` anchors floating Compose views without stealing keyboard focus (`FLAG_NOT_FOCUSABLE`). `LlmForegroundService` keeps the model warm in memory across app switches.
- **Data Subsystem (`data`)**: `PromptRepository` manages atomic JSON persistence for prompt CRUD with 12 default seeds. `ModelStorageRepository` manages `/sdcard/Download` scanning, SAF permissions, and direct descriptor resolution. `SettingsRepository` manages themes, server configs, and engine mode. `UpdateRepository` manages GitHub release polling and APK installation.
- **Server Subsystem (`server`)**: `LocalHttpServer` hosts an embedded Ktor CIO server on `127.0.0.1:8080` exposing `/v1/models` and streaming `/v1/chat/completions`.
- **UI Subsystem (`ui`)**: Jetpack Compose Material 3 screens adopting the **Forest Clay** design system (`design.md`) with full-width responsive dialogs (`DialogProperties(usePlatformDefaultWidth = false)` + `FlowRow`) and morphing top bar search.

---

## Feature Inventory & Status Tracker

| # | Feature | Description | Milestone | Status |
|---|---------|-------------|-----------|--------|
| F1 | Project Foundation & Build System | Gradle version catalog, AGP 9.0.1, dependencies (LiteRT-LM, Ktor, Room, Compose), AndroidManifest, and icon assets | M1 | **DONE** |
| F2 | Engineering Guidelines & Design Spec | `design.md` with Forest Clay default palette and `GEMINI.md` architectural contracts | M1 | **DONE** |
| F3 | GitHub Actions CI/CD Pipeline | Automated release APK builds and artifact generation (`release.yml`) | M1 | **DONE** |
| F4 | Custom Prompt Manager & Seeds | Prompt schema (`PromptEntity`), atomic persistence, and 12 seed prompts (Text, Smart Reply, Vision) | M2 | **DONE** |
| F5 | System Readiness & Permission Banner | Checklist cards for Accessibility, Battery Optimizations, Downloads Access, and Notifications | M2 | **DONE** |
| F6 | Main Dashboard & Prompt CRUD UI | Material 3 dashboard, prompt card list, morphing top bar search, and full-width prompt editor dialog | M2 | **DONE** |
| F7 | LiteRT-LM Engine & GPU Backend | Hardware-accelerated `.litertlm` execution with strict `thinking = false` policy and Mutex queuing | M3 | **DONE** |
| F8 | Zero-Copy Downloads/ Model Loader | Auto-scan `/sdcard/Download` and SAF `ParcelFileDescriptor` mmap loading without duplicating files | M3 | **DONE** |
| F9 | Embedded Localhost OpenAI Server | Ktor CIO server on `127.0.0.1:8080` exposing `/v1/models` and streaming `/v1/chat/completions` | M3 | **DONE** |
| F10 | Native Text Selection Handler | Mode 1: `ACTION_PROCESS_TEXT` floating dialog activity with prompt chips, custom instruction, and in-place replacement | M4 | **DONE** |
| F11 | Accessibility Floating AI Toolbar | Mode 2: Non-intrusive floating toolbar anchored above editable text fields via `WindowManager` | M5 | **DONE** |
| F12 | Full-Field Text Transformation | Automatic text extraction without manual selection and replacement via `ACTION_SET_TEXT` | M5 | **DONE** |
| F13 | Direct Vision-to-Input Engine | Accessibility screenshot capture (`canTakeScreenshot="true"`), multimodal image bytes handling, and direct insertion | M6 | **DONE** |
| F14 | Smart Reply Recommender | Visual and UI context awareness with contextual affirmative, inquiry, and polite decline suggestions | M6 | **DONE** |
| F15 | In-App Update Engine & Developer Logs | Real-time diagnostic logging (`AppLogger`), GitHub release update dialog, and In-App APK installer | M6 | **DONE** |

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

---

## Verification & Build Results
- **Unit Tests**: `testDebugUnitTest` executed and passed with exit code `0`.
- **Release Build**: `assembleRelease` executed and passed with exit code `0` (`app-release.apk` 15MB generated and signed with release keystore).
- **Application Version**: `v1.1.1` (versionCode `3`).
