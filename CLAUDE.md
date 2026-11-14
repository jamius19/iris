# Iris

Android voice dictation app (Kotlin, Jetpack Compose, single `:app` module, package `dev.jamius.iris`).
An AccessibilityService shows a bubble overlay when a text field is focused. Tap the mic to dictate: a pill with a
live waveform slides out, the partial transcript streams in a panel beside the bubble. Tap the pill to stop: the
transcript is finalized, filler words removed, optionally cleaned up by an LLM, then inserted at the cursor.
STT is cloud (OpenAI, Vercel AI Gateway, Deepgram, Soniox) or on device (sherpa-onnx models downloaded in
settings). Models either stream (live text) or upload after stop (no live text). Silero VAD ("Skip silence")
decides which audio is sent; "Speed" time-stretches it to cut billed audio.

## Tech stack & build
- AGP 9.x with built-in Kotlin (no `kotlin-android` plugin), Kotlin 2.2.10, `minSdk 30`, `targetSdk 37`, Java 11.
- Compose foundation only (custom design system, no Material), OkHttp (HTTP + WebSocket), kotlinx.serialization,
  DataStore Preferences, Coroutines/Flow. All versions in `gradle/libs.versions.toml`, never inline coordinates.
- sherpa-onnx v2.0.1 AAR from JitPack (on-device STT + the VAD), vendored Sonic (`audio/sonic/Sonic.java`).
  Sources, pins and licences of all vendored code and assets: `docs/third-party.md`.
- Manual DI: `AppContainer` held by `IrisApp`. No Hilt/Koin.
- Build + test (Git Bash, repo root; both must pass before reporting work as done):
  ```
  ./gradlew assembleDebug testDebugUnitTest --console=plain -q
  ```
- Release: `.github/workflows/release.yml` builds, signs and tags `v<versionName>` on every push to `main`. Bump
  `versionCode` with `versionName`.

## Architecture
| Package | Responsibility |
|---|---|
| (root) | `IrisApp`, `AppContainer` (DI, app scope, picks STT and cleanup providers), `MainActivity` (settings UI) |
| `data.settings` | `Settings` model, `SettingsRepository` (DataStore), `SecretStore` (Keystore AES/GCM) |
| `ui.settings` | `SettingsApp` (navigation), the five pages, `ModelCatalogs`, `SettingsText` (pure copy helpers) |
| `ui.components` / `ui.theme` | Design system (cards, fields, dropdowns, slider, `Saver`); `IrisTheme` colours + type |
| `audio` | `AudioRecorder` (PCM16 mono), `SileroVad`, `SpeechGate` (pure VAD gate), `timeStretched` (Sonic) |
| `stt` | `SpeechToTextProvider`; `stt.openai` / `vercel` / `deepgram` / `soniox` / `ondevice`; `Wav.kt` (uploads) |
| `cleanup` | `CleanupProvider`; `cleanup.vercel` / `openrouter` / `compatible` (user-added OpenAI-compatible) |
| `dictation` | `DictationController` (session state machine), `Statistics`, `FillerWords`, `LanguageDetector` |
| `overlay` | `IrisAccessibilityService`, `BubbleOverlay` (bubble + pill + text panel), `TextInsertion` |

Session flow (`DictationController`, main thread): `Idle -> Listening -> Finalizing -> Done | Error -> Idle`.
Audio pipeline: `AudioRecorder.chunks(rate)` (50 ms chunks) -> `SpeechGate` (VAD) -> `transformWhile` (level, stop
check, keep-alive) -> `onCompletion` (no speech check) -> `timeStretched` -> `stt.transcribe` -> filler removal ->
`IrisAccessibilityService.onFinalTranscript` (cleanup + insertion) -> `Statistics`.

Only two abstractions: `SpeechToTextProvider` and `CleanupProvider`. Provider choice is
`AppContainer.speechToText(settings)` / `cleanup(settings)`, not a router. A new provider is a new implementation.
Provider differences are interface members (`sampleRate(model)`, `streams`, `liveText(model)`, `allowsSpeedUp`,
`wantsSegmentEnds`), not new types.

## Key decisions (don't re-litigate without a concrete reason)
- On device (Nemotron) is the default STT. The mode (streaming vs upload) follows the model, except Deepgram,
  where a setting picks the cheaper pre-recorded upload.
- On device: sherpa-onnx offline models decode each VAD phrase as the user pauses (pseudo-live); Nemotron (the
  default) is a true streaming model with one stream per dictation.
- API keys are user-entered and encrypted with an Android Keystore key, ciphertext in DataStore. Never use the
  deprecated `androidx.security:security-crypto`.
- Insertion via accessibility actions: `ACTION_SET_TEXT` + set selection, falling back to clipboard + `ACTION_PASTE`.
- Mic capture from the AccessibilityService with `AudioSource.VOICE_RECOGNITION`, no foreground service.
- Dictation is never lost: any cleanup or insertion failure inserts or copies the raw transcript with a notice.
- Settings autosave on blur (no Save buttons), except the custom cleanup provider form.
- Custom cleanup providers are HTTPS only; a changed base URL host needs a new key (never send a key elsewhere).
- Accepted trade-offs that can look like bugs are listed in `docs/decisions.md`: check it before "fixing" one.

