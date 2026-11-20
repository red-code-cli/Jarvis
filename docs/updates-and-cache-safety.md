# Update safety and cache preservation

Findings for section B5 (caches survive updates) and B6 (update without uninstall). All
claims below were checked against the code in this branch and, where marked, against the
APK assets published on GitHub.

## 1. Where the sandbox and its caches live

| Data | Path | Survives an APK update? |
| --- | --- | --- |
| Linux rootfs | `filesDir/runtime/ubuntu` | yes — `filesDir` is preserved when the app is replaced because `applicationId` never changes |
| PRoot launcher | `applicationInfo.nativeLibraryDir/libproot.so` | yes — republished by the new APK |
| npm cache | `<rootfs>/root/.npm` (`HOME=/root`) | yes |
| pip cache | `<rootfs>/root/.cache/pip` | yes |
| Gradle / Maven caches | `<rootfs>/root/.gradle`, `<rootfs>/root/maven` | yes |
| Android SDK | `<rootfs>/root/android-sdk` | yes |
| Bundled Gradle + JDK | `<rootfs>/opt/gradle`, `<rootfs>/opt/jdk-*` | yes (see fix below) |
| apt archives / lists | `<rootfs>/var/cache/apt`, `<rootfs>/var/lib/apt/lists` | yes (see fix below) |
| Runtime archive staging | `cacheDir/runtime-downloads` | no — deliberately on the reclaimable cache volume; every staged file is checksum-verified, so losing it only re-downloads |

`RuntimeInstaller` keeps the sandbox under `filesDir`, and the package caches sit inside the
Linux home, which PRoot starts with `HOME=/root`.

**Fix:** `RootfsRepair` used to preserve only `<rootfs>/root` when the runtime was rebuilt
(a changed rootfs bundle version, or a repair). Everything outside the home — the bundled
Gradle/JDK under `/opt` and the apt caches under `/var` — was deleted. Because the Android
toolchain's version marker lives under `root`, the installer believed the toolchain was
installed while `/opt/gradle` was gone, so it re-downloaded the ~570 MB Android bundle.
`RootfsRepair` now preserves `root`, `opt`, `var/cache/apt`, and `var/lib/apt/lists` across
activate/reconcile/rollback (`REUSABLE_ROOTFS_PATHS`). A rebuilt root still replaces those
directories when a bundled version changes, because the version markers no longer match.

## 2. Cached probe / tree / plan results

- **Capability probe (key probe):** the free-model key probe in
  `ProviderModelRefresh.refreshIfDue` already runs at most once a day and stores its result
  in `ProviderPriorityStore` (`workingModels`, `lastModelRefreshMillis`). It is reused
  across restarts and updates. The arm64 runtime probe (`supportsArm64Runtime`) is pure and
  cheap; `RuntimeInstaller.isInstalled()` is deliberately **not** cached because a stale
  negative would block installation.
- **Project file-tree summary:** `MainViewModel.folderMetadataCache` cached folder child
  counts in memory only and re-walked every folder after each restart. It is now persisted
  through `ProjectTreeSummaryCache` to `filesDir/project-tree-summary.json` and reloaded on
  startup. Entries are only trusted while the folder's modification time still matches, so a
  stale count is recomputed. The file carries an explicit `version` field; a different
  version is discarded and rebuilt (the migration for this format).
- **Plan:** `AgentTaskEngine` rebuilt `plan.joinToString(" → ")` on every `publish()`
  (i.e. on every action and observation). The summary is now computed once in `begin` /
  `replacePlan` and stored on the context, and `publish()` reuses it.

## 3. CI Gradle caches

`.github/workflows/android-release.yml` uses `gradle/actions/setup-gradle@v4`. The PR job
reads the shared dependency/build cache but never writes it (`cache-read-only: true`) so a
PR cannot poison the cache the release job uses. The single `main` release job owns the
cache (`cache-disabled: false`, `cache-read-only: false`) and trims it with
`gradle-home-cache-cleanup`. The one-build-on-main policy is unchanged: PRs still only
compile and unit-test, and only `main` builds the APK.

## 4. Update safety

- `applicationId` is `com.jarvis.assistant` and is unchanged.
- `minSdk` is 26 and is unchanged.
- The signing block keeps its shape: a `release` signing config populated only when all four
  `JARVIS_RELEASE_*` values are present, plus the same `JARVIS_RELEASE_CERT_SHA256` check.
  No signing secret was added or changed.
- **Room:** the database is `jarvis_memory.db`, version 1. `fallbackToDestructiveMigration()`
  was removed — that call silently deleted every saved memory whenever the schema version
  changed. Schema export is now enabled (`room.schemaLocation = app/schemas`) and
  `MemoryDatabase.MIGRATIONS` is explicit (empty at version 1, which is the shipped
  baseline). Any future schema change must ship a `Migration`; the instrumented
  `MemoryDatabaseMigrationTest` exercises `createDatabase(1)` →
  `runMigrationsAndValidate(current)` and checks the saved rows survive. That test needs a
  device/emulator and is not part of the PR compile check (the PR job does compile it).
- **Stable storage keys and paths:** the app uses `SharedPreferences` only (no Jetpack
  DataStore). Preference file names (`harness_preferences`, `harness_secrets`,
  `provider_priority`, `jarvis_shell`, plus the app preferences file) and keys, the Keystore
  alias `harness-provider-key`, and the `filesDir` layouts (`runtime/`, `workspaces/`,
  `chats/`, `terminal-history/`, `android-builds/`, `setup/`, `updates/`,
  `runtime-bridge/`) are unchanged. The only new file is `project-tree-summary.json`, which
  is additive and versioned.

## 5. Why the release APK cannot be installed over an existing one

The `main` release job always runs `assembleDebug`, so the published APK is signed with the
**Android debug** certificate (`C=US, O=Android, CN=Android Debug`). The debug keystore is
created on the runner and is not cached or persisted, so **each release is signed with a
different certificate**. Verified directly from the published APKs with
`apksigner verify --print-certs`:

| Release | Signer DN | Certificate SHA-256 |
| --- | --- | --- |
| v1.14.2 | `C=US, O=Android, CN=Android Debug` | `8eb08978563a3bf0b23536df05847b60a760411b1ed4b877aabab4f4580c562b` |
| v1.14.1 | `C=US, O=Android, CN=Android Debug` | `045b807c45fa67a6a8f3fef3e3eea7efa081fa7b92b8f4eb2be2ae655ea51c8b` |
| v1.14.0 | `C=US, O=Android, CN=Android Debug` | `ba7c890edc7e205651e0e5929bc3ec5944977504ffc98436ac386b08189da8cb` |
| v1.13.0 | `C=US, O=Android, CN=Android Debug` | `76e169f822275f8d44c062dd5ca99f9f2bb537b88827593c4b1d68d8d4955e50` |

Because the signing certificate differs between releases, Android refuses to replace the
installed app (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). **One uninstall is required before
installing a new release**, and that uninstall deletes the app-private data (the Linux
runtime, npm/pip caches and saved memories). After that first uninstall the caches are
rebuilt once.

To make future updates install over the old version, the maintainer must publish every
release with one stable key — add the four `JARVIS_RELEASE_*` secrets plus
`JARVIS_RELEASE_CERT_SHA256` and build the signed `release` variant instead of
`assembleDebug`. That is a signing decision and was intentionally not made in this change.
