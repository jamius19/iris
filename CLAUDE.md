# Iris

Android voice dictation app (Kotlin, Jetpack Compose, single `:app` module, package `dev.jamius.iris`).
When the user focuses a text field in any app, an AccessibilityService shows a small circular bubble overlay.
Tap the bubble to start dictating. The partial transcript streams live in a panel beside the bubble (left of it
if the bubble is on the right edge, right of it if on the left edge). Tap again to stop. The transcript is
finalized, optionally cleaned up by an LLM (toggle in settings), then inserted at the cursor of the focused field.
Cloud APIs only for now. On-device models are future scope: don't build for them, but keep the provider
interfaces so a new provider is just a new implementation.

## Tech stack & build
- AGP 9.x with built-in Kotlin (no separate `kotlin-android` plugin). Kotlin 2.2.10, Compose compiler plugin,
  kotlinx.serialization plugin.
- `minSdk 30`, `targetSdk`/`compileSdk 37`, Java 11 target. JDK 25 locally.
- Compose Material3 (app UI), OkHttp (HTTP + WebSocket), kotlinx.serialization (JSON),
  Jetpack DataStore Preferences, Coroutines/Flow.
- Manual DI: `AppContainer` held by the custom `Application` class `IrisApp`. No Hilt/Koin.
- All versions live in `gradle/libs.versions.toml`.

## Architecture
Planned layout, being built in checkpoints on branch `feature/add-voice-dictation`. Check the tree for what
exists before assuming a package is there.

| Package | Responsibility |
|---|---|
| (root) | `IrisApp` (Application), `AppContainer` (manual DI), `MainActivity` (hosts settings UI) |
| `data.settings` | Settings model, `SettingsRepository` (DataStore), `SecretStore` (Keystore crypto) |
| `ui.settings` | Settings screen (Compose) |
| `audio` | PCM16 mono 24 kHz recorder emitting chunks |
| `stt` | `SpeechToTextProvider` interface; `stt.openai` realtime implementation |
| `cleanup` | `CleanupProvider` interface; `cleanup.vercel` gateway implementation |
| `dictation` | Session state machine/controller: audio -> STT -> cleanup -> insertion |
| `overlay` | AccessibilityService, bubble overlay view, live-text panel |

Overlay (`overlay`, View-based, not Compose: a Compose window in a service needs lifecycle/savedstate owners):
- `IrisAccessibilityService` re-checks `findFocus(FOCUS_INPUT)` on view-focused/window events and shows the
  bubble only for `isEditable && !isPassword` nodes outside Iris's own package. The node is kept in
  `focusedEditable` for CP4 (call `refresh()` before use). IME bounds come from `windows` (TYPE_INPUT_METHOD).
- `BubbleOverlay` owns two `TYPE_ACCESSIBILITY_OVERLAY` windows (bubble + live-text panel), positioned in display
  coordinates. API: `onTap` callback, `setListening(Boolean)`, `showText(String)`, `hideText()`. The service
  renders `DictationState` onto it (`render()`); the bubble pulses only while Listening.
- Bubble position persists as `Settings.bubbleSide` + `bubbleYFraction` (fraction of usable height, so it
  survives rotation). Placement math is in `BubbleGeometry.kt` (unit tested).

Dictation (CP3):
- `audio.AudioRecorder.chunks()`: cold `Flow<ByteArray>`, 100 ms PCM16 chunks on `Dispatchers.IO`; each collection
  opens the mic, cancellation releases it. Throws `AudioCaptureException` (user-readable) for missing permission,
  init/start failure, or `activeRecordingConfiguration.isClientSilenced` (how blocked background capture shows up).
- `stt.SpeechToTextProvider.transcribe(audio: Flow<ByteArray>, model, language, onPartial): String`. Streams until
  `audio` completes, then finalizes and returns the final text; `onPartial` gets the whole live text; cancelling
  the coroutine aborts. Failures are `SttException` with a user-readable message.
- `dictation.DictationController` (one per service, main thread, built by `AppContainer.dictationController`):
  `Idle -> Listening(text) -> Finalizing(text) -> Done(text) | Error(message) -> Idle` (Done shown 1.5 s, Error
  3 s; blank transcript = Error "No speech detected"). Tap: start (also from Done/Error), stop in Listening,
  cancel to Idle in Finalizing (escape if it stalls). Stop ends the audio flow after the chunk in progress
  (`transformWhile`). `cancel()` discards; the service calls it from the debounced focus-lost hide. Service destroy
  cancels its scope. Errors other than `SttException`/`AudioCaptureException` are logged and shown as
  "Something went wrong".
- CP4 hook: `suspend IrisAccessibilityService.onFinalTranscript(text)`, run inside the session (state still
  Finalizing) before Done, so cancel/focus loss cancels it and its exceptions end in Error. Cleanup + insertion
  go there.

