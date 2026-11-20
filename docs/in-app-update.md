# In-App Auto Update (Force Update) — Developer Guide

Ye guide batata hai ki Jarvis ka in-app update system kaise kaam karta hai aur
naya APK release karte waqt kya karna hai. Poora text Hinglish me hai.

---

## 1. System ek nazar me

- App start/resume par, har 6 ghante WorkManager se, aur Settings → Updates ke
  **Check now** button se app ek HTTPS `update.json` padhti hai.
- `update.json` me naya `latestVersionCode`, `apkUrl`, `apkSha256`, `mandatory`
  jaise fields hote hain.
- Agar `latestVersionCode` installed se bada ho → **optional** update (Later ka option).
- Agar `mandatory: true` ho **ya** installed `minSupportedVersionCode` se kam ho →
  **force** update: full-screen blocking screen, chat/orb/wake se session start band.
- Download → SHA-256 + package name + signer verify → install. Verify fail ho to
  file delete, install nahi.
- **Fail-open:** server down / JSON galat ho to user lock nahi hota (app chalti rehti hai).

### Kya block hota hai / kya nahi (force update me)

| Feature | Force update me |
|---|---|
| Wake word se assistant session, orb, chat, automation | Band (block) |
| Emergency SOS "Code Red" | **Chalta rehta hai** (jaan-boojh kar) |
| Call Pick/Cut, Guard phrases | Chalte rehte hain |
| Settings, Updates screen | Chalte rehte hain |

> SOS ko block nahi kiya gaya kyunki emergency me user ko fasna nahi chahiye.
> Ye default hai; badalna ho to `JarvisConversationService.onWakeWordDetected()`
> ka force gate dekh lena.

---

## 2. Update source (koi backend nahi chahiye)

App ek hi jagah se URL padhti hai:

```
app/build.gradle.kts → buildConfigField("String", "UPDATE_MANIFEST_URL", ...)
Default: https://github.com/red-code-cli/Jarvis/releases/latest/download/update.json
```

Naya URL chahiye to sirf yahi `buildConfigField` badlo. Code me URL kahin aur
hardcoded nahi hai. **Sirf HTTPS** chalta hai; HTTP ya redirect-to-HTTP reject hota hai.

### update.json format

```json
{
  "latestVersionCode": 162,
  "latestVersionName": "1.16.0",
  "minSupportedVersionCode": 160,
  "apkUrl": "https://github.com/red-code-cli/Jarvis/releases/download/v1.16.0/jarvis-v1.16.0.apk",
  "apkSha256": "<64 hex chars, lowercase>",
  "apkSizeBytes": 12345678,
  "releaseNotes": "Kya naya hai: ... (Hinglish)",
  "mandatory": false,
  "publishedAt": "2026-11-20T06:00:00Z"
}
```

- `mandatory: true` → force update (Later nahi dikhega).
- `minSupportedVersionCode` → isse purane version force update honge.
- Agar `latestVersionCode <= installed` → app kuch nahi dikhati (downgrade block).

---

## 3. Naya release kaise karo (step by step)

1. **versionCode badhao** `app/build.gradle.kts` me (`versionCode` +1, `versionName`
   jaise `1.16.0`). Same release ke liye ek hi baar.
2. **Same keystore se sign karo** — `app/keystore/jarvis-release.jks` (already repo me).
   Alag key se sign kiya APK install nahi hoga (signer mismatch → verify fail).
   Release APK GitHub Actions `assembleRelease` se banega.
3. APK build hone ke baad us APK ka **SHA-256** nikaalo:

   ```bash
   # Linux / macOS
   scripts/make_update_json.sh <apk> <versionCode> <versionName> <apkUrl> \
       [minSupportedVersionCode] [mandatory:true|false] [notes.txt] update.json
   ```

   ```powershell
   # Windows
   ./scripts/make_update_json.ps1 -Apk <apk> -LatestVersionCode 162 -LatestVersionName 1.16.0 `
       -ApkUrl https://... -MinSupportedVersionCode 160 -Mandatory:$false -ReleaseNotesFile notes.txt
   ```

   Ye script hash + size nikaal kar poora `update.json` bana deti hai.

4. APK ko GitHub Release me upload karo (tag `v<versionName>`).
5. `update.json` ko bhi waheen rakho jahan `UPDATE_MANIFEST_URL` point karta hai
   (default repo ke "latest release" ka `update.json` asset).
6. `mandatory` ko sach me force karna ho tabhi `true` karo.
7. Users ko phone me "Update available" dikhega; force ho to blocking screen.

---

## 4. Zaroori baatein

- **Pehla APK manually dena padega.** Ye in-app update feature tab kaam karega jab
  user ke paas yeh feature wala build pehle se installed ho. Us se purane build
  (bina feature) ko yeh update automatic nahi milega — unhe ek baar manually APK dena hoga.
- **Keystore na badlo.** Warna update install hi nahi hoga (signer mismatch).
- **HTTP allowed nahi.** Sirf HTTPS.
- **Koi secret JSON/code me nahi.** `update.json` public ho sakta hai (usme koi key nahi).
- **Download location:** app ka internal `filesDir/updates/` (world-readable nahi).

---

## 5. Optional: FCM "instant push" (abhi skip kiya gaya)

Abhi project me `google-services.json` **nahi** hai, isliye FCM support **skip** kiya
gaya. Feature FCM ke bina bhi poora kaam karta hai (start/resume + 6h WorkManager + Check now).

FCM chahiye to setup:

1. Firebase console me project banao, Android app add karo (package `com.jarvis.assistant`).
2. `google-services.json` `app/` folder me rakho.
3. `build.gradle.kts` me Google Services plugin + `firebase-messaging` add karo.
4. FCM topic `app-update` subscribe karo aur message aaने par `UpdateScheduler.checkOnce()` chalao.

(Tab tak kuch add nahi karna zaroori nahi. Bolo to FCM wiring bhi jod dunga.)

---

## 6. Testing / risk

- Unit tests: `app/src/test/java/com/jarvis/assistant/update/` (manifest parse,
  decision table, hash). CI PR job me chalte hain.
- On-device manually check karo: download progress, "Install unknown apps" permission
  flow, force block, SOS force me bhi chalna, Android 8 se 14.
