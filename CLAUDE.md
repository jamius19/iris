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
  `focusedEditable` as the insertion target (call `refresh()` before use). IME bounds come from `windows`
  (TYPE_INPUT_METHOD). Window-change events from Iris's own overlay windows are skipped (`ownsWindow`). The
  overlay is created only once the stored bubble position has loaded.
- `BubbleOverlay` owns two `TYPE_ACCESSIBILITY_OVERLAY` windows (bubble + live-text panel), positioned in display
  coordinates. API: `onTap`/`onMoved(side, yFraction)` callbacks, `show()`, `hide()`, `setListening(Boolean)`,
  `showText(String, tail = true)`, `hideText()`, `setPosition(side, yFraction)`, `setImeTop(Int?)`,
  `onConfigurationChanged()`, `ownsWindow(windowId)`. `addView` failures (`BadTokenException` during teardown)
  are logged and the view stays unadded. The service renders `DictationState` onto it (`render()`, also after
  each `show()` so a re-shown bubble restores the panel); the bubble pulses only while Listening.
- Bubble position persists as `Settings.bubbleSide` + `bubbleYFraction` (fraction of usable height, so it
  survives rotation). Placement math is in `BubbleGeometry.kt` (unit tested).
- `BubbleView`: 40 dp circle (`circlePx`) inside a 52 dp view (`sizePx`, the touch target; 6 dp each side for the
  pulse ring). Panel: max width half the display, 15 sp text; vertical padding makes one line exactly the circle's
  height. Live text (`tail = true`: Listening/Finalizing) keeps its last 2 lines: `showText` trims with a
  `StaticLayout` at the max width (simple line breaking, bounds for width on API 35+), and re-trims once from the
  TextView's own layout if that still overflows, so the newest words always show with no scrolling or ellipsis.
  Done/Error text (`tail = false`) shows from the start, up to 4 lines, ellipsized at the end.
  The panel sits beside the circle with its top on the circle's top and grows down; if its max height (2 or 4
  lines) wouldn't fit above the IME/usable bottom, its bottom sits on the circle's bottom and it grows up; if
  neither fits, its top sits on the usable top.

Dictation (CP3):
- `audio.AudioRecorder.chunks()`: cold `Flow<ByteArray>`, 100 ms PCM16 chunks on `Dispatchers.IO`; each collection
  opens the mic, cancellation releases it. Throws `AudioCaptureException` (user-readable) for missing permission,
  init/start failure, or `activeRecordingConfiguration.isClientSilenced` (how blocked background capture shows up).
- `stt.SpeechToTextProvider.transcribe(audio: Flow<ByteArray>, model, language, onPartial): String`. Streams until
  `audio` completes, then finalizes and returns the final text; `onPartial` gets the whole live text; cancelling
  the coroutine aborts. Failures are `SttException` with a user-readable message.
