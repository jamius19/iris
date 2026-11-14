# Accepted trade-offs

Choices that can look like bugs or omissions. Each was decided on purpose; change one only with a concrete reason.

## Dictation
- **Finalizing can't be cancelled by a tap.** Stalls end through the STT and cleanup timeouts instead.
- **Focus loss cancels only while Listening.** Once stopped, a session always finishes.
- **No retry or skip for cleanup.** Any failure inserts the raw transcript with a notice.
- **The previous clipboard isn't restored** after a paste fallback.
- **The cleaned text is never shown in the panel**, only the STT transcript.

## Audio
- **VAD assumes no provider bills a session that got no audio.** Soniox real-time is the exception (wall-clock).
- **Keep-alive zeros are real audio and are billed** (~1 % of the withheld silence). Pings alone don't stop
  servers from ending an idle stream.
- **The VAD hangover isn't scaled by Speed.** A live model gets 1650 / speed ms of tail.
- **Sonic runs on the main thread** (measured < 0.3 ms per chunk on a desktop JVM; phone cost unmeasured). Moving
  it would need per-chunk thread hops.
- **Recording rate is per session, not resampled in Kotlin**, except a 16 kHz copy for the VAD.

## STT providers
- **All OpenAI realtime models stream**, even those without live text: less wait at stop.
- **OpenAI and Deepgram don't reconnect**; only the Gateway does (once). Every Gateway `error` part is retried
  once, since error names can't be told apart.
- **The Gateway's send-drain timeout is terminal**: a stuck uplink won't improve with a full resend.
- **The Gateway key isn't sent as a WebSocket subprotocol** (the server may echo it). Header auth works today.
- **`gatewaySttLive` can go stale** if the catalog retags a model, until the model is re-saved.
- **Soniox's mode reaches the warm-up only at the next settings change** when it comes from the catalogue.
- **A typed model id not in a loaded catalogue** gets no refetch (Soniox: strict hints omitted; Gateway: live).
- **Deepgram Flux (`/v2/listen`) is out of scope.**

## On device
- **With Skip Silence off, per-phrase models show text only every ~20 s** (the segment cap) and the rest at stop.
- **Nemotron at Speed >= ~1.6x with Skip Silence on**: a phrase's last words show only when the next phrase
  starts or at stop. The final text is complete.
- **The STT timeout is ignored**: a native decode can't be aborted, so a short timeout only throws text away.
- **Downloads have no WorkManager or notification.** They stop when the process dies and resume on the next tap.
- **Starting a download deletes other models' partial files**, so an abandoned partial can't hold ~670 MB.
- **No migration for the default model change to Nemotron** (unreleased branch).

## Privacy
- **Filler-word language detection may pass the transcript to the system TextClassifier** (on-device, no
  network, but outside Iris's process), for every provider incl. On device.
- **The language setting is trusted as evidence** even where the provider treats it as a hint or ignores it.
- **Provider options are stored unencrypted**; the UI warns not to put keys there.
- **Favicons for custom providers are fetched from Google's favicon service** by registrable domain.

## Statistics
- **Time Saved assumes 38 WPM typing** and counts whitespace-separated words, so CJK is undercounted.
- **A filler-only on-device dictation still records its sample**, though it ends as "No speech detected".
- **A speed change mid-dictation skews that one sample.**
- **A deleted custom provider's stats stay** under its last name until Reset.
- **Billed audio ignores** keep-alive zeros, padding and per-request minimums.

## UI
- **Small icon buttons are below the 48 dp touch guideline** (dense design; they sit edge to edge).
- **No confirmation on deletes** (prompt, provider, model, statistics reset).
- **Sub-pages open fresh**: their saved state is dropped once back on Home.
- **Release builds are arm64-v8a only**: 32-bit devices can't install the app.
