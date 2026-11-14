# Provider provenance

Where each provider's protocol code came from, when it was last checked, and the prices and measurements behind
some decisions. The protocol itself lives in the code and its tests. Re-check the sources here before changing
protocol code, and update the date when you do.

## STT

### OpenAI Realtime (`stt.openai.RealtimeProtocol.kt`)
- Verified 2026-11-11 against https://developers.openai.com/api/docs/guides/realtime-transcription and
  https://developers.openai.com/api/reference/resources/realtime/client-events (and `/server-events`).
- `languages` (array) vs singular `language` per model: checked 2026-11-12.
- Realtime model list (`REALTIME_TRANSCRIPTION_MODELS`): client events reference, checked 2026-11-12: `whisper-1`,
  `gpt-transcribe`, `gpt-live-transcribe`, `gpt-4o-mini-transcribe`, `gpt-4o-mini-transcribe-2025-12-15`,
  `gpt-4o-transcribe`, `gpt-4o-transcribe-diarize`, `gpt-realtime-whisper`. Only `gpt-live-transcribe` and
  `gpt-realtime-whisper` send text before the commit.
- `gpt-4o-*-transcribe` are deprecated, shutdown 2027-02-26.
- Warm-up, measured 2026-11-11 with curl `--http1.1`: keyless `HEAD /v1/models` 401, ~250 ms after TLS (`HEAD /`
  ~5 ms but a 421, `HEAD /v1/realtime` 405 ~750 ms). api.openai.com closed an idle HTTP/1.1 connection after 400 s;
  OkHttp's pool evicts idle ones after 5 min, hence the 3 min throttle.

### OpenAI file upload (`stt.openai.OpenAiFileTranscription`)
- Verified 2026-11-12 against https://developers.openai.com/api/docs/guides/speech-to-text and
  https://developers.openai.com/api/reference/resources/audio/subresources/transcriptions/methods/create.
- Files are limited to 25 MB (we split at 20 MB of PCM, ~10 min).

### OpenAI model list (`stt.openai.OpenAiModels.kt`)
- Verified 2026-11-12 against https://developers.openai.com/api/reference/resources/models/methods/list. The
  response has no capability field, so ids are filtered by `transcribe` / `whisper`.

### Vercel AI Gateway streaming (`stt.vercel.GatewayTranscriptionProtocol.kt`)
- Beta (launched 2026-07-22), no public protocol docs. Verified 2026-11-12 against the source of `@ai-sdk/gateway`
  4.0.143 (`gateway-transcription-model.ts` `doStream`, `gateway-realtime-auth.ts`, `gateway-provider.ts`),
  `@ai-sdk/provider-utils` (`transcription-stream-envelope.ts`) and Handy's `vercel_live_transcription.rs` /
  `vercel_transcription.rs`.
- Live emulator session 2026-11-12: header auth, `finish` and an unknown model's 404 message worked.
- Recording rates (`openai/*` 24 kHz, others 16 kHz) follow Handy's working setup; accepted rates are undocumented.
- Live models in the catalog (2026-11-12, `websocket-transcription` tag): elevenlabs/scribe-v2-realtime,
  google/gemini-3.5-transcribe-live, microsoft/mai-transcribe-2-streaming, openai/gpt-live-transcribe,
  openai/gpt-realtime-whisper, openai/gpt-transcribe, spacexai/grok-stt.
- The Gateway's HTTP/1.1 idle timeout is unmeasured. Emulator warm-up log: `Warm-up: http/1.1 200`.

### Vercel AI Gateway upload (`stt.vercel.VercelGatewayFileTranscription`)
- Verified 2026-11-12 against https://vercel.com/docs/ai-gateway/modalities/speech-to-text (updated 2026-09-08)
  and Handy's `vercel_transcription.rs`. No size limit is documented.
- After-stop models in the catalog (2026-11-12): elevenlabs/scribe-v2, fish-audio/transcribe-1,
  google/gemini-3.5-transcribe, microsoft/mai-transcribe-2, mistral/voxtral-mini-transcribe-3,
  mistral/voxtral-small-transcribe-3, openai/gpt-4o-mini-transcribe, openai/gpt-4o-transcribe, openai/whisper-1.

### Deepgram streaming (`stt.deepgram.DeepgramProtocol.kt`)
- Verified 2026-11-13 against https://developers.deepgram.com/reference/speech-to-text/listen-streaming,
  `/docs/close-stream`, `/docs/errors`, `/docs/audio-keep-alive`, `/docs/understand-endpointing-interim-results`,
  `/docs/multilingual-code-switching`, `/docs/models-languages-overview` and https://deepgram.com/pricing. Handy's
  `deepgram_live_transcription.rs` does the same.
- Server limits: no audio for 10 s ends the stream (`1011 NET-0001`, not reset by KeepAlive; `NET-0002`); 150
  concurrent streams.
- Pricing (pay as you go, 2026-11-13): Nova-3 streaming $0.0077/min, `language=multi` $0.0092/min (~19 % more).
- Warm-up, 2026-11-13 with curl `--http1.1`: keyless `HEAD /v1/listen` 405, ~240 ms after TLS; idle timeout
  unmeasured.
- Not via the Gateway: its catalog (2026-11-13) has no `deepgram/*` model. The keyless `GET /v1/models` takes
  6-15 s and 207 KB, so the menu is the static `DEEPGRAM_MODELS`.

### Deepgram upload (`stt.deepgram.DeepgramFileTranscription`)
- Verified 2026-11-13 against https://developers.deepgram.com/reference/speech-to-text/listen-pre-recorded and
  `/docs/pre-recorded-audio`.
