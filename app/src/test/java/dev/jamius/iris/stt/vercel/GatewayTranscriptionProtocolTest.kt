/*
* Author: Jamius Siam
* Since: 12/11/2026
*/

package dev.jamius.iris.stt.vercel

import dev.jamius.iris.cleanup.GatewayModel
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
            startFrame(24_000, providerOptions("openai/gpt-live-transcribe", "en"))
        )
    }

    @Test
    fun audioDoneFrame() {
        assertJson("""{"type":"transcription-stream.audio-done"}""", AUDIO_DONE)
    }

    @Test
    fun languageOptionPerProvider() {
        assertJson("""{"openai":{"language":"fr"}}""", providerOptions("openai/gpt-transcribe", " fr ").toString())
        assertJson("""{"mistral":{"language":"fr"}}""", providerOptions("mistral/voxtral", "fr").toString())
        assertJson(
            """{"elevenlabs":{"languageCode":"fr"}}""",
            providerOptions("elevenlabs/scribe-v2-realtime", "fr").toString()
        )
        assertJson(
            """{"google":{"languageCodes":["fr"]}}""",
            providerOptions("google/gemini-3.5-transcribe-live", "fr").toString()
        )
        assertNull(providerOptions("microsoft/mai-transcribe-2-streaming", "fr"))
    }

    @Test
    fun blankLanguageSendsNoOptions() {
        assertNull(providerOptions("openai/gpt-live-transcribe", " "))
        assertNull(providerOptions("google/gemini-3.5-transcribe-live", ""))
    }

    @Test
    fun grokAlwaysAsksForInterimResultsUnderBothNames() {
        val interim =
            """{"xai":{"streaming":{"interimResults":true}},"spacexai":{"streaming":{"interimResults":true}}}"""
        assertJson(interim, providerOptions("spacexai/grok-stt", "").toString())
        assertJson(interim, providerOptions("xai/grok-stt", "en").toString())
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
        assertJson("""{"openai":{"language":"en"}}""", providerOptions("gpt-live-transcribe", "en").toString())
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
