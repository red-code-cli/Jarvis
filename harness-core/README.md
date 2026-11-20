# :harness-core — reusable autonomous coding engine

`:harness-core` is the engine extracted from [Mobile Harness](https://github.com/Vempire-X/Mobile-Harness)
(MIT). It gives an Android app a self-contained Linux (PRoot) workspace with coding-agent
runners, package/toolchain installers, a project/task manager and a sandboxed terminal.

It contains **no UI**: no activities, fragments, Compose, XML layouts, themes or navigation.
The host app renders everything.

```
harness-core/
├── src/main/java/com/jarvis/harness/      engine + public facade
├── src/main/cpp/                          PRoot launcher / JNI spawn (arm64 only)
├── third_party/{proot,libandroid-shmem,talloc}
├── src/main/res/values/strings_harness.xml  engine status strings (English)
├── src/main/assets/harness-licenses/      third-party licence texts (shipped in APK)
├── NOTICE.md                              licences, incl. GPL-2.0 for PRoot
└── LICENSE.txt                            MIT licence of the upstream project
```

## Requirements

- Android **minSdk 26**, **arm64-v8a** device (PRoot has no other ABI)
- JDK 17, Android Gradle Plugin 8.x, Kotlin 2.0.x
- **NDK 28.2.13676358** and **CMake 3.22.1** (GitHub Actions installs both)
- No root, no Shizuku, no Termux

## Add it to another app

1. **Copy the module** into your repository, e.g. `harness-core/`, and register it:

   ```kotlin
   // settings.gradle.kts
   include(":harness-core")
   ```

2. **Depend on it** from the application module:

   ```kotlin
   // app/build.gradle.kts
   dependencies { implementation(project(":harness-core")) }
   ```

3. **Tell the native build your application id.** `libandroid-shmem` needs the
   absolute private-cache path at compile time. Either add it to `gradle.properties`:

   ```properties
   harnessApplicationId=com.your.app
   ```

   …or pass `-PharnessApplicationId=com.your.app`. It defaults to
   `com.jarvis.assistant` because that is the module's first host.

4. **Required app-level packaging.** The Linux binaries are executed from
   `applicationInfo.nativeLibraryDir`, so they must be extracted at install time:

   ```kotlin
   android {
       packaging { jniLibs { useLegacyPackaging = true } }
       androidResources { noCompress += "zst" }
   }
   ```

5. **Nothing to add to your manifest.** The module's manifest already contributes
   its permissions, services (`RuntimeExecutionService`, `RuntimeSetupService`,
   `BackupService`), installer receiver and a dedicated FileProvider with authority
   `${applicationId}.harness.files`. Existing host providers are untouched.
   The host app must grant `POST_NOTIFICATIONS` at runtime on Android 13+.

6. **Initialise and use it:**

   ```kotlin
   // Application.onCreate()
   HarnessEngine.initialize(
       context = this,
       versionName = BuildConfig.VERSION_NAME,
       versionCode = BuildConfig.VERSION_CODE,
       autoInstallOnFirstRun = false,          // install only on explicit user action
   ).also { engine -> engine.host = myHostAdapter }

   // On user action
   engine.install()                            // core runtime + all toolchains + DeepSeek Harness
   val taskId = engine.startTask("todo app banao ...", HarnessProjectType.ANDROID)

   engine.taskStatus(taskId)?.let { status -> /* real state, never guessed */ }
   engine.pauseTask(taskId); engine.resumeTask(taskId); engine.cancelTask(taskId)
   engine.sendFollowUp(taskId, "dark mode bhi add karo")

   val result = engine.runCommand("ls -la")    // Linux sandbox only
   if (HarnessEngine.assessCommand("rm -rf build") == HarnessCommandRisk.CONFIRM) {
       /* ask the user out loud first, then runCommand(cmd, confirmed = true) */
   }
   ```

7. **Observe progress:**

   ```kotlin
   engine.installState.collect { state -> /* status, progress, bytes, error, logs */ }
   engine.events.collect { event -> /* Progress / Completed / Failed */ }
   ```

## Public API

| Call | Purpose |
|---|---|
| `install(types)` / `retryInstall()` | Core runtime (Ubuntu, Node, npm, Git) + toolchains + DeepSeek Harness |
| `installAgent(kind)` | Opt-in install of Claude Code / Antigravity CLI |
| `updateAll()` / `checkForUpdates()` | Agents, packages and toolchains to latest |
| `installState` | `StateFlow<HarnessInstallState>` (resumable, retryable) |
| `startTask(prompt, projectType, name)` | Queue an autonomous build; returns immediately |
| `taskStatus(id)` / `allTasks()` | Durable, persisted truth (survives process death) |
| `pauseTask` / `resumeTask` / `cancelTask` | One task runs at a time; rest queue |
| `sendFollowUp(id, text)` | Add instructions mid-run |
| `runCommand(cmd, timeoutMs, confirmed)` | Sandboxed shell command with risk policy |
| `assessCommand(cmd)` | `SAFE` / `CONFIRM` / `BLOCK` for destructive-command gating |
| `listProjects()` / `projectLocation(id)` | Workspace inventory |
| `configureProvider(...)` / `testConnection(...)` | Custom API (OpenRouter / Anthropic / OpenAI-compatible) |
| `events` | `SharedFlow<HarnessEvent>` |

`HarnessHost` is the only callback the host must provide:

```kotlin
interface HarnessHost {
    suspend fun onTaskCompleted(status: HarnessTaskStatus) {}
    suspend fun onTaskFailed(status: HarnessTaskStatus, reason: String) {}
}
```

## Configuration

```kotlin
HarnessBuildConfig.runtimeReleaseBaseUrl = "https://…/runtime-2026.09.4" // mirror override
HarnessBuildConfig.offlineRuntimeBundles  = false  // never bundle the rootfs in the APK
HarnessBuildConfig.autoInstallOnFirstRun  = false  // require an explicit user action
```

API keys are stored with Android Keystore-backed AES-256-GCM (`ApiKeyVault`), in the
module's own preferences (`harness_preferences` / `harness_secrets`) and with its own
Keystore alias, so they never mix with the host's credentials.

## Storage layout

Everything is per-app private:

- `filesDir/runtime/ubuntu` — Linux rootfs
- `filesDir/runtime/…` — toolchains and agents
- `filesDir/workspaces/<slug>` — project files
- `filesDir/harness/tasks.json` — durable task state
- `filesDir/harness/shell` — sandbox scratch for `runCommand`

Publishing a finished deliverable to public storage is the host app's job
(Jarvis copies it to `Downloads/Jarvis/<project>/`).

## Things to know

- **PRoot is GPL-2.0** and is statically linked into the APK. Read `NOTICE.md` before
  shipping. Licence texts are inside the APK at `assets/harness-licenses/`.
- The rootfs and toolchains are **downloaded at runtime**, never bundled (APK stays small).
- The whole module is **arm64-v8a only**; call `isSupportedDevice()` and hide the UI elsewhere.
- `pauseTask` stops the current agent run; `resumeTask` continues the same project
  conversation with a "continue where you stopped" turn.
- Runtime background work uses the module's own foreground services, notification
  channels (`harness_*`) and notification ids (81xx) so host notifications are unaffected.