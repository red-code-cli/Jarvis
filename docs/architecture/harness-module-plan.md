# Jarvis × Mobile Harness — Step 0 Analysis & Plan

Status: **analysis / awaiting approval** (no Jarvis source touched yet, no commits made)

## 1. Facts gathered

### Jarvis (this working copy)
- Path: `/mnt/sdcard/isha` → `/storage/emulated/0/isha`
- **Not a git repository.** No remote, no `.github/workflows/`.
- `:app` only, namespace/appId `com.jarvis.assistant`, minSdk 26, compileSdk/targetSdk 34, AGP 8.7.2, Kotlin 2.0.20, `viewBinding` (no Compose), Room + kapt, OkHttp, Gson, JNA/Vosk/openWakeWord, Shizuku.
- versionCode 151 / versionName 1.9.5.
- Release signing = env vars `JARVIS_RELEASE_*` + cert pin. **No signing secrets present in this environment.**
- Settings UI = XML (`res/layout/fragment_settings.xml`, 1550 lines) + `ui/settings/SettingsFragment.kt` (471 lines), expandable cards using `@drawable/bg_ref_card` / `bg_jarvis_card`.
- Gemini Live function-calling pattern:
  - Declarations: `network/GeminiLiveWebSocket.kt` (JSON array `functionDeclarations`, lines ~239–960).
  - Execution: `background/JarvisConversationService.kt` → `handleToolCallExecution()` + `when (name)` (line 2068).
- Already present: `ui/terminal/TerminalActivity`, Room DB, `EncryptedSharedPreferences`, foreground conversation service, overlay avatar.

### Mobile Harness (cloned to `/tmp/opencode/Mobile-Harness`, MH 1.0.15, commit 9692f49)
- **License: MIT** (Copyright 2026 Mobile Harness Contributors) — compatible with reuse in a closed/other app, **must keep the LICENSE + copyright notice**.
- Bundled third-party native licences (`app/src/main/assets/licenses/`):
  - **PRoot — GPL-2.0** (statically linked into the APK → copyleft; shipping requires distributing sources/licence text)
  - libandroid-shmem — BSD-3-Clause, talloc — LGPL-3.0-or-later, zstd-jni — BSD, commons-compress — Apache-2.0.
- Kotlin size: **10,715 LOC engine** (`runtime/`, `network/`, `data/`, `model/`, `backup/`, `update/`) + 20,816 LOC UI (Compose).
- Native: `app/src/main/cpp/` (CMake 3.22.1) builds `libproot.so`, `libprootloader.so`, `libpocketspawn.so`, `libtalloc.so`, `libandroid-shmem.so` (arm64-v8a only). Requires NDK **28.2.13676358**, `platforms;android-36`, `cmake;3.22.1`.
- `third_party/proot` + `third_party/libandroid-shmem` are **git submodules → currently empty**; `third_party/talloc` is vendored (3,074 lines of C).
- Runtime bundles are **not in the APK** — downloaded from
  `https://github.com/techjarves/Mobile-Harness/releases/download/runtime-2026.09.4/…`
  (`pocketdev-core`, `-dsh`, `-claude`, `-python`, `-android`, `-agy`), verified by SHA-256, unpacked with zstd + commons-compress.
- Manifests/services to bring in: `RuntimeExecutionService`, `RuntimeSetupService` (`foregroundServiceType="specialUse"` + special-use subtype property), `FileProvider` (`${applicationId}.files`), `AndroidAppInstallReceiver`, `REQUEST_INSTALL_PACKAGES`.
- Flavor dim `runtimeDelivery` (online/offline) with `BuildConfig.OFFLINE_RUNTIME_BUNDLES`, `RUNTIME_RELEASE_BASE_URL`, `APP_UPDATE_MANIFEST_URL`, `APP_VARIANT`.
- Native build hardcodes a package path: `_PATH_TMP="/data/data/com.jarves.mh/cache/"`.
- UI coupling inside engine files is thin: `com.jarves.mh.ui.AppStrings` (locale helper), `R.string.*`, `BuildConfig.*`, and `MainActivity` (notification tap intents only). `MainViewModel.kt` (5,495 LOC) is the real orchestrator that mixes engine calls with UI state.

### Local build environment limits
- Present: JDK 17, Android SDK (`platforms;android-34/35/37`, `build-tools 34/36/37`, **NDK 30.0.16248370**), Gradle 9.8.2, network OK.
- **Missing:** `cmake`, `platforms;android-36`, `build-tools;36.0.0`, NDK 28.2. → native MH build cannot run locally until those are installed (network is available).

