# Iris

Android voice dictation app (Kotlin, Jetpack Compose, single `:app` module, package `dev.jamius.iris`).
When the user focuses a text field in any app, an AccessibilityService shows a small circular bubble overlay.
Tap the mic to start dictating: a red pill with a live waveform slides out below the button (above it near the
keyboard/screen bottom), which becomes ✕ (cancel). The partial transcript streams live in a panel beside the bubble
(left of it if the bubble is on the right edge, right of it if on the left edge). Tap the pill to stop. The
transcript is finalized, optionally cleaned up by an LLM (toggle in settings), then inserted at the cursor of the
focused field. Models that can't stream are "after stop" ones: the recording is uploaded after the pill tap, with no
live text (pill + waveform while recording, spinner after stop); the mode follows the model.
Cloud APIs only for transcription and cleanup. On-device transcription is future scope: don't build for it, but keep
the provider interfaces so a new provider is just a new implementation. The one on-device model is Silero VAD
("Skip silence"): it only decides which audio is sent, to cut STT cost.

## Tech stack & build
- AGP 9.x with built-in Kotlin (no separate `kotlin-android` plugin). Kotlin 2.2.10, Compose compiler plugin,
  kotlinx.serialization plugin.
- `minSdk 30`, `targetSdk`/`compileSdk 37`, Java 11 target. JDK 25 locally.
- Compose Material3 (app UI), OkHttp (HTTP + WebSocket), kotlinx.serialization (JSON),
  Jetpack DataStore Preferences, Coroutines/Flow.
- ONNX Runtime Android 1.28.0 (Silero VAD), pinned: 1.29+ adds default-on telemetry (INTERNET permission + an
  `ai.onnxruntime.TelemetryInitializer` ContentProvider); 1.28.0's AAR manifest is empty (checked 2026-11-12: the
  merged manifest gets nothing from it; INTERNET comes from OkHttp) and its libs are 16 KB page aligned.
  `abiFilters` arm64-v8a + x86_64 (phones + emulator): 32-bit-only devices can't install the app. The native libs
  are ~29 MB (arm64) + ~35 MB (x86_64), stored uncompressed: the debug APK went from 34 MB to 99.6 MB (a store
  bundle would ship one ABI).
- Sonic (time-stretch, "Speed"), vendored, no dependency: `audio/sonic/Sonic.java` (Java, Apache-2.0, Bill Cox) from
  https://github.com/waywardgeek/sonic commit `1d7051354f32392a3b3cdb8a8bcfe56e6f8b744f` (upstream file SHA-256
  `c23fb313fafa9cdab4c9aa291bfdc2426f33a42d0e19e3e8d1cfd3f85258d7df`); only the package line changed (noted in its
  header); licence ships as `assets/sonic_LICENSE.txt`.
