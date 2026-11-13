/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.stt.ondevice

import dev.jamius.iris.stt.SttException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OnDeviceSpeechToTextTest {

    private val decoded = mutableListOf<FloatArray>()
    private val languages = mutableListOf<String>()
    private val partials = mutableListOf<String>()

    private fun TestScope.provider(
        downloaded: Boolean = true,
        maxSegmentBytes: Int = MAX_SEGMENT_BYTES,
        decode: suspend (FloatArray) -> String,
    ) = OnDeviceSpeechToText(
        isDownloaded = { downloaded },
        preload = { _, language -> languages += "preload:$language" },
        decode = { _, language, samples ->
            decoded += samples
            languages += language
            decode(samples)
        },
        selection = { MOONSHINE to "" },
        scope = backgroundScope,
        maxSegmentBytes = maxSegmentBytes
    )

    /** Replies with [texts] in order. */
    private fun replies(vararg texts: String): suspend (FloatArray) -> String {
        var i = 0
        return { texts[i++] }
    }

    private suspend fun OnDeviceSpeechToText.run(
        audio: Flow<ByteArray>,
        model: String = MOONSHINE,
        language: String = "",
    ): String = transcribe(audio, model, language, timeoutMs = 1, onPartial = { partials += it })

    /** [n] samples of [value]. */
    private fun chunk(n: Int, value: Short = 1_000) = ByteArray(2 * n).also {
        for (i in 0 until n) {
            it[2 * i] = value.toByte()
            it[2 * i + 1] = (value.toInt() shr 8).toByte()
        }
    }

    @Test
    fun modelNotDownloadedFailsBeforeTheAudioIsCollected() = runTest {
        var collected = false
        val stt = provider(downloaded = false, decode = replies())
        try {
            stt.run(flow { collected = true })
            fail("Expected SttException")
        } catch (e: SttException) {
            assertEquals("Download the Moonshine Base EN model in Iris settings", e.message)
        }
        assertFalse(collected)
        assertTrue(languages.isEmpty())
    }

    @Test
    fun segmentEndsAreDecodedInOrderWhileRecording() = runTest {
        val stt = provider(decode = replies("Hello there.", "How are you?"))
        val audio = flow {
            emit(chunk(100))
            emit(chunk(50))
            emit(ByteArray(0))
            delay(100)
            // The first segment was decoded while recording.
            assertEquals(listOf("Hello there."), partials)
            emit(chunk(30))
            emit(ByteArray(0))
            emit(ByteArray(0)) // An empty segment: nothing to decode.
        }

        assertEquals("Hello there. How are you?", stt.run(audio))
        assertEquals(listOf(150, 30), decoded.map { it.size })
        assertEquals(listOf("Hello there.", "Hello there. How are you?"), partials)
    }

    @Test
    fun cjkSegmentsJoinUnspacedAndBlankOnesAreSkipped() = runTest {
        val stt = provider(decode = replies("你好", "  ", "世界"))
        val audio = flowOf(chunk(10), ByteArray(0), chunk(10), ByteArray(0), chunk(10))

        assertEquals("你好世界", stt.run(audio))
        assertEquals(3, decoded.size)
        assertEquals(listOf("你好", "你好世界"), partials)
    }

    @Test
    fun withoutSegmentEndsTheAudioIsDecodedOnceAfterItEnds() = runTest {
        val stt = provider(decode = replies("All of it."))
        val audio = flow {
            repeat(5) {
                emit(chunk(100))
                delay(50)
            }
            assertTrue(decoded.isEmpty())
        }

        assertEquals("All of it.", stt.run(audio))
        assertEquals(listOf(500), decoded.map { it.size })
    }

    @Test
    fun aSegmentOverTheCapIsCutAfterTheQuietestChunk() = runTest {
        val stt = provider(maxSegmentBytes = 400, decode = replies("a", "b"))
        // 50 samples (100 bytes) each: loud, loud, quiet, loud, loud.
        val audio = flowOf(chunk(50), chunk(50), chunk(50, 0), chunk(50), chunk(50))

        assertEquals("a b", stt.run(audio))
        assertEquals(listOf(150, 100), decoded.map { it.size })
        assertTrue(decoded[0].takeLast(50).all { it == 0f })
    }

    @Test
    fun decodeFailurePropagates() = runTest {
        val stt = provider(decode = { throw SttException("On-device transcription failed") })
        try {
            stt.run(flowOf(chunk(10)))
            fail("Expected SttException")
        } catch (e: SttException) {
            assertEquals("On-device transcription failed", e.message)
        }
    }

    @Test
    fun aHungDecodeTimesOutAfterTheDeadline() = runTest {
        val stt = provider(decode = { awaitCancellation() })
        try {
            stt.run(flowOf(chunk(16_000))) // 1 s of audio.
            fail("Expected SttException")
        } catch (e: SttException) {
            assertEquals("On-device transcription timed out", e.message)
        }
        // 60 s plus twice the pending audio.
        assertEquals(62_000L, currentTime)
    }

    @Test
    fun cancellingReturnsPromptlyDuringADecode() = runTest {
        val stt = provider(decode = { awaitCancellation() })
        val session = launch { stt.run(flowOf(chunk(10))) }
        runCurrent()
        assertEquals(1, decoded.size)

        session.cancel()
        runCurrent()
        assertTrue(session.isCompleted)
        assertEquals(0L, currentTime)
    }

    @Test
    fun onlyWhisperGetsTheLanguageAndOnlyOneItKnows() = runTest {
        val stt = provider(decode = { "x" })

        stt.run(flowOf(chunk(10)), model = "whisper-small", language = " DE ")
        stt.run(flowOf(chunk(10)), model = "whisper-small", language = "yue")
        stt.run(flowOf(chunk(10)), model = "parakeet-tdt-v3", language = "de")

        assertEquals(listOf("preload:de", "de", "preload:", "", "preload:", ""), languages)
    }

    @Test
    fun warmUpPreloadsTheStoredModel() = runTest {
        val stt = provider(decode = replies())
        stt.warmUp()
        advanceTimeBy(1)
        assertEquals(listOf("preload:"), languages)
    }

    @Test
    fun pcm16BecomesFloatsAcrossChunks() {
        val floats = pcm16ToFloats(
            listOf(byteArrayOf(0x00, 0x80.toByte(), 0xFF.toByte(), 0x7F), byteArrayOf(0x00, 0x40))
        )
        assertArrayEquals(floatArrayOf(-1f, 32767 / 32768f, 0.5f), floats, 0f)
    }

    private companion object {
        const val MOONSHINE = "moonshine-base-en"
    }
}