## 2. What moves into `:harness-core`

| Keep (engine) | Notes |
|---|---|
| `runtime/` (`RuntimeInstaller`, `RuntimeSetupService`, `RuntimeExecutionService`, `RuntimeBridge`, `NativeSpawnProcess`, `RootfsRepair`, `RuntimeCompatibility`, `BoundedFileReads`, `WorkspaceCheckpoints`, `AgentDriver`, `ClaudeRuntimeBridge`, `DshRuntimeBridge`, `AntigravityRuntimeBridge`, `AntigravityAuthController`, `LocalFormatGateway`, `OpenRouterRoutingGateway`, `AndroidBuildSupport`, `AndroidProjectTemplateGenerator`, `AndroidAppInstaller`, `AndroidAppInstallReceiver`) | PRoot/Linux runtime, terminal/shell engine, installer, agent runners, project/workspace + task state, Android build/install |
| `data/ApiKeyVault.kt`, `data/AppPreferences.kt` | Keystore-backed AES-256-GCM key vault + prefs |
| `model/Models.kt` | domain models |
| `network/ProviderApiClient.kt`, `network/LocalNetwork.kt` | provider/API config + "test connection" + local port discovery |
| `backup/BackupManager.kt`, `BackupCrypto.kt`, `BackupService.kt` | workspace backup (optional keep; only the service is Android-y) |
| `app/src/main/cpp/**`, `third_party/{talloc,proot,libandroid-shmem}` | native PRoot runtime (mandatory) |
| `app/src/test/java/com/jarves/mh/{runtime,network,model,backup}` | existing unit tests (1630 LOC) as baseline |

### Removed (UI only)
- Everything in `ui/`: `PocketDevApp.kt` (9072), `MainViewModel.kt` (5495), `AgentScreen`, `SettingsScreenModern`, `TerminalScreen`, `MarkdownText`, `PocketComponents`, `BackupRestoreUi`, `WhatsNewDialog`, `Theme.kt`, `ModelLabels.kt`, `LocaleHelper` (see below), `MainActivity.kt`.
- All `res/` (strings ×10 locales, themes, drawables, mipmaps, fonts), `ui/theme/`, `fastlane/`, `docs/`, `assets/readme/`, `.github/`.
- `update/AppUpdater.kt` — Mobile-Harness **app self-update**; Jarvis keeps its own updater. (Decision point, see Q3.)