- Limits: 2 GB per file, 10 min processing (then 504), 50 concurrent requests.
- Pricing (2026-11-13): Nova-3 $0.0043/min mono-language, $0.0052 multi: ~44 % cheaper than streaming.

### Soniox real-time (`stt.soniox.SonioxProtocol.kt`)
- Verified 2026-11-13 against https://docs.soniox.com/api-reference/stt/websocket-api,
  `/authentication/websocket-authentication`, `/stt/rt/connection-keepalive`, `/stt/rt/error-handling` and
  `/api-reference/errors`.
- Server limit: no audio for 20 s = `408 request_timeout`.
- Warm-up, 2026-11-13 with curl `--http1.1`: keyless `HEAD` on the WebSocket URL, 426, ~280 ms warm; idle timeout
  unmeasured.

### Soniox async (`stt.soniox.SonioxFileTranscription`) and catalogue (`SonioxModels.kt`)
- Verified 2026-11-13 against `/api-reference/stt/files/upload_file`, `.../files/delete_file`,
  `.../transcriptions/{create,get,delete}_transcription`, `.../get_transcription_transcript`.
- Leftover files and transcriptions are auto-deleted by Soniox after 30 days.
- Pricing (2026-11-13): real-time ~$0.12/h, billed by wall-clock stream time; async ~$0.10/h by audio duration.

### On device (`stt.ondevice`)
- sherpa-onnx v2.0.1 Kotlin API; behaviour read from its source (`offline-recognizer-transducer-nemo-impl.h`,
  `online-recognizer-transducer-nemo-impl.h`, `online-stream.cc`, `silero-vad-model.cc`), checked 2026-11-13.
- Model files are pinned to Hugging Face commits; sizes and SHA-256 re-checked against the HF API 2026-11-13.
  Full commits and hashes are in `ON_DEVICE_MODELS`.

  | id | repo @ commit | size | licence |
  |---|---|---|---|
  | `nemotron-streaming-en` | `csukuangfj2/sherpa-onnx-nemotron-speech-streaming-en-0.6b-1120ms-int8-2026-04-25` @ `b0b6bae3` | 661,919,419 B | NVIDIA Open Model License |
  | `parakeet-unified-en` | `csukuangfj2/sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming` @ `8c3a10fb` | 663,043,117 B | NVIDIA Open Model License |
  | `parakeet-tdt-v3` | `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8` @ `ed5d7456` | 670,576,784 B | CC BY 4.0 |
  | `whisper-small` | `csukuangfj/sherpa-onnx-whisper-small` @ `8f3c18b3` | 375,485,327 B | MIT (OpenAI) |
  | `moonshine-base-en` | `csukuangfj2/sherpa-onnx-moonshine-base-en-quantized-2026-02-27` @ `8f4d6c58` | 141,300,566 B | MIT (Moonshine AI) |
- Emulator (x86_64, 2026-11-13, debug, silence): Moonshine load 1.2-1.9 s, 3-4 s of audio in 109-130 ms; Parakeet
  Unified load 4.8 s, 5 s in 585 ms; Parakeet TDT v3 load 3.6 s, 5 s in 547 ms; Whisper Small load 2.0 s, 4.9 s in
  4.1 s. Release build PSS ~244 MB with Moonshine loaded. Real-voice numbers on a phone are still missing.

### VAD
- Silero VAD v6.2.3, MIT:
  https://github.com/snakers4/silero-vad/raw/v6.2.3/src/silero_vad/data/silero_vad.onnx (2,327,524 B, SHA-256
  `1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3`). I/O follows the official `OnnxWrapper`
  (`silero_vad/utils_vad.py` v6.2.3).
- Gate logic is a port of Handy's `SmoothedVad` (`src-tauri/src/audio_toolkit/vad/`).

## Cleanup

### Vercel AI Gateway (`cleanup.vercel.GatewayProtocol.kt`)
- Verified 2026-11-11 against
  https://vercel.com/docs/ai-gateway/sdks-and-apis/openai-chat-completions/reasoning,
  `.../openai-chat-completions/structured-outputs`, https://vercel.com/docs/ai-gateway/models-and-providers/reasoning
  and https://vercel.com/docs/ai-gateway/sdks-and-apis/rest-api. Non-streamed response shape verified 2026-11-12
  against `.../openai-chat-completions/chat-completions`.
- Provider options: https://vercel.com/docs/ai-gateway/models-and-providers/provider-options (not live-verified).
- Default model `openai/gpt-6-luna`: default effort medium per
  https://developers.openai.com/api/docs/models/gpt-6-luna (2026-11-11).
- Catalog `GET /v1/models`: public, no auth (checked 2026-11-11; an invalid Bearer key gets 401, so no key is sent).
- Warm-up, measured 2026-11-11: keyless `HEAD /v1/models` ~50-100 ms after TLS, h2; cold POST ~400 ms vs warm
  ~60 ms; an idle h2 connection was still reusable after 290 s.

### OpenRouter (`cleanup.openrouter.OpenRouterProtocol.kt`)
- Sources (2026-11-13): https://openrouter.ai/docs/api_reference/overview, `/guides/features/structured-outputs`,
  `/guides/best-practices/reasoning-tokens`, `/api_reference/errors-and-debugging`,
  `/guides/routing/provider-selection`. Top-level `reasoning_effort` and the 200-with-error shapes are assumed, not
  live-verified.
- Catalog `GET /api/v1/models?supported_parameters=structured_outputs`: keyless (curl 2026-11-13: 200, 67 KB, 372
  entries, 82 `:batch`).

### OpenAI-compatible (custom)
- No external source: the Gateway's Chat Completions request against the user's base URL.

## Other ported logic
- Filler words: Handy's `src-tauri/src/audio_toolkit/text.rs`.
- Cleanup timeout default: Handy's `post_process_timeout_secs`.
