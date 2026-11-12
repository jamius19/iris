/*
* Author: Jamius Siam
* Since: 12/11/2026
*/

package dev.jamius.iris.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.nio.FloatBuffer

/**
 * Silero VAD v6.2 (`assets/silero_vad.onnx`, MIT, see `assets/silero_vad_LICENSE.txt`) on ONNX Runtime. One per app;
 * the model loads on first use and stays loaded for the process lifetime.
 */
class SileroVad(private val context: Context) {

    // Loads the native library, so it is only touched off the main thread (from [session]).
    private val env by lazy { OrtEnvironment.getEnvironment() }

    /** Null if loading failed (logged once): sessions then send all audio. */
    private val session: OrtSession? by lazy {
        try {
            val start = SystemClock.elapsedRealtime()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            env.createSession(context.assets.open(MODEL).use { it.readBytes() }, options)
                .also { Log.d(TAG, "Model loaded in ${SystemClock.elapsedRealtime() - start} ms") }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't load the VAD model", e)
            null
        } catch (e: LinkageError) {
            Log.e(TAG, "Couldn't load ONNX Runtime", e)
            null
        }
    }

    /**
     * A new stream for one recording (zeroed LSTM state and context): maps each [FRAME_SAMPLES]-sample 16 kHz frame
     * (-1..1) to its speech probability. Call it off the main thread (the first call loads the model); throws if the
     * model is unavailable. Not thread-safe: one caller at a time.
     */
    fun newStream(): (FloatArray) -> Float {
        // As the official OnnxWrapper (silero_vad/utils_vad.py): each input is the previous input's last
        // CONTEXT_SAMPLES samples followed by the new frame; the recurrent state is fed back.
        val input = FloatArray(CONTEXT_SAMPLES + FRAME_SAMPLES)
        val state = FloatArray(STATE_SIZE)
        return { frame ->
            val session = session ?: throw IllegalStateException("VAD model unavailable")
            require(frame.size == FRAME_SAMPLES) { "Expected $FRAME_SAMPLES samples, got ${frame.size}" }
            frame.copyInto(input, CONTEXT_SAMPLES)
            // Tensors copy the arrays they wrap, so both arrays can be overwritten once they exist.
            val probability = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), INPUT_SHAPE).use { x ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(state), STATE_SHAPE).use { h ->
                    OnnxTensor.createTensor(env, SAMPLE_RATE).use { sr ->
                        session.run(mapOf("input" to x, "state" to h, "sr" to sr)).use { result ->
                            (result[1] as OnnxTensor).floatBuffer.get(state)
                            (result[0] as OnnxTensor).floatBuffer.get(0)
                        }
                    }
                }
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
        const val STATE_SIZE = 2 * 1 * 128

        /** A 0-d int64 tensor, as the Python wrapper passes it. */
        const val SAMPLE_RATE = 16_000L
        val INPUT_SHAPE = longArrayOf(1, (CONTEXT_SAMPLES + FRAME_SAMPLES).toLong())
        val STATE_SHAPE = longArrayOf(2, 1, 128)
    }
}
