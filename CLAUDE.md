# Iris

Android voice dictation app (Kotlin, Jetpack Compose, single `:app` module, package `dev.jamius.iris`).
When the user focuses a text field in any app, an AccessibilityService shows a small circular bubble overlay.
Tap the mic to start dictating: a red pill with a live waveform slides out below the button (above it near the
keyboard/screen bottom), which becomes ✕ (cancel). The partial transcript streams live in a panel beside the bubble
(left of it if the bubble is on the right edge, right of it if on the left edge). Tap the pill to stop. The
transcript is finalized, optionally cleaned up by an LLM (toggle in settings), then inserted at the cursor of the
focused field.
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
- `BubbleOverlay` owns two `TYPE_ACCESSIBILITY_OVERLAY` windows (bubble stack + live-text panel), positioned in
  display coordinates. API: `onButtonTap`/`onPillTap`/`onMoved(side, yFraction)` callbacks, `show()`, `hide()`,
  `setStatus(BubbleStatus)`, `setLevel(Float)`, `showText(String, tail = true)`, `hideText()`,
  `setPosition(side, yFraction)`, `setImeTop(Int?)`, `setPanelSize(lines, widthPercent)`, `onConfigurationChanged()`,
  `ownsWindow(windowId)`. `addView` failures (`BadTokenException` during teardown) are logged and the view stays
  unadded. The service renders `DictationState` onto it (`render()`, also after each `show()`).
  `BubbleStatus` per state: Idle = mic on `bubble_idle` (black circle + white icon; white + black in
  `values-night`, so it contrasts with the theme; tinted `bubble_idle_icon`); Listening = ✕ on `bubble_control`
  (white circle + black icon; black + white in `values-night`, no outline) and the pill out; Finalizing (incl. cleanup + insertion) = WORKING, a spinner in the control colours, pill
  collapsed, panel keeps the STT transcript (fixed once transcription ends); Done without notice = SUCCESS, white tick
  on `bubble_success` `#2FF740`, panel animates out (1.5 s); Done with notice or Error = FAILURE, red exclamation mark,
  panel shows the notice/message (3 s, `ERROR_DISPLAY_MS = 2 * DONE_DISPLAY_MS`). No "Listening…"/"Finishing…"
  placeholders: the panel comes in with the first words (blank text = hidden). Colours are re-read on config change
  (dark mode).
