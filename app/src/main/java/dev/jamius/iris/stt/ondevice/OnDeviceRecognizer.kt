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
import dev.jamius.iris.stt.SttException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Holds one sherpa-onnx offline recognizer (one model + language) at a time. Load, decode, unload and release all
 * run on one worker thread: decodes are serialized across sessions (Whisper mutates shared decoder state), a session
 * started during a load waits for it, and an unload never runs mid-decode. Released 5 min after the bubble hides
 * (or on memory trim). Not unit tested (native).
 *
 * @param dir a model's download directory (the store's).
 */
class OnDeviceRecognizer(private val dir: (modelId: String) -> File) {

    private val worker = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + worker)

    private class Loaded(val key: String, val modelId: String, val recognizer: OfflineRecognizer)

    /** Touched only on [worker]. */
    private var loaded: Loaded? = null

    /** The key being loaded or loaded: repeated [preload]s for it are skipped. */
    @Volatile
    private var wanted: String? = null

    /** `elapsedRealtime` of the last load or decode. */
    @Volatile
    private var lastUse = 0L

    @Volatile
    private var bubbleShown = false

    /** Main thread only. */
    private var idleRelease: Job? = null

    /** Loads [model] with [language] ahead of a decode. Fire-and-forget; failures are only logged. */
    fun preload(model: OnDeviceModel, language: String) {
        val key = key(model, language)
        if (wanted == key) return
        wanted = key
        scope.launch {
            try {
                ensureLoaded(model, language)
            } catch (e: SttException) {
                Log.w(TAG, "Preload failed: ${e.message}")
            }
        }
    }

    /**
     * The text of [samples] (16 kHz mono, -1..1). Cancelling returns at once; the native decode (not interruptible)
     * still finishes on the worker and its result is dropped.
     */
    suspend fun decode(model: OnDeviceModel, language: String, samples: FloatArray): String = scope.async {
        val recognizer = ensureLoaded(model, language)
        val started = SystemClock.elapsedRealtime()
        lastUse = started
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.also {
                val ms = SystemClock.elapsedRealtime() - started
                Log.d(TAG, "Decoded ${samples.size * 1000L / SAMPLE_RATE} ms of audio in $ms ms")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decode failed", e)
            throw SttException("On-device transcription failed")
        } finally {
            stream.release()
            lastUse = SystemClock.elapsedRealtime()
        }
    }.await()

    /** Releases [modelId] if it is loaded (before its files are deleted); waits for any queued decode first. */
    suspend fun unload(modelId: String) = withContext(worker) {
        if (loaded?.modelId == modelId) releaseLoaded()
    }

    /** Releases the loaded model (memory trim). */
    fun release() {
        scope.launch { releaseLoaded() }
    }

    /** Main thread. Hidden: releases the model once nothing used it for 5 min; shown: keeps it. */
    fun setBubbleShown(shown: Boolean) {
        bubbleShown = shown
        idleRelease?.cancel()
        idleRelease = if (shown) null else scope.launch {
            delay(IDLE_MS)
            // A decode since (e.g. Finalizing after focus loss) re-arms the wait.
            while (true) {
                val idle = SystemClock.elapsedRealtime() - lastUse
                if (idle >= IDLE_MS) break
                delay(IDLE_MS - idle)
            }
            if (!bubbleShown) releaseLoaded()
        }
    }

    /** On [worker]: the recognizer for [model] and [language], loading it (and releasing another) if needed. */
    private fun ensureLoaded(model: OnDeviceModel, language: String): OfflineRecognizer {
        val key = key(model, language)
        loaded?.let { if (it.key == key) return it.recognizer }
        releaseLoaded()
        wanted = key
        val started = SystemClock.elapsedRealtime()
        val recognizer = try {
            OfflineRecognizer(null, config(model, language))
        } catch (e: Exception) {
            loadFailed(e)
        } catch (e: LinkageError) {
            loadFailed(e)
        }
        loaded = Loaded(key, model.id, recognizer)
        lastUse = SystemClock.elapsedRealtime()
        Log.d(TAG, "Loaded ${model.id} in ${lastUse - started} ms")
        return recognizer
    }

    private fun loadFailed(e: Throwable): Nothing {
        Log.e(TAG, "Load failed", e)
        wanted = null
        throw SttException("Couldn't load the on-device model")
    }

    /** On [worker]. */
    private fun releaseLoaded() {
        val current = loaded ?: return
        current.recognizer.release()
        loaded = null
        wanted = null
        Log.d(TAG, "Released ${current.modelId}")
    }

    private fun config(model: OnDeviceModel, language: String): OfflineRecognizerConfig {
        val dir = dir(model.id)
        fun path(file: ModelFile) = File(dir, file.name).path
        val modelConfig = when (model.engine) {
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

    private fun key(model: OnDeviceModel, language: String) = "${model.id}|$language"

    private companion object {
        const val TAG = "OnDeviceRecognizer"
        const val SAMPLE_RATE = 16_000

        /** sherpa's Android examples; more threads land on little cores and slow the decode. */
        const val NUM_THREADS = 2
        const val IDLE_MS = 5 * 60 * 1000L
    }
}
