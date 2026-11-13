# Iris

Android voice dictation app (Kotlin, Jetpack Compose, single `:app` module, package `dev.jamius.iris`).
When the user focuses a text field in any app, an AccessibilityService shows a small circular bubble overlay.
Tap the mic to start dictating: a red pill with a live waveform slides out below the button (above it near the
keyboard/screen bottom), which becomes ✕ (cancel). The partial transcript streams live in a panel beside the bubble
(left of it if the bubble is on the right edge, right of it if on the left edge). Tap the pill to stop. The
transcript is finalized, optionally cleaned up by an LLM (toggle in settings), then inserted at the cursor of the
focused field. Models that can't stream are "after stop" ones: the recording is uploaded after the pill tap, with no
live text (pill + waveform while recording, spinner after stop); the mode follows the model (Deepgram: a setting).
Cloud APIs for transcription and cleanup, plus one on-device STT provider ("On device", CP14: sherpa-onnx offline models
the user downloads in settings; pseudo-live text, each VAD phrase decoded as the user pauses; CP15: one true streaming
model, Nemotron, the default, decoded chunk by chunk while recording). A new provider is just a new implementation.
Silero VAD ("Skip silence") decides which audio is sent (cloud: to cut STT cost; On device: also where phrases end).

## Tech stack & build
- AGP 9.x with built-in Kotlin (no separate `kotlin-android` plugin). Kotlin 2.2.10, Compose compiler plugin,
  kotlinx.serialization plugin.
- `minSdk 30`, `targetSdk`/`compileSdk 37`, Java 11 target. JDK 25 locally.
- Compose foundation (custom settings UI, no Material), activity-compose 1.13.0 (`PredictiveBackHandler`), OkHttp
  (HTTP + WebSocket), kotlinx.serialization (JSON), Jetpack DataStore Preferences, Coroutines/Flow.
