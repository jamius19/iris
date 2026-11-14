/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.stt.ondevice

import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import dev.jamius.iris.data.settings.Settings
import dev.jamius.iris.stt.SttException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** A decode's text and the recognizer's busy time for it (a stream's: so far). */
data class Decoded(val text: String, val busyMs: Long)

/**
 * Holds one sherpa-onnx recognizer (one model + language; offline, or online for a streaming model) at a time. Load,
 * decode, unload and release all run on one worker thread under one lock: decodes are serialized across sessions
 * (Whisper mutates shared decoder state), a session started during a load waits for it, and an unload never runs
 * mid-decode. A [stream] session holds the lock for the whole dictation, so unload, release and model switches wait
 * for it to end. Released once unused for the Unload Model delay while idle (see [setIdle]), on memory trim, or by
 * [release]. Not unit tested (native).
 *
 * @param dir a model's download directory (the store's).
 * @param onLoaded a model's load time after each successful load (on the worker; must not block).
 */
class OnDeviceRecognizer(
    private val dir: (modelId: String) -> File,
    private val onLoaded: (modelId: String, ms: Long) -> Unit,
) {

    private val worker = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + worker)

    private class Loaded(
        val key: String,
        val modelId: String,
        val offline: OfflineRecognizer? = null,
        val online: OnlineRecognizer? = null,
    )

    /** Touched only on [worker], under [inUse]. */
    private var loaded: Loaded? = null

    /** Held by every entry point; only a [stream] session holds it across suspensions. */
    private val inUse = Mutex()

    /** The key being loaded or loaded: repeated [preload]s for it are skipped. */
    @Volatile
    private var wanted: String? = null

    /** `elapsedRealtime` of the last load or decode. */
    @Volatile
    private var lastUse = 0L

    /** The bubble is hidden and no dictation is listening or finalizing: the idle countdown runs (see [setIdle]). */
    @Volatile
    private var idle = false

    /** `elapsedRealtime` when [idle] last turned true. */
    @Volatile
    private var idleSince = 0L

    /** The Unload Model setting, in minutes: -1 never, 0 at once. */
    @Volatile
    private var unloadMinutes = Settings.DEFAULT_ON_DEVICE_UNLOAD_MINUTES

    /** Main thread only. */
    private var idleRelease: Job? = null

    /** Loads [model] with [language] ahead of a decode. Fire-and-forget; failures are only logged. */
    fun preload(model: OnDeviceModel, language: String) {
        val key = key(model, language)
        if (wanted == key) return
        wanted = key
        scope.launch {
            try {
                inUse.withLock { ensureLoaded(model, language) }
            } catch (e: SttException) {
                Log.w(TAG, "Preload failed: ${e.message}")
            }
        }
    }

    /**
     * The text of [samples] (16 kHz mono, -1..1) and the decode's time. Cancelling returns at once; the native decode
     * (not interruptible) still finishes on the worker and its result is dropped.
     */
    suspend fun decode(model: OnDeviceModel, language: String, samples: FloatArray): Decoded = scope.async {
        inUse.withLock {
            val recognizer = checkNotNull(ensureLoaded(model, language).offline)
            val started = SystemClock.elapsedRealtime()
            lastUse = started
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                recognizer.decode(stream)
                val text = recognizer.getResult(stream).text
                val ms = SystemClock.elapsedRealtime() - started
                Log.d(TAG, "Decoded ${samples.size * 1000L / SAMPLE_RATE} ms of audio in $ms ms")
                Decoded(text, ms)
            } catch (e: Exception) {
                Log.e(TAG, "Decode failed", e)
                throw SttException("On-device transcription failed")
            } finally {
                stream.release()
                lastUse = SystemClock.elapsedRealtime()
            }
        }
    }.await()

    /**
     * One dictation through [model]'s online recognizer: emits the whole text after each chunk taken from [audio]
     * (16 kHz PCM16), then the final text once [audio] closes, each with the decode time so far. Cancelling returns at
     * once; the native stream is released after any decode in flight.
     */
    fun stream(model: OnDeviceModel, audio: ReceiveChannel<ByteArray>): Flow<Decoded> = channelFlow {
        // In the recognizer's scope, not the caller's: a cancelled caller returns without waiting for a native decode.
        val session = scope.async {
            inUse.withLock {
                val recognizer = checkNotNull(ensureLoaded(model, "").online)
                val stream = recognizer.createStream()
                var samples = 0L
                var busyMs = 0L
                try {
                    for (chunk in audio) {
                        val started = SystemClock.elapsedRealtime()
                        lastUse = started
                        val floats = pcm16ToFloats(listOf(chunk))
                        stream.acceptWaveform(floats, SAMPLE_RATE)
                        while (recognizer.isReady(stream)) {
                            ensureActive()
                            recognizer.decode(stream)
                        }
                        samples += floats.size
                        busyMs += SystemClock.elapsedRealtime() - started
                        send(Decoded(recognizer.getResult(stream).text, busyMs))
                    }
                    val started = SystemClock.elapsedRealtime()
                    stream.acceptWaveform(FloatArray(TAIL_PADDING_SAMPLES), SAMPLE_RATE)
                    stream.inputFinished()
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                    busyMs += SystemClock.elapsedRealtime() - started
                    Log.d(TAG, "Decoded ${samples * 1000 / SAMPLE_RATE} ms of audio in $busyMs ms")
                    send(Decoded(recognizer.getResult(stream).text, busyMs))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Decode failed", e)
                    throw SttException("On-device transcription failed")
                } finally {
                    stream.release()
                    lastUse = SystemClock.elapsedRealtime()
                }
            }
        }
        try {
            session.await()
        } finally {
            session.cancel()
        }
    }

    /**
     * Releases [modelId] if it is loaded (before its files are deleted); waits for any queued decode or running
     * streaming session first.
     */
    suspend fun unload(modelId: String) = withContext(worker) {
        inUse.withLock { if (loaded?.modelId == modelId) releaseLoaded() }
    }

    /** Releases the loaded model (memory trim). */
    fun release() {
        scope.launch { inUse.withLock { releaseLoaded() } }
    }

    /**
     * Main thread. [idle] = the bubble is hidden and no dictation is listening or finalizing: the model is released
     * once unused for the [setUnloadMinutes] delay. Not idle stops the countdown. A repeat doesn't restart it.
     */
    fun setIdle(idle: Boolean) {
        if (idle == this.idle) return
        this.idle = idle
        if (idle) idleSince = SystemClock.elapsedRealtime()
        armIdleRelease()
    }

    /** Main thread. The Unload Model setting; a running countdown takes it at once (time already idle counts). */
    fun setUnloadMinutes(minutes: Int) {
        if (minutes == unloadMinutes) return
        unloadMinutes = minutes
        if (idle) armIdleRelease()
    }

    /**
     * Main thread. (Re)starts the countdown while [idle]; a load or decode since then (e.g. a cancelled decode
     * finishing) pushes it out.
     */
    private fun armIdleRelease() {
        idleRelease?.cancel()
        idleRelease = if (!idle) null else scope.launch {
            while (true) {
                val wait = idleReleaseWaitMs(unloadMinutes, idleSince, lastUse, SystemClock.elapsedRealtime())
                    ?: return@launch
                if (wait <= 0) break
                delay(wait)
            }
            // Under the lock: after any running decode.
            inUse.withLock { if (idle) releaseLoaded() }
        }
    }

    /**
     * On [worker], under [inUse]: the recognizer for [model] and [language] (online for a streaming model), loading
     * it (and releasing another) if needed.
     */
    private fun ensureLoaded(model: OnDeviceModel, language: String): Loaded {
        val key = key(model, language)
        loaded?.let { if (it.key == key) return it }
        releaseLoaded()
        wanted = key
        val started = SystemClock.elapsedRealtime()
        val new = try {
            if (model.streams) {
                Loaded(key, model.id, online = OnlineRecognizer(null, onlineConfig(model)))
            } else {
                Loaded(key, model.id, offline = OfflineRecognizer(null, offlineConfig(model, language)))
            }
        } catch (e: Exception) {
            loadFailed(e)
        } catch (e: LinkageError) {
            loadFailed(e)
        }
        loaded = new
        lastUse = SystemClock.elapsedRealtime()
        Log.d(TAG, "Loaded ${model.id} in ${lastUse - started} ms")
        onLoaded(model.id, lastUse - started)
        return new
    }

    private fun loadFailed(e: Throwable): Nothing {
        Log.e(TAG, "Load failed", e)
        wanted = null
        throw SttException("Couldn't load the on-device model")
    }

    /** On [worker]. */
    private fun releaseLoaded() {
        val current = loaded ?: return
        current.offline?.release()
        current.online?.release()
        loaded = null
        wanted = null
        Log.d(TAG, "Released ${current.modelId}")
    }

    private fun offlineConfig(model: OnDeviceModel, language: String): OfflineRecognizerConfig {
        val dir = dir(model.id)
        fun path(file: ModelFile) = File(dir, file.name).path
        val modelConfig = when (model.engine) {
            OnDeviceEngine.NEMO_STREAMING -> error("${model.id} streams")
            OnDeviceEngine.NEMO_TRANSDUCER -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = path(model.encoder),
                    decoder = path(model.decoder),
                    joiner = path(checkNotNull(model.joiner))
                ),
                modelType = "nemo_transducer"
            )
            // Only codes from WHISPER_LANGUAGES or "" (auto) get here: sherpa exits the process on others.
            OnDeviceEngine.WHISPER -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = path(model.encoder),
                    decoder = path(model.decoder),
                    language = language,
                    task = "transcribe"
                ),
                modelType = "whisper"
            )

            OnDeviceEngine.MOONSHINE_V2 -> OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    encoder = path(model.encoder),
                    mergedDecoder = path(model.decoder)
                )
            )
        }.apply {
            tokens = path(model.tokens)
            numThreads = NUM_THREADS
        }
        // The NeMo models' feature dim (128) comes from their metadata.
        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search"
        )
    }

    /** The NeMo streaming impl takes its feature dim (128) and chunk sizes from the model's metadata. */
    private fun onlineConfig(model: OnDeviceModel): OnlineRecognizerConfig {
        val dir = dir(model.id)
        fun path(file: ModelFile) = File(dir, file.name).path
        return OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = path(model.encoder),
                    decoder = path(model.decoder),
                    joiner = path(checkNotNull(model.joiner))
                ),
                tokens = path(model.tokens),
                numThreads = NUM_THREADS
            ),
            enableEndpoint = false,
            decodingMethod = "greedy_search"
        )
    }

    private fun key(model: OnDeviceModel, language: String) = "${model.id}|$language"

    private companion object {
        const val TAG = "OnDeviceRecognizer"
        const val SAMPLE_RATE = 16_000

        /** sherpa's Android examples; more threads land on little cores and slow the decode. */
        const val NUM_THREADS = 2

        /**
         * Zeros after the last chunk, 1.3 s: `inputFinished()` doesn't pad, and a chunk is decoded only once more than
         * a whole window (Nemotron's metadata: 121 frames, shift 112, 10 ms each) is ready, so without them the last
         * ~1.2 s of speech would never be decoded.
         */
        const val TAIL_PADDING_SAMPLES = 20_800
    }
}

/**
 * Milliseconds until an idle model is released: null = never ([unloadMinutes] < 0), <= 0 = now. Idle time counts
 * from the later of [idleSince] and [lastUse] (the last load or decode); all `elapsedRealtime`.
 */
internal fun idleReleaseWaitMs(unloadMinutes: Int, idleSince: Long, lastUse: Long, now: Long): Long? =
    if (unloadMinutes < 0) null else unloadMinutes * 60_000L - (now - maxOf(idleSince, lastUse))
