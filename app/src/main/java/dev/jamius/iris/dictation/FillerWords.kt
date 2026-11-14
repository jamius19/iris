/*
* Author: Jamius Siam
* Since: 14/11/2026
*/

package dev.jamius.iris.dictation

import dev.jamius.iris.data.settings.Settings
import dev.jamius.iris.stt.ondevice.OnDeviceEngine
import dev.jamius.iris.stt.ondevice.onDeviceModel
import dev.jamius.iris.stt.ondevice.whisperLanguage
import dev.jamius.iris.stt.soniox.sonioxLanguageHints

/** Never real words in a language Iris's models output: removed whatever the language. (Handy's list.) */
internal val UNIVERSAL_FILLER_WORDS = listOf(
    "uh", "uhm", "umm", "uhh", "uhhh", "ehh", "ehm", "ahm", "hmm", "hm", "mmm", "хм", "ммм"
)

/** Real words elsewhere (Portuguese "um" = "a", German "um" = "at"): removed only with the language known. */
internal val GATED_FILLER_WORDS = mapOf(
    "en" to listOf("um", "ah", "eh"),
    "de" to listOf("äh", "ähm"),
    "fr" to listOf("euh"),
)

/** Minimum confidence for a text-detected language (Handy's threshold). */
internal const val MIN_LANGUAGE_CONFIDENCE = 0.9f

private const val WORD_CHAR = "[\\p{L}\\p{N}_'’-]"

/**
 * Each filler as a whole word (not joined by `-` or an apostrophe: "Uh-huh", "Um's"), any case, plus an optional
 * `...`, `…` or one of `, . ! ?` (group 1). Lookarounds instead of `\b`: Java's `\b` is ASCII-only without
 * `UNICODE_CHARACTER_CLASS`, and that flag throws on Android.
 */
private val PATTERNS: Map<String, Regex> = (UNIVERSAL_FILLER_WORDS + GATED_FILLER_WORDS.values.flatten())
    .associateWith { Regex("(?<!$WORD_CHAR)$it(?!$WORD_CHAR)(?:\\.\\.\\.|…|([,.!?]))?", RegexOption.IGNORE_CASE) }

private val MULTI_SPACE = Regex("[ \\t]{2,}")

/** Spaces or tabs next to a line break, e.g. left by a filler that opened the line. */
private val LINE_EDGE_SPACES = Regex("[ \\t]*\\n[ \\t]*")

private const val SENTENCE_ENDS = ".!?…"

/** The base language of an ISO code ("pt-BR", "PT_br" -> "pt"); blank = null. */
internal fun baseLanguage(code: String): String? = code.trim().lowercase().split('-', '_').first().ifEmpty { null }

/** The base language code of the transcript as the settings give it, or null if they don't. */
fun outputLanguage(settings: Settings): String? {
    val code = when {
        settings.sttOnDevice -> {
            val model = onDeviceModel(settings.onDeviceSttModel)
            // The shared language reaches only Whisper on device; the other multilingual models never see it.
            model.outputLanguage
                ?: if (model.engine == OnDeviceEngine.WHISPER) whisperLanguage(settings.sttLanguage) else ""
        }
        settings.sttSoniox -> sonioxLanguageHints(settings.sonioxLanguages).singleOrNull().orEmpty()
        else -> settings.sttLanguage
    }
    return baseLanguage(code)
}

/** Whether [text] holds a filler that is removed only with the language known (else detection can't matter). */
fun hasGatedFiller(text: String): Boolean =
    GATED_FILLER_WORDS.values.flatten().any { PATTERNS.getValue(it).containsMatchIn(text) }

/**
 * [text] without filler words: the universal ones always, the gated ones only for [language] (an ISO code, null =
 * unknown). Port of Handy's built-in removal (`src-tauri/src/audio_toolkit/text.rs`, `remove_filler_matches`): a
 * filler eats one trailing `...`, `…`, comma, period, `!` or `?`, a capitalized filler that opened a sentence hands
 * its capital on, and space runs collapse (line breaks kept). Unlike Handy, a `.`, `!` or `?` the filler ate still
 * ends the sentence before it. Not ported: stutter collapse, a custom list, Chinese script handling. A changed text
 * left with no letter or digit becomes "".
 */
fun removeFillerWords(text: String, language: String?): String {
    val words = UNIVERSAL_FILLER_WORDS + GATED_FILLER_WORDS[language?.let(::baseLanguage)].orEmpty()
    var filtered = text
    for (word in words) filtered = removeMatches(filtered, PATTERNS.getValue(word))
    filtered = LINE_EDGE_SPACES.replace(MULTI_SPACE.replace(filtered, " "), "\n").trim()
    return if (filtered != text && filtered.none { it.isLetterOrDigit() }) "" else filtered
}

private fun removeMatches(text: String, pattern: Regex): String {
    val kept = StringBuilder()
    var capitalOwed = false

    fun push(segment: String) {
        val i = segment.indexOfFirst { it.isLetterOrDigit() }
        if (!capitalOwed || i < 0) {
            kept.append(segment)
            return
        }
        kept.append(segment, 0, i).append(segment[i].uppercase()).append(segment, i + 1, segment.length)
        capitalOwed = false
    }

    var resume = 0
    for (match in pattern.findAll(text)) {
        push(text.substring(resume, match.range.first))
        capitalOwed = capitalOwed || (match.value.first().isUpperCase() && opensSentence(kept))
        val mark = match.groupValues[1]
        if (mark.isNotEmpty() && mark != ",") endSentence(kept, mark.single())
        resume = match.range.last + 1
    }
    push(text.substring(resume))
    return kept.toString()
}

private fun opensSentence(kept: CharSequence): Boolean =
    kept.trimEnd().lastOrNull().let { it == null || it in SENTENCE_ENDS }

/** The [mark] a removed filler ate ends the kept text's sentence ("Let me check, um." -> "Let me check."). */
private fun endSentence(kept: StringBuilder, mark: Char) {
    val end = kept.trimEnd().removeSuffix(",").trimEnd()
    if (end.isBlank() || end.last() in SENTENCE_ENDS) return
    kept.setLength(end.length)
    kept.append(mark)
}
