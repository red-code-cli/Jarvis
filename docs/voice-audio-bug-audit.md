# Voice & Audio bug report — verification and fixes (2026-11-14)

Branch: `fix/voice-and-audio-bugs`. Every claim in `bug.md` was verified against the
source before any change.

> **Important:** while this branch was in progress, PR **#12** ("comprehensive voice
> protocol, latency, AEC and system fixes") was merged into `main` and had already
> addressed several of the same bugs. This branch was rebased onto that `main`. Where
> PR #12's fix is correct it is kept as-is (no duplicate/parallel re-implementation);
> this PR only adds what was still missing. The table below states the final state of
> `main` after this PR merges.

## Summary table

| Bug | Verdict | Final state |
|-----|---------|-------------|
| 1.1 `tool_response` snake_case | **FALSE** | `tool_response` / `function_responses` are the original protobuf field names; protobuf JSON accepts both the proto name and lowerCamelCase. No change needed from me. (PR #12 converted them to camelCase, which is equally valid.) |
| 1.2 `client_content` / `turn_complete` | **FALSE** | Same — valid proto field names. |
| 1.3 setup snake_case params | **FALSE** | Every key is a valid proto field name. |
| 2.1 early-verification deadlock | **REAL** | **Fixed here.** All three call sites now share one documented minimum: `SherpaVoiceprint.MIN_BYTES` = 0.3 s (9 600 B), public, aligned to the 20 ms frame size. PR #12 had lowered the private limit to 0.25 s but left the call sites at their own numbers. |
| 2.2 short commands block tools | **REAL** | **Fixed here** (same shared minimum as 2.1). |
| 2.3 wake verification wiped | **REAL** | Fixed (PR #12's `wakeVerified` retention; equivalent to the intended fix). |
| 2.4 120 s owner lease | **CONFLICT** | I did **not** restore the lease. ⚠️ See the note below — PR #12 has since reintroduced lease-based retention, which conflicts with the stated product decision. |
| 2.5 voice-gate buffer leak | **REAL** | Fixed by PR #12 (`openVoiceGate` moves held chunks into `preConnectAudioQueue` when the session is not ready, flushed on `LIVE`). |
| 3.1 `MODE_NORMAL` vs AEC | **REAL** | Fixed by PR #12 (builtin-speaker communication device + `MODE_IN_COMMUNICATION`). |
| 3.2 partial `AudioTrack.write` | **REAL** | Fixed by PR #12 (write loop until the chunk is complete). |
| 3.3 recorder release race | **REAL** | **Fixed here.** `AudioRecorder.stop()` now stops the record (unblocking native `read()`), joins the reader job with a bounded timeout, then releases. PR #12 did not touch this. |
| 4.1 enrollment duration mismatch | **REAL** | **Fixed here.** Both enrollment checks now use `SherpaVoiceprint.MIN_BYTES`. |
| 5.1 inconclusive turns dropped | **REAL** | Fixed by PR #12 (`isOwnerCheckPassed` now includes `ownerVerificationInconclusive`). |
| 5.2 turn-completion flag order | **REAL** | Fixed by PR #12 (`hasActualSpeechInThisTurn` is computed before the flag is cleared). |
| 6.1 `SmsManager.getDefault()` | **REAL** | Fixed by PR #12 (`getSystemService(SmsManager::class.java)` on API 31+ with a `getDefault()` fallback). |
| 6.2 per-frame `ByteBuffer` | **REAL** | Fixed by PR #12 (reusable staging buffer). |
| 6.3 call-status version gate | **REAL** | **Fixed here.** `PhoneCallController.status()` delegates to the existing cross-version `CallStateHelper.isCallActive()` instead of returning "unavailable" below API 31. |

## ⚠️ Bug 2.4 conflict (read this)

The instruction for this task was that the 120-second owner lease was **intentionally
removed** by an explicit product decision and must **not** be restored.

I did not write any lease-restoration code. However, PR #12 — merged into `main`
independently — **did** reintroduce lease-based retention at utterance start:

- `onInterrupted()` now keeps `isCurrentTurnOwnerVoiceVerified` while
  `(now - lastOwnerVerifiedTimestamp) < 120_000L`.
- The speech-start block does the same.

Because this arrived from `main` and is not part of my change set, I left it in place
rather than silently reverting another merged change. **If the product decision still
stands, this retention should be removed in a separate, deliberate change.**

## Unit tests

- `VoiceprintMinimumTest` — locks the shared 0.3 s (9 600 B) minimum and asserts the
  early-verify frame window (15 × 640 B) reaches it.

The partial-write loop and the voice-gate drain were already fixed by PR #12 with inline
code that has no extractable pure decision left, so no redundant test was added for them
(adding one would test code that no longer exists in a testable form).

## Notes

- `OwnerVoiceManager.extractSpeakerVector()` keeps its cheap 100 ms guard; the
  authoritative floor is now `SherpaVoiceprint.MIN_BYTES`, which it calls.
- Changing the *length* floor does not weaken the owner decision: the cosine-distance
  threshold and multi-sample matching are untouched.