- Lucide icon `audio-lines` (https://lucide.dev/icons/audio-lines, ISC) as `res/drawable/ic_audio_lines.xml`
  (converted from the 24x24 SVG: stroke 2, round caps/joins); licence ships as `assets/lucide_LICENSE.txt`.
- Manual DI: `AppContainer` held by the custom `Application` class `IrisApp`. No Hilt/Koin.
- All versions live in `gradle/libs.versions.toml`.

## Architecture
| Package | Responsibility |
|---|---|
| (root) | `IrisApp` (Application), `AppContainer` (manual DI), `MainActivity` (hosts settings UI) |
| `data.settings` | Settings model, `SettingsRepository` (DataStore), `SecretStore` (Keystore crypto) |
| `ui.settings` | Settings screen (Compose) |
| `audio` | PCM16 mono recorder (rate per session); `SileroVad` (ORT wrapper), `SpeechGate` (pure VAD gate); `timeStretched` (Sonic speed-up) |
| `stt` | `SpeechToTextProvider`; `stt.openai` / `stt.vercel` streaming + upload providers; `Wav.kt` (upload helpers) |
| `cleanup` | `CleanupProvider` interface; `cleanup.vercel` gateway implementation |
| `dictation` | Session state machine/controller: audio -> STT -> cleanup -> insertion |
| `overlay` | AccessibilityService, bubble overlay view, live-text panel |

Overlay (`overlay`, View-based, not Compose: a Compose window in a service needs lifecycle/savedstate owners):
- `IrisAccessibilityService` re-checks `findFocus(FOCUS_INPUT)` on view-focused/window events and shows the
  bubble for `isEditable && !isPassword` nodes outside Iris's own package, and whenever the keyboard
  (TYPE_INPUT_METHOD window) is open, even without an editable focused node (e.g. ChatGPT's chat box): not for a
  password node, nor if the focused node (or, with none, the active window) is Iris's own. Keyboard open and the
  input focus not editable (`keyboardOnly`): `findFocusedEditable` walks `rootInActiveWindow` breadth first (max
  `MAX_TREE_NODES` = 1000) for a focused editable node and uses it if found. Checked 2026-11-12 on a phone: a freshly
  launched ChatGPT (Compose) reports only its host `View` (not editable, no paste) until its node tree is walked
  once; after the walk it reports the `EditText` (editable, SET_TEXT) and insertion works. The input-focused node
  (editable, non-editable or null) is kept in `focusedField` as the insertion target (call `refresh()` before use);
  `fieldFocused` says the bubble is wanted (also gates warm-up). IME bounds come from `windows`
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
  stop the bars shrink to dots as the pill retracts. Animations run only while attached; removing the stack ends
  only running animators (`end()` on a finished one replays it). The overlay keeps the desired panel state
  (`panelWanted`) while hidden.
- Show/hide: `show()` adds the stack window and fades + zooms the stack's root in (alpha 0 -> 1, scale 0.6 -> 1,
  pivot on the circle's centre, 150 ms emphasized decelerate); a wanted panel animates in with it (its own enter).
  Status and pill are restored as they are (no replayed morph/slide). `hide()` runs the reverse (150 ms emphasized
  accelerate), the panel takes its own 200 ms exit, and each window is removed (`removeViewImmediate`) when its
  exit ends. `bubbleShown` = the stack window is added, so it stays true during the exit (`stackExiting`), while
  `ownsWindow` still matches it; during the exit taps (incl. accessibility clicks) are ignored and `showText` only
  records the text, like when hidden. `show()` during the exit turns stack and panel back from their current
  alpha/scale (no remove/re-add); `hide()` during the enter reverses likewise; repeated calls are no-ops. Pill and
  status changes during the exit still animate (focus loss mid-Listening: the pill retracts while fading).
  `hide(animate = false)` (service `onDestroy`) cancels the animators and removes both windows at once. A config
  change ends a running stack animation (fully in, or removed).
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
- `audio.AudioRecorder.chunks(sampleRate)`: cold `Flow<AudioChunk>`, 50 ms PCM16 mono chunks at `sampleRate` (the
  session's `SpeechToTextProvider.sampleRate(model)`; Android resamples if the hardware doesn't run at it) on
  `Dispatchers.IO`, each with `readAtNanos` (`SystemClock.elapsedRealtimeNanos()` right after `read` returns); each
  collection opens the mic, cancellation releases it. Throws `AudioCaptureException` (user-readable) for missing
  permission, init/start failure, or `activeRecordingConfiguration.isClientSilenced` (how blocked background capture
  shows up); a read that returns after cancellation (stop) ends quietly (`ensureActive()` before those checks). Each
  chunk carries `level` = `pcm16Level` (pure, unit tested): RMS dBFS mapped from -55..-12 dB to 0..1, then `pow 0.7`.
- `stt.SpeechToTextProvider.transcribe(audio: Flow<ByteArray>, model, language, timeoutMs, onPartial): String`.
  Streams until `audio` completes, then finalizes and returns the final text; `onPartial` gets the whole live text;
  cancelling the coroutine aborts. `timeoutMs`: how long to wait for the final transcript once all audio is sent;
  the controller passes `Settings.sttTimeoutSeconds` (5..30, default 10; min 5 so a too-short wait can't fail
  every dictation) per session, all providers use it (below). Failures are `SttException` with a user-readable
  message; exceptions from `audio` propagate unchanged (the VAD no-speech path relies on it). `warmUp()`:
  fire-and-forget pre-connect, throttled by the provider (below). `sampleRate(model)`: the rate `audio` must have
  (default 24 000; the Gateway and upload providers override it). `streams` (default true): sends audio while
  recording over a socket (gets the VAD keep-alive); false = uploads after stop, never calls `onPartial` (the upload
  providers). `liveText(model)` (default `streams`; OpenAI realtime: `hasLiveText(model)`): reports text while
  recording; it picks the VAD hangover.
- `dictation.DictationController` (one per service, main thread, built by `AppContainer.dictationController`;
  `audio: (sampleRate) -> Flow<AudioChunk>` = `audioRecorder::chunks`, `stt: (Settings) -> SpeechToTextProvider` =
  `AppContainer::speechToText`, picked once per session after the settings load, with `Settings.activeSttModel`;
  `vadModel: () -> (FloatArray) -> Float` = `sileroVad::newStream`, a fresh Silero stream per session with VAD on;
  `vadContext` = `Dispatchers.Default`, where the gate runs, tests pass `EmptyCoroutineContext`):
  `Idle -> Listening(text) -> Finalizing(text) -> Done(text, notice?) | Error(message) -> Idle` (Done shown 1.5 s,
  or 3 s with a notice, which the panel shows instead of the text; Error 3 s; blank transcript, or nothing passed
  VAD = Error "No speech detected"). An upload provider stays at `Listening("")`/`Finalizing("")` (blank text keeps
  the panel hidden). `start()`: new session (also from Done/Error), ignored while Listening/Finalizing;
  `stop()`: Listening only; `cancel()`: discards any session. The service maps the button to `cancel()` while
  Listening, else `start()` (so the spinner ignores taps), and the pill to `stop()`. A tap that acts (start, stop,
  cancel; decided from the state at tap time) gets a haptic (`haptic()`: `EFFECT_HEAVY_CLICK`, touch usage, so the
  touch-haptics setting applies; needs `VIBRATE`); ignored taps, drags and focus-loss cancels don't. Finalizing
  can't be cancelled by a tap: stalls end via the STT timeout `T` = `sttTimeoutSeconds` (OpenAI: 60 s drain, then
  `T`; Gateway: 60 s drain, `T` stall, one reconnect within its budget, then `T` stall again) / cleanup
  `cleanupTimeoutSeconds`.
  `level: StateFlow<Float>`: the latest chunk's `level` while Listening (set in `transformWhile`), 0 otherwise; the
  service pushes it to the pill. Stop records the stop time (`clock`); the audio flow (`transformWhile`) keeps
  sending queued chunks (e.g. behind a stalled main thread) until it has sent the first one read after that time
  (the chunk in progress), then ends. The mic runs in its own coroutine (`produceIn`), so the audio (and the STT
  commit) doesn't wait for the mic's next read and release; it starts on the provider's first collect, after its
  API key check, so a missing key never opens the mic. Flow:
  `audio(rate).map { chunk to gate.process(chunk) }.flowOn(vadContext)` -> `produceIn` -> `transformWhile` (main:
  level and stop check on every chunk, emits the gated bytes) -> `onCompletion` (VAD log / no speech, see VAD) ->
  `timeStretched` (speed != 1.0x, see Speed) -> `stt.transcribe`.
  `onFocusLost()` (the service's debounced focus-lost hide) cancels only while Listening: once stopped, the session
  always finishes.
  Service destroy cancels its scope. Errors other than `SttException`/`AudioCaptureException` are logged and shown
  as "Something went wrong".
- Final hook: `suspend IrisAccessibilityService.onFinalTranscript(text): Done`, called directly inside the session
  (state still Finalizing). It does cleanup + insertion and returns the Done to show (inserted text, plus a notice
  on fallback). No retry/skip.

VAD ("Skip silence", CP7):
- Model: `app/src/main/assets/silero_vad.onnx`, Silero VAD v6.2.3
  (https://github.com/snakers4/silero-vad/raw/v6.2.3/src/silero_vad/data/silero_vad.onnx, 2,327,524 bytes, SHA-256
  `1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3`), MIT; its licence ships as
  `assets/silero_vad_LICENSE.txt` (attribution: Silero Team, https://github.com/snakers4/silero-vad).
- `audio.SileroVad` (one per app in `AppContainer`, not unit tested: needs native ORT): `OrtSession` with 1 intra-
  and 1 inter-op thread, `ALL_OPT`, loaded lazily on the first frame (off main, on `vadContext`; emulator log
  `SileroVad: Model loaded in ~160 ms`) and never closed (process lifetime). `newStream()` = a closure with zeroed
  state/context. I/O as the official `OnnxWrapper` (`silero_vad/utils_vad.py` v6.2.3, checked 2026-11-12): `input`
  float32 `[1, 576]` = the previous input's last 64 samples + the 512-sample frame, `state` float32 `[2, 1, 128]`
  (fed back from output 1), `sr` int64 0-d tensor 16000; output 0 = probability. Load failure (incl.
  `LinkageError`: `UnsatisfiedLinkError`, `ExceptionInInitializerError`, `NoClassDefFoundError`) is logged once and
  the stream throws on every frame.
- `audio.SpeechGate` (pure, unit tested), Handy's `SmoothedVad` (`src-tauri/src/audio_toolkit/vad/`): 32 ms frames
  (512 samples at 16 kHz; a 24 kHz frame of 768 samples is decimated to 512 for the VAD only: `s0, (s1+s2)/2` per 3;
  the STT always gets the original audio), frame remainders carried across chunks. Voiced = probability `> 0.3`;
  onset 2 voiced frames in a row; hangover after the last voiced frame: 1650 ms = 52 frames for live-text models
  (`liveText(model)`), 450 ms = 15 frames for the others (uploads, OpenAI realtime models without live text)
  (frames rounded up like Handy's `frames_for_duration_ms`); pre-roll: the unsent chunks of the last >= 450 ms. A
  chunk is sent whole if a frame completed in it is kept (or it completed none while in speech); the onset chunk
  also sends the pre-roll, oldest first. An unconfirmed onset at stop is dropped. If the model throws, the gate
  fails open (`failure`): pre-roll + chunk, then every chunk.
- Gate placement: upstream of `transformWhile`, never drops chunks from the flow (level and stop see every chunk;
  only the bytes riding along are gated). `onCompletion` with no cause (the recording ended at the stop) logs
  `VAD: sent X ms of Y ms` (`Log.w` with the failure if the gate failed open) and, if nothing was sent, throws
  `SttException("No speech detected")`: the provider's `audio.collect` rethrows it, so the socket is cancelled before
  any padding, `input_audio_buffer.commit` / `audio-done` or upload (provider tests pin this). Assumption: neither
  service bills a session that received no audio (only `session.update` / the start frame).
- Keep-alive (streaming providers only, `stt.streams`): in `transformWhile`, when the gate has sent nothing for
  5 s (`readAtNanos` of the last emitted chunk, starting at the first chunk), one chunk-sized `ByteArray` of zeros
  is emitted and the timer resets, since servers may end a stream that gets no audio (e.g. Deepgram after 10 s;
  the Gateway upstreams' limits are undocumented); pings only keep the TCP/NAT path. ~1 % of the withheld
  silence. Zeros don't count in `sentBytes`: a session with no speech still ends with "No speech detected" and no
  commit / `audio-done` (if it ran >= 5 s, the socket got only keep-alive zeros). Upload providers get none.
- Setting `Settings.vadEnabled` (`vad_enabled`, default on): Settings > Transcription > "Skip silence" switch
  (`ic_mic`), subtitle "Detects speech on-device and sends only that, to cut cost".

Speed (CP8):
- `audio.timeStretched(sampleRate, speed)` (`TimeStretch.kt`, pure, unit tested): `Flow<ByteArray>` of PCM16 mono
  -> the same audio `speed` times faster through vendored Sonic (`Sonic(rate, 1)`, `setSpeed`, defaults otherwise:
  quality 0, pitch/rate/volume 1, so only `changeSpeed` runs and pitch is kept). Output rate = input rate (WAV
  headers, start frame and `session.update` rates stay right). Sonic holds back up to ~30 ms; `flushStream()` emits
  that tail (measured 12-27 ms of output) when upstream completes normally; an upstream error propagates unchanged
  with no flush. Never emits empty arrays.
- Placement: after `onCompletion` (VAD), before `stt.transcribe`, only when `sttSpeedPercent != 100` (at 1.0x no
  Sonic object, the same arrays). After `transformWhile`, so only sent chunks reach Sonic and the flush happens
  exactly at the stop; after `onCompletion`, so `NO_SPEECH` is thrown before any flush output (nothing reaches the
  provider). Every provider acts (commit / `audio-done` / upload) only after its `audio.collect` returns, i.e. after
  the tail. Keep-alive zeros go through Sonic too (keeps byte order; a 50 ms zero chunk comes out as ~25-45 ms).
- Counts/logs: `sentBytes`, `recordedBytes` and `VAD: sent X ms of Y ms` stay in recorded time; a completed
  stretched session logs `Speed Xx: sent A ms as B ms` (`Log.d`).
- Thread: runs in the collector's context (the provider's `transcribe`, main thread), like `transformWhile`.
  Measured on a desktop JVM (50 ms chunks): avg 6-48 µs per chunk, worst 283 µs (24 kHz 2.0x); phone cost
  unmeasured. Off-main would need per-chunk thread hops or `flowOn` around main-thread state.
- Interactions (all see the stretched bytes, unchanged code): OpenAI 100 ms min-commit padding, `wavBytes` 1 s /
  1.25 s padding (short dictations at high speed save less), `uploadReadTimeoutMs` (stretched duration),
  `splitAtPauses` (chunks ~25-45 ms instead of 50 ms; limits count the file), Gateway reconnect resend (its sent
  list, no re-stretch). The VAD hangover is not scaled: a live model gets 1650 / speed ms of tail.
- Setting `Settings.sttSpeedPercent` (`stt_speed_percent`, 100..200, default 100 = as recorded; percent int so the
  integer slider works and 1.0x is an exact check; no read-side clamp). Read per session.

Cleanup + insertion (CP4):
- Cleanup runs only if `cleanupEnabled`, with the active prompt and `cleanupModel`; no separate status text: the
  bubble spins (Finalizing) until insertion is done. The panel keeps the transcript; the cleaned text is never shown.
  `CleanupProvider.cleanup` throws `CleanupException` (user-readable). Any cleanup failure (incl. missing Gateway
  key) inserts the raw transcript; Done's notice is the error plus "Inserted the original text" (or "Original
  copied to clipboard"), so dictation is never lost. Other unexpected cleanup exceptions are logged and treated the
  same. Overall deadline: `Settings.cleanupTimeoutSeconds` (1..30, default 5, like Handy's
  `post_process_timeout_secs`); `onFinalTranscript` wraps the cleanup call in `withTimeoutOrNull` (not
  `withTimeout`: its `TimeoutCancellationException` is a `CancellationException` and would be rethrown, losing the
  dictation). Cancelling cancels the OkHttp call (`invokeOnCancellation`). On timeout the raw transcript is inserted with the
  notice "Cleanup timeout. Pasted original text." (if it was copied instead: "Cleanup timeout." plus the clipboard
  line from `doneNotice`).
- Target: the service snapshots `focusedField` when the user taps stop. At insertion, if it still equals
  `focusedField` (`AccessibilityNodeInfo.equals`) the text is inserted there; else it is copied to the
  clipboard ("Copied to clipboard — field changed"; the bubble may already be hidden). A null target (keyboard
  only, no focused node) is never matched: it is copied ("Copied to clipboard"). Any other hook failure
  (settings read, insertion throwing) is logged and copies the text too. Never "Something went wrong" with the
  text lost. Notice wording is `doneNotice` (pure, unit tested).
- `overlay/TextInsertion.kt`: `mergeInsertion` (pure, unit tested) replaces the selection (or inserts at the
  cursor; invalid/-1 selection, either end, appends at the end; reversed selections are normalized). Adds a
  leading space after a non-whitespace char other than `( [ { " ' “`, and a trailing space before a
  non-whitespace char other than `. , ! ? : ; ) ] } ” ’ …` (ASCII `"`/`'` still get one: they may be openers).
  `insertText`: `refresh()` the node, text = "" if `isShowingHintText` or text equals
  `hintText`, `ACTION_SET_TEXT` with the merged text, then `ACTION_SET_SELECTION` to the end of the inserted text.
  If SET_TEXT returns false: clipboard + `ACTION_PASTE` of the same spaced text (previous clipboard not restored).
  A non-editable node (keyboard only) skips SET_TEXT: clipboard + `ACTION_PASTE` of the unspaced text.
  No/stale node or paste fails: text left on the clipboard, notice "Copied to clipboard".

Providers (settings store a provider id for each):
- STT provider: `Settings.sttProviderId` (`stt_provider_id`): `openai` (default) = OpenAI (direct), or
  `vercel-gateway` = Vercel AI Gateway. Each has its own model setting (`sttModel`, `gatewaySttModel`, default
  `openai/gpt-live-transcribe`); `activeSttModel` / `sttKeySet` follow the selection; the language setting is
  shared. `AppContainer` builds four providers; `speechToText(settings)` picks one by mode: Gateway ->
  `gatewaySttLive` ? streaming : upload; OpenAI -> `isRealtimeTranscriptionModel(sttModel)` ? realtime : upload.
  The service's warm-up cache follows the selection.
- WebSocket pings: both streaming providers connect on `httpClient.newBuilder().pingInterval(30, SECONDS)`, so long
  pauses VAD doesn't send don't let a mobile NAT drop the idle socket; a pong must arrive within the interval (OkHttp
  fails the socket when the previous ping is unanswered at the next one), like any other failure (Gateway: its one
  reconnect; OpenAI: error). On a saturated uplink the ping queues behind sent data in the kernel and network
  buffers, so 30 s (not 15) allows that much queueing before a false failure; the longest idle gap stays under the
  usual 60 s+ NAT/proxy idle timeouts. `pingInterval` isn't part of OkHttp's `Address`, so the
  HTTP/1.1 warm-up connection is still reused (the `connectionIndex` warm-up tests pass).
- STT, OpenAI (direct): OpenAI Realtime API, transcription-only session over WebSocket. Audio is PCM16 mono
  24 kHz, sent as base64 chunks via `input_audio_buffer.append`. On stop: commit the buffer, wait for the final
  transcript. Verify event names and session config against current OpenAI docs before changing protocol code.
  Verified 2026-11-11 (`stt.openai.RealtimeProtocol.kt`) against
  https://developers.openai.com/api/docs/guides/realtime-transcription and
  https://developers.openai.com/api/reference/resources/realtime/client-events (`/server-events`):
  connect `wss://api.openai.com/v1/realtime?intent=transcription` (no `model` param: that makes a conversation
  session) with only `Authorization: Bearer <key>` (no `OpenAI-Beta` header in the GA docs); send
  `session.update` `{session:{type:"transcription", audio:{input:{format:{type:"audio/pcm",rate:24000},
  transcription:{model, languages? | language?}, turn_detection:null}}}}` (`languages: [code]` only for
  `gpt-transcribe` / `gpt-live-transcribe` (`usesLanguageList`), singular `language` for the others, never both;
  checked 2026-11-12). Server events used: `input_audio_buffer.committed`
  (item_id), `conversation.item.input_audio_transcription.delta` / `.completed` / `.failed`, `error` (all
  `error` events are treated as fatal). Deltas concatenate per item_id; items join with spaces in first-seen
  order; final = completed items once the committed item completes; it is returned at once (`close(1000)` only
  enqueues the close frame, the handshake finishes in the background). The final timeout (`timeoutMs`, from
  `sttTimeoutSeconds`, default 10 s) starts only after OkHttp's send queue drains (`queueSize() == 0`, polled
  every 10 ms; max 60 s) so slow uplinks don't time out.
  A commit needs >= 100 ms of audio (server error "buffer too small" otherwise), so less is padded with silence.
  Warm-up (`OpenAiRealtimeSpeechToText.warmUp()`, failures only `Log.d`): the service calls it when it shows the
  bubble (`refreshFocus`) and on each state change, skipped while a session is Listening/Finalizing or without the
  selected provider's key (the service caches `sttWarmUp` = `speechToText(settings)` if `Settings.sttKeySet`;
  no decryption per event). Request:
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
  Realtime models (`REALTIME_TRANSCRIPTION_MODELS`, client events reference checked 2026-11-12): `whisper-1`,
  `gpt-transcribe`, `gpt-live-transcribe`, `gpt-4o-mini-transcribe`, `gpt-4o-mini-transcribe-2025-12-15`,
  `gpt-4o-transcribe`, `gpt-4o-transcribe-diarize`, `gpt-realtime-whisper`; all stream (decided: less wait at stop),
  but only gpt-live-transcribe and gpt-realtime-whisper send text before the commit (`hasLiveText`: live hangover
  and "Mode: live"; the others get the short hangover). Any other id (trimmed) uploads.
- STT, OpenAI file upload (`stt.openai.OpenAiFileTranscription`, ids outside the realtime list): verified 2026-11-12
  against https://developers.openai.com/api/docs/guides/speech-to-text and
  https://developers.openai.com/api/reference/resources/audio/subresources/transcriptions/methods/create.
  `POST https://api.openai.com/v1/audio/transcriptions`, `Authorization: Bearer <key>`, multipart: `file`
  (`audio.wav`, `audio/wav`), `model`, `response_format=json`, plus `languages[]` (list models) or `language` when
  set -> `{text, usage}` (missing text = "Unexpected response from OpenAI"; blank = "No speech detected"). Records
  at 16 kHz; `streams = false`. Key checked before collecting (no mic without a key). Files are limited to 25 MB:
  the recording is split (`splitAtPauses`, pure, unit tested) into parts of <= 20 MB of PCM (~10 min), each cut
  after the quietest 50 ms chunk within the part's last 30 s (with VAD on, the joins between kept segments are
  ~900 ms of hangover + pre-roll non-speech, so cuts land there; with VAD off, in natural pauses); parts upload one
  after another, each with its own timeout, texts joined with `joined` (CJK-aware). Errors (`openAiHttpErrorMessage`):
  401/403 key, 429 quota, 5xx server, else `OpenAI error: <error.message>`; transport = "Network error: ...".
  Logs `Timing: upload X KB in N part(s), stop->response Y ms`.
- OpenAI model list (`stt.openai.listOpenAiTranscriptionModels`, `OpenAiModels.kt`; Settings' OpenAI model menu via
  `AppContainer.openAiTranscriptionModels`): verified 2026-11-12 against
  https://developers.openai.com/api/reference/resources/models/methods/list. `GET https://api.openai.com/v1/models`,
  `Authorization: Bearer <key>` -> `{object:"list", data:[{id, object:"model", created, owned_by, shutdown_date?}]}`
  (no capability field). Keeps ids containing `transcribe` or `whisper` (any case), sorted
  (`parseTranscriptionModelIds`, pure). No/blank key = "Set your OpenAI API key to load models", a key OkHttp can't
  send as a header = "Invalid OpenAI API key" (never quoted); neither sends a request. Errors:
  `openAiHttpErrorMessage`; no `data` array = "Unexpected response from OpenAI"; transport =
  `modelsNetworkErrorMessage` ("Loading models timed out" / "Couldn't load models (network error)"). Default client
  timeouts.
- Uploads (both providers, `stt/Wav.kt`): `wavBytes` = 44-byte RIFF/WAVE header (PCM, mono, 16-bit) + the chunks,
  padded with silence to 1.25 s when under 1 s (as Handy: services reject very short files). Per upload,
  `httpClient.newBuilder().readTimeout(uploadReadTimeoutMs)` = `timeoutMs + audio duration / 4` (the server's
  transcription time grows with the audio; default 10 s + 15 s for 1 min); OkHttp's 10 s write timeout covers a
  stalled upload; no call timeout. A timeout before the body was fully written (`sent` flag: reset at the start
  of `writeTo`, set at its end) = "Timed out sending audio to OpenAI" / "... to AI Gateway", after = "Timed out
  waiting for the transcript". `Call.await()` (enqueue, cancelling the coroutine cancels the call). Warm-up:
  keyless `HEAD /v1/models` through the default (h2) client, 3 min throttle (reset only by a failure: an h2
  connection returns to the pool).
- STT, Vercel AI Gateway (`stt.vercel`): streaming transcription WebSocket, beta (launched 2026-07-22); the only
  official client is the AI SDK's `experimental_streamTranscribe`, with no public protocol docs. Verified
  2026-11-12 (`stt.vercel.GatewayTranscriptionProtocol.kt`) against the source of `@ai-sdk/gateway` 4.0.143
  (`gateway-transcription-model.ts` `doStream`, `gateway-realtime-auth.ts`, `gateway-provider.ts`) and
  `@ai-sdk/provider-utils` (`transcription-stream-envelope.ts`), and Handy's `vercel_live_transcription.rs` /
  `vercel_transcription.rs`; a live emulator session (header auth, `finish`, an unknown model's 404 message) worked
  2026-11-12. Re-check those before changing protocol code.
  Connect `wss://ai-gateway.vercel.sh/v4/ai/transcription-model?ai-model-id=<id>` (`/` encoded) with headers
  `Authorization: Bearer <key>`, `ai-gateway-protocol-version: 0.0.1`, `ai-gateway-auth-method: api-key`,
  `ai-transcription-model-specification-version: 4`, `ai-model-id: <id>` and `Sec-WebSocket-Protocol:
  ai-gateway-transcription.v1` (marker only; the AI SDK also offers `ai-gateway-auth.<key>` for browsers, which
  the server turns into the same Bearer auth; we keep the key out of a subprotocol the server may echo. If header
  auth ever gets a 401, adding it is a one-line change in `transcribe`). A model id OkHttp can't send as a header =
  "Invalid model id: <id>", no request. First frame (text) `{type:"transcription-stream.start",
  inputAudioFormat:{type:"audio/pcm", rate}, providerOptions?}`; then raw PCM16 LE mono binary frames (each 50 ms
  chunk is one frame, far under the 64 KiB per-frame rule); end with `{type:"transcription-stream.audio-done"}`.
  Rate: `openai/*` 24 kHz, others 16 kHz (Handy's working setup; accepted rates are undocumented server policy).
  providerOptions by id prefix, only for a non-blank language: `openai`/`mistral` `{language}`, `elevenlabs`
  `{languageCode}`, `google` `{languageCodes:[..]}`, others none; `xai/*` and `spacexai/*` always get
  `{streaming:{interimResults:true}}` under both `xai` and `spacexai`.
  Server text frames are one JSON part each (unknown/malformed/missing required field = ignored, binary ignored):
  `stream-start {warnings}` (each logged with `Log.w`: unsupported options show up there), `transcript-delta
  {delta}`, `transcript-partial {text, startSecond?, durationInSeconds?}`, `transcript-final {text}`, `finish
  {text}` (we don't require `segments`; blank text = the live text), `error {error: {name,message} | string}` =
  "AI Gateway error: <message>". Close or socket failure without `finish` = failure. Live text = `LiveTranscript`
  (ported from Handy, unit tested): settled finals + the current segment's deltas/partials (Gemini's partial
  repeats the deltas, MAI's follows them, Grok's ~3 s chunks advance when a partial starts where the last ended);
  `onPartial` skips text that only cuts words off the end (`onlyTrims`), finals always show; CJK joins unspaced.
  One loop over a `Channel` fed by the audio collector, each socket's listener (tagged with an attempt number, so
  a replaced socket's late events are ignored) and the drain wait. Reconnect once: a failure after connecting (or
  a connect failure without an HTTP response, or the stall below) cancels the socket, opens a new one, sends
  the start frame, resends every chunk sent so far (all kept in memory: 48 KB/s at 24 kHz) waiting while OkHttp's
  queue holds > 1 MiB (OkHttp closes a socket over 16 MiB queued), then `audio-done` if already stopped; logs a
  `Log.w` with the seconds resent. Every `error` part is retried once (error names can't be told apart; the cost is
  one resend). The reconnect has one deadline, set at the failure: 10 s + resent bytes / 200 KB/s (1 min of audio
  ~24 s, 2 min ~39 s). It covers handshake retries, the resend and, if already stopped, the new socket's drain;
  expiry = "Reconnecting to AI Gateway timed out". If a reconnect handshake fails without an HTTP response
  (DNS/connect error, e.g. still offline; `Listener.opened` is false), it is tried again (with a resend) every 1 s
  until the deadline, so a network drop of up to ~10 s recovers. An HTTP rejection of the handshake is never
  retried and maps like cleanup (`httpErrorMessage`: 401/403 key, 429 quota, 5xx server, else
  `AI Gateway error: <error.message>`, e.g. an unknown model); its body is read in `onFailure`. Any other failure
  after the reconnect = `SttException`. After a reconnect the panel keeps the old text (only prefix shrinks are
  hidden), so it may jump back once while the new stream catches up. Under 100 ms of audio is padded with silence
  before `audio-done`. After `audio-done`: wait for OkHttp's queue to drain (max 60 s, "Timed out sending audio to
  AI Gateway"; terminal on purpose: a slow/stuck uplink won't improve with a full resend, which would push
  Finalizing past ~90 s), then a stall timer: `timeoutMs` (`sttTimeoutSeconds`, default 10 s; the same both times)
  without a server part reconnects (above; a silently dead socket shows up here or as a missed pong), a second
  stall = "Timed out waiting for the transcript" (a timer, not a total, so a reconnected stream re-transcribing a
  long recording isn't cut off). Worst-case Finalizing without server parts: 60 s drain + stall + reconnect budget +
  stall. A `finish` before `audio-done` is accepted (text so far, `Log.w`). A model id without `/` counts as
  `openai/` for the rate and options (the Gateway qualifies it so). On `finish`: `close(1000)` and return.
  Warm-up: like OpenAI's (keyless `HEAD https://ai-gateway.vercel.sh/v1/models` through the HTTP/1.1 client, 3 min
  throttle, reset by `transcribe` and by a failure; emulator log `Warm-up: http/1.1 200`; the Gateway's HTTP/1.1
  idle timeout is unmeasured). Logs `Timing: transcribe->ws open X ms` and `Timing: audio-done->finish X ms`.
  Uses the Gateway API key (shared with cleanup).
- STT, Vercel AI Gateway upload (`stt.vercel.VercelGatewayFileTranscription`, models without the
  `websocket-transcription` tag): verified 2026-11-12 against
  https://vercel.com/docs/ai-gateway/modalities/speech-to-text (updated 2026-09-08) and Handy's
  `vercel_transcription.rs`. `POST https://ai-gateway.vercel.sh/v4/ai/transcription-model`
  with the WebSocket's headers minus `Sec-WebSocket-Protocol` (invalid header id = "Invalid model id", checked
  before recording) and a JSON body `{audio: "<base64 WAV>", mediaType: "audio/wav", providerOptions?}`
  (`UploadBody`: Base64 written straight into the sink in 48 KiB slices, no big String; repeatable) -> `{text,
  segments, language, durationInSeconds, warnings}` (warnings logged `Upload warning`). 16 kHz WAV for every model
  (Handy). `providerOptions(model, language, streaming = false)`: as streaming, except `openai/gpt-transcribe` gets
  `{openai:{languages:[code]}}` and Grok gets no interim-results option. No size limit is documented, so it is one
  upload (a 413 would map through `httpErrorMessage`). Errors map like cleanup; transport =
  `gatewayNetworkErrorMessage`. The Gateway's catalog (2026-11-12) has after-stop models elevenlabs/scribe-v2,
  fish-audio/transcribe-1, google/gemini-3.5-transcribe, microsoft/mai-transcribe-2, mistral/voxtral-mini-transcribe-3,
  mistral/voxtral-small-transcribe-3, openai/gpt-4o-mini-transcribe, openai/gpt-4o-transcribe, openai/whisper-1.
