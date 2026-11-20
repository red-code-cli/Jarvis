# Built-in coding-agent catalog (Section 6)

This document records the *evaluation* behind the built-in agent catalog. It exists so
the decision is explicit and reviewable instead of folklore. The runtime catalog itself
is `AgentKind` in `harness-core/src/main/java/com/jarvis/harness/model/Models.kt`.

## What the runtime can host

Everything below runs inside the app's private PRoot sandbox:

- Ubuntu 20.04.5 rootfs, **aarch64 (64-bit ARM) only** — `supportsArm64Runtime(...)`
  hides the Coding Engine card on every other device.
- Node.js **v24.19.0** + npm 11 (`/usr/local/bin/node`).
- Git, plus the on-demand Python 3.8 / Android SDK 36 / C-C++ / PHP stacks.
- Network access from inside the guest; agents are fetched only on a user tap
  ("Install") or an explicit voice "install karo". There is **no auto-install**.

An agent is only added when it (a) publishes an official 64-bit ARM Linux build and
(b) can be driven headlessly. A CLI that needs a GUI, a desktop session, a Linux kernel
feature PRoot cannot provide, or a client secret we may not reuse is rejected.

## Verdicts

| Agent | Runs on ARM64 Linux? | proot-safe? | Verdict | Reason |
|---|---|---|---|---|
| **Claude Code** | Yes — official self-contained `linux-arm64` binary | Yes | **In catalog** | Already shipped as a bundled overlay; verified by the installer (`claude --version`). |
| **DeepSeek Harness (dsh)** | Yes — npm `@deepseek-ai/dsh` (Node) | Yes (with a documented hard-link patch) | **In catalog** | Already shipped; pinned to an Android-validated release. |
| **Antigravity CLI (agy)** | Yes — official `cli_linux_arm64` tarball | Yes | **In catalog** | Already shipped; Google OAuth is the official agy flow (see below). |
| OpenCode | Yes — npm `opencode-ai` pulls `opencode-linux-arm64` (`libc` field also ships a musl build) | Unverified — native Bun binary **and** a shared background service/daemon it starts by default | **Rejected (for now)** | Distribution exists, but a native binary's glibc floor is unknown against the 20.04 rootfs, and its default shared-server daemon has no verified behaviour under PRoot. Cannot justify shipping unverified. |
| Gemini CLI | Yes — npm `@google/gemini-cli`, pure JavaScript, `engines.node >= 20` | Yes (pure JS; optional `node-pty` is a soft dep) | **Rejected (for now)** — distribution-viable | Would run, but every provider is **Google** (Gemini API key or Google sign-in). The app has no Google provider kind, so adding it means new provider plumbing + a second Google OAuth controller. Out of scope for "glue only". |
| Qwen Code | Yes — npm `@qwen-code/qwen-code`, pure JavaScript, `engines.node >= 22` | Yes (pure JS) | **Rejected (for now)** — distribution-viable | Would run with an OpenAI/Anthropic-compatible endpoint, but its headless env contract (`OPENAI_*` / Qwen OAuth) does not match `RuntimeLaunchConfigBuilder`, and it needs a new `RuntimeBridge`. Out of scope for "glue only". |
| Codex CLI | Partial — npm `@openai/codex` pulls `@openai/codex-linux-arm64` | **No** | **Rejected** | Native Rust binary that sandboxes with **Landlock + seccomp**, which PRoot cannot honour; the arm64 build also targets a newer glibc than Ubuntu 20.04. It only limps with sandbox fully disabled, which we will not ship. |
| Aider | Yes — PyPI `aider-chat` (Python) | Yes in principle | **Rejected** | Needs the full Python stack plus a large dependency tree installed inside the guest (unverified here), and duplicates API-key provider support the catalog already has. |
| Goose (Block) | Yes — Rust binary from GitHub releases | Unverified | **Rejected** | No official npm package (`npm goose` is an unrelated Go tool), and the CLI/desktop split has no documented Linux-ARM PRoot path. |

## Why nothing new was added in this change

The rule for this section was: *verify an agent actually runs in the PRoot environment
before adding it, and do not silently include one that cannot be verified.*

That verification cannot be done from this environment (no device, and local builds are
forbidden), and every candidate that is otherwise viable still needs a **new
`RuntimeBridge`** — i.e. real harness-engine work, which this change is explicitly not
allowed to do. Adding an enum entry forces a registered driver **and** a working bridge,
so a half-added agent would either crash `AgentRegistry` at startup or silently fail.

The two Node-JS agents (Gemini CLI, Qwen Code) are the recommended next additions. Each
needs: an npm install/update/remove path, a print-mode (`-p`) `RuntimeBridge`, and a
provider mapping (Gemini: a new Google provider kind + OAuth; Qwen: an OpenAI-compatible
env builder). That is a dedicated change, not a catalog edit.

## Antigravity Google sign-in — the official flow only

`AntigravityAuthController` does **not** implement OAuth itself. It drives the official
`agy` binary's own interactive login in a real PTY and forwards exactly what a terminal
would (see its class comment: *"Drives only agy's official interactive OAuth flow; it
never reads the resulting credentials."*). Concretely:

1. The runtime is started with `SSH_CONNECTION` set, which makes agy pick its
   **manual browser URL + one-time code** flow instead of trying to open a browser.
2. The controller parses the `https://accounts.google.com/...` authorization URL agy
   prints and hands it to the UI to open in the user's browser / Custom Tab.
3. The user pastes the one-time code back; the controller types it into agy's PTY.
4. agy writes its **own** credential file
   (`root/.gemini/antigravity-cli/antigravity-oauth-token`). The app never reads,
   copies, logs, or transmits it. `logout` simply deletes that file.
5. Sign-in is confirmed by running `agy models` headlessly (the same way a task uses it).

No third-party client secret or token is extracted or reused, and no OAuth client is
created to impersonate Google's. The localhost-redirect variant (`http://localhost:<port>`)
is *not* used because agy's SSH-detected flow is the one Google's official CLI ships and
it produces a user-visible code the app can relay without binding a loopback port inside
PRoot.

### Terms-of-service note

Driving Google's sign-in inside a re-hosted environment is the official binary's own
supported mechanism, but Google's Universal Terms and the Antigravity/Gemini CLI terms
apply to the *account owner*, not to this app. Distributing an APK that signs a user into
Google on their behalf can be read as automation of Google's sign-in. The user signs in
with their own account and can sign out at any time; the app stores nothing itself. If
this app is ever published to a store, re-check Google's then-current OAuth and
Antigravity/Gemini CLI terms before shipping.
