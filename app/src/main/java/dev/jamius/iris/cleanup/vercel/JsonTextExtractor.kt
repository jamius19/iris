/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.cleanup.vercel

/**
 * Incrementally decodes the `text` string of a streamed `{ "text": "..." }` JSON object, for live display only:
 * the full content is parsed again once complete ([parseCleanedText]), so a bug here can't change what's inserted.
 * Feed the content deltas in order to [append]. Escapes may be split across deltas. On anything unexpected it stops
 * decoding, keeping the text decoded so far. Other top-level keys are skipped.
 */
internal class JsonTextExtractor {

    private enum class State { START, KEY_OR_END, KEY, COLON, VALUE, TEXT, SKIP, DONE, FAILED }

    private var state = State.START
    private val key = StringBuilder()
    private val text = StringBuilder()

    /** Length of the text [append] last returned. */
    private var returned = 0

    // String decoding, shared by keys, the text value and skipped strings.
    private var escaping = false
    private var unicodeDigits = -1 // Hex digits read of a `\uXXXX` escape; -1 outside one.
    private var unicode = 0

    // Skipping another key's value: nesting depth and whether inside a string.
    private var depth = 0
    private var inString = false

    /**
     * Feeds the next [chunk] of the content. Returns the text decoded so far if it grew since the last returned text
     * (the text only grows), else null, so unchanged text isn't copied again.
     */
    fun append(chunk: String): String? {
        for (c in chunk) {
            if (state == State.DONE || state == State.FAILED) break
            accept(c)
        }
        // A high surrogate waits for its low half (the next `\uXXXX`), so a partial never ends in half an emoji.
        val end = if (text.isNotEmpty() && text.last().isHighSurrogate()) text.length - 1 else text.length
        if (end == returned) return null
        returned = end
        return text.substring(0, end)
    }

    private fun accept(c: Char) {
        when (state) {
            State.START -> if (c == '{') state = State.KEY_OR_END else if (!c.isJsonSpace()) state = State.FAILED
            State.KEY_OR_END -> when {
                c == '"' -> {
                    key.clear()
                    state = State.KEY
                }
                c == '}' -> state = State.DONE
                c != ',' && !c.isJsonSpace() -> state = State.FAILED
            }
            State.KEY -> if (!string(c, key)) state = State.COLON
            State.COLON -> if (c == ':') state = State.VALUE else if (!c.isJsonSpace()) state = State.FAILED
            State.VALUE -> when {
                c.isJsonSpace() -> Unit
                c == '"' && key.toString() == TEXT_KEY -> state = State.TEXT
                else -> {
                    state = State.SKIP
                    depth = 0
                    inString = false
                    skip(c)
                }
            }
            State.TEXT -> if (!string(c, text)) state = State.DONE
            State.SKIP -> skip(c)
            State.DONE, State.FAILED -> Unit
        }
    }

    /** Decodes [c] of a string's body into [out] (null discards it). False when [c] is the closing quote. */
    private fun string(c: Char, out: StringBuilder?): Boolean {
        when {
            unicodeDigits >= 0 -> {
                val digit = Character.digit(c, 16)
                if (digit < 0) {
                    state = State.FAILED
                } else {
                    unicode = unicode * 16 + digit
                    if (++unicodeDigits == 4) {
                        unicodeDigits = -1
                        out?.append(unicode.toChar())
                    }
                }
            }
            escaping -> {
                escaping = false
                if (c == 'u') {
                    unicodeDigits = 0
                    unicode = 0
                } else {
                    val decoded = ESCAPES[c]
                    if (decoded == null) state = State.FAILED else out?.append(decoded)
                }
            }
            c == '\\' -> escaping = true
            c == '"' -> return false
            else -> out?.append(c)
        }
        return true
    }

    /** Skips another key's value (any JSON value); a top-level `,` or `}` ends it. */
    private fun skip(c: Char) {
        if (inString) {
            if (!string(c, null)) inString = false
            return
        }
        when (c) {
            '"' -> inString = true
            '{', '[' -> depth++
            '}', ']' -> if (depth == 0) state = State.DONE else depth--
            ',' -> if (depth == 0) state = State.KEY_OR_END
        }
    }

    private fun Char.isJsonSpace() = this == ' ' || this == '\n' || this == '\r' || this == '\t'

    private companion object {
        const val TEXT_KEY = "text"
        val ESCAPES = mapOf(
            '"' to '"', '\\' to '\\', '/' to '/', 'b' to '\b', 'f' to '\u000C', 'n' to '\n', 'r' to '\r', 't' to '\t'
        )
    }
}
