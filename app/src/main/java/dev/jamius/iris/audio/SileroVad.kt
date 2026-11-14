/*
* Author: Jamius Siam
* Since: 12/11/2026
*/

package dev.jamius.iris.audio

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Silero VAD v6.2 (`assets/silero_vad.onnx`, MIT, see `assets/silero_vad_LICENSE.txt`) on sherpa-onnx's ONNX Runtime
 * (`Vad.compute`: one model run, the LSTM state kept natively). One per app; the model loads on first use and stays
 * loaded for the process lifetime.
 */
class SileroVad(private val context: Context) {

    /** Null if loading failed (logged once): sessions then send all audio. Loads the native library: off main only. */
    private val vad: Vad? by lazy {
        try {
            val start = SystemClock.elapsedRealtime()
            val config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(model = MODEL, windowSize = FRAME_SAMPLES),
                sampleRate = 16_000,
                numThreads = 1
            )
            Vad(context.assets, config)
                .also { Log.d(TAG, "Model loaded in ${SystemClock.elapsedRealtime() - start} ms") }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't load the VAD model", e)
            null
        } catch (e: LinkageError) {
            Log.e(TAG, "Couldn't load sherpa-onnx", e)
            null
        }
    }

    /** The stream whose state the model holds; another stream's first frame resets it. Guarded by [lock]. */
    private var owner: Any? = null
    private val lock = Any()

    /**
     * A new stream for one recording (zeroed LSTM state and context): maps each [FRAME_SAMPLES]-sample 16 kHz frame
     * (-1..1) to its speech probability. Call it off the main thread (the first call loads the model); throws if the
     * model is unavailable. Not thread-safe: one caller at a time.
     */
    fun newStream(): (FloatArray) -> Float {
        // Each input is the previous input's last CONTEXT_SAMPLES samples followed by the new frame, as the
        // official OnnxWrapper (silero_vad/utils_vad.py) and sherpa's own acceptWaveform (512 + 64-sample windows)
        // do; compute() runs the model on exactly the samples passed (no context added), so it is added here.
        val input = FloatArray(CONTEXT_SAMPLES + FRAME_SAMPLES)
        val stream = Any()
        return { frame ->
            val vad = vad ?: throw IllegalStateException("VAD model unavailable")
            require(frame.size == FRAME_SAMPLES) { "Expected $FRAME_SAMPLES samples, got ${frame.size}" }
            frame.copyInto(input, CONTEXT_SAMPLES)
            val probability = synchronized(lock) {
                // A new recording starts from a zeroed state (an ended one may still finish a frame meanwhile).
                if (owner !== stream) {
                    vad.reset()
                    owner = stream
                }
                vad.compute(input)
            }
            input.copyInto(input, 0, input.size - CONTEXT_SAMPLES)
            probability
        }
    }

    private companion object {
        const val TAG = "SileroVad"
        const val MODEL = "silero_vad.onnx"
        const val FRAME_SAMPLES = 512
        const val CONTEXT_SAMPLES = 64
    }
}
