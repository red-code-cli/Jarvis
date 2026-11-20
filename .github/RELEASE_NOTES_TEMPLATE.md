### In-app auto update (force update)
- App ke andar hi check, download, verify aur install — GitHub Release ke `update.json` se (HTTPS only).
- Optional update par "Later" milta hai; mandatory par full-screen block (wake/orb/chat/automation band).
- Emergency SOS ("Code Red"), call pick/cut aur guard phrases force-update me bhi kaam karte rahenge.
- Install se pehle SHA-256 + package + signer verify; mismatch par file delete.
- Root/Shizuku silent install (agar available) warna standard installer.

### Wake word — faster, more stable, better accuracy
- Wake hit ab audio thread par nahi, alag callback thread par (mic read loop block nahi hota).
- Fixed 220ms handoff hataya (event-driven); VAD peak window 1.2s -> 0.5s; standby par noise filter; sensitivity re-tune.
- Wake fail-open: owner voice on hai par profile/audio/extraction fail → wake phir bhi fire hota hai (kabhi block nahi).

### Voice verification & enrollment — sirf aap
- Owner voiceprint encrypted store me (AES-256 GCM/SIV); sirf owner ki awaaz se commands.
- Enrollment: 3 Hinglish phrases, quality check (duration, loudness, clipping, voiced frames) — ghatiya sample reject.
- SOS "Code Red" hamesha bypass karta hai verification (emergency).

### Barge-in — beech me bolo
- Jarvis bolte hue aap bolte ho → react karta hai, interruption owner-verified hoti hai.
- Noise/wind filter + echo floor guard: background TV/shoor se fake trigger nahi.

### Voice & phone controls (Advanced card)
- Call control: "Pick karo" / "Cut karo" (TelecomManager → Accessibility → shell fallback).
- Unread reader: naye messages padh ke sunata hai; per-app allow/deny (App-level notification control).
- Noise filter toggle: ON par TV/shoor ignore; OFF par purana audio path.

### Notifications — private & selective
- OTP/bank/password-sensitive notifications kabhi nahi suni/parhi jayengi (secure, encrypted store).
- NotificationListenerService: all apps, per-app deny, dismissed/dup/group/ongoing filtered, Doze-stable.

### Agent Live card
- Fixed-height live dashboard; RETRY / RUKO (CONTINUE) / CANCEL bottom-sheet control.
- Home layout fix: greeting aur orb ab kabhi cut nahi hote.

### Themes & orb — 34 themes, 32 orb styles
- Naya Theme picker: live preview, custom HEX + quick swatches.
- Orb style registry: holographic plasma, aurora, nebula, supernova + 28 aur (60fps, reduced-motion aware).
- Selected theme/style save hoke preserve rehta hai.

> Note: jo build pehle se installed hai usme ye update feature nahi hai, isliye **ye build (v__VERSION__) ek baar manually install karna padega**. Uske baad har naya release in-app update ho jayega.
