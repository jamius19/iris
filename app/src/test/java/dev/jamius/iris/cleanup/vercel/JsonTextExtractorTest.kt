/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.cleanup.vercel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTextExtractorTest {

    /** Feeds [chunks] in order and returns the text after each (the last returned text when unchanged). */
    private fun feed(vararg chunks: String): List<String> {
        val extractor = JsonTextExtractor()
        var text = ""
        return chunks.map { chunk -> extractor.append(chunk)?.also { text = it } ?: text }
    }

    private fun extract(content: String) = feed(content).last()

    // Every escape kind, a BMP `\u` escape, a surrogate pair as escapes and raw, whitespace around the value.
    private val tricky = """{ "text" :
        "Line1\nTab\t \"q\" back\\slash \/ \b\f\r caf\u00e9 \ud83d\ude00 ok 😀 end" }"""
    private val trickyText = Json.parseToJsonElement(tricky).jsonObject["text"]!!.jsonPrimitive.content

    @Test
    fun decodesWholeContent() {
        assertEquals("Hello, world.", extract("""{"text":"Hello, world."}"""))
        assertEquals(trickyText, extract(tricky))
    }

    @Test
    fun everySplitIntoTwoChunksDecodesTheSame() {
        for (i in 0..tricky.length) {
            assertEquals("split at $i", trickyText, feed(tricky.substring(0, i), tricky.substring(i)).last())
        }
    }

    @Test
    fun everySplitIntoThreeChunksDecodesTheSame() {
        for (i in 0..tricky.length) {
            for (j in i..tricky.length) {
                val chunks = arrayOf(tricky.substring(0, i), tricky.substring(i, j), tricky.substring(j))
                assertEquals("split at $i, $j", trickyText, feed(*chunks).last())
            }
        }
    }

    @Test
    fun partialsGrowAsPrefixesAndNeverEndInHalfASurrogatePair() {
        val partials = feed(*tricky.map { it.toString() }.toTypedArray())

        assertEquals(trickyText, partials.last())
        partials.zipWithNext().forEach { (a, b) -> assertTrue("'$b' extends '$a'", b.startsWith(a)) }
        partials.forEach { assertTrue(trickyText.startsWith(it)) }
        partials.filter { it.isNotEmpty() }.forEach { assertFalse(it.last().isHighSurrogate()) }
    }

    @Test
    fun partialEscapesAreHeldBack() {
        assertEquals(
            listOf("", "a", "a", "a\n", "a\né", "a\né", "a\né😀"),
            feed("""{"te""", """xt": "a""", """\""", """n\u00""", "e9", """\ud83d""", """\ude00""")
        )
    }

    @Test
    fun returnsNullWhileTheTextIsUnchanged() {
        val extractor = JsonTextExtractor()
        assertNull(extractor.append("""{"text":"""))
        assertEquals("a", extractor.append("\"a"))
        assertNull(extractor.append("""\u00"""))
        assertEquals("aé", extractor.append("e9"))
        assertNull(extractor.append("\"}"))
    }

    @Test
    fun otherKeysAreSkipped() {
        val content = """{"other":{"text":"no","list":[1,"}]\"",{"x":null}]},"n":-1.5e3,"b":true,"s":"text",""" +
            """"text":"yes","after":"x"}"""
        assertEquals("yes", extract(content))
    }

    @Test
    fun unexpectedShapesYieldNothingOrStopEarly() {
        assertEquals("", extract("""["text"]"""))
        assertEquals("", extract("""{"text":5}"""))
        assertEquals("", extract("""{"text":null}"""))
        assertEquals("", extract("Hello"))
        assertEquals("ab", extract("""{"text":"ab\x cd"}""")) // Invalid escape.
        assertEquals("ab", extract("""{"text":"ab\u00zz"}""")) // Invalid hex.
        assertEquals("a", extract("""{"text":"a"}trailing"more"""))
    }
}
