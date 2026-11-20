# Wake word service (standby architecture)

Jarvis keeps listening for its wake word with a foreground service that holds the microphone.
This document describes what actually runs while Jarvis is idle, how the wake word is tuned and
configured, and which Android 14+ rules constrain the design.

## 1. Two states, one microphone foreground service

| | Standby ("idle") | Active session |
|---|---|---|
| Microphone owner | `SherpaWakeEngine` (KWS + tiny VAD) | `AudioRecorder` (Gemini Live) |
| Gemini Live WebSocket | not connected | connected |
| Speaker model (`SherpaVoiceprint`) | pre-warmed after entering standby, released after 30 min of uninterrupted standby | loaded on the first wake verification |
| Wi-Fi low-latency lock | released | held |
| Harness run/UI (`HarnessRunService`, log sheet) | not created | created on demand |
| Wake models on disk | loaded read-only by KWS | same files, no extra copy |
| Maintenance poll | every 5 s | every 900 ms |

The service that owns the mic is `JarvisConversationService` (declared with
`foregroundServiceType="microphone|mediaPlayback|mediaProjection"`). In standby it runs only the
detector: sherpa-onnx keyword spotting over 20 ms frames plus an energy VAD over the same frames.
The detector's microphone and native decode run on a single daemon thread; a keyword hit is
dispatched to a separate callback thread, so heavy work on the wake path (owner verification,
persona switch, session hand-off) can never stall audio capture.
Everything else is released:

* `enterStandby()` → `stopActiveConversation()` closes the live session and its `AudioRecorder`.
* `releaseStandbyHeavyResources()` drops the low-latency Wi-Fi lock immediately, pre-warms the
  speaker model on a background thread when owner verification is enabled, and schedules
  `OwnerVoiceManager.releaseSpeakerModel()` after `SPEAKER_MODEL_IDLE_RELEASE_MS` (30 min) of
  uninterrupted standby, so the first wake after going idle is instant but a phone that has been
  listening for hours is not holding the speaker model.
* The 30-minute inactivity timeout (`INACTIVITY_TIMEOUT_MS`) is what unloads the session after
  the user simply walks away.

### Why there is no separate "small" microphone service

A dedicated mini-service that only hosts the detector is **not** possible on `targetSdk = 34`
without breaking wake-on-Android-14. See §4: Android 14+ throws
`SecurityException`/`ForegroundServiceStartNotAllowedException` when a foreground service of type
`microphone` is *created* while the app is in the background. The wake word is normally heard
while the app is in the background, so the transition "small mic service → conversation service"
would need to create a second microphone foreground service from the background and would fail.
The microphone therefore stays in the one foreground service that was created while the app was
user-visible, and standby is made lightweight *inside* it. Class names and call sites are
unchanged (`startWakeListening`, `onWakeKeyword`, `enterStandby`, `stopWakeEngine`).

## 2. Wake sensitivity → detector thresholds

`audio/WakeSensitivity.kt` maps the Settings value `wakeSensitivity` to the sherpa-onnx knobs and
to the VAD floor. The value is read when the detector is built; the 4 s watchdog compares it with
the running engine and rebuilds the detector when it changes, so a change in Settings applies
without restarting the app.

| Setting (`wakeSensitivity`) | `keywordsScore` | `keywordsThreshold` | VAD `minRms` | VAD margin |
|---|---|---|---|---|
| `high` – High (Far-field) | 1.9 | 0.18 | 30 | 2.2 |
| `balanced` – Balanced | 1.5 | 0.28 | 52 | 2.8 |
| `low` – Low (Strict) | 1.1 | 0.45 | 88 | 3.4 |

`keywordsScore` is the keyword boosting score (higher = easier) and `keywordsThreshold` is the
posterior a hit must clear (lower = easier). The VAD accepts a hit only when the peak energy of the
last 500 ms is above both an absolute floor and `noiseFloor * margin`, where `noiseFloor` follows
quiet quickly and climbs slowly, capped at 600 RMS so a loud room cannot push speech below the bar.
The peak window is used because the spotter reports the hit after its trailing blanks, i.e. possibly
on a frame that is already silent; it is deliberately short (500 ms) so a loud transient from
earlier in the room cannot fake the speech energy for a later keyword hit.

**Noise filter.** When **Settings → Noise filter** is on, the standby detector attaches the platform
`NoiseSuppressor` to its capture session (just like `AudioRecorder` does for the active session), so
the same "ignore TV/chatter" intent applies while idle too. The 4 s watchdog rebuilds the detector
when the setting flips.

