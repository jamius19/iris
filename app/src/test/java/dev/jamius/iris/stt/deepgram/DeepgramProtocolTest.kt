/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.stt.deepgram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepgramProtocolTest {

    @Test
    fun streamingQueryStatesTheAudioFormatAndInterimResults() {
        val url = listenUrl(LISTEN_URL, "nova-3", "en", streaming = true)

        assertEquals("api.deepgram.com", url.host)
        assertEquals("/v1/listen", url.encodedPath)
        assertEquals("nova-3", url.queryParameter("model"))
        assertEquals("en", url.queryParameter("language"))
        assertEquals("true", url.queryParameter("smart_format"))
        assertEquals("true", url.queryParameter("punctuate"))
        assertEquals("linear16", url.queryParameter("encoding"))
        assertEquals("16000", url.queryParameter("sample_rate"))
        assertEquals("1", url.queryParameter("channels"))
        assertEquals("true", url.queryParameter("interim_results"))
        assertNull(url.queryParameter("endpointing"))
    }

    @Test
    fun blankLanguageIsMultiWithEndpointing() {
        val url = listenUrl(LISTEN_URL, "nova-3", "", streaming = true)

        assertEquals("multi", url.queryParameter("language"))
        assertEquals("100", url.queryParameter("endpointing"))
    }

    @Test
    fun uploadQueryHasNoStreamingParameters() {
        val url = listenUrl(LISTEN_URL, "nova-3", "", streaming = false)

        assertEquals(
            setOf("model", "language", "smart_format", "punctuate"),
            url.queryParameterNames
        )
        assertEquals("multi", url.queryParameter("language"))
    }

    @Test
    fun modelIdIsEncoded() {
        val url = listenUrl(LISTEN_URL, "nova 3&x=1", "en", streaming = false)

        assertEquals("nova 3&x=1", url.queryParameter("model"))
        assertEquals("model=nova%203%26x%3D1", url.encodedQuery!!.substringBefore("&language"))
    }

    @Test
    fun parsesFinalAndInterimResults() {
        assertEquals(
            DeepgramEvent.Result(isFinal = true, transcript = "Hello there."),
            parseDeepgramEvent(result("Hello there.", isFinal = true))
        )
        assertEquals(
            DeepgramEvent.Result(isFinal = false, transcript = "Hello"),
            parseDeepgramEvent(result("Hello", isFinal = false))
        )
    }

    @Test
    fun parsesMetadataAndErrors() {
        assertEquals(
            DeepgramEvent.Metadata,
            parseDeepgramEvent("""{"type":"Metadata","request_id":"r","duration":1.5,"channels":1}""")
        )
        assertEquals(
            DeepgramEvent.Error("Could not parse the message"),
            parseDeepgramEvent(
                """{"type":"Error","variant":"SchemaError","description":"Could not parse the message",
                   "message":"{oops"}"""
            )
        )
        assertEquals(DeepgramEvent.Error("legacy"), parseDeepgramEvent("""{"type":"Error","message":"legacy"}"""))
    }

    @Test
    fun ignoresUnknownAndMalformedMessages() {
        assertNull(parseDeepgramEvent("""{"type":"SpeechStarted","channel":[0],"timestamp":1.0}"""))
        assertNull(parseDeepgramEvent("not json"))
        assertNull(parseDeepgramEvent("[1,2]"))
        assertNull(parseDeepgramEvent("""{"type":"Results","is_final":true}"""))
        assertNull(parseDeepgramEvent("""{"type":"Results","is_final":true,"channel":{"alternatives":[]}}"""))
    }

    @Test
    fun transcriptJoinsFinalsAndShowsTheLatestInterim() {
        val transcript = DeepgramTranscript()

        transcript.onResult(isFinal = false, transcript = "Hel")
        assertEquals("Hel", transcript.liveText)
        transcript.onResult(isFinal = false, transcript = "Hello")
        assertEquals("Hello", transcript.liveText)
        transcript.onResult(isFinal = true, transcript = "Hello.")
        transcript.onResult(isFinal = false, transcript = "How")
        assertEquals("Hello. How", transcript.liveText)
        assertEquals("Hello.", transcript.finalText)
        transcript.onResult(isFinal = true, transcript = " ")
        assertEquals("Hello.", transcript.liveText)
        transcript.onResult(isFinal = true, transcript = "How are you?")
        assertEquals("Hello. How are you?", transcript.finalText)
    }

    @Test
    fun transcriptJoinsCjkUnspaced() {
        val transcript = DeepgramTranscript()

        transcript.onResult(isFinal = true, transcript = "你好")
        transcript.onResult(isFinal = true, transcript = "世界")

        assertEquals("你好世界", transcript.finalText)
    }

    @Test
    fun parsesThePreRecordedTranscript() {
        val body = """{"metadata":{"request_id":"r","duration":1.25,"channels":1},
            "results":{"channels":[{"alternatives":[{"transcript":"Yeah, as much as.","confidence":0.98,
            "words":[]}]}]}}"""

        assertEquals("Yeah, as much as.", parseListenResponse(body))
        assertNull(parseListenResponse("""{"results":{"channels":[]}}"""))
        assertNull(parseListenResponse("""{"request_id":"r"}"""))
        assertNull(parseListenResponse("<html>"))
    }

    @Test
    fun httpErrorsMapToReadableMessages() {
        assertEquals(
            "Deepgram rejected the API key. Check it in Iris settings.",
            deepgramHttpErrorMessage(401, errBody("INVALID_AUTH", "Invalid credentials."), null)
        )
        assertEquals("Deepgram account is out of credits", deepgramHttpErrorMessage(402, "", null))
        assertEquals("Deepgram rate limit reached", deepgramHttpErrorMessage(429, "", null))
        assertEquals("Deepgram server error (HTTP 503)", deepgramHttpErrorMessage(503, "", null))
        assertEquals(
            "Deepgram error: No such model/language/tier combination found.",
            deepgramHttpErrorMessage(
                400,
                errBody("Bad Request", "No such model/language/tier combination found."),
                "header message"
            )
        )
        assertEquals(
            "Deepgram error: Project does not have access to the requested model.",
            deepgramHttpErrorMessage(
                403,
                errBody("INSUFFICIENT_PERMISSIONS", "Project does not have access to the requested model."),
                null
            )
        )
        assertEquals(
            "Deepgram error: Invalid JSON submitted.",
            deepgramHttpErrorMessage(400, """{"category":"INVALID_JSON","message":"Invalid JSON submitted."}""", null)
        )
        assertEquals("Deepgram error: From the header", deepgramHttpErrorMessage(400, "", "From the header"))
        assertEquals("Deepgram request failed (HTTP 400)", deepgramHttpErrorMessage(400, "", null))
    }

    @Test
    fun closeMessages() {
        assertEquals("Deepgram closed the connection: NET-0001", deepgramClosedMessage(1011, "NET-0001"))
        assertEquals("Deepgram closed the connection (code 1011)", deepgramClosedMessage(1011, ""))
    }

    private fun errBody(code: String, message: String) = """{"err_code":"$code","err_msg":"$message"}"""

    private fun result(transcript: String, isFinal: Boolean) =
        """{"type":"Results","channel_index":[0,1],"duration":1.0,"start":0.0,"is_final":$isFinal,
           "speech_final":$isFinal,"channel":{"alternatives":[{"transcript":"$transcript","confidence":0.9,
           "words":[]}]}}"""
}