Providers (settings store a provider id for each):
- STT: OpenAI Realtime API, transcription-only session over WebSocket. Audio is PCM16 mono 24 kHz, sent as
  base64 chunks via `input_audio_buffer.append`. On stop: commit the buffer, wait for the final transcript.
  Verify event names and session config against current OpenAI docs before changing protocol code.
  Verified 2026-11-11 (`stt.openai.RealtimeProtocol.kt`) against
  https://developers.openai.com/api/docs/guides/realtime-transcription and
  https://developers.openai.com/api/reference/resources/realtime/client-events (`/server-events`):
  connect `wss://api.openai.com/v1/realtime?intent=transcription` (no `model` param: that makes a conversation
  session) with only `Authorization: Bearer <key>` (no `OpenAI-Beta` header in the GA docs); send
  `session.update` `{session:{type:"transcription", audio:{input:{format:{type:"audio/pcm",rate:24000},
  transcription:{model, languages?}, turn_detection:null}}}}`. Server events used: `input_audio_buffer.committed`
  (item_id), `conversation.item.input_audio_transcription.delta` / `.completed` / `.failed`, `error` (all
  `error` events are treated as fatal). Deltas concatenate per item_id; items join with spaces in first-seen
  order; final = completed items once the committed item completes, then close 1000. The 10 s final timeout
  starts only after OkHttp's send queue drains (`queueSize() == 0`, polled; max 60 s) so slow uplinks don't
  time out.
  Default model `gpt-live-transcribe` (`gpt-4o-*-transcribe` are deprecated, shutdown 2027-02-26). It requires
  `turn_detection: null` (no server/semantic VAD) and takes `audio.input.transcription.languages: [..]` (array;
  never also send singular `language`). Settings store one optional code; the client wraps it into the array.
- Cleanup: Vercel AI Gateway, OpenAI-compatible `POST https://ai-gateway.vercel.sh/v1/chat/completions`,
  `Authorization: Bearer <key>`. Structured output via
  `response_format: {type:"json_schema", json_schema:{name, schema, strict:true}}` with schema
  `{ "text": string }`. Supports multiple saved system prompts; user picks the active one. Optional (toggle).
  Default model `openai/gpt-6-luna` (OpenAI's replacement for the deprecated 4.1/4o minis; cheap, structured
  output). It is a reasoning model: send reasoning effort `none` for low latency.

## Key decisions (don't re-litigate without a concrete reason)
- **OpenAI Realtime for STT**: Vercel AI Gateway's streaming STT protocol is undocumented and JS-SDK-only beta,
  so it was dropped for STT. The Gateway is used for cleanup only.
- **API keys are user-entered** in Settings and encrypted with an Android Keystore AES/GCM key; ciphertext is
  persisted in DataStore. Do NOT use the deprecated `androidx.security:security-crypto`.
- **Insertion at cursor via accessibility actions**: merge with existing text using `ACTION_SET_TEXT` + set
  selection; fall back to clipboard + `ACTION_PASTE`.
- **Mic capture from the AccessibilityService, no foreground service**. AOSP binds accessibility services with
  `BIND_INCLUDE_CAPABILITIES` (`AccessibilityServiceConnection.bindLocked`), so system_server's process
  capabilities, incl. `FOREGROUND_MICROPHONE`, propagate to the app while the service is enabled. AOSP
  `AudioPolicyService` also allows capture by an accessibility service with `AudioSource.VOICE_RECOGNITION`:
  CP3 must record with that source. Still unverified on real devices/OEMs. Fallback if capture is silenced:
  start a `microphone` FGS from the bubble tap. Android 17 (target 37) background-audio rules cover playback/focus.
- **Only two abstractions**: `SpeechToTextProvider` and `CleanupProvider`. No others without a reason.

## Commands
Run from repo root in Git Bash on Windows:
```
./gradlew assembleDebug testDebugUnitTest --console=plain -q
```
Both build and unit tests must pass before reporting work as done.

## Conventions
- Every new Kotlin file starts with this header (date = file creation date, `dd/MM/yyyy`):
  ```
  /*
  * Author: Jamius Siam
  * Since: 11/11/2026
  */
  ```
- 120-char line limit. When a line would exceed it, put the call args on their own indented lines with the
  closing paren on its own line:
  ```
  return ResponseEntity.ok(
          ApiResponse(true, "Project updated", ProjectWrapperResponse(project))
  )
  ```
- Kotlin official code style; match existing code over personal preference.
- Dependencies go in the version catalog (`gradle/libs.versions.toml`), never inline coordinates.
- Minimum code that solves the problem: no speculative features, no extra abstractions, no error handling for
  impossible cases. Don't touch unrelated code.

## Testing
- JUnit4 unit tests for core logic only: settings serialization, request/response building and parsing,
  protocol event handling, state machine.
- Use OkHttp MockWebServer (`mockwebserver3`) for HTTP/WebSocket tests, with `runBlocking` (real I/O; `runTest`
  virtual time would fire timeouts instantly). Controller tests use `kotlinx-coroutines-test` virtual time.
- No Robolectric, no instrumented/UI tests. Overlay and UI are verified manually on device by the user.

## Status
Update this section as checkpoints land.

| Checkpoint | Scope | State |
|---|---|---|
| CP1 | Settings + secure API key storage | Done |
| CP2 | AccessibilityService + bubble overlay | Done |
| CP3 | Audio capture + OpenAI Realtime STT | Done |
| CP4 | Text insertion + LLM cleanup | Planned |
| CP5 | Polish | Planned |