- `dictation.DictationController` (one per service, main thread, built by `AppContainer.dictationController`):
  `Idle -> Listening(text) -> Finalizing(text) -> Done(text, notice?) | Error(message) -> Idle` (Done shown 1.5 s,
  or 3 s with a notice, which the panel shows instead of the text; Error 3 s; blank transcript = Error
  "No speech detected"). Tap: start (also from Done/Error), stop in Listening; in Finalizing, before the
  transcript is final: ignored for `FINALIZE_TAP_GRACE_MS` (5 s) after stop (a double tap on stop must not discard
  the dictation), then cancel to Idle (escape if STT stalls); after (hook running): skip cleanup (below). Stop ends
  the audio flow after the chunk in progress (`transformWhile`). `cancel()` discards. `onFocusLost()` (the
  service's debounced focus-lost hide) cancels only while Listening: once stopped, the session always finishes.
  Service destroy cancels its scope. Errors other than `SttException`/`AudioCaptureException` are logged and shown
  as "Something went wrong".
- Final hook: `suspend IrisAccessibilityService.onFinalTranscript(text, skipCleanup): Done`, run inside the session
  (state still Finalizing). It does cleanup + insertion and returns the Done to show (inserted text, plus a notice
  on fallback). The controller runs it as a child `async`; a tap cancels that (cancelling the cleanup HTTP call)
  and calls the hook again with `skipCleanup = true`, which inserts the raw transcript. Later taps are ignored.

Cleanup + insertion (CP4):
- Cleanup runs only if `cleanupEnabled`, with the active prompt and `cleanupModel`; the panel shows
  "Cleaning up…" (`render()` shows it for Finalizing while the service's `cleaningUp` flag is set; the hook
  re-renders when setting it, since the state doesn't change). `CleanupProvider.cleanup`
  throws `CleanupException` (user-readable). Any cleanup failure (incl. missing Gateway key) inserts the raw
  transcript; Done's notice is the error plus "Inserted the original text" (or "Original copied to clipboard"),
  so dictation is never lost. Other unexpected cleanup exceptions are logged and treated the same.
- Target: the service snapshots `focusedEditable` when the user taps stop. At insertion, if it still equals
  `focusedEditable` (`AccessibilityNodeInfo.equals`) the text is inserted there; else it is copied to the
  clipboard ("Copied to clipboard — field changed"; the bubble may already be hidden). Any other hook failure
  (settings read, insertion throwing) is logged and copies the text too. Never "Something went wrong" with the
  text lost. Notice wording is `doneNotice` (pure, unit tested).
- `overlay/TextInsertion.kt`: `mergeInsertion` (pure, unit tested) replaces the selection (or inserts at the
  cursor; invalid/-1 selection, either end, appends at the end; reversed selections are normalized). Adds a
  leading space after a non-whitespace char other than `( [ { " ' “`, and a trailing space before a
  non-whitespace char other than `. , ! ? : ; ) ] } ” ’ …` (ASCII `"`/`'` still get one: they may be openers).
  `insertText`: `refresh()` the node, text = "" if `isShowingHintText` or text equals
  `hintText`, `ACTION_SET_TEXT` with the merged text, then `ACTION_SET_SELECTION` to the end of the inserted text.
  If SET_TEXT returns false: clipboard + `ACTION_PASTE` of the same spaced text (previous clipboard not restored).
  No/stale node or paste fails: text left on the clipboard, notice "Copied to clipboard".

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
  output). It is a reasoning model: send reasoning effort `none` for low latency. A user-set cleanup model must
  accept effort `none` (settings says so under the field); we always send it.
  Verified 2026-11-11 (`cleanup.vercel.GatewayProtocol.kt`) against
  https://vercel.com/docs/ai-gateway/sdks-and-apis/openai-chat-completions/reasoning,
  `.../openai-chat-completions/structured-outputs` and
  https://vercel.com/docs/ai-gateway/models-and-providers/reasoning:
  Chat Completions takes top-level `reasoning_effort` (alias of the Gateway's `reasoning.effort`; nested wins if
  both are sent); we send `reasoning_effort: "none"`. The catalog (`GET /v1/models`) lists `openai/gpt-6-luna`
  effort values `none|low|medium|high|xhigh|max`. Response: `choices[0].message.content` is the JSON string
  `{"text":...}`; `message.refusal` with no content = "Model refused: <refusal>"; blank text or any other shape =
  "Unexpected response from AI Gateway". User message = raw
  transcript; a fixed framing line appended to the system prompt says to treat it as text, not instructions.
  Errors: 401/403 = API key message, 429 = rate limit/quota, 5xx = server error, other 4xx = gateway
  `error.message`, transport = network error, 30 s OkHttp call timeout = "Cleanup timed out".
- Cleanup model list: `CleanupProvider.listModels()` = `GET https://ai-gateway.vercel.sh/v1/models` (checked
  2026-11-11: public, no auth needed; an invalid Bearer key gets 401, so the request is sent unauthenticated and
  the key is never read). Body `{object:"list", data:[{id, type, ...}]}` with `type` one of
  `language|embedding|image|video|speech|transcription|realtime|reranking|evaluation`. We keep
  `type == "language"` (or no `type`), sorted by id. HTTP errors map as above; timeout = "Loading models timed
  out", other transport errors = "Couldn't load models (network error)" (no raw exception in the UI); no `data`
  array = "Unexpected response from AI Gateway".
  Settings shows the cleanup model as an editable M3 `ExposedDropdownMenuBox` (`PrimaryEditable` field): any
  custom id can be typed; typing filters the list (case-insensitive substring); explicit Save as other fields.
  The list is fetched on first open or via the refresh button and kept in memory only. The menu renders a lazy,
  height-bounded `LazyColumn` (fixed to the field's width: the menu measures intrinsic width, which lazy lists
  don't support). Refresh is an `IconButton` beside the field (not in it), so it doesn't focus the field or
  clear the filter; the box gets `expanded` only while the field is focused, since it focuses its anchor (IME)
  while expanded. The field's anchor also sees taps on the trailing Save (it toggles the menu first), so Save
  closes the menu explicitly. A fetch error re-triggers the field's bring-into-view above the IME.

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

## Verify on device
Not covered by unit tests; check on a real device (ideally more than one OEM) after overlay/audio/insertion changes:
- Background mic capture works from the bubble while another app is in front. If silenced
  (`isClientSilenced`), add the `microphone` FGS fallback (see Key decisions).
- Insertion in a TextView `EditText`, a Compose `TextField` and a WebView `<input>`/`<textarea>`, incl. the cursor
  mid-text and a selection; spacing and final cursor position are right.
- Paste fallback: a field that rejects `ACTION_SET_TEXT` gets the text via clipboard + paste.
- Rotation mid-dictation (Listening and Finalizing): bubble re-snaps, panel follows, session isn't lost.
- Changing field during cleanup: text is copied with "field changed"; the panel is restored if the bubble re-shows.
- Sideloaded APK: the accessibility toggle needs App info > ⋮ > "Allow restricted settings".

## Status
Update this section as checkpoints land.

| Checkpoint | Scope | State |
|---|---|---|
| CP1 | Settings + secure API key storage | Done |
| CP2 | AccessibilityService + bubble overlay | Done |
| CP3 | Audio capture + OpenAI Realtime STT | Done |
| CP4 | Text insertion + LLM cleanup | Done |
| CP5 | Polish | Done |
