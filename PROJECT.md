# Project: TextMorph — System-Wide Privacy-First Multimodal AI Assistant

## Architecture
TextMorph is built with **Modern Android Architecture (MVVM)**, **Jetpack Compose (Material 3)**, **LiteRT-LM (On-Device Multimodal AI Inference)**, **Android Accessibility Service**, and an embedded **Ktor Localhost Server**.

Key architectural boundaries:
- **Navigation & Routing**: In `MainActivity.kt`, managed via `AppScreen` enum state and explicit `BackHandler`s with `subScreenSourceScreen` origin tracking.
- **Inference Subsystem (`llm`)**: `LiteRtLmEngine` provides GPU-first hardware-accelerated inference with zero-copy memory mapping from `Downloads/`. `OpenAiCloudEngine` provides optional fallback.
- **Service & Overlay Subsystem (`service`)**: `TextMorphAccessibilityService` monitors input focus and window changes. `FloatingToolbarWindowManager` anchors floating Compose views without stealing keyboard focus. `LlmForegroundService` keeps the model warm in memory across app switches.
- **Data Subsystem (`data`)**: `PromptRepository` manages Room DB prompt CRUD with default seed data. `ModelStorageRepository` manages `/sdcard/Download` scanning, SAF permissions, and direct descriptor resolution.
- **Server Subsystem (`server`)**: `LocalHttpServer` hosts an embedded Ktor CIO server on `127.0.0.1:8080` exposing `/v1/models` and `/v1/chat/completions`.
- **UI Subsystem (`ui`)**: Jetpack Compose Material 3 screens adopting the **Forest Clay** design system (`design.md`) with full-width responsive dialogs and morphing top bar search.

---

## Feature Inventory & Status Tracker

| # | Feature | Description | Milestone | Status |
|---|---------|-------------|-----------|--------|
| F1 | Project Foundation & Build System | Gradle version catalog, AGP 9.0.1, dependencies (LiteRT-LM, Ktor, Room, Compose), AndroidManifest, and icon assets | M1 | **DONE** |
| F2 | Engineering Guidelines & Design Spec | `design.md` with Forest Clay default palette and `GEMINI.md` architectural contracts | M1 | **DONE** |
| F3 | GitHub Actions CI/CD Pipeline | Automated release APK builds and artifact generation (`release.yml`) | M1 | **DONE** |
| F4 | Room DB Custom Prompt Manager | Prompt schema (`PromptEntity`), DAO, repository, and seed data (Text & Vision prompts) | M2 | PLANNED |
| F5 | System Readiness & Permission Banner | Checklist cards for Accessibility, Battery Optimizations, Downloads Access, and Notifications | M2 | PLANNED |
| F6 | Main Dashboard & Prompt CRUD UI | Material 3 dashboard, prompt card list, and full-width responsive prompt editor dialog | M2 | PLANNED |
| F7 | LiteRT-LM Engine & GPU Backend | Hardware-accelerated `.litertlm` execution with strict `thinking = false` policy | M3 | PLANNED |
| F8 | Zero-Copy Downloads/ Model Loader | Auto-scan `/sdcard/Download` and SAF `ParcelFileDescriptor` mmap loading without duplicating files | M3 | PLANNED |
| F9 | Embedded Localhost OpenAI Server | Ktor CIO server on `127.0.0.1:8080` exposing `/v1/models` and streaming `/v1/chat/completions` | M3 | PLANNED |
| F10 | Native Text Selection Handler | Mode 1: `ACTION_PROCESS_TEXT` floating dialog activity with prompt selection and in-place replacement | M4 | PLANNED |
| F11 | Accessibility Floating AI Toolbar | Mode 2: Non-intrusive floating toolbar anchored above editable text fields via `WindowManager` | M5 | PLANNED |
| F12 | Full-Field Text Transformation | Automatic text extraction without manual selection and replacement via `ACTION_SET_TEXT` | M5 | PLANNED |
| F13 | Direct Vision-to-Input Engine | Screen region crop capture, camera snapshot, multimodal Gemma 4 inference, and direct text injection | M6 | PLANNED |
| F14 | Smart Reply Recommender | Visual and UI tree conversation traversal with 3-option context-aware smart replies | M6 | PLANNED |
| F15 | In-App Update Engine & Developer Logs | Real-time diagnostic logging (`AppLogger`), global error bus (`AppErrorBus`), and update dialogs | M6 | PLANNED |

---

## Milestones

| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| **M1** | **Foundation, CI/CD & Scaffolding** | F1, F2, F3 | none | **DONE** |
| **M2** | **Dashboard, Readiness Checklist & Prompt CRUD** | F4, F5, F6 | M1 | IN PROGRESS |
| **M3** | **LiteRT-LM Engine, Downloads mmap & Ktor Server** | F7, F8, F9 | M1 | PLANNED |
| **M4** | **Mode 1: Native Text Selection (`ACTION_PROCESS_TEXT`)** | F10 | M2, M3 | PLANNED |
| **M5** | **Mode 2: Floating Accessibility Toolbar** | F11, F12 | M2, M3 | PLANNED |
| **M6** | **Multimodal Vision, Smart Replies & Testing** | F13, F14, F15 | M4, M5 | PLANNED |

---

## Code Layout

- `android-app/app/src/main/`:
  - `AndroidManifest.xml`: Permissions, largeHeap, Activity & Service registrations.
  - `res/xml/accessibility_service_config.xml`: Accessibility service configuration.
  - `res/mipmap-*/`: Multi-density launcher icons generated from app branding.
  - `res/values/`: Strings and base/dialog themes.
  - `java/com/textmorph/app/`:
    - `TextMorphApplication.kt`: Application initialization.
    - `MainActivity.kt`: Main UI entry point.
    - `theme/`: `Color.kt`, `Theme.kt`, `Type.kt` (Forest Clay default palette).
    - `util/`: `AppLogger.kt`, `AppErrorBus.kt`.
    - `ui/selection/ProcessTextActivity.kt`: Mode 1 selection activity.
    - `service/overlay/TextMorphAccessibilityService.kt`: Mode 2 accessibility engine.
    - `service/LlmForegroundService.kt`: Background model host service.
- `.github/workflows/release.yml`: Automated GitHub Actions release build.
- `design.md`: Complete Design System specification.
- `GEMINI.md`: Engineering Guidelines and architecture contracts.
