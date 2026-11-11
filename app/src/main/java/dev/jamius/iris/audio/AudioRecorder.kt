/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/** A user-readable microphone failure. */
class AudioCaptureException(message: String) : Exception(message)

/**
 * Recorded PCM [bytes]; [readAtNanos] is `SystemClock.elapsedRealtimeNanos()` when the read returned. [level] is the
 * chunk's loudness, 0..1, for the waveform.
 */
class AudioChunk(val bytes: ByteArray, val readAtNanos: Long, val level: Float = 0f)

/**
 * Records PCM16 mono audio at the rate the session asks for (Android resamples if the hardware doesn't run at it). Uses
 * `VOICE_RECOGNITION`, the source AOSP lets an accessibility service capture from while in the background (see
 * CLAUDE.md).
 */
class AudioRecorder(private val context: Context) {

    /**
     * Cold flow: each collection opens the mic at [sampleRate] Hz, emits ~50 ms chunks on [Dispatchers.IO] until
     * cancelled, then releases the recorder. Throws [AudioCaptureException] if the permission is missing, the mic
     * can't be opened, or Android silences the capture (e.g. background capture is blocked, or another app took the
     * mic).
     */
    fun chunks(sampleRate: Int): Flow<AudioChunk> = flow {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw AudioCaptureException("Microphone permission is missing. Grant it in the Iris app.")
        }
        // 50 ms of 16-bit mono audio: stop takes effect after the chunk in progress, so smaller is snappier.
        val chunkBytes = sampleRate * 2 / 20
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, CHANNEL, ENCODING)
        // 200 ms of headroom, so a briefly stalled reader doesn't drop audio.
        val record = AudioRecord(SOURCE, sampleRate, CHANNEL, ENCODING, maxOf(minBuffer, 4 * chunkBytes))
        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                throw AudioCaptureException("Couldn't open the microphone")
            }
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw AudioCaptureException("Couldn't start recording. Is another app using the microphone?")
            }
            val buffer = ByteArray(chunkBytes)
            while (currentCoroutineContext().isActive) {
                val read = record.read(buffer, 0, buffer.size)
                val readAt = SystemClock.elapsedRealtimeNanos()
                // Cancelled during the read (e.g. stopped): end quietly, not with a capture error.
                currentCoroutineContext().ensureActive()
                if (read < 0) throw AudioCaptureException("Microphone read failed (error $read)")
                // A silenced client keeps receiving zeros instead of an error, so check explicitly.
                if (record.activeRecordingConfiguration?.isClientSilenced == true) {
                    throw AudioCaptureException("Android silenced the microphone for Iris")
                }
                // A blocking read returning 0 is unusual; back off briefly instead of spinning.
                if (read > 0) {
                    emit(AudioChunk(buffer.copyOf(read), readAt, pcm16Level(buffer, read)))
                } else {
                    delay(EMPTY_READ_BACKOFF_MS)
                }
            }
        } finally {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            record.release()
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        const val EMPTY_READ_BACKOFF_MS = 10L
    }
}
