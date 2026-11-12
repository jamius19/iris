/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.stt.soniox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonioxProtocolTest {

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test
    fun regionsMapTheirIdsAndUnknownIsUs() {
        assertEquals(SonioxRegion.US, sonioxRegion("us"))
        assertEquals(SonioxRegion.EU, sonioxRegion("eu"))
        assertEquals(SonioxRegion.JP, sonioxRegion("jp"))
        assertEquals(SonioxRegion.IN, sonioxRegion("in"))
        assertEquals(SonioxRegion.US, sonioxRegion("mars"))
        assertEquals(SonioxRegion.US, sonioxRegion(""))
        assertEquals("https://api.eu.soniox.com", SonioxRegion.EU.restUrl)
        assertEquals("https://stt-rt.eu.soniox.com/transcribe-websocket", SonioxRegion.EU.webSocketUrl)
    }

    @Test
    fun languageHintsAreTrimmedLowercasedAndUnique() {
        assertEquals(listOf("en", "bn"), sonioxLanguageHints(" en, BN,,en "))
        assertEquals(emptyList<String>(), sonioxLanguageHints(""))
        assertEquals(emptyList<String>(), sonioxLanguageHints(" , "))
    }

    @Test
    fun startMessageStatesTheRawFormat() {
        assertEquals(
            json("""{"model":"stt-rt-v6","audio_format":"pcm_s16le","sample_rate":16000,"num_channels":1}"""),
            json(sonioxStartMessage("stt-rt-v6", emptyList(), strict = true))
        )
    }

    @Test
    fun startMessageHintsAndStrictOnlyWhenAsked() {
        assertEquals(
            json(
                """{"model":"stt-rt-v6","audio_format":"pcm_s16le","sample_rate":16000,"num_channels":1,
                   "language_hints":["en","bn"],"language_hints_strict":true}"""
            ),
            json(sonioxStartMessage("stt-rt-v6", listOf("en", "bn"), strict = true))
        )
        assertEquals(
            json(
                """{"model":"stt-rt-v6","audio_format":"pcm_s16le","sample_rate":16000,"num_channels":1,
                   "language_hints":["en"]}"""
            ),
            json(sonioxStartMessage("stt-rt-v6", listOf("en"), strict = false))
        )
    }

    @Test
    fun createBodyHasTheFileAndTheSameHintRules() {
        assertEquals(
            json("""{"model":"stt-async-v6","file_id":"f1"}"""),
            json(sonioxCreateTranscriptionBody("stt-async-v6", "f1", emptyList(), strict = true))
        )
        assertEquals(
            json("""{"model":"stt-async-v6","file_id":"f1","language_hints":["en"],"language_hints_strict":true}"""),
            json(sonioxCreateTranscriptionBody("stt-async-v6", "f1", listOf("en"), strict = true))
        )
        assertEquals(
            json("""{"model":"stt-async-v6","file_id":"f1","language_hints":["en"]}"""),
            json(sonioxCreateTranscriptionBody("stt-async-v6", "f1", listOf("en"), strict = false))
        )
    }

    @Test
    fun tokensAreParsedWithoutMarkers() {
        val event = parseSonioxEvent(
            """{"tokens":[{"text":"Hello","start_ms":600,"is_final":true},{"text":" wor","is_final":false},
               {"text":"<fin>","is_final":true},{"text":"<end>","is_final":true},{"start_ms":1},{"text":7},"x",
               {"text":" there"}],"final_audio_proc_ms":760,"total_audio_proc_ms":880}"""
        )

        assertEquals(
            SonioxEvent.Tokens(
                listOf(SonioxToken("Hello", true), SonioxToken(" wor", false), SonioxToken(" there", false)),
                finished = false
            ),
            event
        )
    }

    @Test
    fun finishedResponseIsParsed() {
        assertEquals(
            SonioxEvent.Tokens(emptyList(), finished = true),
            parseSonioxEvent("""{"tokens":[],"final_audio_proc_ms":1560,"total_audio_proc_ms":1680,"finished":true}""")
        )
    }

    @Test
    fun errorFrameIsParsed() {
        assertEquals(
            SonioxEvent.Error(401, "Invalid API key."),
            parseSonioxEvent(
                """{"tokens":[],"error_code":401,"error_type":"unauthenticated","error_message":"Invalid API key.",
                   "request_id":"r"}"""
            )
        )
        assertEquals(SonioxEvent.Error(503, null), parseSonioxEvent("""{"tokens":[],"error_code":503}"""))
    }

    @Test
    fun malformedOrUnknownMessagesAreIgnored() {
        for (text in listOf("not json", "[1]", "\"x\"", """{"finished":true}""", """{"tokens":{}}""")) {
            assertNull(text, parseSonioxEvent(text))
        }
    }

    @Test
    fun transcriptAppendsFinalsAndReplacesTheTail() {
        val transcript = SonioxTranscript()

        transcript.onTokens(listOf(SonioxToken("How", true), SonioxToken(" a", false)))
        assertEquals("How a", transcript.liveText)
        assertEquals("How", transcript.finalText)

        transcript.onTokens(listOf(SonioxToken(" are", true), SonioxToken(" you", false), SonioxToken("?", false)))
        assertEquals("How are you?", transcript.liveText)
        assertEquals("How are", transcript.finalText)

        transcript.onTokens(listOf(SonioxToken(" you?", true), SonioxToken(" ", true)))
        assertEquals("How are you?", transcript.liveText)
        assertEquals("How are you?", transcript.finalText)
    }

    @Test
    fun errorMessagesMapByCode() {
        assertEquals("Soniox rejected the API key. Check it in Iris settings.", sonioxErrorMessage(401, "x"))
        assertEquals("Soniox account is out of credits or over budget", sonioxErrorMessage(402, null))
        assertEquals("Soniox API key doesn't have permission for this", sonioxErrorMessage(403, null))
        assertEquals("Soniox limit reached: Too many requests", sonioxErrorMessage(429, "Too many requests"))
        assertEquals("Soniox limit reached", sonioxErrorMessage(429, null))
        assertEquals("Soniox server error (503)", sonioxErrorMessage(503, "Cannot continue request"))
        assertEquals("Soniox error: Invalid language hint.", sonioxErrorMessage(400, "Invalid language hint."))
        assertEquals("Soniox request failed (408)", sonioxErrorMessage(408, null))
    }

    @Test
    fun httpErrorBodiesMap() {
        assertEquals(
            "Soniox rejected the API key. Check it in Iris settings.",
            sonioxHttpErrorMessage(401, """{"status_code":401,"error_type":"unauthenticated","message":"Bad key"}""")
        )
        assertEquals(
            "Soniox error: Model not found",
            sonioxHttpErrorMessage(
                400,
                """{"status_code":400,"error_type":"invalid_request","message":"Model not found",
                   "validation_errors":[]}"""
            )
        )
        assertEquals(
            "Soniox error: Invalid language hint.",
            sonioxHttpErrorMessage(
                400,
                """{"status_code":400,"error_type":"invalid_request","message":"",
                   "validation_errors":[{"error_type":"invalid","location":"language_hints","message":
                   "Invalid language hint."}]}"""
            )
        )
        assertEquals("Soniox request failed (400)", sonioxHttpErrorMessage(400, "<html>Bad request</html>"))
        assertEquals("Soniox server error (502)", sonioxHttpErrorMessage(502, ""))
    }

    @Test
    fun noAudioErrorIsToldApartByItsMessage() {
        assertTrue(isNoAudioError(SonioxEvent.Error(400, "No audio received.")))
        assertFalse(isNoAudioError(SonioxEvent.Error(400, "Invalid language hint.")))
        assertFalse(isNoAudioError(SonioxEvent.Error(400, null)))
        assertFalse(isNoAudioError(SonioxEvent.Error(408, "No audio received.")))
    }

    @Test
    fun closedAndNetworkMessages() {
        assertEquals("Soniox closed the connection: Going away", sonioxClosedMessage(1001, "Going away"))
        assertEquals("Soniox closed the connection (code 1006)", sonioxClosedMessage(1006, ""))
        assertEquals("Network error: timeout", sonioxNetworkErrorMessage(java.io.IOException("timeout")))
        assertEquals("Network error: IOException", sonioxNetworkErrorMessage(java.io.IOException()))
    }

    @Test
    fun restHelpersParseTheirFields() {
        assertEquals("f1", parseSonioxId("""{"id":"f1","filename":"audio.wav","size":10}"""))
        assertNull(parseSonioxId("""{"id":1}"""))
        assertNull(parseSonioxId("nope"))
        assertEquals(SonioxJob("queued", null), parseSonioxJob("""{"id":"t1","status":"queued"}"""))
        assertEquals(
            SonioxJob("error", "Audio can't be decoded"),
            parseSonioxJob(
                """{"status":"error","error_type":"invalid_audio_file","error_message":"Audio can't be decoded"}"""
            )
        )
        assertEquals(
            SonioxJob("failed", "file_not_found"),
            parseSonioxJob("""{"status":"failed","error_type":"file_not_found","error_message":null}""")
        )
        assertNull(parseSonioxJob("""{"id":"t1"}"""))
        assertEquals("Hello", parseSonioxTranscriptText("""{"id":"t1","text":"Hello","tokens":[]}"""))
        assertNull(parseSonioxTranscriptText("""{"id":"t1"}"""))
    }

    @Test
    fun jobFailureStatuses() {
        assertTrue(isSonioxJobFailed("error"))
        assertTrue(isSonioxJobFailed("failed"))
        assertFalse(isSonioxJobFailed("completed"))
        assertFalse(isSonioxJobFailed("queued"))
        assertFalse(isSonioxJobFailed("processing"))
    }
}
