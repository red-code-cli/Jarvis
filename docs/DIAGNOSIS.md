# DIAGNOSIS — Agent Live reliability, resume and updates

Evidence is taken from code paths plus the released artefacts (`v1.14.2`, `v1.15.0`).
Device logs are not available in this environment; every claim points at a file/function so
it can be re-checked on a device.

This document is current as of **v1.15.1**, a follow-up that completes the two items
`v1.15.0` did not cover: **B1 (truthful verification/counters/feed)** and
**B6 (update without uninstall)**.

---

## 1. `FAILED: No web entry file (index.html)` while files exist

**Verifier:** `harness-core/.../HarnessEngine.kt` → `verify()`.

**Where the project root comes from:** `MainViewModel.projectWorkspaceRoot(project)` returns
`filesDir/workspaces/<projectId>` (or `<base>/<project.rootPath>`). The agent runs in the
Linux runtime with `/workspace/<projectSlug>` bind-mounted to that host directory
(`RuntimeInstaller.process()`: `-b ${workspace.absolutePath}:$guestWorkspacePath`). A file the
agent writes at `/workspace/single-page-professional-site/index.html` therefore lands under
`filesDir/workspaces/<projectId>/.../single-page-professional-site/index.html`.

**Root causes (confirmed in `v1.14.2`):**
1. Verification used only `Project.projectId` from the *current UI state*; on a retry/resume
   `record.projectId` could be null and `activeProject` could point elsewhere, so the walk
   started in the wrong directory.
2. There was no depth-limited, subfolder-aware entry lookup, and the failure message did not
   prove which path/files were checked.
3. A static `index.html` had no preview server of its own; preview only existed if the agent
   started a dev server and printed its URL.

**Fix in `v1.15.1`:**
- `policy/WorkspaceFiles.findEntryFile()` searches the project root **and subfolders up to
  depth 3**, prefers the shallowest `index.html`, then any `*.html`.
- `verify()` prefers the root recorded in project memory (`workspacePath`), falls back to the
  project workspace, and reports `checked <absolute path> (N files, depth <= 3)` on failure.
- `policy/StaticPreviewServer` is started as the finalize fallback, so a finished static site
  always has a real preview URL and opens in Jarvis Browser.

## 2. `files=0`, duplicate feed text, mid-token truncation

**Files counter:** `HarnessEngine.Counters.files` was filled only from `FILE_WRITE`/`FILE_EDIT`
events, and `note()` wrote `fileCount = counts.files.size` on every event. If a write event
lacked a parseable path, the card stayed at `files=0` even though the files existed. There was
no workspace watcher.

**Duplicate text:** `HarnessEngine.restoreCard()` rebuilt the feed after a restart with
`(event.title + event.detail.take(120)).take(220)` — no dedupe. `"Wrote index.html"` + detail
`"index.html"` therefore rendered as **`Wrote index.htmlindex.html`**. (`note()` had a dedupe
check; the restore path did not.)

**Mid-token truncation:** raw `take(n)` cut `...section class=...` in half.

**Fix in `v1.15.1`:**
- `policy/WorkspaceFiles.list()/count()` walks the workspace (excluding `node_modules`,
  `.git`, `build`, `.gradle`, `__pycache__`, `.venv`). `refreshWorkspaceFileCount()` runs on
  every `FilesChanged` and before completion; **the walk wins** when it disagrees with events,
  and `note()` uses `max(watcher, events)` so it can never be pulled back to 0.
- `policy/FeedText.compose()/truncate()` dedupe the detail against the title and cut on a word
  boundary. Both `note()` and `restoreCard()` use it — one implementation, so a restart cannot
  reintroduce the duplicate.

## 3. 35+ steps / 15+ minutes for a single page

**Shipped in `v1.15.0`:**
- `policy/TaskClassifier` + `AgentPolicyConfig` tier the request before the run
  (TRIVIAL 10/3, SIMPLE 25/8, MEDIUM 80/25; COMPLEX is milestone-driven).
- `policy/AgentPromptPolicy` prepends the efficiency rules (plan first, write each file in one
  pass, no base64 assets unless asked, split large files, one verification pass, no heavy test
  tooling, finalize when done).
- `policy/StepBreakdown` reports `build/edit/verify/test` counts on the card.

**Evidence:** `HarnessEngine.observeStepPolicy`, `AgentPromptPolicy.efficiencyInstructions`.

## 4. Agent stuck in testing

