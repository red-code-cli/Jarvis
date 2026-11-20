# Third-party notices for :harness-core

`:harness-core` is a port of the [Mobile Harness](https://github.com/Vempire-X/Mobile-Harness)
engine (Copyright © 2026 Mobile Harness Contributors), used under the MIT License.
The full MIT text is in [`LICENSE.txt`](LICENSE.txt).

The module deliberately ships **no** Linux rootfs, SDK or toolchain binaries in the APK.
Those are downloaded at runtime. What the APK does contain is the native PRoot layer,
which is built from the sources vendored under [`third_party/`](third_party):

| Component | Version / commit | License | Where |
|---|---|---|---|
| PRoot | termux/proot `61681c6` | **GPL-2.0** (see `COPYING`) | `third_party/proot` |
| libandroid-shmem | termux/libandroid-shmem `7f0bd7e` (v0.7) | BSD-3-Clause | `third_party/libandroid-shmem` |
| talloc | 2.4.3 | LGPL-3.0-or-later | `third_party/talloc` |
| zstd-jni | 1.5.7-12 | BSD-2-Clause | Maven (`com.github.luben:zstd-jni`) |
| Apache Commons Compress | 1.27.1 | Apache-2.0 | Maven |
| Claude Code mobile launcher | — | MIT | `src/main/assets/harness-licenses/claude-code-android-MIT.txt` |
| JetBrains Mono (fonts) | — | OFL-1.1 | `src/main/assets/harness-licenses/jetbrains-mono-OFL-1.1.txt` |

## GPL-2.0 obligation (important)

PRoot is statically linked into the packaged `libproot.so` / `libprootloader.so`. Any app
that distributes this module in an APK is distributing a combined work that includes PRoot,
so it must:

1. keep the PRoot source available (it is vendored in `third_party/proot`, so shipping the
   repository alongside the APK satisfies this), and
2. ship the GPL-2.0 text and attribution — the module copies the licence files into
   `assets/harness-licenses/` so they are present inside the APK automatically.

If the combined-work obligations are not acceptable for a planned distribution channel,
PRoot must be replaced or the module must not be shipped there.

Runtime components downloaded on demand (Ubuntu 20.04 rootfs, Node.js, Git, Android SDK,
Python, PHP, C/C++ toolchains and the coding agents) keep their own upstream licences and
are governed by their own terms.
