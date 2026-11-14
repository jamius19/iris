/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.cleanup

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderOptionsTest {

    @Test
    fun blankIsNull() {
        assertNull(parseProviderOptions(""))
        assertNull(parseProviderOptions(" \n\t "))
    }

    @Test
    fun emptyObjectIsKept() {
        assertEquals(JsonObject(emptyMap()), parseProviderOptions("{}"))
    }

    @Test
    fun nestedObjectIsReturnedIntact() {
        val options = parseProviderOptions(
            """
            {"order": ["openai", "azure"], "allow_fallbacks": false, "max_price": {"prompt": 1.50}}
            """
        )!!

        assertEquals(listOf("openai", "azure"), options["order"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("false", options["allow_fallbacks"]!!.jsonPrimitive.content)
        assertEquals("1.50", options["max_price"]!!.jsonObject["prompt"]!!.jsonPrimitive.content)
        assertEquals(
            """{"order":["openai","azure"],"allow_fallbacks":false,"max_price":{"prompt":1.50}}""",
            options.toString()
        )
    }

    @Test
    fun invalidJsonFails() {
        val texts = listOf(
            "{", "{a:1}", """{"a":1,}""", "{} x", "{'a':1}",
            """{"order": [openai]}""", """{"a": True}""", """{"a": NaN}""", """{"a": 1e}""", """{"a": 01}""",
            """{"a": tru}"""
        )
        for (text in texts) {
            val error = assertThrows(text, IllegalArgumentException::class.java) { parseProviderOptions(text) }
            assertEquals(text, "Not valid JSON", error.message)
        }
    }

    @Test
    fun strictValuesPass() {
        val options = parseProviderOptions(
            """{"a": null, "b": 1.50, "c": -0, "d": 2E+3, "e": true, "f": "x", "g": {}, "h": []}"""
        )!!

        assertEquals(
            """{"a":null,"b":1.50,"c":-0,"d":2E+3,"e":true,"f":"x","g":{},"h":[]}""",
            options.toString()
        )
        assertEquals(
            """{"gateway":{"order":["azure","openai"]}}""",
            parseProviderOptions("""{"gateway": {"order": ["azure", "openai"]}}""").toString()
        )
    }

    @Test
    fun nonObjectsFail() {
        for (text in listOf("[1]", "1", "\"x\"", "true", "null")) {
            val error = assertThrows(text, IllegalArgumentException::class.java) { parseProviderOptions(text) }
            assertEquals(text, "Must be a JSON object: { ... }", error.message)
        }
    }
}
