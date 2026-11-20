# JARVIS Voice Assistant

An Android voice assistant built with Kotlin, Gemini Live, on-device voice components, accessibility automation, screen capture, and a native Android UI.

## Project status

- Android application module: `app`
- Application ID: `com.jarvis.assistant`
- Minimum Android version: Android 8.0 (API 26)
- Compile and target SDK: 34
- Java/Kotlin target: 17
- Current app version: 1.16.0 (version code 162)
- Latest release: [Jarvis v1.16.0](https://github.com/red-code-cli/Jarvis/releases/tag/v1.16.0)
- Download: [`jarvis-v1.16.0.apk`](https://github.com/red-code-cli/Jarvis/releases/download/v1.16.0/jarvis-v1.16.0.apk) ([SHA-256](https://github.com/red-code-cli/Jarvis/releases/download/v1.16.0/checksums.txt))

> **v1.16.0 ek baar manually install karna padega.** Isse pehle ke builds me in-app
> updater nahi hai. Ye build install karne ke baad har naya release app ke andar hi
> download aur install ho jayega.

## Getting started

1. Open the project root in Android Studio and allow Gradle to sync.
2. Set the Android SDK path in your local `local.properties` file. The file is machine-specific and is ignored by Git.
3. Launch the app on an Android 8.0+ device or emulator.
4. Add a Gemini API key in the app's Settings and grant the requested runtime permissions.

The Gradle wrapper is included in this project. Use `gradlew.bat` on Windows or `./gradlew` on macOS/Linux.

## Build commands

```powershell
# Windows: compile and package a debug APK
.\gradlew.bat :app:assembleDebug

# Windows: compile and package a release APK
.\gradlew.bat :app:assembleRelease
```

```bash
# macOS/Linux
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

Build outputs are written under `app/build/outputs/` and are generated files, not source files. Release builds enable R8 and resource shrinking.

### Signing (stable identity — do not replace)

Every release is signed with the single stable keystore committed at
`app/keystore/jarvis-release.jks`. The same certificate must be used for every future
build: a different signer cannot over-install an existing app, and the in-app updater
rejects an APK whose signature does not match the installed one.

The release APK is built once by `.github/workflows/android-release.yml` when a version is
merged to `main` (or when the workflow is run manually). The workflow reads the optional
`JARVIS_RELEASE_*` values first and falls back to the bundled keystore:

- `JARVIS_RELEASE_STORE_BASE64` — base64 of the keystore file
- `JARVIS_RELEASE_STORE_PASSWORD`
- `JARVIS_RELEASE_KEY_ALIAS`
- `JARVIS_RELEASE_KEY_PASSWORD`
- `JARVIS_RELEASE_CERT_SHA256` — 64-hex certificate pin

Real deployment values belong in **GitHub Secrets**, never in the repository or a commit.
Do not create a second keystore and do not print or commit passwords.

The project currently has no deployed verification backend or agreed verification API
protocol. Local signer pinning and APK signatures do not provide server-side
authentication, Play Integrity attestation, or rate limiting; the app identity screen
reports remote verification as unavailable until that service is implemented and
deployed.

## Repository layout

```text
.
├── app/                         Android application module
│   └── src/main/
│       ├── java/com/jarvis/assistant/
│       │   ├── audio/           Audio capture, playback, and voice handling
│       │   ├── automation/      Accessibility, actions, and app integrations
│       │   ├── background/      Conversation foreground service
│       │   ├── data/            Preferences, persistence, and data models
│       │   ├── harness/         Adapter that connects Jarvis to :harness-core
│       │   ├── network/         Gemini Live transport
│       │   ├── ui/              Activities, fragments, and adapters
│       │   ├── update/          In-app auto update (manifest, verify, install)
│       │   ├── util/            Shared app utilities
│       │   └── vision/          Screen capture and visual task execution
│       ├── assets/              Bundled web UI and offline voice models
│       └── res/                 Android layouts, drawables, values, and config
├── harness-core/                Reusable autonomous coding engine (no UI)
│   ├── src/main/java/com/jarvis/harness/   engine + HarnessEngine facade
│   ├── src/main/cpp/                       PRoot loader and JNI spawn (arm64)
│   └── third_party/                        proot, libandroid-shmem, talloc
├── docs/
│   ├── architecture/            Design notes and research
│   ├── diagnostics/             User-provided issue recordings and index
│   └── in-app-update.md         Update publishing guide (Hinglish)
├── scripts/                     Helper scripts (update.json generator)
├── gradle/                      Gradle wrapper configuration
├── build.gradle.kts             Root Gradle plugin declarations
├── settings.gradle.kts          Project and repository configuration
└── gradle.properties             Gradle runtime settings
```

## Coding engine (voice-driven app building)

Jarvis can build apps, websites and scripts on the phone itself using the reusable
`:harness-core` module (extracted from Mobile Harness, MIT).

- **Nothing auto-installs.** The Linux runtime, toolchains and the DeepSeek Harness
  agent are downloaded only from **Settings → Coding Engine → Install**, or when you
  agree by voice ("haan install karo").
- Say *"Jarvis mere liye ek todo Android app banao jisme ye features ho…"* and Jarvis
  picks the project type, writes the brief, and runs the agent in the background.
- Voice controls: *"kahan tak bana?"*, *"ruko"*, *"resume karo"*, *"cancel karo"*,
  *"ye feature bhi add karo"*.
- Finished work is copied to `Downloads/Jarvis/<project-name>/` and Jarvis tells you
  the exact location.
- Custom model keys for the coding agent live under **Settings → Coding Engine →
  Custom API** (encrypted, separate from the Gemini Live voice key).

See [`harness-core/README.md`](harness-core/README.md) for the module's public API and
how to reuse it in another app. `harness-core/NOTICE.md` covers third-party licences
(PRoot is GPL-2.0).

## In-app auto update (force update)

Users no longer have to grab a new APK by hand: the app checks for updates itself,
shows an in-app update screen, and downloads, verifies and installs the new build
inside the app.

- **Where the check runs:** on app start/resume (throttled), a WorkManager job every
  6 hours (network-connected, battery-friendly), and a **Check now** button in
  **Settings → Updates**. The existing "What's new in Jarvis" content stays on that
  screen; the live update card is added above it.
- **Optional vs forced:** a normal update shows *Later* / *Update karo*. A manifest
  with `mandatory: true` (or an installed build older than `minSupportedVersionCode`)
  locks Jarvis — wake word, orb, chat and automation cannot start a new session — until
  the update is installed. Emergency voice SOS ("Code Red"), voice call pick/cut and
  guard phrases keep working while locked. Back button cannot bypass the force screen.
- **Security:** HTTPS only (plain HTTP and redirect-to-HTTP are rejected); the APK is
  installed only after SHA-256, package name (`com.jarvis.assistant`) and signing
  certificate all match; a hash or signer mismatch deletes the file and stops.
  Downgrades are blocked. The updater **fails open**: if the manifest is unreachable or
  invalid, the app keeps working instead of locking the user out.
- **Silent install (optional):** if Root or Shizuku is already enabled, the update is
  installed silently via `pm install`; otherwise it falls back to the standard Android
  installer (`REQUEST_INSTALL_PACKAGES`).

### Publishing an update

The release workflow does most of this automatically: when a new version is merged to
`main`, the single signed APK is built and `update.json` is generated and uploaded next to
it, so users get the update in-app.

1. Bump `versionCode` (and `versionName`) in `app/build.gradle.kts`. The APK is built once
   on merge to `main` with the **same keystore** (`app/keystore/jarvis-release.jks`) — a
   different signer cannot over-install. (The workflow auto-increments the versionCode for
   each build — `versionCodeOverride` / `versionNameOverride` — so a manual bump is only
   needed when you want a specific name to publish.)
2. The workflow generates `update.json` from the built APK (SHA-256, size, version fields)
   using [`scripts/make_update_json.sh`](scripts/make_update_json.sh) and publishes it with
   the APK. The app reads a single source of truth: `UPDATE_MANIFEST_URL` in
   `app/build.gradle.kts` (default = the latest GitHub Release's `update.json`).
3. Optional/forced is controlled by repo **variables** (Settings → Variables), no code
   change needed: `UPDATE_MANDATORY=true` forces the update, and
   `UPDATE_MIN_SUPPORTED=<versionCode>` locks any install below that code. Both default to
   optional (`false` / `0`).
4. To host the manifest differently (no CI), build the APK and run
   `scripts/make_update_json.sh <apk> <code> <name> <https apkUrl> [minSupported] [mandatory]`
   yourself, then upload both files.

### Release guide (Hinglish)

Ye release workflow khud sab kuch kar deta hai — **ek release = ek APK build**, sirf GitHub
Actions par, merge ke baad:

- **PR / branch** par sirf tests, lint, compile aur R8 check chalta hai — koi APK nahi banta.
- **`main` par merge** hote hi single signed APK build hota hai (wahi stable keystore),
  `update.json` banata hai, aur GitHub Release banata hai `v<versionName>` tag ke saath.
- Release notes me **SHA-256 checksum** aur Hinglish changelog apne-aap aa jata hai.
- **Manual re-release**: `.github/workflows/android-release.yml` me `workflow_dispatch` se
  (Actions tab → Run workflow) kisi bhi commit se release nikal sakte ho, ya `v*` tag push
  karke.
- **Keystore secret**: agar `JARVIS_RELEASE_KEYSTORE_B64` secret set hai to wahi use hota
  hai (temp file me decode → build → delete). Nahi to committed keystore.
- **Certificate pinning**: `JARVIS_RELEASE_CERT_SHA256` secret set ho to build ke baad APK
  ka certificate match kiya jata hai, mismatch par fail.
- Version tag / manual dispatch se versionName override hota hai; versionCode hamesha
  auto-increment hota hai (kabhi ghatta nahi).

**Rules (AGENTS.md)** — release se pehle check karo: current branch aur working tree clean,
koi active CI run nahi, changes already commit me hain, aur APK is release ke liye pehle se
nahi bana. APK sirf ek baar, `main` merge ke baad, GitHub Actions par banta hai. Kabhi
local Gradle/APK build nahi.

FCM "instant push" (topic `app-update`) is intentionally not wired up yet because the
project has no `google-services.json`; the feature works fully without it. See
[`docs/in-app-update.md`](docs/in-app-update.md) for the full guide (Hinglish) and the
FCM setup steps.

> Note: builds released before this feature do not check for updates, so the first
> build that includes it must be installed manually once. Every update after that can
> arrive in-app.

## Documentation

- [Documentation index](docs/README.md)
- [In-app auto update guide](docs/in-app-update.md)
- [Voice architecture research](docs/architecture/voice-architecture-research.md)
- [Voice issue recordings](docs/diagnostics/voice-videos/README.md)

## Configuration and privacy

- Keep API keys, SDK paths, signing keys, and local machine settings out of source control.
- `local.properties` is local-only; start from `local.properties.example` when setting up a new machine.
- Screen sharing and accessibility automation require explicit Android user consent and permissions.
- Bundled offline wake-word models (`app/src/main/assets/wake-models`) are runtime dependencies; do not remove or rename their directories without updating the model-loading code.
