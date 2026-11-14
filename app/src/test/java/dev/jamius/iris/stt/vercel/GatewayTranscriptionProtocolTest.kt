/*
* Author: Jamius Siam
* Since: 12/11/2026
*/

package dev.jamius.iris.stt.vercel

import dev.jamius.iris.cleanup.GatewayModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class GatewayTranscriptionProtocolTest {

    private fun assertJson(expected: String, actual: String?) =
        assertEquals(Json.parseToJsonElement(expected), actual?.let(Json::parseToJsonElement))

    @Test
    fun startFrameWithoutOptions() {
        assertJson(
            """{"type":"transcription-stream.start","inputAudioFormat":{"type":"audio/pcm","rate":16000}}""",
            startFrame(16_000, null)
        )
    }

    @Test
    fun startFrameWithOptions() {
        assertJson(
            """
            {"type":"transcription-stream.start","inputAudioFormat":{"type":"audio/pcm","rate":24000},
             "providerOptions":{"openai":{"language":"en"}}}
            """,
            startFrame(24_000, providerOptions("openai/gpt-live-transcribe", "en", streaming = true))
        )
    }

    @Test
    fun audioDoneFrame() {
        assertJson("""{"type":"transcription-stream.audio-done"}""", AUDIO_DONE)
    }

    @Test
    fun languageOptionPerProvider() {
        assertJson(
            """{"openai":{"language":"fr"}}""",
            providerOptions("openai/gpt-transcribe", " fr ", streaming = true).toString()
        )
        assertJson(
            """{"mistral":{"language":"fr"}}""",
            providerOptions("mistral/voxtral", "fr", streaming = true).toString()
        )
        assertJson(
            """{"elevenlabs":{"languageCode":"fr"}}""",
            providerOptions("elevenlabs/scribe-v2-realtime", "fr", streaming = true).toString()
        )
        assertJson(
            """{"google":{"languageCodes":["fr"]}}""",
            providerOptions("google/gemini-3.5-transcribe-live", "fr", streaming = true).toString()
        )
        assertNull(providerOptions("microsoft/mai-transcribe-2-streaming", "fr", streaming = true))
    }

    @Test
    fun uploadOptionsListGptTranscribesLanguageAndSkipGrokInterimResults() {
        assertJson(
            """{"openai":{"languages":["fr"]}}""",
            providerOptions("openai/gpt-transcribe", "fr", streaming = false).toString()
        )
        assertJson(
            """{"openai":{"language":"fr"}}""",
            providerOptions("openai/gpt-4o-transcribe", "fr", streaming = false).toString()
        )
        assertJson(
            """{"elevenlabs":{"languageCode":"fr"}}""",
            providerOptions("elevenlabs/scribe-v2", "fr", streaming = false).toString()
        )
        assertNull(providerOptions("spacexai/grok-stt", "en", streaming = false))
    }

    @Test
    fun blankLanguageSendsNoOptions() {
        assertNull(providerOptions("openai/gpt-live-transcribe", " ", streaming = true))
        assertNull(providerOptions("google/gemini-3.5-transcribe-live", "", streaming = true))
    }

    @Test
    fun grokAlwaysAsksForInterimResultsUnderBothNames() {
        val interim =
            """{"xai":{"streaming":{"interimResults":true}},"spacexai":{"streaming":{"interimResults":true}}}"""
        assertJson(interim, providerOptions("spacexai/grok-stt", "", streaming = true).toString())
        assertJson(interim, providerOptions("xai/grok-stt", "en", streaming = true).toString())
    }

    @Test
    fun sampleRatePerModel() {
        assertEquals(24_000, gatewaySampleRate("openai/gpt-live-transcribe"))
        assertEquals(24_000, gatewaySampleRate("openai/gpt-realtime-whisper"))
        assertEquals(16_000, gatewaySampleRate("google/gemini-3.5-transcribe-live"))
        assertEquals(16_000, gatewaySampleRate("spacexai/grok-stt"))
    }

    @Test
    fun bareModelIdCountsAsOpenAi() {
        assertEquals(24_000, gatewaySampleRate("gpt-live-transcribe"))
        assertJson(
            """{"openai":{"language":"en"}}""",
            providerOptions("gpt-live-transcribe", "en", streaming = true).toString()
        )
    }

    @Test
    fun streamUrlEncodesTheModelId() {
        assertEquals(
            "https://ai-gateway.vercel.sh/v4/ai/transcription-model?ai-model-id=openai%2Fgpt-live-transcribe",
            streamUrl(TRANSCRIPTION_URL, "openai/gpt-live-transcribe").toString()
        )
    }

    @Test
    fun streamingFilterKeepsTranscriptionModelsTaggedForTheWebSocket() {
        val catalog = listOf(
            GatewayModel("elevenlabs/scribe-v2", "transcription", emptyList(), null),
            GatewayModel("openai/gpt-6-luna", "language", emptyList(), null),
            GatewayModel("openai/gpt-live-transcribe", "transcription", listOf(STREAMING_TAG), null),
            GatewayModel(
                "openai/gpt-realtime-whisper",
                "transcription",
                listOf("websocket-realtime", STREAMING_TAG),
                null
            ),
            GatewayModel("openai/whisper-1", "transcription", listOf("open-weights"), null),
            GatewayModel("x/untyped", null, listOf(STREAMING_TAG), null),
        )

        assertEquals(
            listOf("openai/gpt-live-transcribe", "openai/gpt-realtime-whisper"),
            catalog.filter(::isStreamingTranscriptionModel).map { it.id }
        )
    }

    @Test
    fun allTranscriptionModelsAreListedAndTheTagPicksLiveOrAfterStop() {
        val catalog = listOf(
            GatewayModel("elevenlabs/scribe-v2", "transcription", emptyList(), null),
            GatewayModel("openai/gpt-6-luna", "language", emptyList(), null),
            GatewayModel("openai/gpt-live-transcribe", "transcription", listOf(STREAMING_TAG), null),
        )

        assertEquals(
            listOf("elevenlabs/scribe-v2", "openai/gpt-live-transcribe"),
            catalog.filter(::isTranscriptionModel).map { it.id }
        )
        assertTrue(gatewaySttLive("openai/gpt-live-transcribe", catalog))
        assertFalse(gatewaySttLive(" elevenlabs/scribe-v2 ", catalog))
        // Not in the catalog, or no catalog: live.
        assertTrue(gatewaySttLive("acme/custom-stt", catalog))
        assertTrue(gatewaySttLive("elevenlabs/scribe-v2", null))
    }

    @Test
    fun uploadBodyIsJsonWithTheBase64Wav() {
        // Lengths 0, 1 and 2 mod 3, and more than one slice.
        for (size in listOf(0, 1, 2, 3, 48 * 1024, 48 * 1024 + 1, 100_000)) {
            val wav = ByteArray(size) { (it * 7).toByte() }
            val options = providerOptions("openai/gpt-transcribe", "fr", streaming = false)
            val body = UploadBody(wav, options)
            val buffer = Buffer()

            body.writeTo(buffer)

            assertEquals(body.contentLength(), buffer.size)
            val json = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            assertArrayEquals(wav, Base64.getDecoder().decode(json["audio"]!!.jsonPrimitive.content))
            assertEquals("audio/wav", json["mediaType"]!!.jsonPrimitive.content)
            assertEquals(options, json["providerOptions"])
            assertTrue(body.sent)
        }
        val plain = Buffer().also { UploadBody(byteArrayOf(1), null).writeTo(it) }.readUtf8()
        assertEquals("""{"audio":"AQ==","mediaType":"audio/wav"}""", plain)
    }

    @Test
    fun parsesUploadResponse() {
        assertEquals(
            "Hello." to listOf("\"Unsupported option\""),
            parseUploadResponse(
                """{"text":"Hello.","segments":[],"language":"en","durationInSeconds":1.2,
                   "warnings":["Unsupported option"]}"""
            )
        )
        assertEquals("" to emptyList<String>(), parseUploadResponse("""{"text":""}"""))
        assertNull(parseUploadResponse("""{"segments":[]}"""))
        assertNull(parseUploadResponse("not json"))
    }

    @Test
    fun parsesParts() {
        assertEquals(
            GatewayPart.StreamStart(listOf("""{"type":"unsupported","feature":"language"}""")),
            parseGatewayPart("""{"type":"stream-start","warnings":[{"type":"unsupported","feature":"language"}]}""")
        )
        assertEquals(GatewayPart.Delta("Hi"), parseGatewayPart("""{"type":"transcript-delta","id":"1","delta":"Hi"}"""))
        assertEquals(
            GatewayPart.Partial("Hi there", 1.5, 2.0),
            parseGatewayPart(
                """{"type":"transcript-partial","text":"Hi there","startSecond":1.5,"durationInSeconds":2}"""
            )
        )
        assertEquals(
            GatewayPart.Partial("Hi", null, null),
            parseGatewayPart("""{"type":"transcript-partial","text":"Hi"}""")
        )
        assertEquals(GatewayPart.Final("Hi."), parseGatewayPart("""{"type":"transcript-final","text":"Hi."}"""))
        assertEquals(
            GatewayPart.Finish("Hi."),
            parseGatewayPart("""{"type":"finish","text":"Hi.","segments":[],"language":"en"}""")
        )
    }

    @Test
    fun parsesErrorObjectOrString() {
        assertEquals(
            GatewayPart.Error("AI Gateway error: Upstream failed"),
            parseGatewayPart("""{"type":"error","error":{"name":"Error","message":"Upstream failed"}}""")
        )
        assertEquals(
            GatewayPart.Error("AI Gateway error: Boom"),
            parseGatewayPart("""{"type":"error","error":"Boom"}""")
        )
        assertEquals(GatewayPart.Error("AI Gateway error"), parseGatewayPart("""{"type":"error","error":{}}"""))
    }

    @Test
    fun ignoresUnknownMalformedAndIncompleteParts() {
        assertNull(parseGatewayPart("""{"type":"response-metadata","modelId":"x"}"""))
        assertNull(parseGatewayPart("""{"type":"raw","rawValue":{}}"""))
        assertNull(parseGatewayPart("""{"type":"something-new"}"""))
        assertNull(parseGatewayPart("not json"))
        assertNull(parseGatewayPart("[1,2]"))
        assertNull(parseGatewayPart("""{"type":"transcript-delta"}"""))
        assertNull(parseGatewayPart("""{"type":"transcript-delta","delta":3}"""))
        assertNull(parseGatewayPart("""{"type":"transcript-partial"}"""))
        assertNull(parseGatewayPart("""{"type":"transcript-final","text":null}"""))
        assertNull(parseGatewayPart("""{"type":"error"}"""))
    }

    @Test
    fun deltasAppend() {
        val transcript = LiveTranscript()
        transcript.onDelta("Hello")
        transcript.onDelta(" world")
        assertEquals("Hello world", transcript.text)
    }

    @Test
    fun geminiPartialRepeatingTheDeltasIsShownOnce() {
        val transcript = LiveTranscript()
        transcript.onDelta("Hello")
        transcript.onPartial("Hello world", null, null)
        assertEquals("Hello world", transcript.text)
    }

    @Test
    fun maiPartialAfterDeltasIsAppended() {
        val transcript = LiveTranscript()
        transcript.onDelta("Hello ")
        transcript.onPartial("world", null, null)
        assertEquals("Hello world", transcript.text)
    }

    @Test
    fun grokChunkedPartialsAdvanceButRevisionsReplace() {
        val transcript = LiveTranscript()
        transcript.onPartial("one two", 0.0, 3.0)
        transcript.onPartial("one two three", 0.0, 3.2) // Same start: a revision.
        assertEquals("one two three", transcript.text)
        transcript.onPartial("four five", 3.2, 2.0) // Starts where the last one ended: a new chunk.
        assertEquals("one two three four five", transcript.text)
        transcript.onPartial("four five six", 3.2, 2.5)
        assertEquals("one two three four five six", transcript.text)
    }

    @Test
    fun finalSettlesAndClearsTheSegment() {
        val transcript = LiveTranscript()
        transcript.onDelta("Hello wor")
        transcript.onFinal(" Hello world. ")
        assertEquals("Hello world.", transcript.text)
        transcript.onPartial("Next", 0.0, 1.0)
        assertEquals("Hello world. Next", transcript.text)
        transcript.onFinal("Next one.")
        transcript.onFinal(" ")
        assertEquals("Hello world. Next one.", transcript.text)
    }

    @Test
    fun cjkJoinsWithoutSpaces() {
        val transcript = LiveTranscript()
        transcript.onFinal("你好")
        transcript.onFinal("世界")
        assertEquals("你好世界", transcript.text)
        assertEquals("a b", joined("a", "b"))
        assertEquals("ab", joined("", "ab"))
        assertEquals("ab", joined("ab", ""))
    }

    @Test
    fun onlyTrimsDetectsCutWords() {
        assertTrue(onlyTrims("one two three", "one two"))
        assertFalse(onlyTrims("one two", "one two"))
        assertFalse(onlyTrims("one two", "one tw0 three"))
        assertFalse(onlyTrims("one two three", "one too"))
    }
}
