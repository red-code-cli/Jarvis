# Stable release signing identity

`jarvis-release.jks` is the **single, committed signing identity** for Jarvis releases.

## Why it is committed

Every release before v1.14.4 was built with `assembleDebug`, and AGP generated a fresh
`~/.android/debug.keystore` on every CI runner. Each release therefore had a different
certificate and Android refused to over-install it — users had to uninstall on every update.
Committing one keystore makes the certificate stable, so updates install straight over the
previous build.

## Certificate

```
Alias:    jarvis
SHA-256:  81:87:84:21:DF:41:E2:E0:C0:38:FA:0C:48:37:68:65:17:56:DC:DA:3C:52:0E:B8:C7:CB:89:09:EA:7B:7C:84
```

`PINNED_RELEASE_CERT_SHA256` in `app/build.gradle.kts` defaults to this fingerprint.

## Security note

Because the keystore and its passwords are in the repository, anyone can produce an APK with
the same signing identity. That is acceptable only for this project's current (sideloaded,
personal/community) distribution. For a public production release:

1. Generate a private keystore and keep it out of the repository.
2. Set the GitHub secrets `JARVIS_RELEASE_STORE_FILE`, `JARVIS_RELEASE_STORE_PASSWORD`,
   `JARVIS_RELEASE_KEY_ALIAS`, `JARVIS_RELEASE_KEY_PASSWORD` and `JARVIS_RELEASE_CERT_SHA256`.
3. Those secrets automatically override the bundled keystore (see `app/build.gradle.kts`).

## Passwords

Store/key passwords are `jarvis-release` (kept in `app/build.gradle.kts` as the bundled
defaults). This is a deliberate, documented trade-off for automatic updates without secrets.