**Shipped in `v1.15.0`:**
- `policy/CapabilityProbe` + `CapabilityProbeCache` probe once per engine version for a
  chrome/chromium binary and a usable puppeteer/playwright module, cache the answer under
  `filesDir`, and tell the agent `visual test: available/unavailable (<reason>)`.
- `AgentPolicyConfig.testingPatterns` + `LoopWatchdog.testingAttemptsCap = 2` stop the loop.
- Finalize falls back to a light static check (files exist + entry file resolves), not a
  browser test.

## 5. Loop watchdog

`policy/LoopWatchdog` detects repeated tool+args (`3`), repeated file edits (`5`), no new
file/milestone for `12` steps, more than `2` testing commands, and tier budget overruns.
First detection → corrective instruction injected into the session; if the loop continues →
`supervisorFinalize()` stops the run and `completeWithWarnings()` ships what exists, opens the
preview and marks the task `COMPLETED_WITH_WARNINGS` with the reason. COMPLEX work uses
milestones, each with its own budget.

## 6. Session resume / "Jarvis ko project yaad nahi rehta"

**Shipped in `v1.15.0`:** `HarnessProjectMemory` persists project id, workspace path, project
root, agent type + session id, chat, provider/model, tier, milestones, summary, file-tree
snapshot, preview URL/port, status and checkpoint, flushed atomically on every meaningful
event. `ProjectResume.kt` + `resume_project` / `get_project_status` / `list_projects` continue
a project in the same workspace, or start a new session whose prompt carries the saved
summary/files/todo/checkpoint. `JarvisConversationService` injects the unfinished-projects block
on Gemini Live start/reconnect and asks one short question when the choice is ambiguous. All
state lives under `filesDir`, which Android preserves across an APK update (only an uninstall
wipes it).

## 7. Update requires uninstall

**Root cause (confirmed):** the release workflow ran `assembleDebug`. On GitHub-hosted runners
AGP generates `~/.android/debug.keystore` **fresh per run** (it is not cached), so every
release was signed with a different certificate. Android refuses to over-install an APK whose
signing certificate differs. Repository secrets were empty (`total_count: 0`), so release
signing was never wired.

**Fix in `v1.15.1`:**
- A stable keystore is committed at `app/keystore/jarvis-release.jks` and used for both
  `release` and `debug` signing. `JARVIS_RELEASE_*` secrets still override it.
- The release job builds **`assembleRelease`** (signed + R8) once and verifies the certificate
  and versionCode before publishing. Certificate SHA-256:
  `81:87:84:21:DF:41:E2:E0:C0:38:FA:0C:48:37:68:65:17:56:DC:DA:3C:52:0E:B8:C7:CB:89:09:EA:7B:7C:84`.
- `PINNED_RELEASE_CERT_SHA256` defaults to that fingerprint.
- `versionCode` 161 / `versionName` 1.15.1 (previous release 160 / 1.15.0).
- `applicationId` (`com.jarvis.assistant`) and `minSdk` (26) unchanged.

**Honest limitation:** the build currently installed on the phone was debug-signed with a
random per-run key, so **one uninstall is unavoidable** when installing this release. Every
update after this one will over-install.

## 8. Data / cache durability

- `RuntimeInstaller` keeps the sandbox under `filesDir` (not `cacheDir`), so it survives an
  update.
- Capability probe and project/file-tree summaries are cached with explicit versions; a format
  change discards the old file instead of misreading it.
- Room: `exportSchema = true`, `fallbackToDestructiveMigration()` removed, explicit
  `MIGRATIONS` array, exported schema checked by `MemoryDatabaseSchemaTest`, and an
  instrumented `MemoryDatabaseMigrationTest` kept compiling in CI. Overwriting data on update
  is no longer possible.

## Tests added by this follow-up

| Area | Test |
| --- | --- |
| Entry file in root / subfolder / missing | `WorkspaceFilesTest` |
| Non-empty file count + excluded dirs | `WorkspaceFilesTest` |
| Feed dedupe + word-boundary truncation | `FeedTextTest` |
| Static preview serve / 404 / path traversal | `StaticPreviewServerTest` |
| Exported Room schema | `MemoryDatabaseSchemaTest` |

## Limitations (cannot be verified without a device/emulator here)

- End-to-end Agent Live run against the real Linux runtime, Gemini Live and Jarvis Browser.
- Instrumented Room migration test (compiled in CI, not executed).
- Real before/after step/time numbers on a device.
- Actual `adb install` over-install (CI verifies certificate + versionCode instead).