- Gateway mode: `Settings.gatewaySttLive` (`gateway_stt_live`, default true), written with the model by
  `setGatewaySttModel(model, live)`; `live = gatewaySttLive(id, catalog)` (pure, tested): the catalog entry's
  `websocket-transcription` tag; an id not in the catalog (typed, or catalog not loaded) = live. Stale if the
  catalog later retags a model, until re-saved.
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
  Not streamed (no `stream` field; nothing used the deltas), verified 2026-11-12 against
  `.../openai-chat-completions/chat-completions` and `.../structured-outputs`: the response is a `chat.completion`;
  `choices[0].message.content` is the JSON `{"text":...}`, parsed by `parseCleanedText` (via `parseCompletion`):
  no `choices[0]`, missing/blank text or any other shape = "Unexpected response from AI Gateway".
  `message.refusal` (OpenAI spec, which the Gateway implements; its docs don't show it) with no content =
  "Model refused: <refusal>". `finish_reason` `length` = "Cleanup output was cut off", `content_filter` =
  "Cleanup blocked by content filter". HTTP errors: 401/403 = API key message, 429 = rate limit/quota, 5xx = server
  error, other 4xx = gateway `error.message`, transport = network error. The call goes through the shared `execute`
  (cancelling the coroutine cancels the OkHttp call). Timeouts: the service's `cleanupTimeoutSeconds` deadline
  (at most 30 s) is the real limit; the provider's cleanup client has no read timeout (the response comes only
  after the whole generation) and the 30 s call timeout (`CALL_TIMEOUT_MS`, shared with `listModels`), which maps
  to "Cleanup timed out".
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
  the final-transcript hook (not error/no-speech/cancel): stop->transcript, cleanup (`X ms`, `X ms failed`,
  `X ms timed out` or `off`), stop->done; no text.