**Owner verification is separate.** `AppPreferences.ownerVoiceThreshold` (cosine distance for the
CAM++ speaker embedding) is the only knob for *who* may wake Jarvis and is never derived from
`wakeSensitivity`. Sensitivity decides how easily the phrase is heard; the owner threshold decides
whether the utterance belongs to the enrolled owner.

## 3. Persona wake words

Each wake phrase is pinned to one persona in `JarvisConversationService.WAKE_KEYWORDS`
(BPE tokens generated with the pinned KWS model, plus the `@display` name):

| Phrase | Persona |
|---|---|
| "Jarvis" | `PERSONALITY_ASSISTANT` |
| "Isha" | `PERSONALITY_GIRLFRIEND` |
| "Virus" | `PERSONALITY_VIRUS` |

Configurable in **Settings → Wake words** (multi-select) or with the harness setting
`wake_words = "jarvis,isha"`. Disabling a phrase removes it from the keyword spotter
(`enabledWakeKeywords()`), so that name can no longer switch personas at all. Call-control
("pick"/"cut"), emergency ("code red"/"emergency") and guard ("table guard"/"guard on|off") phrases
are always armed because they are safety features. The persona switch itself still happens only
after owner verification, inside `onWakeWordDetected()`.

## 4. Android 14+ microphone foreground service rules (limitations)

These are the constraints the code follows today, from the Android developer documentation
("Restrictions on starting a foreground service from the background"):

1. **Type + permission are mandatory.** `foregroundServiceType="microphone"` in the manifest,
   `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MICROPHONE` permissions, and `RECORD_AUDIO` held at
   the moment `startForeground()` runs — otherwise `SecurityException`.
2. **A visible activity is required to *create* a microphone FGS.** On Android 14+ the app must
   call `startForegroundService()` while it has a visible activity, unless one of the documented
   exemptions applies (system component, notification/widget interaction, `PendingIntent` from a
   visible app, exact alarm, etc.). If you start one from the background the system throws and
   logs: `Foreground service started from background can not have location/camera/microphone
   access`. **This is the reason the wake detector cannot live in a second, separate mic service**
   (it would have to be created from the background on every wake).
3. **The mic must be released before the session starts.** Android allows one active `AudioRecord`
   per app; the wake engine is stopped and joined before `AudioRecorder` starts. The hand-off is
   event-driven: `onWakeWordDetected()` runs on the engine's callback thread, so `stopWakeEngine()`
   actually joins the microphone worker and returns only once `AudioRecord` is released — there is
   no fixed delay before the active mic opens.
4. **Notification is mandatory** while the FGS runs. On Android 13+ the notification is only
   *visible* if `POST_NOTIFICATIONS` is granted; without it the service still runs and Android shows
   its own "running in background" hint.
5. **The app may not be started with a mic FGS from `BOOT_COMPLETED`.** Android 15 restricts which
   FGS types a boot receiver may start (microphone is not on the list). This app therefore does not
   start the mic from `AccessibilityBootReceiver`; the accessibility service connection and the app
   UI are the eligible triggers.
6. **Doze / battery managers can still stop listening.**
   * Doze defers network, jobs and alarms; a foreground service with a partial wake lock normally
     keeps recording, but the CPU can be throttled on some OEM builds.
   * OEM battery managers (Funtouch/OriginOS, MIUI, One UI, ColorOS) kill or freeze background
     foreground services regardless of the API contract. Mitigations already in the app: the
     accessibility service keeps the process important, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is
     declared and offered in the permission flow, and the watchdog restarts the detector within
     ~4 s once the process is scheduled again.
   * "Smart battery saver" pauses the detector below 15 % battery and resumes on charger/`BATTERY_OKAY`.
   * Android 15's 6-hour foreground-service timeout applies to `dataSync`/`mediaProcessing`, not to
     `microphone`; the wake detector is not time-limited by it.
7. **The microphone is shared with the rest of the phone.** During a call (`CallStateHelper.isCallOffhook()`),
   or while another app holds the mic, `AudioRecord` fails or returns silence. The engine retries
   forever with backoff and the watchdog re-arms it when the call ends.
8. **Privacy indicators.** While the detector runs, Android shows the mic indicator. That is
   expected: standby listening is a foreground, user-visible function, never hidden recording.

## 5. Operational notes

* The detector is considered stalled if no audio frame arrived for 15 s; it is stopped and rebuilt.
* Wake-model download (`WakeModelDownloader.ensureModels`) runs with a 30 s retry backoff and never
  blocks the session start path.
* Logcat tags: `SherpaWakeEngine`, `JarvisService` (`Wake keyword matched`, `Standby: released
  speaker model…`), `OwnerVoiceManager`.