- Stack window = `FrameLayout` with `PillView` (z below) and `BubbleView`. The pill is GONE when collapsed, so the
  WRAP_CONTENT window is the button view at rest (52 x 58 dp: `sizePx` plus `shadowRoomPx`, 6 dp below the margin so
  the downward shadow isn't cut by the window edge; placement still uses `sizePx`, so the window reaches 6 dp past
  the clamp, e.g. over the IME's top edge) and 136 dp while the pill is visible (142 dp opening up). Pill: 40 x 78
  dp, radius 20, 6 dp gap below the circle; `PillView` (52 x 110 dp) starts at the circle's centre, so at `reveal` 0
  the pill's cap sits exactly on the circle's lower half and the rest is clipped by the parent. Its outline (shadow)
  is the pill's rect intersected with the view's bounds (`Outline.setPath`), so the clipped part casts no shadow
  (it showed as a grey band cut off at the window edge). Near the bottom/IME the pill opens
  up (`pillOpensUp(buttonTop, stackHeight, visibleBottom)`, unit tested): mirrored, the ✕ stays where the mic was
  (button at the window's bottom, window y = `buttonY - 84 dp`). Both overlay windows set
  `setCanPlayMoveAnimation(false)` (API 34+): otherwise the system animates the window move when the stack grows up
  (or shrinks back), sliding the re-laid-out content from the old position (the button jumped over the IME).
  Decided when it expands, re-evaluated on every
  relayout while visible (IME, rotation, drag), switched instantly. `buttonY` (the button's top) is the position;
  `yFraction` keeps its meaning. One `updateViewLayout` at expand start and collapse end, none per frame; a new
  reveal replaces a running one (listeners removed, then cancelled) and continues from its current value. While the
  pill opens up the panel grows up (`panelPlacement(growUp)`), kept after collapse so it doesn't jump on stop.
  Taps: one shared `DragHandler` (a drag past touch slop never clicks); button -> `onButtonTap`, pill (and the
  button's 6 dp margin on the pill's side, i.e. the gap) -> `onPillTap`.
- Motion (`PathInterpolator`s, private per class): icon/colour morph 250 ms standard (0.2, 0, 0, 1): icon
  crossfade + scale, ✕ rotates in from -90°; the new colour grows as a disc from the centre over the old one, full
  at t = 0.8 (`REVEAL_END`) so the old colour's last ring doesn't linger (an RGB blend of e.g. red -> white passes
  through a washed-out pink; an alpha crossfade is the same blend). A status set back mid-morph (e.g. cancel right
  after start) reverses the running morph from where it is (`reversed`); another status morphs on from whichever
  dominates. A morph never draws the same status twice (a VectorDrawable drawn twice in one frame uses its last
  alpha for both: the icon vanished). Pill slide out and back 300 ms symmetric ease-in-out (0.65, 0, 0.35, 1);
  panel in 250 ms emphasized decelerate (0.05, 0.7, 0.1, 1) / out 200 ms emphasized accelerate (0.3, 0, 0.8, 0.15)
  (alpha + scale 0.92, pivot on the button side and the panel's growth edge; alpha/scale are reset in `show()`);
  snap 150 ms. Both windows are removed with `removeViewImmediate`: on Android 13-16 the async `removeView` frees the
  hardware renderer before the already scheduled frame, which then draws in software without alpha/scale/shadows (the
  faded-out panel flashed once at full opacity). Waveform: 6 horizontal bars (6 dp round caps, max 23.5 dp, dots at
  rest; centres 14 dp from the pill's ends, 10 dp apart = 4 dp gaps; at full length the outer bars stay ~6 dp
  inside the cap curve), level smoothed per frame (attack 40 ms, release 200 ms) with a small per-bar wobble; after
  stop the bars shrink to dots as the pill retracts. Animations run only while attached; `hide()` ends only running
  animators (`end()` on a finished one replays it). The overlay keeps the desired panel state (`panelWanted`) while
  hidden, and `show()` restores stack and panel instantly (no replayed animations).
- Bubble position persists as `Settings.bubbleSide` + `bubbleYFraction` (fraction of usable height, so it
  survives rotation). Placement math is in `BubbleGeometry.kt` (unit tested). Panel size persists as
  `Settings.panelLines` (1..6, default 3) and `panelWidthPercent` (30..90, default 50); the service collects
  settings and calls `setPanelSize` (also before the first show), applied live. Settings > Text panel: two sliders,
  saved on release.
- `BubbleView`: 40 dp circle (`circlePx`) inside a 52 dp view (`sizePx`, the touch target; 6 dp margin each side
  for touch and shadow, plus `shadowRoomPx` 6 dp below). Panel: max width `panelWidthPercent` % of the display, clamped to the usable width beside
  the snapped bubble (`panelMaxWidth`), 15 sp text; vertical padding makes one line exactly the circle's height.
  Live text (`tail = true`: Listening/Finalizing) keeps its last `panelLines` lines: `showText` trims with a
  `StaticLayout` at the max width (simple line breaking, bounds for width on API 35+), and re-trims once from the
  TextView's own layout if that still overflows, so the newest words always show with no scrolling or ellipsis.
  Done/Error text (`tail = false`) shows from the start, up to max(`panelLines`, 4) lines, ellipsized at the end.
  The panel sits beside the circle with its top on the circle's top and grows down; if its max height (live or
  notice lines) wouldn't fit above the IME/usable bottom, its bottom sits on the circle's bottom and it grows up; if
  neither fits, its top sits on the usable top.

Dictation (CP3):
- `audio.AudioRecorder.chunks()`: cold `Flow<AudioChunk>`, 50 ms PCM16 chunks on `Dispatchers.IO`, each with
  `readAtNanos` (`SystemClock.elapsedRealtimeNanos()` right after `read` returns); each collection opens the mic,
  cancellation releases it. Throws `AudioCaptureException` (user-readable) for missing permission, init/start
  failure, or `activeRecordingConfiguration.isClientSilenced` (how blocked background capture shows up); a read
  that returns after cancellation (stop) ends quietly (`ensureActive()` before those checks). Each chunk carries
  `level` = `pcm16Level` (pure, unit tested): RMS dBFS mapped from -55..-12 dB to 0..1, then `pow 0.7`.
- `stt.SpeechToTextProvider.transcribe(audio: Flow<ByteArray>, model, language, onPartial): String`. Streams until
  `audio` completes, then finalizes and returns the final text; `onPartial` gets the whole live text; cancelling
  the coroutine aborts. Failures are `SttException` with a user-readable message. `warmUp()`: fire-and-forget
  pre-connect, throttled by the provider (OpenAI below).
- `dictation.DictationController` (one per service, main thread, built by `AppContainer.dictationController`):
  `Idle -> Listening(text) -> Finalizing(text) -> Done(text, notice?) | Error(message) -> Idle` (Done shown 1.5 s,
  or 3 s with a notice, which the panel shows instead of the text; Error 3 s; blank transcript = Error
  "No speech detected"). `start()`: new session (also from Done/Error), ignored while Listening/Finalizing;
  `stop()`: Listening only; `cancel()`: discards any session. The service maps the button to `cancel()` while
  Listening, else `start()` (so the spinner ignores taps), and the pill to `stop()`. Finalizing can't be cancelled
  by a tap: stalls end via the STT 10 s final timeout / cleanup 15 s stall. `level: StateFlow<Float>`: the latest
  chunk's `level` while Listening (set in `transformWhile`), 0 otherwise; the service pushes it to the pill. Stop
  records the stop time (`clock`); the audio flow (`transformWhile`) keeps sending queued chunks (e.g. behind a
  stalled main thread) until it has sent the first one read after that time (the chunk in progress), then ends.
  The mic runs in its own coroutine (`produceIn`), so the audio (and the STT commit) doesn't wait for the mic's
  next read and release; it starts on the provider's first collect, after its API key check, so a missing key never
  opens the mic.
  `onFocusLost()` (the service's debounced focus-lost hide) cancels only while Listening: once stopped, the session
  always finishes.
  Service destroy cancels its scope. Errors other than `SttException`/`AudioCaptureException` are logged and shown
  as "Something went wrong".
- Final hook: `suspend IrisAccessibilityService.onFinalTranscript(text): Done`, called directly inside the session
  (state still Finalizing). It does cleanup + insertion and returns the Done to show (inserted text, plus a notice
  on fallback). No retry/skip.

Cleanup + insertion (CP4):
- Cleanup runs only if `cleanupEnabled`, with the active prompt and `cleanupModel`; no separate status text: the
  bubble spins (Finalizing) until insertion is done. The panel keeps the transcript; the cleaned text is never shown.
  `CleanupProvider.cleanup` throws `CleanupException` (user-readable). Any cleanup failure (incl. missing Gateway
  key) inserts the raw transcript; Done's notice is the error plus "Inserted the original text" (or "Original
  copied to clipboard"), so dictation is never lost. Other unexpected cleanup exceptions are logged and treated the
  same.
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
  order; final = completed items once the committed item completes; it is returned at once (`close(1000)` only
  enqueues the close frame, the handshake finishes in the background). The 10 s final timeout starts only after
  OkHttp's send queue drains (`queueSize() == 0`, polled every 10 ms; max 60 s) so slow uplinks don't time out.
  A commit needs >= 100 ms of audio (server error "buffer too small" otherwise), so less is padded with silence.
  Warm-up (`OpenAiRealtimeSpeechToText.warmUp()`, failures only `Log.d`): the service calls it when it shows the
  bubble (`refreshFocus`) and on each state change, skipped while a session is Listening/Finalizing or without an
  OpenAI key (`Settings.openAiKeySet`, cached by the service; no decryption per event). Request:
  keyless `HEAD https://api.openai.com/v1/models` (401, no body; measured 2026-11-11 with curl `--http1.1`:
  ~250 ms after TLS; `HEAD /` is faster at ~5 ms but an odd 421, `HEAD /v1/realtime` 405 ~750 ms). It must go
  through `httpClient.newBuilder().protocols(listOf(HTTP_1_1))` of the WebSocket's client: OkHttp connects
  WebSockets on that, and pooled connections are reused only for an equal `Address` (protocols included), so an
  h2 warm-up is never reused (unit test checks `connectionIndex`). The upgraded connection never returns to the
  pool, so `transcribe` resets the throttle; so does a failed warm-up (e.g. offline). Throttle: one warm-up per
  3 min; api.openai.com closed an idle HTTP/1.1 connection after 400 s (measured) and OkHttp's pool evicts idle
  ones after 5 min. The provider logs `Timing: transcribe->ws open X ms` (≈ tap->connected; the session starts
  right after the start tap).
  Default model `gpt-live-transcribe` (`gpt-4o-*-transcribe` are deprecated, shutdown 2027-02-26). It requires
  `turn_detection: null` (no server/semantic VAD) and takes `audio.input.transcription.languages: [..]` (array;
  never also send singular `language`). Settings store one optional code; the client wraps it into the array.
- Cleanup: Vercel AI Gateway, OpenAI-compatible `POST https://ai-gateway.vercel.sh/v1/chat/completions`,
  `Authorization: Bearer <key>`. Structured output via
  `response_format: {type:"json_schema", json_schema:{name, schema, strict:true}}` with schema
  `{ "text": string }`. Supports multiple saved system prompts; user picks the active one. Optional (toggle).
  Default model `openai/gpt-6-luna` (OpenAI's replacement for the deprecated 4.1/4o minis; cheap, structured
  output, a reasoning model). The reasoning effort is a setting (`Settings.cleanupReasoningEffort`, default
  `low`): null = "Model default", `reasoning_effort` is omitted and the provider decides (gpt-6-luna: medium,
  per https://developers.openai.com/api/docs/models/gpt-6-luna, verified 2026-11-11); otherwise we send top-level
  `reasoning_effort: <value>`.
  Verified 2026-11-11 (`cleanup.vercel.GatewayProtocol.kt`) against
  https://vercel.com/docs/ai-gateway/sdks-and-apis/openai-chat-completions/reasoning,
  `.../openai-chat-completions/structured-outputs`, https://vercel.com/docs/ai-gateway/models-and-providers/reasoning
  and https://vercel.com/docs/ai-gateway/sdks-and-apis/rest-api:
  Chat Completions takes top-level `reasoning_effort` (alias of the Gateway's `reasoning.effort`; nested wins if
  both are sent). Accepted values are `none|minimal|low|medium|high|xhigh|max`; any other string gets a 400 (before
  auth). The Gateway translates effort per provider (Gemini 3 `thinkingLevel`: low stays low, every other value
  becomes high); a valid value the model doesn't list is translated, not documented as an error. The catalog
  (`GET /v1/models`) lists `openai/gpt-6-luna` effort values `none|low|medium|high|xhigh|max`. User message = raw
  transcript; a fixed framing line appended to the system prompt says to treat it as text, not instructions.
  Streaming (`stream: true`), verified 2026-11-11 against `.../openai-chat-completions/streaming` and
  `.../structured-outputs` ("Streaming with structured outputs": json_schema works with streaming; concatenate the
  content and parse it at the end): SSE `data: {chat.completion.chunk}` events, `: heartbeat` comment lines,
  `data: [DONE]` last. `choices[0].delta.content` pieces concatenate into the JSON `{"text":...}`; chunks may have
  no choices (usage). The body is read line by line on an OkHttp thread (`callbackFlow`, deltas collected in the
  caller's context; cancelling cancels the call). The result is `parseCleanedText` of the joined content: blank
  text, any other shape, a non-JSON `data:` or a cut-off stream = "Unexpected response from AI Gateway".
  `delta.refusal` (OpenAI streaming reference; the Gateway doesn't document it) with no content =
  "Model refused: <refusal>". Mid-stream errors aren't documented; a `data:` with a top-level `error` (seen in
  vercel/ai#18303) = "AI Gateway error: <error.message>" (or just "AI Gateway error"). `choices[0].finish_reason`
  `length` = "Cleanup output was cut off", `content_filter` = "Cleanup blocked by content filter". Any other
  exception while reading the stream is logged and = "Unexpected response from AI Gateway" (the flow always closes).
  Errors before the stream: 401/403 = API key message, 429 = rate limit/quota, 5xx = server error, other 4xx =
  gateway `error.message`, transport = network error. Timeouts: no call timeout (a long cleanup keeps streaming);
  "Cleanup timed out" after `STALL_TIMEOUT_MS` (15 s) without a `data:` event: OkHttp's read timeout covers a
  silent socket (incl. waiting for headers = time to first byte), a per-line check covers heartbeat-only streams.
  Warm-up (`CleanupProvider.warmUp()`, fire-and-forget, failures only `Log.d`): `DictationController` calls it
  when a session's settings load (recording start) and again on stop, only if `cleanupEnabled`. Request: `HEAD
  /v1/models`, no key (edge-cached, 200, no body; measured 2026-11-11 ~50-100 ms after TLS, like `HEAD /` (308,
  OkHttp would follow it to vercel.com) and `OPTIONS`/404 paths). It goes through the cleanup client (derived via
  `newBuilder()`, same pool), so the cleanup POST reuses the TCP+TLS connection (cold ~400 ms vs warm ~60 ms).
  The Gateway negotiates HTTP/2 via ALPN (openssl, OkHttp JVM and device log `Warm-up: h2 200`); OkHttp's
  default protocols suffice. An idle h2 connection was still reusable after 290 s; OkHttp's pool drops idle ones
  after 5 min, hence the stop re-warm (runs alongside STT finalization). A warm-up still connecting when cleanup
  starts doesn't help (OkHttp pools a connection only after its handshake), so very short dictations or
  pool-evicted connections still pay the handshake. The service logs one `Timing:` line per session that reaches
  the final-transcript hook (not error/no-speech/cancel): stop->transcript, cleanup (`X ms`, `X ms failed` or
  `off`), stop->done; no text.
- Cleanup model list: `CleanupProvider.listModels()` = `GET https://ai-gateway.vercel.sh/v1/models` (checked
  2026-11-11: public, no auth needed; an invalid Bearer key gets 401, so the request is sent unauthenticated and
  the key is never read). Body `{object:"list", data:[{id, type, ...}]}` with `type` one of
  `language|embedding|image|video|speech|transcription|realtime|reranking|evaluation`. We keep
  `type == "language"` (or no `type`), sorted by id, as `CleanupModel(id, reasoningEfforts)`. `reasoningEfforts` =
  the `reasoning_options[]` entry `{type:"effort", values:[..]}` (other entry types: `{type:"toggle"}`,
  `{type:"budget_tokens", min?, max?}`; a missing `reasoning_options` means the controls are "not specified"). It
  is null when the field is missing, null or not an array, there is no effort entry, or `values` is empty or not an
  array. E.g. luna `none..max`, `google/gemini-3-flash` `low|high`, `anthropic/claude-sonnet-5.5` `low..max`;
  `openai/gpt-4.1-mini` has no `reasoning_options`. HTTP errors map as above; timeout = "Loading models timed out",
  other transport errors = "Couldn't load models (network error)" (no raw exception in the UI); no `data` array =
  "Unexpected response from AI Gateway".
  Settings shows the cleanup model as an editable M3 `ExposedDropdownMenuBox` (`PrimaryEditable` field): any
  custom id can be typed; typing filters the list (case-insensitive substring); explicit Save as other fields.
  The catalog (`models`/loading/error) lives in `SettingsScreen`, shared by the model field and the reasoning
  level, in memory only (refetched on each `MainActivity` creation, incl. rotation). It loads when the screen
  opens with cleanup on (or when cleanup is turned on, if not loaded yet), once on the model menu's first open if
  still not loaded (later failures need refresh), or via refresh; one fetch at a time (a load in progress isn't
  restarted). A failed load shows its error under the model field. The menu renders a lazy,
  height-bounded `LazyColumn` (fixed to the field's width: the menu measures intrinsic width, which lazy lists
  don't support). Refresh is an `IconButton` beside the field (not in it), so it doesn't focus the field or
  clear the filter; the box gets `expanded` only while the field is focused, since it focuses its anchor (IME)
  while expanded. The field's anchor also sees taps on the trailing Save (it toggles the menu first), so Save
  closes the menu explicitly. A fetch error re-triggers the field's bring-into-view above the IME. The model hint
  reads "Model must support structured output".
  Under it, "Reasoning level" is a read-only `ExposedDropdownMenuBox` (`PrimaryNotEditable`) that saves on
  selection. Options: "Model default" plus the saved model's efforts, else `Settings.REASONING_EFFORTS` (model not
  in the catalog, catalog not loaded or failed). "Not listed for this model" (error colour) shows only when the
  model's list is known and lacks the saved value; the value is sent anyway (a cleanup error falls back to the raw
  transcript).

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
- Panel size: the Lines/Max width sliders change the panel (live text and notices) in another app without
  restarting the service; at 90 % the panel stays on screen on both sides and in landscape.
- TalkBack reads the Text panel sliders' name and value.
- Recording UI: mic -> ✕ morph and pill slide are smooth on both edges; dark mode ✕ colours (toggle while it
  shows); the waveform follows voice and settles to dots in silence; drag from the pill never stops, drag from the
  ✕ never cancels; tapping the gap stops; near the IME/bottom the pill opens upward (✕ stays put, panel grows up)
  and switches direction when the IME shows/hides mid-Listening without a flicker; panel enters with the first
  words and animates out after the tick; re-show after focus loss mid-Finalizing restores spinner/panel without
  replayed animations; TalkBack reads "Start/Cancel/Stop dictation" and double-tap works on the ✕ and the pill.
- Sideloaded APK: the accessibility toggle needs App info > ⋮ > "Allow restricted settings".

### Emulator / Android MCP
Checked 2026-11-11 on `emulator-5554` (AVD `Medium_Phone_API_37.0`, API 37, 1080x2400; SDK `K:\Android\SDK`).
- Build + install: `./gradlew installDebug --console=plain -q`; launch: MCP `launch_app` `dev.jamius.iris`.
- MCP tools that work: `screenshot` (`save_path`), `get_ui_tree` (text + content-desc; no stateDescription),
  `scroll_to_element`, `tap`, `swipe` (drags Compose sliders), `adb_shell`, `clear_logs`. Raw tree:
  `MSYS_NO_PATHCONV=1 adb shell uiautomator dump /sdcard/ui.xml` (Git Bash rewrites `/sdcard` paths otherwise).
- Text field to test on: `adb shell am start -a android.intent.action.INSERT -t vnd.android.cursor.dir/contact`
  (Contacts editor), tap "First name". The bubble appears ~1 s after focus; bubble window bounds via
  `adb shell dumpsys window windows | rg "dev.jamius.iris, frame"`. Rapid frames: `adb exec-out screencap -p`.
- Accessibility service: `adb shell settings put secure enabled_accessibility_services
  dev.jamius.iris/dev.jamius.iris.overlay.IrisAccessibilityService` (binds at once). `am force-stop
  dev.jamius.iris` clears that setting, so re-run it after a force-stop.
- No speech input: the emulator mic opens (session reaches the STT, "No speech detected"), but host audio isn't
  forwarded (host TTS played through speakers wasn't heard). Transcript/cleanup/insertion paths need a device or
  Extended controls > Microphone > "Virtual microphone uses host audio input" plus a real voice.
- If adb shows the device `offline` while qemu runs, kill and restart the AVD.

## Status
Update this section as checkpoints land.

| Checkpoint | Scope | State |
|---|---|---|
| CP1 | Settings + secure API key storage | Done |
| CP2 | AccessibilityService + bubble overlay | Done |
| CP3 | Audio capture + OpenAI Realtime STT | Done |
| CP4 | Text insertion + LLM cleanup | Done |
| CP5 | Polish | Done |