### `LocaleHelper` / `AppStrings`
Not really UI: it is a locale-aware resource lookup used by engine files for status strings. Two options:
- **(a)** keep a tiny locale-aware string resolver inside the module (with the module's own `strings.xml`, English only) — least code churn; used only for human-readable status/log text.
- **(b)** drop the helper and make engine return **codes/enums**, letting the Jarvis adapter render text.
I recommend **(b) for anything Jarvis speaks aloud, (a) for internal logs**, so voice/jarvis persona stays in Jarvis's language. Confirm in Q2.

## 3. New code (the only new code)

1. **`harness-core/src/main/java/<pkg>/HarnessEngine.kt`** — public facade, UI-free:
   `install()`, `updateAll()`, `installState: Flow<InstallState>`, `startTask(prompt, projectType): String`, `taskStatus(id)`, `pause/resume/cancel(id)`, `sendFollowUp(id, text)`, `runCommand(cmd)`, `listProjects()`, `projectLocation(id)`, `events: Flow<HarnessEvent>` (progress / completed / failed), plus `getAgent(id)` manual install for Claude Code / Antigravity.
   Backed by a ported, de-UI'd `HarnessOrchestrator` derived from MH's `MainViewModel` engine paths (task queue, single active task, persistence, resume).
2. **`HarnessConfig`** — replaces `BuildConfig` (bundle base URL, offline flag off, `appPrivateDir` for the native `_PATH_TMP`), plus a `HarnessStrings` provider injected by the host app (language).
3. **Jarvis adapter `app/.../harness/JarvisHarnessAdapter.kt`** — implements module's string/notification/location callbacks using Jarvis resources, foreground-service coordination, and copies deliverables to `Downloads/Jarvis/<project>/` via MediaStore.
4. **Gemini tools** in `GeminiLiveWebSocket.kt` + `JarvisConversationService.kt`:
   `start_build_task`, `get_task_status`, `pause_task`, `resume_task`, `cancel_task`, `send_followup`, `list_projects`, `get_project_location`, `run_terminal_command`, `install_coding_engine`.
5. **Settings card "Coding Engine"** — one expandable card in `fragment_settings.xml` in Jarvis's existing `bg_ref_card` style + `HarnessSettingsController.kt`; contains Install, per-agent manual install, "Update all", Custom API (provider/endpoint/model/key + Test connection, key in encrypted storage, separate from the Gemini Live API key).
6. **`background/HarnessTaskService`** — Jarvis-owned foreground service (or reuse of existing conversation service) so the module stays free of Jarvis service names; correct Android 14+ FGS types, wakelock.

## 4. Gradle / packaging plan
- Module: `com.android.library`, own namespace (Q4), minSdk 26 (matches Jarvis), compileSdk 36, `externalNativeBuild.cmake`, `ndk { abiFilters += "arm64-v8a" }`, `buildConfig = true`, NDK 28.2.13676358.
- App: add `packaging { jniLibs { useLegacyPackaging = true } }` (required for the PRoot executables) and `androidResources { noCompress += "zst" }`; keep the module's ProGuard rules so engine classes survive R8.
- Deps added to app (via module `api`/`implementation`): `commons-compress 1.27.1`, `zstd-jni 1.5.7-12@aar`, `kotlinx-coroutines-android`. No Compose anywhere. Kotlin/AGP version: module must build under Jarvis's AGP 8.7.2 + Kotlin 2.0.20 (MH uses 8.13.2/2.2.21) — plan is to keep MH's engine code unchanged and only adjust the build script, so no Kotlin-2.2 syntax may be required.
- Expected **APK size increase: ~10–20 MB** (native .so + Kotlin + zstd). Rootfs/toolchains stay download-only. (Q3 covers where they download from.)
- Jarvis keeps `arm64-v8a` (already the effective device ABI); 32-bit devices lose nothing today since MH is arm64-only → **the Coding Engine card must hide itself on non-arm64 devices**.

## 5. Risks

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| R1 | No git repo/remote here → no branch, commits, PR, release | **blocking** | Q1 |
| R2 | No signing secrets → cannot produce signed release APK | **blocking** | Q1 |
| R3 | PRoot is GPL-2.0, statically linked → copyleft obligations when distributing Jarvis APK | high | keep licence texts, publish native sources; Q5 |
| R4 | Runtime bundles downloaded from a **third-party repo** (`techjarves/Mobile-Harness` releases) — outside user's control; deletion/breakage kills the engine | high | Q3 |
| R5 | Native build hardcodes `/data/data/com.jarves.mh/cache/` | medium | parameterize path via CMake define from module config |
| R6 | Submodules empty → `git submodule update --init` needed; will pin exact commits for reproducibility | medium | pinned commits + offline copy in module |
| R7 | `MainViewModel` (5,495 LOC) mixes engine + UI; extracting engine paths is the largest single piece of work | medium | port only engine-relevant methods; every deviation listed in PR |
| R8 | Local env missing cmake / android-36 / NDK 28.2 → cannot build APK here | medium | install via `sdkmanager` (network OK) or build in CI |
| R9 | Foreground service conflicts with Jarvis's existing conversation service (FGS type limits, notification ids) | medium | separate service + distinct notification channel/id; Q2 |
| R10 | Gradle/AGP/Kotlin version skew (MH 8.13/2.2.21 vs Jarvis 8.7.2/2.0.20) | medium | adapt build scripts only, don't rewrite engine code; verified by compiling |
| R11 | `run_command` voice surface could be abused / destructive | medium | module-level command deny/confirm list + Jarvis voice confirmation, timeouts, sandbox-only |
| R12 | APK/Google Play policy: `REQUEST_INSTALL_PACKAGES`, `specialUse` FGS, on-device code execution | medium | declare reasons; if Play distribution is intended, review before shipping |

## 6. Execution phases (after approval)
1. Module skeleton + Gradle + native build wiring, engine sources copied verbatim, UI stripped, compiles.
2. `HarnessEngine` facade + config/strings/provider injection + task state persistence/resume.
3. Jarvis adapter: services, notifications, overlay progress, MediaStore delivery, browser preview, APK install offer.
4. Gemini tool declarations + execution wiring.
5. Settings card (Install / per-agent install / Update all / Custom API).
6. Tests: module unit tests (task state, command safety, installer resume), plus the full regression checklist.
7. Git/CI/release per the repo answers.