## Gotchas
- **Protocol code**: every provider's protocol was verified against its docs (or the AI SDK source for the
  Gateway). Re-check the current docs before changing event names, session config or request shapes. Sources,
  check dates and pricing are in `docs/providers.md`: read it first and update it after.
- **sherpa-onnx dependency**: use `com.github.k2-fsa.sherpa-onnx:sherpa-onnx`, never the aggregate
  `com.github.k2-fsa:sherpa-onnx` (pulls desktop libs). Its AAR checksum is pinned in
  `gradle/verification-metadata.xml`: a version bump needs new checksums. R8 must keep `com.k2fsa.sherpa.onnx.**`
  (JNI reads fields by name).
- **No `onnxruntime-android`**: its `libonnxruntime.so` clashes with sherpa's (versioned symbols, only one can be
  packaged). The VAD runs through sherpa's `Vad.compute`.
- **sherpa native exits**: an unknown Whisper language kills the process. Only `whisperLanguage()` output may reach
  it. Native load errors in `Vad` abort too.
- **On-device recognizer**: load, decode and release run on one serialized dispatcher under one `Mutex`. Don't
  call the native recognizer from anywhere else.
- **Empty `ByteArray` = segment end**, only sent to providers with `wantsSegmentEnds`. Other providers must never
  see one (an empty frame closes Deepgram's stream).
- **VAD gate** never drops chunks from the flow (level and stop see every chunk), only the bytes. It fails open.
  Nothing sent = `SttException("No speech detected")` thrown from the audio flow, before any commit or upload.
- **Soniox real-time** is billed by wall-clock stream time: never sped up (`allowsSpeedUp` false). Send
  `language_hints_strict` only when the catalogue says the model supports it (else a 400).
- **OpenAI realtime**: `turn_detection: null`; `languages: [code]` for `gpt-transcribe` / `gpt-live-transcribe`,
  singular `language` for the others, never both. A commit needs >= 100 ms of audio (padded with silence).
- **WebSocket warm-up** must go through an HTTP/1.1 client derived from the socket's client, or OkHttp won't
  reuse the connection.
- **Cleanup timeout** uses `withTimeoutOrNull`, not `withTimeout` (its exception is a `CancellationException` and
  would lose the dictation).
- **Overlay is View-based**, not Compose (a Compose window in a service needs lifecycle owners). Remove windows
  with `removeViewImmediate` and keep `setCanPlayMoveAnimation(false)`, or frames flash and the button jumps.
- **Insertion quirks**: apps may report a placeholder as text with selection -1 (WhatsApp): paste instead of
  `SET_TEXT`. A keyboard-only field (ChatGPT) needs the node tree walked once to find the editable node.
- **Inter weights**: build fonts with `InterFont` (platform `Font.Builder`), not Compose's `variationSettings`
  (HyperOS draws every weight at 400).
- **Regex on Android**: `UNICODE_CHARACTER_CLASS` throws. Use explicit `\p{L}\p{N}` lookarounds.
- **Statistics DataStore file** is named `on_device_stats` on purpose: renaming orphans stored data.
- **ABI filters**: release is arm64-v8a only, so a release build won't install on the x86_64 emulator.

## Conventions
- Every new Kotlin file starts with this header (date = file creation date, `dd/MM/yyyy`):
  ```
  /*
  * Author: Jamius Siam
  * Since: 11/11/2026
  */
  ```
- 120-char line limit. When a line would exceed it, put the call args on their own indented lines with the
  closing paren on its own line.
- Kotlin official code style; match existing code over personal preference.
- Minimum code that solves the problem: no speculative features, no extra abstractions, no error handling for
  impossible cases. Don't touch unrelated code.
- User-facing errors are `SttException` / `CleanupException` / `AudioCaptureException` with readable messages.
  Logs never contain transcript text or keys.

## Testing
- JUnit4 unit tests for core logic only: settings serialization, request/response building and parsing, protocol
  event handling, state machine, pure helpers.
- OkHttp MockWebServer (`mockwebserver3`) with `runBlocking` for HTTP/WebSocket tests (`runTest` virtual time
  would fire timeouts instantly). Controller tests use `kotlinx-coroutines-test` virtual time.
- No Robolectric, no instrumented/UI tests. Native code (`SileroVad`, `OnDeviceRecognizer`), overlay and UI are
  verified manually on device by the user, from the checklist in `docs/device-checks.md` (add to it when a
  change needs a device check).

## Emulator
- `./gradlew installDebug --console=plain -q`, then launch `dev.jamius.iris` (AVD `Medium_Phone_API_37.0`).
- Enable the service: `adb shell settings put secure enabled_accessibility_services
  dev.jamius.iris/dev.jamius.iris.overlay.IrisAccessibilityService`. `am force-stop` clears it: re-run after.
- Test field: `adb shell am start -a android.intent.action.INSERT -t vnd.android.cursor.dir/contact`, tap
  "First name". The bubble appears ~1 s after focus.
- Prefix adb commands that take `/sdcard` paths with `MSYS_NO_PATHCONV=1` (Git Bash rewrites them).
- The emulator mic is silent: transcript, cleanup and insertion paths need a real device and voice.

## Status
CP1-CP18 are done (settings, overlay, OpenAI/Gateway/Deepgram/Soniox STT, VAD, speed-up, cleanup providers,
settings redesign, on-device STT incl. streaming, statistics, filler words). Real-voice device checks are still
pending for CP6 onwards.