- Model catalog: `CleanupProvider.listModels()` = `GET https://ai-gateway.vercel.sh/v1/models` (checked
  2026-11-11: public, no auth needed; an invalid Bearer key gets 401, so the request is sent unauthenticated and
  the key is never read). Body `{object:"list", data:[{id, type, ...}]}` with `type` one of
  `language|embedding|image|video|speech|transcription|realtime|reranking|evaluation`. It returns every entry with
  an id, sorted by id, as `GatewayModel(id, type, tags, reasoningEfforts)` (`tags` = the string entries of `tags`,
  e.g. `websocket-transcription`, `open-weights`); callers filter: cleanup with `isChatModel` (`type ==
  "language"` or no `type`), Gateway STT with `isTranscriptionModel` (`type == "transcription"`), live ones with
  `isStreamingTranscriptionModel` (also `websocket-transcription` in `tags`; 2026-11-12:
  elevenlabs/scribe-v2-realtime, google/gemini-3.5-transcribe-live, microsoft/mai-transcribe-2-streaming,
  openai/gpt-live-transcribe, openai/gpt-realtime-whisper, openai/gpt-transcribe, spacexai/grok-stt).
  `reasoningEfforts` = the `reasoning_options[]` entry `{type:"effort", values:[..]}` (other entry types:
  `{type:"toggle"}`, `{type:"budget_tokens", min?, max?}`; a missing `reasoning_options` means the controls are "not
  specified"). It is null when the field is missing, null or not an array, there is no effort entry, or `values` is
  empty or not an array. E.g. luna `none..max`, `google/gemini-3-flash` `low|high`, `anthropic/claude-sonnet-5.5`
  `low..max`; `openai/gpt-4.1-mini` has no `reasoning_options`. HTTP errors map as above; timeout = "Loading models
  timed out", other transport errors = "Couldn't load models (network error)" (no raw exception in the UI); no `data`
  array = "Unexpected response from AI Gateway".
  Settings > Transcription: two radio rows (OpenAI (direct) / Vercel AI Gateway, each naming its key; the selected
  one appends " · not set" in the error colour without its key), then the OpenAI model combobox (below; lists the
  key's transcription models (OpenAI model list above); hint "Realtime models (gpt-live-transcribe, gpt-transcribe,
  ...) stream; other models upload after you stop. Mode: live|streams, text after stop|after stop") or the Gateway
  model combobox (lists all transcription models; hint "Live models show text as you speak; others transcribe after
  you stop. Mode: ..." for the saved model). In both menus a streaming model's item ends with the `ic_audio_lines`
  icon (18 dp, `onSurfaceVariant`, content description "Streams"; `streams`: OpenAI `isRealtimeTranscriptionModel`,
  Gateway `isStreamingTranscriptionModel`), others with nothing. Then the shared language field, then a
  "Timeout" `SliderSetting` (5..30 s, step 1) for `sttTimeoutSeconds`, hint "How long to wait for the final
  transcript after you stop.", then a divider and the "Skip silence" switch row (`vadEnabled`), then a divider and
  a "Speed" `SliderSetting` (100..200 %, step 10, shown "1.0x".."2.0x") for `sttSpeedPercent`, hint "Speeds up the
  audio sent (pitch kept), so less is billed. Higher speeds can lower accuracy; services with a per-request minimum
  may not save.", saved on release.
  Settings shows the cleanup model (and both STT models: `ModelField`) as an editable M3
  `ExposedDropdownMenuBox` (`PrimaryEditable` field): any
  custom id can be typed; typing filters the list (case-insensitive substring); explicit Save as other fields.
  The catalog (`models`/loading/error) lives in `SettingsScreen`, shared by both model fields and the reasoning
  level, in memory only (refetched on each `MainActivity` creation, incl. rotation). It loads when the screen
  opens with cleanup or Gateway STT on (or when either is turned on, if not loaded yet), once on a model menu's
  first open if
  still not loaded (later failures need refresh), or via refresh; one fetch at a time (a load in progress isn't
  restarted). A failed load shows its error under the model field. The OpenAI list has its own state in
  `SettingsScreen`, same lifecycle: loads when the screen opens with OpenAI selected (or when it is selected, if not
  loaded yet), on first menu open, or via refresh. Saving or clearing the OpenAI key cancels a running load (it
  writes nothing), drops the list and reloads at once if OpenAI is selected (else on the next switch to it); without
  a key the error asks for one. Other load failures (e.g. the key fails to decrypt) are logged (`Log.w`) and shown
  as "Couldn't load models". The menu renders a lazy,
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
  transcript). Under that, a "Timeout" `SliderSetting` (1..30 s, step 1) for `cleanupTimeoutSeconds`, hint "If
  cleanup takes longer, the original text is inserted."

## Key decisions (don't re-litigate without a concrete reason)
- **STT providers**: OpenAI Realtime (direct) stays the default. Vercel AI Gateway's streaming transcription
  (beta, protocol taken from the AI SDK source and Handy, see Providers) is offered as a second provider for model
  choice and one shared key. Recording rate is per session (`sampleRate(model)`), not resampled in Kotlin, except
  a 16 kHz copy for the VAD. The mode follows the model (streaming vs upload after stop), no separate setting.
- **VAD on device (Silero, ONNX Runtime 1.28.0 pinned)** to cut STT cost; fails open, never loses dictation.
- **Speed-up after the VAD gate with vendored Sonic** (pitch kept) to cut billed audio; 1.0x = untouched. Not
  media3's `SonicAudioProcessor` (`@UnstableApi`, pulls Guava).
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
- **Only two abstractions**: `SpeechToTextProvider` and `CleanupProvider`. No others without a reason (provider
  choice is a `(Settings) -> SpeechToTextProvider` lambda, not a router provider).

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
- Keyboard-only field, e.g. ChatGPT's chat box right after a fresh launch: bubble shows, text is inserted.
- Sideloaded APK: the accessibility toggle needs App info > ⋮ > "Allow restricted settings".
- VAD on, real voice: first and last words aren't clipped (incl. speaking right after the tap and stopping right
  after the last word); a 10 s pause mid-dictation is dropped (log `VAD: sent X ms of Y ms` with sent < recorded); a
  pause >= 60 s on mobile data still gives a transcript for each live model (pings keep the socket, the 5 s
  keep-alive zeros keep the server's own no-audio timeout away); silence only = instant "No speech detected" with no
  commit / `audio-done` / upload; both providers incl. a 16 kHz Gateway model; a release build loads the model.
- Upload models: an after-stop model on each provider (OpenAI: an id outside the realtime list; Gateway e.g.
  `openai/gpt-4o-transcribe` or `elevenlabs/scribe-v2`): no panel while recording, spinner after stop, text
  inserted; a 2–3 min dictation on mobile data finishes within the timeout; language set; VAD on + silence = no
  upload; picking an after-stop Gateway model from the menu shows "Mode: after stop" and is used at once.
- OpenAI model dropdown lists transcription models with your key (realtime ones with the audio-lines icon, also
  in the Gateway menu for live models); no key shows "Set your OpenAI API key to load models"; saving a key loads
  the list.
- Long Gateway upload: a 10 min Gateway after-stop dictation (VAD off) succeeds; on a 413, reuse `splitAtPauses`
  for the Gateway too.
- Gateway STT with a real voice: live text streams and the final is inserted (default model, plus a 16 kHz one:
  Grok chunks don't flicker/duplicate, MAI/Gemini partials don't double); `en` with `openai/gpt-live-transcribe`
  logs no `Stream warning`; a bad key shows the key message; a brief network drop mid-dictation (airplane mode up
  to ~10 s) logs the reconnect warning(s) and still gives a complete transcript, a second drop shows an error.
- Speed 1.0x: no `Speed` log line, behaviour unchanged.
- Speed 1.5x and 2.0x, real voice: OpenAI realtime live model, Gateway live 24 kHz model, a 16 kHz Gateway model
  (e.g. Grok), an after-stop model on each provider: transcript correct, first/last words kept, live text still
  streams, log `Speed Xx: sent A ms as B ms` with B ≈ A / speed.
- 2.0x with VAD on: a 10 s pause mid-dictation still gives a complete transcript (keep-alive), silence only =
  instant "No speech detected" with no commit / `audio-done` / upload.
- 2.0x: the waveform stays smooth (main-thread cost).
- Short dictation (< 2 s) at 2.0x with an upload model: transcribed (WAV padding path).
- Billing: the provider's usage page shows fewer audio seconds for the same dictation at 2.0x.
- Gateway brief network drop at 2.0x: reconnect + resend still gives a complete transcript.

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
- VAD (checked 2026-11-12): with Skip silence on, a no-speech dictation ends right after the stop tap with "No
  speech detected"; logcat `SileroVad: Model loaded in X ms` and `DictationController: VAD: sent 0 ms of Y ms`.
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
| CP6 | Vercel AI Gateway STT provider | Done (real-voice device check pending) |
| CP7 | On-device VAD (skip silence) + upload-after-stop transcription models | Done (real-voice device check pending) |
| CP8 | Speed-up (time-stretch) | Done (real-voice device check pending) |