- sherpa-onnx v2.0.1 (CP14: on-device STT (offline recognizers; CP15: one online/streaming recognizer) and the Silero
  VAD), the AAR from JitPack: group `com.github.k2-fsa.sherpa-onnx`, artifact `sherpa-onnx` (POM without dependencies),
  resolved through an `exclusiveContent` JitPack repository in `settings.gradle.kts` (nothing else resolves from there).
  Never the aggregate `com.github.k2-fsa:sherpa-onnx` POM (pulls `sherpa-onnx-jvm` + desktop native libs). Its AAR
  manifest is empty and `proguard.txt` too; the JNI reads config fields by name, so `rules.keep` keeps
  `com.k2fsa.sherpa.onnx.**` (checked in an R8 release build: recognizer + VAD load, a decode works). Packaged per ABI:
  its own ONNX Runtime 1.28.2 `libonnxruntime.so` (arm64 22,249,560 B, x86_64 25,581,120 B) and `libsherpa-onnx-jni.so`
  (4,609,320 / 5,053,592 B); `libsherpa-onnx-c-api.so` / `-cxx-api.so` are excluded (`packaging.jniLibs.excludes`:
  nothing loads them). Licence: `assets/sherpa_onnx_LICENSE.txt` (Apache-2.0, `LICENSE` at tag `v2.0.1`). Pinned by
  Gradle dependency verification (`gradle/verification-metadata.xml`, sherpa only: every other group is trusted by a
  regex, metadata and signatures aren't verified): AAR SHA-256
  `a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7` (= the GitHub release AAR, which JitPack
  re-publishes), so a re-pointed tag or rebuild fails the build; the POM's checksum is listed but not enforced
  (`verify-metadata` false). A version bump needs new checksums there.
  ONNX Runtime Android (`com.microsoft.onnxruntime`, 1.28.0, the VAD's runtime until CP14) was dropped: both AARs
  ship a `libonnxruntime.so` and only one can be packaged, but their symbols are versioned (MS 1.28.0 exports
  `OrtGetApiBase@@VERS_1.28.0` and its JNI imports `@VERS_1.28.0`; sherpa's exports and imports `@VERS_1.28.2`;
  `readelf --dyn-syms`, 2026-11-13), so either copy fails the other JNI (emulator: `dlopen failed: cannot locate
  symbol "OrtGetApiBase"`); Maven Central has no `onnxruntime-android` 1.28.2 (1.29.0 adds default-on telemetry).
  The VAD now runs through sherpa's `Vad` (see VAD). No `ai.onnxruntime.TelemetryInitializer`: INTERNET comes from
  OkHttp. `abiFilters` per build type: debug arm64-v8a + x86_64 (phones + emulator), release arm64-v8a only
  (~31 MB less): 32-bit-only devices can't install the app, and a release build won't install on the x86_64 emulator. Native libs
  are stored uncompressed: debug APK 95.8 MB before CP14 (MS ORT), 89.8 MB after (release, R8: 65.3 MB; a store
  bundle would ship one ABI).
- Sonic (time-stretch, "Speed"), vendored, no dependency: `audio/sonic/Sonic.java` (Java, Apache-2.0, Bill Cox) from
  https://github.com/waywardgeek/sonic commit `1d7051354f32392a3b3cdb8a8bcfe56e6f8b744f` (upstream file SHA-256
  `c23fb313fafa9cdab4c9aa291bfdc2426f33a42d0e19e3e8d1cfd3f85258d7df`); only the package line changed (noted in its
  header); licence ships as `assets/sonic_LICENSE.txt`.
- Fonts, bundled in `res/font` (not downloadable fonts), from https://github.com/google/fonts commit
  `3e5667764ccfea32ce0c3d58a3a4756de890adc1` (2026-11-13): `inter.ttf` = `ofl/inter/Inter[opsz,wght].ttf` (variable,
  876,576 B, SHA-256 `29160a80ff49ddcab2c97711247e08b1fab27a484a329ce8b813d820dc559031`; one instance per weight
  400/500/600/800 via `FontVariation`, opsz left at 14) and `blinker_semibold.ttf` = `ofl/blinker/Blinker-SemiBold.ttf`
  (81,676 B, SHA-256 `a9a701eb18cbd5f786f28f986c16895ffc0d0337e4a9a63131b619d9542e07d4`); OFL licences ship as
  `assets/inter_OFL.txt` / `assets/blinker_OFL.txt`.
- Lucide 1.72.0 icons (https://github.com/lucide-icons/lucide/tree/1.72.0/icons) as `res/drawable/lucide_<name>.xml`
  (18: accessibility, mic, audio-lines, sparkles, eye, eye-off, cloud, arrow-down, arrow-right, refresh-cw, trash,
  radio, check, chevron-left, chevron-down, plus, smartphone, gauge): viewport 24, white stroke (tinted in Compose),
  round caps/joins, circles/rects converted to paths; `android:width` = the use size and `strokeWidth` chosen so the
  drawn stroke matches the Figma export (1.5 at 24/16 dp, 1.57 at 23, 1.64 at 22, 1.25 for sparkles, 1.6 at 15, 2.2
  at 11; `smartphone` 14 dp at 1.7, drawn untinted by `BrandIcon` as On device's provider icon). The
  overlay keeps its own `ic_mic`/`ic_close`/`ic_check`/`ic_exclamation`. `assets/lucide_LICENSE.txt` is the full
  upstream LICENSE (ISC + the Feather MIT block, "Copyright (c) 2013-present Cole Bemis": radio, check, chevrons,
  plus, trash, arrows are Feather-derived).
- Logo `res/drawable/iris_logo.xml` (104 x 30 dp) converted from the designer's `iris_logo.svg` (viewBox 834.46 x
  240.43); fills `@color/logo_body` / `@color/logo_capsule` (light #3F3F42 / #777A80, `values-night` #D9DBDC /
  #8E9196).
- Brand marks `res/drawable/provider_vercel.xml` (16 x 13.875 dp, white) / `provider_openrouter.xml` (16 x 11.4 dp,
  #C8FF00) / `provider_openai.xml` (15.75 x 16 dp, white) / `provider_deepgram.xml` (16 x 16 dp, #E042FF) /
  `provider_soniox.xml` (13.48 x 16 dp, #1881FF, from the SVG's `.st0` class), path data copied from the
  user-supplied SVGs (viewBoxes 256 x 222 / 1024 x 730 / 256 x 260 / 24 x 24 / 211.35 x 250.84; no transforms); the
  marks belong to their owners. `BrandIcon` (`ui.components`) draws one untinted at 14 dp.
- Manual DI: `AppContainer` held by the custom `Application` class `IrisApp`. No Hilt/Koin.
- All versions live in `gradle/libs.versions.toml`.

## Architecture
| Package | Responsibility |
|---|---|
| (root) | `IrisApp` (Application; releases the on-device model on memory trim), `AppContainer` (manual DI, app scope, Soniox catalogue, on-device recognizer + model store + stats, picks the STT and cleanup providers), `MainActivity` (hosts settings UI) |
| `data.settings` | Settings model, `SettingsRepository` (DataStore), `SecretStore` (Keystore crypto) |
| `ui.settings` | `SettingsApp` (navigation, catalogue load triggers), the five pages, `ModelCatalogs`, `SettingsText` (pure copy/format helpers), `Favicons` (custom providers' icons) |
| `ui.components` | The design system: cards, rows, buttons, switch, slider, text fields, dropdowns, page scaffold, `Saver`, pure `commitDecision` / `dropdownPlacement` / `sliderValue` / `filterModels` |
| `ui.theme` | `IrisTheme` (own CompositionLocals, no Material): `IrisColors` (light + derived dark), `IrisType` (Inter + Blinker) |
| `audio` | PCM16 mono recorder (rate per session); `SileroVad` (sherpa `Vad` wrapper), `SpeechGate` (pure VAD gate); `timeStretched` (Sonic speed-up) |
| `stt` | `SpeechToTextProvider`; `stt.openai` / `stt.vercel` / `stt.deepgram` / `stt.soniox` streaming + upload providers; `stt.ondevice` (On device: `OnDeviceModels` catalogue, `OnDeviceModelStore` downloads, `OnDeviceRecognizer` sherpa holder, `OnDeviceSpeechToText` provider, `OnDeviceStats` per-model statistics); `Wav.kt` (upload helpers) |
| `cleanup` | `CleanupProvider` interface, `parseProviderOptions`; `cleanup.vercel` / `cleanup.openrouter` / `cleanup.compatible` (user-added OpenAI-compatible) implementations (Chat Completions helpers shared in `GatewayProtocol.kt`) |
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
  settings and calls `setPanelSize` (also before the first show), applied live. Appearance > Text Panel: two
  sliders ("Lines", "Max Width"), saved on release.
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
  cancelling the coroutine aborts. `language`: an ISO-639-1 code or blank (auto-detect); Soniox: a comma-separated
  list (the controller passes `Settings.activeSttLanguage`: `sonioxLanguages` for Soniox, else the shared
  `sttLanguage`). `timeoutMs`: how long to wait for the final transcript once all audio is sent;
  the controller passes `Settings.sttTimeoutSeconds` (5..30, default 10; min 5 so a too-short wait can't fail
  every dictation) per session, all cloud providers use it (below; On device ignores it). Failures are
  `SttException` with a user-readable message; exceptions from `audio` propagate unchanged (the VAD no-speech path
  relies on it). `warmUp()`: fire-and-forget pre-connect, throttled by the provider (below). `sampleRate(model)`: the
  rate `audio` must have (default 24 000; the Gateway and upload providers override it). `streams` (default true): sends
  audio while recording over a socket (gets the VAD keep-alive); false = no socket while recording, so no keep-alive:
  the upload providers (upload after stop, never call `onPartial`) and On device. `liveText(model)` (default `streams`;
  OpenAI realtime: `hasLiveText(model)`; On device: true): reports text while recording; it picks the VAD hangover.
  `allowsSpeedUp` (default true; Soniox real-time false): the audio may be sped up (Speed, below). `wantsSegmentEnds`
  (default false; On device true): `audio` carries an empty `ByteArray` at the end of each speech segment the VAD gate
  detects (Segment ends, below); no other provider ever sees one (an empty frame would close Deepgram's stream). On
  device's streaming model (CP15) skips them (one stream for the whole dictation).
- `dictation.DictationController` (one per service, main thread, built by `AppContainer.dictationController`;
  `audio: (sampleRate) -> Flow<AudioChunk>` = `audioRecorder::chunks`, `stt: (Settings) -> SpeechToTextProvider` =
  `AppContainer::speechToText`, picked once per session after the settings load, with `Settings.activeSttModel`;
  `vadModel: () -> (FloatArray) -> Float` = `sileroVad::newStream`, a fresh Silero stream per session with VAD on;
  `vadContext` = `Dispatchers.Default`, where the gate runs, tests pass `EmptyCoroutineContext`;
  `warmUpCleanup: (Settings) -> Unit` = `{ AppContainer.cleanup(it).warmUp() }`, called with the session's settings):
  `Idle -> Listening(text) -> Finalizing(text) -> Done(text, notice?) | Error(message) -> Idle` (Done shown 1.5 s,
  or 3 s with a notice, which the panel shows instead of the text; Error 3 s; blank transcript, or nothing passed
  VAD = Error "No speech detected"). An upload provider stays at `Listening("")`/`Finalizing("")` (blank text keeps
  the panel hidden). `start()`: new session (also from Done/Error), ignored while Listening/Finalizing;
  `stop()`: Listening only; `cancel()`: discards any session. The service maps the button to `cancel()` while
  Listening, else `start()` (so the spinner ignores taps), and the pill to `stop()`. A tap that acts (start, stop,
  cancel; decided from the state at tap time) gets a haptic (`haptic()`: `EFFECT_HEAVY_CLICK`, touch usage, so the
  touch-haptics setting applies; needs `VIBRATE`); ignored taps, drags and focus-loss cancels don't. Finalizing
  can't be cancelled by a tap: stalls end via the STT timeout `T` = `sttTimeoutSeconds` (OpenAI, Deepgram and
  Soniox real-time: 60 s drain, then `T`; Soniox async: `T` + audio / 4 for each request and for the polling, after
  up to 5 s for the catalogue (strict-hints check) when hints are set and it isn't loaded; Gateway: 60 s drain, `T`
  stall, one reconnect within its budget, then `T` stall again) / cleanup `cleanupTimeoutSeconds`.
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
- `audio.SileroVad` (one per app in `AppContainer`, not unit tested: native): since CP14 on sherpa-onnx's ONNX
  Runtime (Tech stack: MS ORT and sherpa can't share one `libonnxruntime.so`). One `com.k2fsa.sherpa.onnx.Vad`
  (`VadModelConfig(SileroVadModelConfig(model = "silero_vad.onnx" from assets, windowSize = 512), sampleRate =
  16000, numThreads = 1)`), loaded lazily on the first frame (off main, on `vadContext`; emulator log `SileroVad:
  Model loaded in 115-251 ms`, plus sherpa's own `W sherpa-onnx: VadModelConfig(...)` line) and never closed
  (process lifetime). Only `Vad.compute(samples)` is used: one model run with the LSTM state kept natively, `sr` from
  the config (sherpa v2.0.1 `silero-vad-model.cc` `RunV5`: `x` = `[1, n]` of exactly the samples passed, checked
  2026-11-13); sherpa's own segmentation (`acceptWaveform`, thresholds) is unused. I/O as the official `OnnxWrapper`
  (`silero_vad/utils_vad.py` v6.2.3): the closure passes the previous input's last 64 samples + the 512-sample frame
  (576), so the model sees the same input as before CP14. sherpa's own `acceptWaveform` also feeds 512 + 64-sample
  windows; only `compute()` passes the given samples through unchanged, so the closure adds the context itself.
  `newStream()` = a closure with its own zeroed context; the native state is reset (`Vad.reset()`) on the first frame of
  a stream other than the last one that ran (`synchronized`, so an ended session finishing a frame can't run
  concurrently). Java-level load failures (`LinkageError`, the API's `require(ptr != 0L)`) are logged once and the
  stream throws on every frame (the gate fails open); native load errors in sherpa's `Vad` constructor
  (`Vad_newFromAsset` has no try/catch: an `Ort::Exception`, `bad_alloc` or its model checks) abort the process instead.
  Not verified with real speech since the switch (emulator mic is silent): VAD v6.2.3 under sherpa needs a device check
  (below).
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
  any padding, `input_audio_buffer.commit` / `audio-done` or upload (provider tests pin this). Assumption: no
  provider bills a session that received no audio (only `session.update` / the start frame), except Soniox real-time,
  which bills the stream's wall-clock duration.
- Keep-alive (streaming providers only, `stt.streams`): in `transformWhile`, when the gate has sent nothing for 5 s
  (`readAtNanos` of the last emitted chunk, starting at the first chunk), one chunk-sized `ByteArray` of zeros is
  emitted and the timer resets, since servers may end a stream that gets no audio (e.g. Deepgram after 10 s: `1011
  NET-0001`, which its KeepAlive message doesn't reset, and `NET-0002 no_audio_timeout`; Soniox after 20 s: `408
  request_timeout`, so no `{"type":"keepalive"}` is needed; the zeros are real audio, so they satisfy all of these, and
  are billed; the Gateway upstreams' limits are undocumented); pings only keep the TCP/NAT path. ~1 % of the withheld
  silence. Zeros don't count in `sentBytes`: a session with no speech still ends with "No speech detected" and no commit
  / `audio-done` (if it ran >= 5 s, the socket got only keep-alive zeros). Upload providers get none.
- Setting `Settings.vadEnabled` (`vad_enabled`, default on): Transcription Provider > Audio > "Skip Silence" switch,
  subtitle "Detects speech on-device and sends only that, to cut cost." (same for every cloud model; on Soniox
  real-time it saves nothing, since that is billed by session length); On device: "Detects speech on-device and
  transcribes each phrase as you pause.", or with the streaming model "Detects speech on-device and transcribes only
  that." (`skipSilenceSubtitle`, pure, unit tested).
- Segment ends (CP14, only for a provider with `wantsSegmentEnds`): `SpeechGate(..., markSegmentEnds = true)` (the
  controller passes `stt.wantsSegmentEnds`) sets a flag when a segment's hangover runs out (`keepsFrame` flips
  `inSpeech` off); `process()` then returns its bytes plus one empty array (`[chunk, empty]` if the chunk was kept,
  else `[empty]` alone) and clears it. In-band, so the order survives the `flowOn`/`produceIn` buffers and Sonic (a
  side channel would race them). Size 0: `sentBytes`, `NO_SPEECH` and the speed log are untouched. None in fail-open
  mode or at the stop (the provider decodes the rest when the flow completes). `timeStretched` flushes Sonic on an
  empty input, emits that output, then passes the empty array on (unit tested: the audio before it ≈ the segment /
  speed). Unit tested in `SpeechGateTest`, `TimeStretchTest` and `DictationControllerTest` (exactly one empty array
  between two segments, also at 1.5x; none for a provider that didn't opt in). On device's streaming model (CP15)
  drops them in the provider: the gate still only decides which audio is decoded.

Speed (CP8):
- `audio.timeStretched(sampleRate, speed)` (`TimeStretch.kt`, pure, unit tested): `Flow<ByteArray>` of PCM16 mono
  -> the same audio `speed` times faster through vendored Sonic (`Sonic(rate, 1)`, `setSpeed`, defaults otherwise:
  quality 0, pitch/rate/volume 1, so only `changeSpeed` runs and pitch is kept). Output rate = input rate (WAV
  headers, start frame and `session.update` rates stay right). Sonic holds back up to ~30 ms; `flushStream()` emits
  that tail (measured 12-27 ms of output) when upstream completes normally; an upstream error propagates unchanged
  with no flush. Never emits empty arrays except an empty input (a segment end), passed on after a mid-stream flush
  (`flushStream` pads with silence, trims to the expected count and resets its input buffers; the stream stays
  usable; it lands in hangover silence).
- Placement: after `onCompletion` (VAD), before `stt.transcribe`, only when `sttSpeedPercent != 100` and
  `stt.allowsSpeedUp` (otherwise no Sonic object, the same arrays). Soniox real-time is never stretched (billed by
  wall-clock stream time, wants real-time pace) and the "Audio Speed" slider is hidden for a live Soniox model. After
  `transformWhile`, so only sent chunks reach Sonic and the flush happens exactly at the stop; after `onCompletion`, so
  `NO_SPEECH` is thrown before any flush output (nothing reaches the provider). Every provider acts (commit /
  `audio-done` / upload) only after its `audio.collect` returns, i.e. after the tail. Keep-alive zeros go through Sonic
  too (keeps byte order; a 50 ms zero chunk comes out as ~25-45 ms).
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
- Cleanup runs only if `cleanupEnabled`, through `container.cleanup(settings)` with the active prompt and the selected
  provider's `activeCleanupModel` / `activeCleanupReasoningEffort` /
  `parseProviderOptions(activeCleanupProviderOptions)`; no separate status text: the bubble spins (Finalizing) until
  insertion is done. The panel keeps the transcript; the cleaned text is never shown. `CleanupProvider.cleanup` throws
  `CleanupException` (user-readable). Any cleanup failure (incl. missing key; an options parse failure, impossible via
  the UI, is the generic "Cleanup failed") inserts the raw transcript; Done's notice is the error plus "Inserted the
  original text" (or "Original copied to clipboard"), so dictation is never lost. Other unexpected cleanup exceptions
  are logged and treated the same. Overall deadline: `Settings.cleanupTimeoutSeconds` (1..30, default 5, like Handy's
  `post_process_timeout_secs`); `onFinalTranscript` wraps the cleanup call in `withTimeoutOrNull` (not `withTimeout`:
  its `TimeoutCancellationException` is a `CancellationException` and would be rethrown, losing the dictation).
  Cancelling cancels the OkHttp call (`invokeOnCancellation`). On timeout the raw transcript is inserted with the
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
  `hintText`. An invalid selection (`mergeInsertion`'s rule) means the text can't be trusted (checked 2026-11-12:
  WhatsApp's empty box reports its placeholder "Message" as text, no hint, selection -1..-1, so it got
  "Message <dictation>"): first clipboard + `ACTION_PASTE` of the unspaced text (lands at the app's real cursor);
  only if that returns false the steps below. Otherwise `ACTION_SET_TEXT` with the merged text, then `ACTION_SET_SELECTION` to the end of the inserted text.
  If SET_TEXT returns false: clipboard + `ACTION_PASTE` of the same spaced text (previous clipboard not restored).
  A non-editable node (keyboard only) skips SET_TEXT: clipboard + `ACTION_PASTE` of the unspaced text.
  No/stale node or paste fails: text left on the clipboard, notice "Copied to clipboard".

Providers (settings store a provider id for each):
- STT provider: `Settings.sttProviderId` (`stt_provider_id`): `openai` (default) = OpenAI (direct),
  `vercel-gateway` = Vercel AI Gateway, `deepgram` = Deepgram (direct), `soniox` = Soniox (direct), or `on-device` =
  On device (`sttOnDevice`; model `onDeviceSttModel` / `on_device_stt_model`, default `nemotron-streaming-en`, an
  unknown id = the default model; no key, so `sttKeySet` is true; uses the shared `sttLanguage`); an unknown id means
  OpenAI. Each has its own model setting (`sttModel`, `gatewaySttModel`, default
  `openai/gpt-live-transcribe`, `deepgramSttModel`, default `nova-3`, `sonioxSttModel`, default `stt-rt-v6`) and key
  (Soniox also `sonioxRegion`, default `us`); `activeSttModel` / `sttKeySet` follow the selection; the language
  setting is shared except Soniox's own `sonioxLanguages` (`activeSttLanguage`). `AppContainer` builds seven
  providers plus two per Soniox region; `speechToText(settings)` picks one by mode: On device -> the one on-device
  provider (every model); Soniox -> the region's
  `sonioxStreams(sonioxSttModel, catalogue)` ? streaming : upload; Deepgram ->
  `deepgramUpload` ? upload : streaming; Gateway -> `gatewaySttLive` ? streaming : upload; OpenAI ->
  `isRealtimeTranscriptionModel(sttModel)` ? realtime : upload. The service's warm-up cache follows the selection
  (Soniox: recomputed only on settings changes, so a mode that comes from the catalogue reaches the warm-up only at
  the next settings change).
- WebSocket pings: all four streaming providers connect on `httpClient.newBuilder().pingInterval(30, SECONDS)`, so long
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
  but only gpt-live-transcribe and gpt-realtime-whisper send text before the commit (`hasLiveText`: live hangover;
  the others get the short hangover). Any other id (trimmed) uploads.
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
- Uploads (all three providers, `stt/Wav.kt`): `wavBytes` = 44-byte RIFF/WAVE header (PCM, mono, 16-bit) + the chunks,
  padded with silence to 1.25 s when under 1 s (as Handy: services reject very short files). Per upload,
  `httpClient.newBuilder().readTimeout(uploadReadTimeoutMs)` = `timeoutMs + audio duration / 4` (the server's
  transcription time grows with the audio; default 10 s + 15 s for 1 min); OkHttp's 10 s write timeout covers a
  stalled upload; no call timeout. A timeout before the body was fully written (`sent` flag, `SentBody` or the
  Gateway's `UploadBody`: reset at the start of `writeTo`, set at its end) = "Timed out sending audio to OpenAI" /
  "... to AI Gateway" / "... to Deepgram", after = "Timed out waiting for the transcript". `Call.await()`
  (enqueue, cancelling the coroutine cancels the call). Warm-up: keyless `HEAD /v1/models` (Deepgram: `HEAD /v1/listen`)
  through the default (h2) client, 3 min throttle (reset only by a failure: an h2 connection returns to the pool).
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
- STT, Deepgram (direct) (`stt.deepgram.DeepgramSpeechToText`): live transcription WebSocket, one socket, no
  reconnect (like OpenAI). Verified 2026-11-13 (`stt.deepgram.DeepgramProtocol.kt`) against
  https://developers.deepgram.com/reference/speech-to-text/listen-streaming, `/docs/close-stream`, `/docs/errors`,
  `/docs/audio-keep-alive`, `/docs/understand-endpointing-interim-results`, `/docs/multilingual-code-switching`,
  `/docs/models-languages-overview` and https://deepgram.com/pricing; Handy's `deepgram_live_transcription.rs` does
  the same. Connect `wss://api.deepgram.com/v1/listen` with `Authorization: Token <key>` (`Bearer` is for JWTs) and
  query `model, language, smart_format=true, punctuate=true, encoding=linear16, sample_rate=16000, channels=1,
  interim_results=true` (`listenUrl`, pure, tested); a blank language sends `language=multi` (Nova-3
  code-switching, 55 languages, ~19 % pricier: streaming $0.0092 vs $0.0077/min pay as you go) plus
  `endpointing=100` (the docs' code-switching advice). Not sent: `mip_opt_out`, `dictation`, `keyterm`,
  `vad_events`, `utterance_end_ms`. 16 kHz for every model (`sampleRate`). Audio = raw PCM16 binary frames, one
  per chunk, never empty (an empty frame closes the stream). On stop: `{"type":"CloseStream"}`, OkHttp drain wait
  (60 s, "Timed out sending audio to Deepgram"), then `T` for `Metadata` (sent last) or the server's close 1000
  (either ends it, only after CloseStream; an earlier Metadata is ignored) = "Timed out waiting for the
  transcript". Server messages (`parseDeepgramEvent`, unknown/malformed = ignored): `Results` (`is_final`,
  `channel.alternatives[0].transcript`); final = the `is_final` transcripts joined (`joined`, CJK-aware; never
  `speech_final` alone, per the docs), live text = finals + the latest interim (`DeepgramTranscript`, tested);
  `Error` (`description`, non-fatal per the docs) is `Log.w`-ed and the session continues. No padding (no minimum):
  an instant stop gets Metadata with no Results = blank = "No speech detected". A close without CloseStream (e.g.
  `1011 NET-0001`) = "Deepgram closed the connection: <reason>"; network failures "Network error: ...". Handshake
  errors (`deepgramHttpErrorMessage`, body read in `onFailure`): 401 key, 402 "Deepgram account is out of credits",
  429 rate limit (150 concurrent streams), 5xx server, else "Deepgram error: <err_msg | message | dg-error header>".
  Warm-up: keyless `HEAD https://api.deepgram.com/v1/listen` (405, no body; ~240 ms after TLS with curl
  `--http1.1`, 2026-11-13) through the HTTP/1.1 client like OpenAI's, 3 min throttle, reset by `transcribe` and by a
  failure (the host's idle timeout is unmeasured). Logs `Timing: transcribe->ws open X ms` and `Timing:
  CloseStream->Metadata X ms`. Not via the Gateway: its catalog (2026-11-13) has no `deepgram/*` model. Models: a
  static menu list `DEEPGRAM_MODELS` (nova-3, nova-3-medical, nova-2; the keyless `GET /v1/models` takes 6-15 s
  and 207 KB); any id can be typed. Flux (`/v2/listen`) is out of scope.
- STT, Deepgram upload (`stt.deepgram.DeepgramFileTranscription`, when `Settings.deepgramUpload`): pre-recorded
  API, verified 2026-11-13 against https://developers.deepgram.com/reference/speech-to-text/listen-pre-recorded
  and `/docs/pre-recorded-audio`. `POST https://api.deepgram.com/v1/listen?model&language&smart_format&punctuate`
  (`listenUrl(streaming = false)`: no format params, blank language = `multi`, no endpointing), `Authorization:
  Token <key>`, `Content-Type: audio/wav`, body = 16 kHz `wavBytes` -> `{metadata, results: {channels:
  [{alternatives: [{transcript}]}]}}` (`parseListenResponse`; missing = "Unexpected response from Deepgram"; blank
  = "No speech detected" via the controller). Limits: 2 GB per file, 10 min processing (then 504), 50 concurrent
  requests: one upload, no splitting. `streams = false`; key checked before collecting; errors map like the
  handshake (no `dg-error` header: `Call.await` returns only code + body); transport = "Network error: ...".
  Pricing (pay as you go, 2026-11-13): Nova-3 $0.0043/min mono-language, $0.0052 multi: ~44 % cheaper than
  streaming. Warm-up: keyless `HEAD /v1/listen` through the default (h2) client, 3 min throttle (reset by a
  failure). Logs `Timing: upload X KB, stop->response Y ms`.
- Deepgram mode: `Settings.deepgramUpload` (`deepgram_upload`, default false), Transcription Provider > Model
  Settings switch "Disable Realtime Streaming", shown only with Deepgram selected.
- STT, Soniox (direct) (`stt.soniox.SonioxSpeechToText`, `real_time` models): WebSocket, one socket, no reconnect,
  no async fallback. Verified 2026-11-13 (`stt.soniox.SonioxProtocol.kt`) against
  https://docs.soniox.com/api-reference/stt/websocket-api, `/authentication/websocket-authentication`,
  `/stt/rt/connection-keepalive`, `/stt/rt/error-handling` and `/api-reference/errors`. Connect the region's
  `wss://stt-rt[.<region>].soniox.com/transcribe-websocket` with `Authorization: Bearer <key>` only (no
  subprotocol, never the deprecated `api_key`). A bad key doesn't fail the handshake: an error frame follows. First
  (text) frame `{model, audio_format:"pcm_s16le", sample_rate:16000, num_channels:1, language_hints?,
  language_hints_strict?}` (`sonioxStartMessage`): hints = `sonioxLanguageHints(language)` (split on `,`, trimmed,
  lowercased, unique; not filtered against the catalogue), only when non-empty; strict only with hints and when the
  model's catalogue entry has `supports_language_hints_strict` (else the server answers 400). The start frame goes
  out at the first non-empty chunk (or at the end if none), so the mic opens at once; with hints it waits for
  `SonioxCatalog.entry` (up to 5 s if the catalogue isn't loaded; the audio queues meanwhile in the controller's
  `flowOn`/`produceIn` channels, 2 x 64 chunks = 6.4 s, so nothing is lost, but the waveform pauses). Audio = raw
  PCM16 16 kHz binary frames, never empty; end = an empty TEXT frame (`""`), then OkHttp drain (60 s, "Timed out
  sending audio to Soniox"), then `T` for a `{"tokens":[], "finished":true}` response ("Timed out waiting for the
  transcript"). Responses (`parseSonioxEvent`): `tokens[]` (`text`, `is_final`; `<fin>`/`<end>` dropped) +
  `finished`; `SonioxTranscript`: finals appended once, the tail replaced by each response's non-finals, tokens
  carry their own spacing (concatenated, trimmed); final = the finals. Error frame `{error_code, error_type,
  error_message}` (then the server closes) = `sonioxErrorMessage`: 401 key, 402 "out of credits or over budget",
  403 permission, 429 "Soniox limit reached: <message>", 5xx server, else "Soniox error: <message>"; except 400
  "No audio received." (`isNoAudioError`, the one message match) = "" -> "No speech detected". A close before
  `finished` = "Soniox closed the connection: <reason>"; rejected handshake = `sonioxHttpErrorMessage` (REST error
  body `message`, else the first `validation_errors[].message`); network = "Network error: ...". `allowsSpeedUp`
  false; `streams`/`liveText` true (live VAD hangover; the 5 s keep-alive zeros cover Soniox's 20 s no-audio 408).
  Warm-up: keyless `HEAD` on the WebSocket URL through the HTTP/1.1 client like Deepgram's (426 Upgrade Required,
  `Connection: keep-alive`, ~280 ms warm with curl `--http1.1`, 2026-11-13), 3 min throttle, reset by `transcribe`
  and by a failure (idle timeout unmeasured). Logs `Timing: transcribe->ws open X ms` and `Timing: end->finished X
  ms`.
- STT, Soniox async upload (`stt.soniox.SonioxFileTranscription`, `async` models): REST job flow, verified
  2026-11-13 against `/api-reference/stt/files/upload_file`, `.../files/delete_file`,
  `.../transcriptions/{create,get,delete}_transcription`, `.../get_transcription_transcript`. `Authorization:
  Bearer <key>`; key checked before collecting. `POST <base>/v1/files` multipart `file` (`audio.wav`, 16 kHz
  `wavBytes`) -> `{id}`; `POST /v1/transcriptions` `{model, file_id, language_hints?, language_hints_strict?}` (same
  rules; the strict check's `SonioxCatalog.entry` runs after the stop, so with hints and no catalogue the upload starts
  up to 5 s later (inside `stop->transcript`)) -> `{id}`; poll `GET /v1/transcriptions/{id}` after 250, 500, then
  every 1000 ms until `completed` (`error`/`failed` = "Soniox error: <error_message | error_type | transcription
  failed>"); then `GET .../{id}/transcript` -> `text`. Any 2xx accepted; missing ids/status/text =
  "Unexpected response from Soniox". Budget = `uploadReadTimeoutMs(T, audio)`: each request's read timeout, and the
  poll deadline from the create response ("Timed out waiting for the transcript"); an upload timeout before the body
  is written = "Timed out sending audio to Soniox". Always (success, error, timeout, cancel): fire-and-forget `DELETE
  /v1/transcriptions/{id}` and `DELETE /v1/files/{id}` (enqueued on the base client, outlive cancellation; a job
  still processing answers 409 and is left to the 30-day auto-delete: no retry). Warm-up: keyless `HEAD
  /v1/models` (405, HTTP/1.1 only, keep-alive; 2026-11-13) on the default client, 3 min throttle (reset by a
  failure). Logs `Timing: upload X KB, stop->transcript Y ms, N polls`.
- Soniox catalogue (`stt.soniox.SonioxModels.kt`, `SonioxCatalog`): `GET <base>/v1/models` with the key (401
  without one) -> `{models:[{id, transcription_mode, supports_language_hints_strict, ...}]}`
  (`parseSonioxModels`: sorted, no-id entries skipped, missing flag = false). No/blank key = "Set your Soniox API
  key to load models", unsendable key = "Invalid Soniox API key" (no request); errors `sonioxHttpErrorMessage`,
  transport `modelsNetworkErrorMessage`, no `models` = "Unexpected response from Soniox". `SonioxCatalog` (one per
  app in `AppContainer`, main-thread app scope, in memory): `reload()` (cancels a running load, whose result is
  never written, clears, loads) runs at app start and whenever the stored Soniox key ciphertext
  (`SecretStore.changes`) or `sonioxRegion` changes; `refresh()` loads unless one is running (refresh button,
  first menu open, screen open with Soniox, a session); `entry(model)`: loaded -> the entry or null (no refetch);
  not loaded -> loads and waits up to 5 s (`CATALOG_WAIT_MS`), null on timeout/failure (the load keeps running).
  Mode (`sonioxStreams`): the entry's `transcription_mode == "real_time"`; no entry (not loaded, typed id) = id
  starts with `stt-rt-` (`speechToText` is sync: no wait). Typed ids not in a loaded catalogue: no refetch, strict
  omitted.
- Soniox region: `Settings.sonioxRegion` (`soniox_region`: us, eu, jp, in; unknown = us), `SonioxRegion` with the
  REST / WebSocket domains `api[.eu|.jp|.in].soniox.com` / `stt-rt[.eu|.jp|.in].soniox.com`; a project's key works
  only on its region's domains. Soniox languages: `Settings.sonioxLanguages` (`soniox_languages`, default "",
  trimmed on save), separate from the shared `sttLanguage` (other providers take one code).
- Soniox pricing (2026-11-13): real-time ~$0.12/h, billed by wall-clock stream time (VAD gaps don't cut it, so a
  long pause still costs); async ~$0.10/h by audio duration (VAD and Speed cut it).
- STT, On device (CP14, `stt.ondevice`): sherpa-onnx offline recognizers (CP15: plus one online recognizer for the
  streaming model) on CPU, models downloaded in settings; no network while dictating. Catalogue
  (`OnDeviceModels.kt`, pure, unit tested; menu order; files pinned to a Hugging Face commit,
  `https://huggingface.co/<repo>/resolve/<commit>/<file>`; sizes + SHA-256 re-checked against the HF API 2026-11-13):

  | id | label / languages | engine | repo @ commit | size | licence |
  |---|---|---|---|---|---|
  | `nemotron-streaming-en` (default, CP15) | Nemotron Streaming English / English | `NEMO_STREAMING` | `csukuangfj2/sherpa-onnx-nemotron-speech-streaming-en-0.6b-1120ms-int8-2026-04-25` @ `b0b6bae3` | 661,919,419 B | NVIDIA Open Model License |
  | `parakeet-unified-en` | Parakeet Unified EN / English | `NEMO_TRANSDUCER` | `csukuangfj2/sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming` @ `8c3a10fb` | 663,043,117 B | NVIDIA Open Model License |
  | `parakeet-tdt-v3` | Parakeet TDT v3 / 25 European languages | `NEMO_TRANSDUCER` | `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8` @ `ed5d7456` | 670,576,784 B | CC BY 4.0 |
  | `whisper-small` | Whisper Small / 99 languages | `WHISPER` | `csukuangfj/sherpa-onnx-whisper-small` @ `8f3c18b3` | 375,485,327 B | MIT (OpenAI) |
  | `moonshine-base-en` | Moonshine Base EN / English, fastest | `MOONSHINE_V2` | `csukuangfj2/sherpa-onnx-moonshine-base-en-quantized-2026-02-27` @ `8f4d6c58` | 141,300,566 B | MIT (Moonshine AI) |

  Full commits, file names, sizes and hashes are in `ON_DEVICE_MODELS`; the licence line (with its link) shows under
  the download in settings. Configs (`OnDeviceRecognizer`, sherpa Kotlin API v2.0.1): `FeatureConfig(16000, 80)`
  (the NeMo impl takes its feature dim, 128, from model metadata: `offline-recognizer-transducer-nemo-impl.h`
  `PostInit`), `numThreads = 2` (sherpa's Android examples; more threads land on little cores), `greedy_search`;
  transducer `encoder/decoder/joiner` + `modelType = "nemo_transducer"`; Whisper `encoder/decoder`, `language`,
  `task = "transcribe"`, `modelType = "whisper"` (`tailPaddings` default); Moonshine v2 `encoder` + `mergedDecoder`
  (no `modelType`). Whisper language: `whisperLanguage(sttLanguage)` = trimmed + lowercased if in `WHISPER_LANGUAGES`
  (the 99 codes of Whisper Small's `all_language_codes` metadata, no `yue`), else "" (auto-detect): sherpa calls
  `SHERPA_ONNX_EXIT(-1)` (kills the process) on a language Whisper doesn't know, so nothing else may reach it. The
  other engines get "". A failed `OfflineRecognizer(null, config)` (`require(ptr != 0L)`, e.g. a missing file) is an
  `IllegalArgumentException`, not a native exit.
  Streaming (CP15, `OnDeviceModel.streams` = engine `NEMO_STREAMING`): `OnlineRecognizer(null,
  OnlineRecognizerConfig(FeatureConfig(16000, 80), OnlineModelConfig(transducer = encoder/decoder/joiner, tokens,
  numThreads = 2), enableEndpoint = false, greedy_search))`, no `modelType` (the NeMo streaming impl's `PostInit`
  takes feature dim 128, window and mel settings from the model; `ChunkSize()` / `ChunkShift()` = the encoder's
  metadata `window_size` 121 / `chunk_shift` 112 frames, i.e. 1120 ms chunks). Its language is always "".
  `OnDeviceSpeechToText`: 16 kHz, `streams = false` (no socket, no keep-alive), `liveText = true` (1650 ms hangover:
  per-phrase models decode each segment in isolation, so 450 ms would cut sentences at breaths; the streaming model
  just keeps it, so the pause and a whole window reach its stream), `wantsSegmentEnds = true`,
  `allowsSpeedUp` default (Speed just shortens the decode). `transcribe`: an undownloaded model throws "Download the
  <label> model in Iris settings" before collecting (the mic never opens); `preload(model, language)`; audio is
  collected on main into a buffer; an empty array (segment end) queues the buffer; a buffer past `MAX_SEGMENT_BYTES`
  (20 s of model input, stretched bytes: Parakeet's README suggests 15-20 s chunks, Whisper truncates > ~29.5 s,
  Moonshine is trained on short utterances) is cut with `splitAtPauses(buffer, 20 s, 8 s)` and all parts but the last
  are queued. With Skip Silence off there are no segment ends: text comes every ~20 s (the cap) and the rest after
  stop (decided). Queued segments go through a `Channel(UNLIMITED)` to one decoder coroutine (collection never waits
  for a decode: the mic would overflow), which calls `onPartial(joined text)` after each (`joined`, CJK-aware; blanks
  skipped). After the audio ends the buffer is queued, then `withTimeoutOrNull(60 s + 2 x pending audio)` wraps the
  decoder (pending = queued + buffered audio not decoded yet; covers a model load after stop plus RTF <= 2); expiry =
  "On-device transcription timed out" (a hung native decode would otherwise spin the bubble forever). `timeoutMs` is
  ignored (no network to stall; a native decode can't be aborted, so a short timeout only throws text away), and the
  Timeout slider is hidden for On device. Logs `Timing: stop->final X ms, N segments`. `warmUp()` reads the stored
  model + language on `appScope` and preloads it if downloaded. Unit tested with fake lambdas
  (`OnDeviceSpeechToTextTest`, virtual time).
  Streaming model (CP15, `transcribe` after `preload` -> `streamed`): one recognizer stream for the whole dictation
  (context kept across phrases); empty arrays (segment ends) are skipped, every other chunk goes through a
  `Channel(UNLIMITED)` to `stream(model, channel)` (the recognizer's; one emission per chunk taken, then the final
  text), collected in one decoder coroutine: `onPartial(trimmed text)` only when it changed, the result is the last
  text. Deadline after stop as above, pending = the chunks not yet answered by an emission (a deque of their sizes,
  popped per emission; the final emission pops none). Logs `Timing: stop->final X ms, streamed`.
  With Skip Silence on and Speed >= ~1.6x the unscaled hangover (1650 / speed ms of model input) falls below one
  112-frame window shift, so up to 112 - hangover frames of a phrase's end stay undecoded until more audio comes: its
  last words show live only when the next phrase starts or at stop (accepted); the final text is whole (stop padding).
  `OnDeviceRecognizer` (native, not unit tested, like `SileroVad`): one recognizer at a time (`Loaded` holds an
  `OfflineRecognizer` or, for the streaming model, an `OnlineRecognizer`; key `id|lang`; switching model or Whisper
  language releases the old one before loading). Load, decode, unload and release all
  run on `Dispatchers.IO.limitedParallelism(1)` with its own `SupervisorJob` scope: decodes are serialized across
  sessions (Whisper mutates shared decoder state), a session started during a load waits for it, an unload never
  runs mid-decode. `decode` = `scope.async { ... }.await()`: a cancelled session returns at once, the native decode
  finishes on the worker and its result is dropped. Every entry point (`preload`'s launch, `decode`, `stream`'s
  session, `unload`, `release`, the idle release) runs under one `Mutex` (`inUse`); only a `stream` session holds it
  across suspensions, so an unload (model delete), memory trim, idle release or model switch during a streaming
  dictation waits for it to end. `stream` = a `channelFlow` whose session is `scope.async` in the recognizer's scope
  (not the caller's: a cancelled caller returns at once, `session.cancel()` stops it at the next receive / send /
  `ensureActive` between decodes, `finally` releases the native stream and the lock): `createStream()`, per chunk
  `acceptWaveform` + `while (isReady) decode` + emit `getResult().text`; when the channel closes:
  `acceptWaveform(1.3 s of zeros)`, `inputFinished()`, `while (isReady) decode`, emit the final text. The padding
  (`TAIL_PADDING_SAMPLES` = 20,800): `IsReady` needs processed + 121 frames < frames ready and `inputFinished()` only
  flushes the fbank's last frames (sherpa v2.0.1 `online-recognizer-transducer-nemo-impl.h`, `online-stream.cc`), so
  without it the last partial window (up to ~1.2 s of speech) is never decoded; the official examples' 0.6-0.66 s
  is too short for a 121-frame window. `reset()` is never called (endpointing off). Errors: load failure
  (`Exception`/`LinkageError`, `Log.e`) = "Couldn't load the on-device model"; decode failure = "On-device
  transcription failed". Memory: preloaded by `warmUp` (bubble shown / state changes; the service's `sttKeySet`
  gate is true for On device); the service calls
  `setBubbleShown(true)` in `refreshFocus` and `(false)` in the `hideBubble` runnable and `onDestroy`: hidden, the
  model is released once unused for 5 min (re-armed while decodes still run, e.g. Finalizing after focus loss);
  `IrisApp.onTrimMemory(>= TRIM_MEMORY_BACKGROUND)` releases it too (with a bound accessibility service this may
  rarely fire). Logs `Loaded <id> in X ms`, `Decoded X ms of audio in Y ms` (no text), `Released <id>`. Emulator
  (x86_64, 2026-11-13, Moonshine Base EN): load 1.2-1.9 s, 3-4 s of silence decoded in 109-130 ms, `stop->final`
  ~180 ms; release build PSS ~244 MB with it loaded (native heap ~204 MB).
  Statistics hooks (CP16): `decode` returns `Decoded(text, busyMs)` (the native decode's time) and `stream` emits
  `Decoded(text, busyMs so far)` (chunk decodes + the stop flush, cumulative); log lines unchanged. The recognizer's
  `onLoaded(modelId, ms)` (= `OnDeviceStats.recordLoad`) runs after each successful load (the `Loaded` log, preloads
  included). The provider's `record(modelId, audioMs, decodeMs, waitMs)` (= `OnDeviceStats.recordDictation`) is
  called once per dictation whose final text is non-empty: audioMs = the non-empty chunks' bytes at 16 kHz (model
  input, i.e. after Speed), decodeMs = the segments' busy times summed (offline) or the last emission's (streaming),
  waitMs = stop -> decoder joined (the `Timing: stop->final` span). Nothing is recorded for "No speech detected"
  (thrown out of `audio.collect`, or blank text), cancellation, timeout, load/decode failure (all throw first) or a
  cloud provider. Speech before Speed = `speechMs(audioMs, sttSpeedPercent)` (`audioMs * percent / 100`, Sonic is
  exact enough), with the speed read from Settings by the stats store on `appScope` when it records: no change to
  `transcribe`, the controller or the interface, and the audio path never waits (a speed change mid-dictation skews
  that sample; accepted).
- Model downloads (`OnDeviceModelStore`, `AppContainer.onDeviceModels`, unit tested with MockWebServer): one at a
  time, on `appScope` (outlives the screen), OkHttp with a 30 s read timeout, files in
  `noBackupFilesDir/models/<modelId>/` (never backed up; no backup-rule change). `init` scans off main: a model is
  downloaded when every final file exists with its catalogue size (hashes are checked once, at download). Download:
  free space (`StatFs.availableBytes`) must cover what is missing + 50 MB, else "Not enough storage: needs N MB
  free." (no request). Per file: a final file of the right size counts as done (wrong size: deleted); a `<name>.tmp`
  longer than the file is deleted; `GET` with `Range: bytes=<tmp length>-` if a tmp exists (HF `resolve` 302s to its
  CDN, which honours Range; OkHttp keeps the header across the redirect). `206` with `Content-Range` starting at the
  tmp length: the tmp is hashed, then appended to; `200`: restarted from 0; a `206` at another start: the tmp is deleted
  and the file re-requested once without `Range` (a second one: "Download failed (HTTP 206)."); `416`: a tmp of the
  full size goes straight to the check, else it is deleted and retried once from 0; other codes "Download failed
  (HTTP n).". A body longer than the file stops being written there (the size/hash check decides). 64 KiB reads, SHA-256
  on the fly, progress at most every 512 KiB (and at once on a resume). Size or hash mismatch: tmp deleted, "Download
  was corrupted. Try again."; else renamed. Network `IOException` = "Download failed (network error). Tap Download to
  resume." (tmp kept, so the next Download resumes, also after an app restart); a file write failure = "Couldn't save
  the model (storage error).". Cancelling the coroutine cancels the `Call` (a watcher child calls `Call.cancel()`, which
  unblocks the blocking read). Cancel deletes the model's `*.tmp` files, no error (the state reset runs inside the
  `NonCancellable` block: a cancelled coroutine never resumes after `withContext`). Starting a download deletes the
  `*.tmp` files of every OTHER model (an abandoned partial would otherwise use up to ~670 MB until app data is cleared;
  its resume is lost), never the downloading model's own partials or any final files. `delete(id)` sets `State.deleting`
  at once (cleared in a `finally` after the delete; `download(id)` is ignored meanwhile, so a Download can't "complete"
  from files about to be wiped), cancels its download, marks it not downloaded, waits for `OnDeviceRecognizer.unload`
  (after any queued decode), then deletes the directory. Logs `Downloaded <id> in X s`. No WorkManager, no notification:
  a download stops when the process dies (the next Download resumes it). Emulator: Moonshine Base EN (141 MB) in 48 s;
  after a force-stop mid-file the next Download resumed (206 append: the 31 MB encoder finished seconds after the
  restart and passed the hash).
- Cleanup provider: `Settings.cleanupProviderId` (`cleanup_provider_id`): `vercel-gateway` (default),
  `openrouter`, or a saved custom provider's id; any other id (e.g. dangling after a delete or a crash mid-write) is
  read as the Gateway (`toSettings`). `AppContainer.cleanup(settings)` picks the provider: `activeCustomCleanup`,
  else OpenRouter, else the Gateway. Per built-in provider:
  model, reasoning effort and provider options. Gateway: `cleanupModel` (`cleanup_model`),
  `cleanupReasoningEffort` (`cleanup_reasoning_effort`), `cleanupProviderOptions` (`cleanup_provider_options`);
  OpenRouter: `openRouterCleanupModel` (`openrouter_cleanup_model`, default `openai/gpt-6-luna`),
  `openRouterCleanupReasoningEffort` (`openrouter_cleanup_reasoning_effort`, same `low` default / "" = model
  default), `openRouterCleanupProviderOptions` (`openrouter_cleanup_provider_options`); key `Secret.OPENROUTER_API_KEY`
  (`openrouter_api_key`). `activeCleanupModel` / `activeCleanupReasoningEffort` / `activeCleanupProviderOptions`
  follow the selection (custom: its `model` / `reasoningEffort` / "" options). Provider options are stored as
  typed (trimmed; blank = not sent; `{}` is sent as-is);
  `cleanup.parseProviderOptions` (pure, tested; default `Json` plus a check that every value is a quoted string,
  number, true/false or null) returns null for blank, else the JSON object, else throws `IllegalArgumentException`
  ("Not valid JSON" / "Must be a JSON object: { ... }"). The object goes to
  `CleanupProvider.cleanup(..., providerOptions)` and is put as-is under the provider's own top-level key
  (`chatRequest(..., optionsKey, options)`): Gateway `providerOptions` (`GATEWAY_OPTIONS_KEY`, per
  https://vercel.com/docs/ai-gateway/models-and-providers/provider-options; not live-verified), OpenRouter
  `provider` (routing).
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
- Cleanup, OpenRouter (`cleanup.openrouter.OpenRouterCleanup`, pure parts in `OpenRouterProtocol.kt`): sources
  https://openrouter.ai/docs/api_reference/overview, `/guides/features/structured-outputs`,
  `/guides/best-practices/reasoning-tokens`, `/api_reference/errors-and-debugging`,
  `/guides/routing/provider-selection` (2026-11-13; top-level `reasoning_effort` and the 200-with-error shapes are
  assumed, not live-verified). `POST https://openrouter.ai/api/v1/chat/completions`, only `Authorization: Bearer <key>`
  (no `HTTP-Referer` / `X-OpenRouter-Title`); body = the Gateway's `chatRequest` (same structured output,
  `reasoning_effort`, framing) with options under `provider`. Missing key = "Add your OpenRouter API key in Iris
  settings" (no request). Checks in order: non-2xx -> `openRouterHttpErrorMessage` (401 key, 402 "OpenRouter account
  is out of credits", 429 rate limit, 502 "the model's provider failed", 503 "No OpenRouter provider available for
  this model (check provider options)", other 5xx server, else "OpenRouter error: <error.message>" or "OpenRouter
  request failed (HTTP n)"; 403 is moderation/forbidden there, so it is not a key message); a 200 carrying an error
  (`parseOpenRouterError`: top-level `error` without `choices`, or `choices[0].error` / `finish_reason "error"`) =
  "OpenRouter error: <message | generation failed>"; no `choices[0]` = "Unexpected response from OpenRouter";
  `length` = cut off; a refusal with no content = "Model refused: ..." BEFORE `content_filter` (OpenRouter reports
  refusals with that finish reason); `content_filter` = blocked; unusable text = unexpected. Same clients and
  timeouts as the Gateway's (30 s call timeout, no read timeout for cleanup); `execute` wraps `stt.await` (cancelling
  cancels the call). Catalog: keyless `GET https://openrouter.ai/api/v1/models?supported_parameters=structured_outputs`
  (curl 2026-11-13: 200, 67 KB, 372 entries, 82 `:batch`); `parseOpenRouterModels` keeps non-blank ids, drops
  `:batch` ids (batch API only), sorts by id, `GatewayModel(id, null, [], efforts)`; efforts =
  `reasoning.supported_efforts` (listed high -> low, e.g. luna `max..none`) re-ordered to `REASONING_EFFORTS` order,
  unknown values last; missing/empty = null. Errors as above; transport `modelsNetworkErrorMessage`. Warm-up: keyless
  `HEAD https://openrouter.ai/api/v1/models` (no query) on the base client, `Log.d` only; the cleanup POST reuses the
  connection (unit tested).
- Cleanup, OpenAI-compatible (custom) (`cleanup.compatible.OpenAiCompatibleCleanup`, URL helpers in
  `OpenAiCompatibleProtocol.kt`): user-added providers, stored as one JSON list `custom_cleanup_providers`
  (`CustomCleanupProvider(id (UUID), type "openai-compatible", name, baseUrl, model "", reasoningEffort null,
  sendJsonSchema true)`, unknown keys ignored, defaults filled) plus the secret `custom_cleanup_key_<id>`
  (`Secret.customCleanupKey(id)`; `Secret` is a data class over the pref key, every pref of the secrets file is a
  secret; `customCleanupKeyIds` = the ids with one). Repository: `saveCustomCleanupProvider(id, name, baseUrl,
  apiKey?)` (key first, upsert keeping model settings, selects it), `updateCustomCleanupProvider(id, update)` (model
  settings autosave; nothing if gone), `deleteCustomCleanupProvider(id)` (key first, then the entry; a selected one
  falls back to the Gateway). Base URL: https only (UI check), trimmed, trailing `/` dropped; `chatCompletionsUrl` /
  `modelsUrl` append `/chat/completions` / `/models`, never `/v1`. Request = the Gateway's `chatRequest` with no
  options and `jsonSchema = sendJsonSchema`: on = the strict `{text}` schema + `TRANSCRIPT_FRAMING`, parsed with
  `parseCleanedText`; off = no `response_format`, `PLAIN_TRANSCRIPT_FRAMING` ("Reply with only the cleaned text."),
  the content minus a leading `<think>…</think>` block (`withoutThinkBlock`: dotall, case-insensitive, leading
  whitespace allowed; unit tested), trimmed, is the text (blank = unexpected response). Blank model = "Set a
  cleanup model for <name> in Iris settings", missing key =
  "Add your <name> API key in Iris settings" (no request either way). Errors: `httpErrorMessage(code, body, name)`
  ("<name> rejected the API key...", rate limit, server error, "<name> error: <error.message>", else "<name> request
  failed (HTTP n)", e.g. a plain-string `error`); then `length`, `content_filter`, refusal, "Unexpected response
  from <name>" (Gateway order). Same clients/timeouts as OpenRouter. `listModels`: `GET <base>/models` with the
  Bearer key (no key = "Set your <name> API key to load models", no request), `parseModelsResponse`, no type filter
  (some servers use `type: "chat"`). Warm-up: keyless `HEAD <base>/models` (a 401 is fine), `Log.d` only; the POST
  reuses the connection (unit tested). `AppContainer.compatibleCleanup(p)` builds one instance per call (cheap,
  shared pool), no cache/registry; `customCleanupModels(p)` for the settings menu.
- Model catalog: `VercelGatewayCleanup.listModels()` (`AppContainer.gatewayModels`) =
  `GET https://ai-gateway.vercel.sh/v1/models` (checked 2026-11-11: public, no auth needed; an invalid Bearer key gets
  401, so the request is sent unauthenticated and the key is never read). Body
  `{object:"list", data:[{id, type, ...}]}` with `type` one of
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
  The settings UI for all of this (provider pickers, key fields, model comboboxes, catalogue loading) is described
  in "Settings UI (CP12)" below.

Settings UI (CP12, Figma design; `ui.settings` / `ui.components` / `ui.theme`, Compose foundation, no Material):
- Pages: `SettingsApp` holds `Page` (Home, Transcription, PostProcessing, Appearance, Statistics) in `rememberSaveable`,
  a `SeekableTransitionState` + `Transition.AnimatedContent` (push/pop slides, one 300 ms `FastOutSlowInEasing` tween
  for both pages, no fades or scale: forward the sub-page slides in from the full width over Home, which shifts 1/4 left
  underneath; back the sub-page slides off to the right on top (Home's zIndex -1) while Home returns from 1/4 left. The
  old spec faded the sub-page out in the last 150 ms after a 1/5-width slide, so it vanished abruptly at the end) and a
  `PredictiveBackHandler` (enabled off Home) that seeks the transition with the gesture (`seekTo(progress, Home)`),
  animates back on cancel; 3-button back just completes. A tap navigation first does `seekTo(0f, page)` (waits for the
  new page's composition), then `animateTo`: `animateTo` alone takes its start frame before composing the new page, so
  that composition time (~100 ms on the emulator) skipped the start of the slide. Skipped after a back gesture (already
  seeked towards Home) or while a transition is running (seekTo would wait for it to end, then play the reverse: ~600
  ms). Each page sits in a `SaveableStateProvider`; sub-pages' saved state is dropped once back on Home (they open
  fresh; Home keeps its scroll). Sub-page header: Lucide chevron-left ("Back", 32 x 40 dp box) + title (Blinker 22,
  heading). `MainActivity`: `IrisTheme { SettingsApp(...) }` with a `Saver(container.appScope)` (`AppContainer.appScope`
  is public for it).
- Home: logo (22 dp to the Services card: 7 dp bottom padding + the 15 dp item spacing), Services card
  (Accessibility Service / Microphone Permission: "Enabled" in blue, "Disabled" in the failure colour with "Setup →"
  (accessibility settings / the permission request; permanently denied after an earlier request -> App info;
  accessibility also shows the restricted-settings hint); both re-checked on `ON_RESUME`), then nav cards
  Transcription Provider (audio-lines), Post Processing Provider (sparkles), Appearance (eye), Statistics (gauge, "How
  on-device models run on this phone"); footer
  "Version <versionName>" (PackageManager), pinned to the bottom while the content is short.
- Transcription Provider: picker card (audio-lines icon, "Transcription Provider" black dropdown: On device, OpenAI,
  Vercel AI Gateway, Deepgram, Soniox, each with its brand mark in a `ProviderIconBox` (On device: Lucide `smartphone`;
  Gateway: the Vercel mark; `SelectField`'s `optionIcon`); an unknown stored id shows as OpenAI); "Provider
  Settings": the selected provider's "API Key" (Gateway helper "(shared with post processing)"), Soniox "Region"
  dropdown (`SonioxRegion.label`, hint "Your Soniox project's region. The API key has to be region-specific as well.");
  "Model Settings": "Speech-to-text Model" combobox (per provider; helper = the provider's mode hint: OpenAI "Realtime
  models stream; other models upload after you stop.", Gateway "Live models show text as you speak; others transcribe
  after you stop." (save passes `gatewaySttLive(model, catalog)`), Deepgram "Deepgram models stream live by default
  unless realtime streaming is disabled below." (static `DEEPGRAM_MODELS`, no refresh button), Soniox "Real-time models
  stream; other models upload after you stop." + `sttModeText` of the SAVED model (Soniox only)), Deepgram-only "Disable
  Realtime Streaming" switch ("Transcribe after you stop, about 44% cheaper."), "Language Code" (placeholder "en",
  helper "Setting a language can improve accuracy. Leave blank to auto-detect.") or Soniox's "Language Codes"
  (`sonioxLanguages`, placeholder "en, es, …", helper "Comma-separated language codes. Leave blank to auto-detect."),
  "Timeout" slider (5..30 s, "How long to wait for the final transcript after you stop."); "Audio": "Skip Silence"
  switch, "Audio Speed" slider (100..200 step 10, "1.0x".."2.0x", "Speeds up the audio sent (pitch kept), so less is
  billed.", helper "Higher speeds can lower accuracy; services with a per-request minimum may not save."; hidden for a
  live Soniox model). Menu items of streaming models end with the Lucide `radio` icon ("Streams"; OpenAI
  `isRealtimeTranscriptionModel`, Gateway `isStreamingTranscriptionModel`, Deepgram every model while `deepgramUpload`
  is off, Soniox `sonioxStreams`).
- Transcription Provider with On device (CP14): no "Provider Settings" section (no key); "Model Settings" =
  `OnDeviceModelSettings` (`ui/settings/OnDeviceSettings.kt`): "Speech-to-text Model" read-only dropdown (catalogue
  order, labels `onDeviceModelLabel`: "Parakeet Unified EN · 663 MB" or "· Downloaded"; the streaming model's row
  ends with the radio icon (`SelectDropdown` / `SelectField` `streams`, menu rows only); helper
  `onDeviceModelHelper`: "<languages>. Runs offline on this phone.", for the streaming model "This streaming model
  only supports English."), then "Download": this model downloading -> `ProgressBar` (`ui.components`, 3 dp line
  like the slider track, `progressBarRangeInfo`) + "120 / 663 MB" (`downloadProgressText`) + "Cancel"; another
  model downloading -> helper "Another model is downloading."; being deleted -> "Deleting…" (muted, no button);
  downloaded -> "Downloaded" (enabled colour) +
  "Delete" (`DangerButton`, no confirmation); else "Download (663 MB)" (`megabytesText`: 10^6 B, rounded); the
  store's error for this model in the error slot. Then the licence line (helper style, muted, underlined link).
  "Language Code" only for Whisper (helper "Leave blank to auto-detect. Codes Whisper doesn't know auto-detect
  too."); no Timeout. Audio: Skip Silence subtitle "Detects speech on-device and transcribes each phrase as you
  pause." (streaming model: "Detects speech on-device and transcribes only that."; `skipSilenceSubtitle`); Audio
  Speed subtitle "Speeds up the audio (pitch kept), so it transcribes faster." and helper "Higher
  speeds can lower accuracy.". `SettingsApp` collects `onDeviceModels.state` (app-level, like the Soniox catalogue)
  and doesn't load the OpenAI list while On device is selected.
- Post Processing Provider: "AI Cleanup" switch card (sparkles, "Polish the transcript with an LLM before it's
  inserted."); everything below is dimmed (alpha 0.38, animated) while off but stays editable: picker card ("Vercel
  AI Gateway" / "OpenRouter" / custom providers, see CP13 below), "Provider Settings" key (Gateway: "(shared with
  transcription)"; OpenRouter
  placeholder "sk-or-…"), "Model Settings": "Cleanup Model" combobox ("Model must support structured output."),
  "Reasoning Level" dropdown ("Model default" + the saved model's efforts, else `Settings.REASONING_EFFORTS`; "Not
  listed for this model" error only when the model's list is known and lacks the saved value, which is still sent;
  helper "Model default lets the provider decide."), "Timeout" slider (1..30 s, "If cleanup takes longer, the
  original text is inserted."), "Provider Options" accordion (collapsed, `rememberSaveable`; header `Role.Button`
  with "Expand"/"Collapse" click label, "Expanded"/"Collapsed" state, "No provider option"/"Provider option set"
  subtitle, rotating chevron-down; its press area (8 dp radius) reaches 12 dp past the text horizontally and 10 dp
  vertically via a `layout` bleed + inner padding, so the text stays aligned and clear of the clip (the old
  unpadded header clipped the subtitle's bottom-left on the rounded corner); inside a monospace "JSON" text area,
  placeholder "{ }", validated by `providerOptionsError`, blank clears; hints per provider: Gateway "Sent as
  providerOptions, e.g. {"gateway": {"order": ["azure", "openai"]}}. See the AI Gateway provider options docs. Leave
  blank to send none. Stored unencrypted: don't put API keys here.", OpenRouter "Sent as provider (routing), e.g.
  {"order": ["openai"], "allow_fallbacks": false}. See OpenRouter's provider routing docs. Leave blank to send
  none."); "Prompts": "Active Prompt" read-only dropdown (picks what cleanup uses AND what is edited below) +
  outlined plus ("New prompt", 16 dp Lucide plus in the text colour, same box as the trash; `newPromptName`: "New
  prompt", "New prompt 2", …; the default prompt's text; made active; its Name field gets focus with the text
  selected) + outlined trash ("Delete prompt <name>", disabled for the last prompt, no confirmation), then "Name"
  and "Prompt" (text area, "Instructions for the post-processing model.") of the active prompt
  (`key(id)`; trimmed; blank = error, not saved). Gateway and OpenRouter fields are separate calls (as are the four
  STT providers' fields), so unsaved input never carries over.
- Custom cleanup providers (CP13): the picker is `FieldGroup` + `SelectDropdown` (built-ins, saved custom ones, the
  draft) with an outlined plus ("New provider") beside it. Each option has a `ProviderIconBox` (24 dp, radius 4,
  1 dp `onControl` 30 % outline): the brand marks (untinted `Image`), a custom provider's favicon, else (draft / no
  icon) the cloud. Favicons (`Favicons`, `AppContainer.favicons`): `faviconDomain` (registrable domain via
  `topPrivateDomain()`, computed off main; an IP, localhost or a host with no registrable domain gets none: no fetch,
  the cloud) -> `https://www.google.com/s2/favicons?domain=<d>&sz=64` (2xx only; an unknown domain gets a 404
  globe), cached as `cacheDir/favicons/<domain>.png` (written to `<domain>.tmp`, then renamed; an undecodable cached
  file is deleted and refetched) and in memory (definitive failures only: non-2xx or undecodable; network errors
  aren't cached, so they retry); `rememberFavicon` clears the old icon when the base URL changes; built-ins never
  fetch. The plus makes one in-memory draft (plain `remember`, "Unnamed Provider", selected in the UI only,
  never used for cleanup; a second plus focuses its Name); picking any provider, leaving the page or closing the app
  discards it. Provider Settings for custom providers (draft and saved) is a form committed only by "Save" (dark
  `SmallButton`): "Provider Type" (one option "OpenAI Compatible"), "Provider Name" ("My provider"), "Provider Base
  URL" ("https://api.example.com/v1", "Enter your OpenAI-compatible base URL here."), "API
  Key" (`DraftApiKeyField`). Save checks `providerDraftErrors` (name, `baseUrlError`: blank / not https / unparseable,
  key input or a stored key not marked for removal); errors show per field and clear on edit; success stores, selects
  it, clears the key input and reloads its catalog. Saved providers keep their last saved values; unsaved form edits
  are lost on leaving the page or closing the app. Key trash: clears the input, or with none marks the stored key for
  removal (dots go, "Not set"; Save then needs a key): Save can't leave a saved provider keyless (Delete clears the
  key first, so a failed delete can). A base URL whose host (`sameHost`, unit tested) differs from the saved one
  also needs a new key ("Enter an API key."), so the stored key never goes to another server. "Delete Provider"
  (`DangerButton`, saved only, no confirmation) deletes it and its key; the Gateway is selected. Model Settings
  (saved only) autosave: model combobox ("Must support structured output while Send JSON Schema is on."), Reasoning
  Level (default "Model default"), the shared Timeout, "Send JSON Schema" switch; no Provider Options.
- Appearance: "Text Panel": "Lines" (1..6, "Lines of live text the panel shows.", helper "Notices show at least 4
  lines.") and "Max Width" (30..90 step 10, shown "50%", "Share of the screen width the panel can use.").
- Statistics (CP16, `StatisticsPage.kt`; data in `stt/ondevice/OnDeviceStats.kt`, pure parts unit tested): per
  on-device model with at least one sample (`modelsWithStats`: catalogue order, unknown ids dropped), from its last 20
  dictations (`MAX_SAMPLES`; `StatsSample(speechMs, decodeMs, waitMs)`, `ModelStats(samples, lastLoadMs)`), a
  `Section(model.label)` with "Speed" = the verdict (`speedVerdict` on the ratio of sums `speedFactor` = Σ speech ÷
  Σ decode: >= 1.5 "Keeps up" in `enabled`, >= 1.0 "Borderline" in `warning`, else "Falls behind" in `failure`) with
  `speedFactorText` below ("7.1× faster than you speak", "2.0× slower …", "As fast as you speak" when it rounds to
  1.0; verdict and text both use the factor rounded to one decimal, `roundToTenth`, so 1.45 is "Keeps up" with
  "1.5×"), "Transcription Time" (`medianWaitMs`, even count = mean of the middle two; `tenthsText` "4.8 s"; "Median time
  from stopping to the final text."), "Model Load" (last load, if known; "Last time the model was loaded into
  memory."), then `statsBasisText` ("From your last dictation." / "From your last N dictations."). Rows are
  `StatRow`s (title + value right, subtitle; merged for TalkBack). Below the sections the speed helper and "Reset
  Statistics" (`DangerButton`, no confirmation: removes every model's samples and load times). No samples at all: a
  card "No statistics yet" / "Dictate with an on-device model to see how fast it runs on this phone."; before the
  first read only the header shows (no empty-state flash). `SettingsApp` collects `onDeviceStats.stats` (like
  `onDeviceModels.state`) and passes the value (null until the first read) to the page, so it is read before the page
  slides in and re-opening doesn't restart the collection. Storage: `OnDeviceStats` (`AppContainer.onDeviceStats`),
  its own DataStore file `on_device_stats`, one key `models` holding the JSON map (`encodeStats` / `decodeStats`:
  `ignoreUnknownKeys`, null or malformed = empty); writes on `appScope` (`edit` serializes them; an `IOException` is
  only logged; a corrupt file is replaced with an empty one, `ReplaceFileCorruptionHandler`), excluded from backup
  and device transfer like `secrets` (device-specific numbers). No cloud stats, no usage totals.
- Autosave (no Save buttons, except the custom provider form (CP13)): text fields commit on blur; IME Done, a tap
  on empty space (page `detectTapGestures`),
  every non-text control, the back chevron / gesture and `ON_STOP` (home, app switch, rotation) clear focus, so blur
  is the one commit path. `commitDecision(draft, stored, validate)` (pure, tested): trimmed == stored = nothing,
  validator message = `Invalid` (not saved; shown in the helper slot in the failure colour above the helper, red
  border), else `Save`. Stored values never flow into a focused field; a draft survives rotation
  (`rememberTextFieldState`). Dropdown picks and switches save at once; sliders on release (or key up / TalkBack
  `setProgress`) only if changed. Writes go through `Saver` on `appScope` (they outlive the activity); a failed write
  (e.g. Keystore in `SecretStore.put`) is logged without the value and shows "Couldn't save. Try again." (keys:
  "Couldn't save the API key. Try again.") in that control's helper slot. Language fields (blank valid) have a trash
  that clears and saves "".
- API key field: write-only, starts empty with a placeholder (OpenAI "sk-…", others "Paste your API key"), or with
  a key saved 10 display-only dots "••••••••••" in the text colour (hidden from TalkBack; the field's state
  description is "Saved") until the user types (focus alone keeps them); a plain
  `remember` `TextFieldState` in a `BasicSecureTextField` (hidden; eye / eye-off shows it while there is input),
  never seeded, saved or read back; a trimmed non-blank input commits on blur/Done and is cleared once saved; the
  trash ("Delete saved API key", only when one is set) discards the input, then clears the stored key. Helper "API
  key saved. Enter a new one to replace it." / "Not set" (failure colour). OpenAI key save/clear calls
  `ModelCatalogs.onOpenAiKeyChanged`.
- Dropdowns (all black, panels black): `SelectDropdown` (read-only; `Role.DropdownList`, content description
  "<label>, <value>", state "Expanded"/"Collapsed", click label "Change"; focusable `Popup`, so back and outside taps
  close it) and `ModelCombobox` (editable `BasicTextField`: focusing it opens the full list, typing filters with
  `filterModels` (case-insensitive substring) and reopens it; the arrow ("Show/Hide models") and refresh ("Refresh
  models", spins and is disabled while loading) act without focusing the field or clearing the filter; the first
  open of an unloaded list loads it once; a pick saves at once; blank = "Enter a model id."; non-focusable `Popup`
  so the IME stays; `BackHandler` and blur close it; a tap on the arrow within 400 ms of an outside-tap dismiss
  doesn't reopen it). `DropdownPanel`: as wide as the field, 4 dp gap, 40 dp rows (selected: medium weight + check),
  max 288 dp (7 whole rows), lazy, scrolled near the selection; placed by `dropdownPlacement` (pure, tested): below if it fits above
  the IME / nav bar (minus 8 dp), else on the side with more room, height capped; fades + grows in (120 ms) from the
  anchor side, fades out (90 ms).
- Catalogues (`ModelCatalogs`, in memory, refetched with the activity): Gateway's (shared by the Gateway STT model,
  cleanup model and Gateway reasoning level) loads at app open with Gateway cleanup or Gateway STT on (or when
  either is turned on, if not loaded), on a combobox's first open or via refresh, one fetch at a time; OpenRouter's
  likewise with OpenRouter cleanup on (switching the cleanup provider never touches the Gateway catalog); OpenAI's
  with OpenAI selected; a key save/clear cancels a running OpenAI load (its result is never written), drops the
  list and reloads if OpenAI is selected; no key = "Set your OpenAI API key to load models", no request; other
  failures `Log.w` + "Couldn't load models". Custom: one slot (`customModelsFor(id)`, `customId`) for the selected
  provider, loaded with cleanup on (or first menu open / refresh); another provider's load is cancelled and the slot
  cleared; a save reloads it (`onCustomProviderSaved`); `CleanupException` message, else `Log.w` + "Couldn't load
  models". Soniox: `SonioxCatalog.refresh()` when Soniox is selected and not
  loaded, on first menu open or refresh (it reloads itself on key/region change). Load errors show in the
  combobox's helper slot.
- Theme: `IrisTheme` follows the system dark mode; `IrisColors` (KDoc table) light = the Figma palette exactly
  (bg #E2E7E7, card #FFFFFF, text #2F3031, muted #6F7173, failure #EC1664, enabled #0061F3, control #2F3031, field
  border #D7D7D7, separator #D8DBDF, services divider #D6D8DD, track #C6C8CC, button text #FDFDFD); dark derived: bg
  #141616, card #212424, text #E8EAEA, muted #9B9EA0, failure #FF5C93 and enabled #5C9DFF (>= 4.5:1 on the card),
  warning (CP16, not in the design: Statistics' "Borderline") #B45309 light (~5:1 on white) / #F59E0B dark (~7:1),
  controls stay black (#0B0C0C + 1 dp #3A3D3E outline), slider/switch "on" = the text colour (a black thumb would
  vanish on a dark card). Shadows via `Modifier.dropShadow` (card y 3 blur 3.1 7 %, button y 2 blur 0.95 8 %,
  panel y 4 blur 12 16 %; dark 35-50 %). `Theme.Iris` (+ `values-night`) sets `windowBackground` to
  `settings_background` (no white flash). `IrisType`: Blinker 600 page title 22/26 sp and section heading 16/19;
  Inter 600 titles 15/18, 500 subtitles 13/16, 500 buttons/dropdown values 14/17, 400 field text 14/17, 500 helper
  12/15, 800 slider value 13/16, menu items 14/17 (Figma sizes + 1-2 sp, read better on a phone); letter spacing
  0, line height centred. Press feedback is a flat overlay (`PressIndication`: 6 % black / white, 10 % white on
  black controls; 60 ms in, 150 ms out after the press finished).
- Controls: card radius 15, padding 22 x 15-16, gap 15; controls 40 dp min height (`ControlHeight`; text areas
  88 dp), radius 8, padding 12 / 8; switch 34 x 20 track + 16 dp thumb (150 ms, haptic `ToggleOn/Off`, the whole
  row is `toggleable(Role.Switch)`); slider 3 dp track + 7 x 24 dp pill thumb (9 dp while dragged / key-focused),
  tap or horizontal drag (a vertical drag scrolls the page), haptic tick per step, `progressBarRangeInfo` +
  `setProgress` + state = the value text, DPAD left/right. Small icon buttons are 32 x 40 dp boxes (28 x 40 inside
  fields, 40 x 40 beside the prompt picker; below the 48 dp guideline, since Compose extends touch bounds only
  where no other target is hit and these sit edge to edge; dense design, accepted).
- Edge-to-edge + IME: `enableEdgeToEdge()`, safe-drawing top/horizontal padding, `imePadding()` outside the
  scroll, the nav bar inset as bottom padding inside it; a focused field group (incl. helper/error) is brought above
  the IME once the inset settles and again when its error changes (`bringIntoViewAboveIme`); nav-bar contrast
  enforcement off; with 3-button nav a 90 % page-colour scrim (`WindowInsets.tappableElement` height) sits behind the
  buttons.

## Key decisions (don't re-litigate without a concrete reason)
- **STT providers**: OpenAI Realtime (direct) stays the default. Vercel AI Gateway's streaming transcription
  (beta, protocol taken from the AI SDK source and Handy, see Providers) is offered as a second provider for model
  choice and one shared key. Recording rate is per session (`sampleRate(model)`), not resampled in Kotlin, except
  a 16 kHz copy for the VAD. The mode follows the model (streaming vs upload after stop), no separate setting.
  Deepgram (direct, not via the Gateway: no `deepgram/*` there) is a third provider for Nova-3; it streams by default.
- **Soniox (direct) is a fourth provider**: real-time models stream, async ones upload after stop; the mode
  follows the catalogue's `transcription_mode` (id rule `stt-rt-*` until it loads). Soniox real-time disables
  speed-up (billed by stream time). The catalogue is app-level (`SonioxCatalog`), not just the settings screen's,
  because the start frame needs `supports_language_hints_strict` (sending it to a model without it is a 400).
- **OpenRouter is a second cleanup provider** beside the Gateway (own key, model, reasoning level and options; the
  Gateway keeps the original `cleanup*` fields, no migration). Provider options are wrapped per provider
  (`providerOptions` / `provider`), never merged into the top level; cleanup only, not Gateway STT.
- **Custom OpenAI-compatible cleanup providers** are a third `CleanupProvider` implementation, picked in
  `AppContainer.cleanup` (no registry). HTTPS only. Their Provider Settings are a draft with a Save button (not
  autosaved), so a half-typed URL or key is never used.
- **Deepgram's mode follows a setting, not the model** (the one exception): every Nova model does both, so the
  Deepgram-only "Disable realtime streaming (cheaper)" switch picks the ~44 % cheaper pre-recorded upload.
- **VAD on device (Silero)** to cut STT cost; fails open, never loses dictation. Since CP14 it runs on sherpa-onnx's
  ONNX Runtime (`Vad.compute`), not `onnxruntime-android`: the two AARs' `libonnxruntime.so` copies have different
  symbol versions (1.28.0 vs 1.28.2) and only one can be packaged (Tech stack).
- **On-device transcription (CP14) is a provider, not future scope**: sherpa-onnx (one AAR with a Kotlin API for
  NeMo transducers, Whisper and Moonshine, CPU, its own ONNX Runtime shared with the VAD), offline models downloaded
  on demand (hundreds of MB: not bundled), per-phrase pseudo-live text (each VAD segment decoded as the user pauses)
  instead of streaming models. The segment marker is a provider member (`wantsSegmentEnds`), not a new abstraction.
- **One true on-device streaming model (CP15)**: Nemotron Speech Streaming EN 0.6B (1120 ms chunks, int8) through
  sherpa's online recognizer, beside the per-phrase models; it is the default and first in the menu. With Skip
  Silence on it still gets one continuous stream for the whole dictation (segment ends ignored), so context and
  punctuation carry across phrases.
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
  choice is a `(Settings) -> SpeechToTextProvider` lambda / `AppContainer.cleanup(settings)`, not a router provider).

## Commands
Run from repo root in Git Bash on Windows:
```
./gradlew assembleDebug testDebugUnitTest --console=plain -q
```
Both build and unit tests must pass before reporting work as done.

Release signing: `assembleRelease` signs with a PKCS12 keystore when `IRIS_KEYSTORE_FILE`,
`IRIS_KEYSTORE_PASSWORD` and `IRIS_KEY_ALIAS` are set (key password = store password), else leaves the APK
unsigned. `.github/workflows/release.yml` (every push to `main`, GitHub environment `release` with secrets
`IRIS_KEYSTORE_B64` / `IRIS_KEYSTORE_PASSWORD` / `IRIS_KEY_ALIAS`): builds + runs unit tests, uploads
`iris-<versionName>-arm64-v8a-<sha7>.apk` as an artifact, and if tag `v<versionName>` doesn't exist creates it on
the pushed commit with a GitHub Release (`iris-<versionName>-arm64-v8a.apk`). The version comes from AGP's `output-metadata.json`. Bump
`versionCode` with `versionName`.

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
- Empty WhatsApp / Gmail box: no placeholder ("Message") in the inserted text.
- Rotation mid-dictation (Listening and Finalizing): bubble re-snaps, panel follows, session isn't lost.
- Changing field during cleanup: text is copied with "field changed"; the panel is restored if the bubble re-shows.
- Panel size: the Lines/Max width sliders change the panel (live text and notices) in another app without
  restarting the service; at 90 % the panel stays on screen on both sides and in landscape.
- TalkBack reads the Text Panel sliders' name and value.
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
  upload; picking an after-stop Gateway model from the menu is used at once (no radio icon on it).
- OpenAI model dropdown lists transcription models with your key (realtime ones with the radio icon, also
  in the Gateway menu for live models); no key shows "Set your OpenAI API key to load models"; saving a key loads
  the list.
- Deepgram (V1-V6): real voice with `nova-3` and `en`: live text streams, the final is inserted, log `Timing:
  CloseStream->Metadata`; blank language (`multi`): mixed-language speech is transcribed; VAD on with a pause >= 60 s
  on mobile data still gives a transcript (no `NET-0001`/`NET-0002`, no missed pong); bad key = key message, a
  language Nova-3 lacks (e.g. `yo`) = "Deepgram error: ..."; Deepgram console usage of a VAD-gapped session ≈ the
  sent audio (`VAD: sent X ms`), not wall time; speed 1.5x/2.0x accuracy. "Disable realtime streaming" on: no panel
  while recording, spinner after stop, text inserted, console bills it as pre-recorded; the switch shows only with
  Deepgram selected and the menu's radio icons follow it.
- Soniox (S1-S7): (1) `stt-rt-v6` + `en`: live text streams, the final is inserted, log `Timing: end->finished`;
  (2) languages "en, bn" with mixed speech: the start frame has both hints and `language_hints_strict`; (3) bad key =
  key message while Listening; (4) a pause >= 60 s with VAD on: no 408; (5) `stt-async-v6`: no panel while
  recording, spinner after stop, text inserted, no leftover files/transcriptions in the Soniox console, a poll
  timeout (e.g. Timeout 5 s with a long dictation) or turning the service off mid-poll leaves no file (a failed
  transcription may remain until Soniox's 30-day auto-delete); (6) EU region with an EU key works, a US key on EU =
  key error; (7) app start offline, then a dictation online: the catalogue is fetched at session start. Speed hidden
  for `stt-rt-*`, shown for async; console usage matches (real-time = wall-clock stream time).
- On device (CP14): each model (Parakeet Unified EN, Parakeet TDT v3, Whisper Small, Moonshine Base EN) downloads
  and transcribes a real voice; with Skip Silence on, text appears per phrase as you pause; a phrase over 20 s is
  split without lost words; Whisper with `de` and with a blank language; speed 1.5x; the VAD (now on sherpa's ORT)
  still detects real speech and keeps first/last words (emulator only showed silence); memory (`adb shell dumpsys
  meminfo dev.jamius.iris`) after the preload and 5 min after the bubble hides (log `Released <id>`); load time of
  the Parakeet models on a mid-range phone (and that Finalizing stays within the 60 s + 2 x audio deadline);
  deleting the selected model (next dictation: "Download the ... model"); a release build on arm64 loads sherpa and
  the VAD; a download over mobile data that drops resumes.
- On device streaming (CP15, Nemotron Streaming English): real voice, live text while recording; RTF (`Decoded X ms
  of audio in Y ms`, Y < X on a mid-range phone, else the backlog grows; live text lags >= ~1.2 s + decode;
  stop->final ≈ 2 window decodes + backlog); the first word right after the tap with Skip Silence off (the first 9
  frames, ~90 ms, are pre-encode cache; the VAD pre-roll covers it with Skip Silence on; if clipped, add 0.3 s of
  left padding like `test_online_asr.kt`); joined VAD phrases: punctuation across joins, last words kept at stop, a
  5+ min dictation; deleting / switching the model or a memory trim during a dictation waits for the session; users
  who had Parakeet Unified as the implicit default are now asked to download Nemotron (unreleased branch, no
  migration); the menu label "Nemotron Streaming English · Downloaded" + icons may ellipsize; memory ≈ Parakeet
  (`dumpsys meminfo`); an R8 release build loads `OnlineRecognizer`; ✕ mid-dictation, then the mic again at once: the
  new session streams (no `Loaded` line, no hang: native stream released, lock handed off); a dictation that ends
  "No speech detected" (silence only, Skip Silence on), then a normal one, works likewise; with Skip Silence on at
  Speed >= ~1.6x a phrase's last words show only when the next phrase starts or at stop, the final text is complete.
- Statistics (CP16): after real dictations (e.g. Nemotron at 1.5x) Speed ≈ the `VAD: sent X ms` total ÷ the
  `Decoded … in Y ms` totals (≈ 7×), Transcription Time ≈ the median of the `Timing: stop->final` logs, Model Load = the
  last `Loaded … in X ms`; a "No speech detected" dictation and a ✕ cancel add nothing; Reset empties the page; stats
  survive an app restart; TalkBack reads each row (title, value, subtitle); light and dark verdict colours.
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
- OpenRouter cleanup with a real key: the default model, a non-OpenAI model and a reasoning level clean the text;
  402/503 show as notices with the raw text inserted. Provider options: Gateway `{"gateway":{"order":["azure",
  "openai"]}}` and OpenRouter `{"order":["openai"],"allow_fallbacks":false}` are accepted; an impossible routing
  (e.g. `{"only":["nonexistent"]}`) shows the 503/400 notice. The accordion is collapsed by default, TalkBack reads
  "Provider Options, Collapsed"; invalid JSON shows an error and isn't saved; switching the cleanup provider keeps
  the Gateway STT model menu.
- Custom cleanup providers (CP13): Groq (`https://api.groq.com/openai/v1`) and OpenAI (`https://api.openai.com/v1`)
  base URLs clean text with a listed model; Send JSON Schema off works with a server that rejects `response_format`;
  the favicon shows in the menu, or the cloud when offline; a draft is discarded on picking another provider;
  Delete falls back to the Gateway; TalkBack reads the plus ("New provider"), Save and Delete Provider.
- Settings UI (CP12): light and dark pages match the Figma PNGs (sizes, spacing, colours, fonts) on a real phone;
  page slides (chevron, 3-button and gesture back) run smoothly to the end with no early cut; gesture-nav predictive
  back shows the sub-page sliding away with Home behind and a cancelled gesture settles back; a saved key shows
  dots (TalkBack: "API Key, Saved"); dropdown panels open above the IME when it covers the space below and never
  under the nav bar; autosave on blur, Done, back (chevron and gesture), home button and rotation (value persisted
  after reopening the page), invalid values (blank model, bad JSON, blank prompt name) keep the error and aren't
  saved; API key: eye toggles visibility, Done saves and clears the input, helper switches to "API key saved…",
  trash clears it ("Not set" in red); the plus (New prompt) focuses the name with the text selected, delete is
  disabled for the last prompt; a Keystore save failure shows "Couldn't save the API key. Try again." (hard to
  force: e.g. a device with a broken Keystore); TalkBack reads dropdowns ("<label>, <value>, drop down list,
  Collapsed"), switches, sliders (name + value, volume keys adjust and save), the back button and the icon buttons'
  labels; large font scale keeps controls unclipped.

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
- VAD (checked 2026-11-12; again on sherpa 2026-11-13): with Skip silence on, a no-speech dictation ends right
  after the stop tap with "No speech detected"; logcat `SileroVad: Model loaded in X ms` and `DictationController:
  VAD: sent 0 ms of Y ms`.
- On device (checked 2026-11-13, debug and an R8 release build signed with the debug key): Moonshine Base EN
  downloaded from settings (cancel removed the `.tmp`, a force-stop mid-download resumed); model files via
  `MSYS_NO_PATHCONV=1 adb shell run-as dev.jamius.iris ls -la no_backup/models/moonshine-base-en`. In one process
  the recognizer (`Loaded moonshine-base-en`) and the VAD (`SileroVad: Model loaded`) both load in either order
  (preload first, or both concurrently); with Skip silence off a silent dictation logs `Decoded X ms of audio in Y
  ms` and ends "No speech detected". MCP `tap` didn't open the Compose dropdowns on this AVD; `adb shell input tap`
  did.
- On device, the other three models (checked 2026-11-13, debug build, Skip silence off, silence from the emulator
  mic, the app force-stopped before each so the model loads fresh): downloaded from settings (Parakeet Unified EN
  288 s, Parakeet TDT v3 299 s, Whisper Small 162 s), then loaded and decoded with no crash or native abort:
  Parakeet Unified EN load 4.8 s, 5 s of audio decoded in 585 ms (8.5 s in 1.0 s); Parakeet TDT v3 load 3.6 s, 5 s
  in 547 ms; Whisper Small (blank language) load 2.0 s, 4.9 s in 4.1 s. Switching the provider to OpenAI logged
  `Released whisper-small` at once.
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
| CP9 | Deepgram Nova-3 STT | Done (real-voice device check pending) |
| CP10 | Soniox STT (real-time + async) | Done (real-voice device check pending) |
| CP11 | OpenRouter cleanup provider + provider options | Done (device check pending) |
| CP12 | Settings UI redesign (Figma) | Done (emulator-checked; device check pending) |
| CP13 | Custom OpenAI-compatible cleanup providers | Done (device check pending) |
| CP14 | On-device STT (sherpa-onnx) | Done (emulator-checked with silence; real-voice device check pending) |
| CP15 | On-device streaming STT (Nemotron) | Done (device check pending) |
| CP16 | On-device statistics | Done (device check pending) |
