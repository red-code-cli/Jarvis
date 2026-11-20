# Jarvis Release and Build Rules

These rules are mandatory for every coding agent working in this repository.

## 1. One release, one APK build

- All requested changes must ship together in **one GitHub Release**.
- Build the APK **exactly once**, only after the final PR is merged into `main`.
- The one APK build must upload the APK directly to the GitHub Release and include a checksum.
- Never build an APK separately for a feature branch, PR, tag, retry, or intermediate change.
- Never create piecemeal releases or multiple APK variants for the same requested change.
- If the release build fails, fix the workflow or source first; do not start parallel or duplicate APK builds.

## 2. Allowed CI work before release

- PR and feature-branch workflows may run unit tests, lint, static checks, and compilation checks.
- Pre-release workflows must **not** run `assembleDebug`, `assembleRelease`, or any APK-producing task.
- Release APK packaging is allowed only in the single post-merge `main` release job.
- Do not manually rerun a workflow while an equivalent run is already active.
- Cancel superseded runs before starting a replacement run.

## 3. Required release sequence

1. Make all source, test, version, and workflow changes on one feature branch.
2. Run only non-packaging CI checks on the branch/PR.
3. Merge the PR after the final checks pass.
4. Let the `main` release workflow build the APK once.
5. Let that same workflow publish the GitHub Release and attach the APK/checksum.
6. Verify the release asset and checksum; do not trigger another APK build.

## 4. Local build restriction

- Do not run Gradle builds or APK-producing commands locally.
- GitHub Actions is the only place permitted to build the APK.
- Static inspection and unit-test/source fixes are allowed locally, but they must not package an APK.

## 5. Version and tag discipline

- Bump the version once for the complete release.
- Use exactly one release tag for the complete change set.
- Do not tag intermediate commits.
- Do not create a second release for the same version.

## 6. Before any build or release action

The agent must check:

- current branch and working tree;
- active GitHub Actions runs;
- whether the requested changes are already included in the current commit;
- whether an APK has already been built for this release.

If an equivalent build is active or already completed, stop and reuse its result instead of starting another one.

## 7. Communication

Before triggering or merging release work, tell the user clearly:

> Is release ke liye APK sirf ek baar build hoga, GitHub Actions par, aur wahi APK direct GitHub Release me jayega.

Report the final workflow URL, release URL, APK asset name, checksum, and any signing limitation.
