/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.stt.openai

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RealtimeProtocolTest {

    private fun assertJson(expected: String, actual: String) =
        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(actual))

    @Test
    fun sessionUpdateWithoutLanguage() {
        assertJson(
            """
            {"type":"session.update","session":{"type":"transcription","audio":{"input":{
              "format":{"type":"audio/pcm","rate":24000},
              "transcription":{"model":"gpt-live-transcribe"},
              "turn_detection":null}}}}
            """,
            sessionUpdate("gpt-live-transcribe", " ")
        )
    }

    @Test
    fun sessionUpdateWithLanguageUsesLanguagesArrayOnly() {
        assertJson(
            """
            {"type":"session.update","session":{"type":"transcription","audio":{"input":{
              "format":{"type":"audio/pcm","rate":24000},
              "transcription":{"model":"gpt-live-transcribe","languages":["fr"]},
              "turn_detection":null}}}}
            """,
            sessionUpdate("gpt-live-transcribe", "fr")
        )
    }

    @Test
    fun sessionUpdateUsesSingularLanguageForModelsWithoutTheList() {
        assertJson(
            """
            {"type":"session.update","session":{"type":"transcription","audio":{"input":{
              "format":{"type":"audio/pcm","rate":24000},
              "transcription":{"model":"gpt-realtime-whisper","language":"fr"},
              "turn_detection":null}}}}
            """,
            sessionUpdate("gpt-realtime-whisper", "fr")
        )
        val transcribe = sessionUpdate("gpt-transcribe", "fr")
        assertTrue(""""languages":["fr"]""" in transcribe)
        assertFalse(""""language":""" in transcribe)
    }

    @Test
    fun realtimeModelsStreamOtherIdsUpload() {
        listOf("gpt-live-transcribe", " gpt-transcribe ", "gpt-realtime-whisper", "whisper-1", "gpt-4o-transcribe")
            .forEach { assertTrue(it, isRealtimeTranscriptionModel(it)) }
        listOf("gpt-4o-transcribe-2025-03-20", "my-model", "")
            .forEach { assertFalse(it, isRealtimeTranscriptionModel(it)) }
    }

    @Test
    fun onlyLiveModelsHaveLiveText() {
        listOf("gpt-live-transcribe", " gpt-realtime-whisper ").forEach { assertTrue(it, hasLiveText(it)) }
        listOf("gpt-transcribe", "whisper-1", "gpt-4o-transcribe", "gpt-4o-mini-transcribe", "my-model", "")
            .forEach { assertFalse(it, hasLiveText(it)) }
    }

    @Test
    fun httpErrorMessages() {
        assertEquals("OpenAI rejected the API key. Check it in Iris settings.", openAiHttpErrorMessage(401, ""))
        assertEquals("OpenAI rejected the API key. Check it in Iris settings.", openAiHttpErrorMessage(403, ""))
        assertEquals("OpenAI rate limit or quota reached", openAiHttpErrorMessage(429, ""))
        assertEquals("OpenAI server error (HTTP 503)", openAiHttpErrorMessage(503, ""))
        assertEquals(
            "OpenAI error: Invalid file format.",
            openAiHttpErrorMessage(400, """{"error":{"message":"Invalid file format.","type":"invalid_request"}}""")
        )
        assertEquals("OpenAI request failed (HTTP 400)", openAiHttpErrorMessage(400, "<html>"))
    }

    @Test
    fun appendAndCommitMessages() {
        assertJson(
            """{"type":"input_audio_buffer.append","audio":"AQID/w=="}""",
            appendAudio(byteArrayOf(1, 2, 3, -1))
        )
        assertJson("""{"type":"input_audio_buffer.commit"}""", COMMIT_AUDIO)
    }

    @Test
    fun parsesTranscriptEvents() {
        assertEquals(
            RealtimeEvent.Delta("item_1", "Hello,"),
            parseServerEvent(
                """{"type":"conversation.item.input_audio_transcription.delta","item_id":"item_1",
                   "content_index":0,"delta":"Hello,"}"""
            )
        )
        assertEquals(
            RealtimeEvent.Completed("item_1", "Hello, how are you?"),
            parseServerEvent(
                """{"type":"conversation.item.input_audio_transcription.completed","item_id":"item_1",
                   "content_index":0,"transcript":"Hello, how are you?","usage":{"type":"duration","seconds":1.2}}"""
            )
        )
        assertEquals(
            RealtimeEvent.Committed("item_1"),
            parseServerEvent("""{"type":"input_audio_buffer.committed","item_id":"item_1","previous_item_id":null}""")
        )
        assertNull(parseServerEvent("""{"type":"session.updated","session":{}}"""))
    }

    @Test
    fun mapsErrors() {
        assertEquals(
            RealtimeEvent.Failed("OpenAI error: Invalid language code"),
            parseServerEvent(
                """{"type":"error","event_id":"e1","error":{"type":"invalid_request_error",
                   "code":"invalid_value","message":"Invalid language code","param":null,"event_id":null}}"""
            )
        )
        assertEquals(
            RealtimeEvent.Failed("Transcription failed: Audio too short"),
            parseServerEvent(
                """{"type":"conversation.item.input_audio_transcription.failed","item_id":"item_1",
                   "content_index":0,"error":{"type":"server_error","message":"Audio too short"}}"""
            )
        )
        assertEquals(
            "OpenAI rejected the API key. Check it in Iris settings.",
            connectionFailureMessage(401, IOException())
        )
        assertEquals("Network error: timeout", connectionFailureMessage(null, IOException("timeout")))
    }

    @Test
    fun malformedEventsAreIgnoredOrReadable() {
        assertNull(parseServerEvent("not json"))
        assertNull(parseServerEvent("[1,2]"))
        assertNull(parseServerEvent("""{"type":"input_audio_buffer.committed"}"""))
        assertNull(parseServerEvent("""{"type":"conversation.item.input_audio_transcription.delta","item_id":null}"""))
        assertNull(parseServerEvent("""{"type":{"nested":true}}"""))
        assertEquals(
            RealtimeEvent.Failed("OpenAI error: unknown error"),
            parseServerEvent("""{"type":"error","error":"not an object"}""")
        )
    }

    @Test
    fun assemblerConcatenatesDeltasPerItem() {
        val transcript = TranscriptAssembler()
        transcript.onDelta("a", "Hello")
        transcript.onDelta("a", " wor")
        transcript.onDelta("a", "ld")
        assertEquals("Hello world", transcript.liveText)
        assertNull(transcript.finalText)
    }

    @Test
    fun finalTextWaitsForCommittedItemToComplete() {
        val transcript = TranscriptAssembler()
        transcript.onDelta("a", "Hello wrld")
        transcript.onCommitted("a")
        assertNull(transcript.finalText)
        transcript.onCompleted("a", "Hello world.")
        transcript.onDelta("a", " late")
        assertEquals("Hello world.", transcript.liveText)
        assertEquals("Hello world.", transcript.finalText)
    }

    @Test
    fun multipleItemsJoinInFirstSeenOrder() {
        val transcript = TranscriptAssembler()
        transcript.onDelta("a", "First.")
        transcript.onDelta("b", " Second")
        transcript.onCommitted("b")
        transcript.onCompleted("b", "Second.")
        assertEquals("First. Second.", transcript.liveText)
        // "a" never completed, so it is left out of the final text.
        assertEquals("Second.", transcript.finalText)
        transcript.onCompleted("a", "First.")
        assertEquals("First. Second.", transcript.finalText)
    }
}
