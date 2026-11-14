/*
* Author: Jamius Siam
* Since: 14/11/2026
*/

package dev.jamius.iris.dictation

import androidx.datastore.preferences.core.emptyPreferences
import dev.jamius.iris.data.settings.Settings
import dev.jamius.iris.data.settings.toSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FillerWordsTest {

    private val defaults = emptyPreferences().toSettings(emptySet())

    @Test
    fun universalFillersGoWithAnUnknownLanguage() {
        assertEquals("I think this works", removeFillerWords("uh I think uhm this works", null))
    }

    @Test
    fun gatedFillersGoOnlyWithTheirLanguage() {
        assertEquals("I think this is good", removeFillerWords("um I think um this is good", "en"))
        assertEquals("um I think um this is good", removeFillerWords("um I think um this is good", null))
        assertEquals("um I think um this is good", removeFillerWords("um I think um this is good", "xx"))
    }

    @Test
    fun matchingIgnoresCaseAndCarriesTheCapital() {
        assertEquals("This is a test", removeFillerWords("UHM this is UH a test", "en"))
    }

    @Test
    fun aFillerEatsItsCommaAndItsPeriodEndsTheSentence() {
        assertEquals("Well, I think. that's right", removeFillerWords("Well, uhm, I think, uh. that's right", null))
        assertEquals(
            "So I was, thinking about this",
            removeFillerWords("  Uhm, so I was, uh, thinking about this  ", null)
        )
        assertEquals("so, we go", removeFillerWords("so, uh, we go", null))
    }

    @Test
    fun sentenceEndPeriodIsKept() {
        assertEquals("Let me check.", removeFillerWords("Let me check, um.", "en"))
        assertEquals("I think. Yes", removeFillerWords("I think um. Yes", "en"))
        assertEquals("So I think", removeFillerWords("Um. So I think", "en"))
        assertEquals("That works. Let me check.", removeFillerWords("That works. Um. let me check.", "en"))
    }

    @Test
    fun questionAndExclamationMarksEndTheSentence() {
        assertEquals("What?", removeFillerWords("Hmm? What?", null))
        assertEquals("Is it ready?", removeFillerWords("Is it ready, um?", "en"))
        assertEquals("I see.", removeFillerWords("Ah! I see.", "en"))
    }

    @Test
    fun anEllipsisIsEatenWithoutEndingASentence() {
        assertEquals("I think so.", removeFillerWords("Um... I think so.", "en"))
        assertEquals("I think", removeFillerWords("Um… I think", "en"))
    }

    @Test
    fun fillersJoinedByHyphenOrApostropheAreKept() {
        for (text in listOf("Mm-hmm.", "Uh-huh, that's right.", "Uh-oh.", "Um's")) {
            assertEquals(text, removeFillerWords(text, "en"))
        }
    }

    @Test
    fun lineBreaksAreKept() {
        assertEquals("a\n\nb", removeFillerWords("a\n\nb", null))
        assertEquals("Hello.\nSo I think", removeFillerWords("Hello.\nUm, so I think", "en"))
        assertEquals("so\nyes", removeFillerWords("so uh\nyes", null))
        assertEquals("a b", removeFillerWords("a \t b", null))
    }

    @Test
    fun aSentenceOpeningFillerHandsOnItsCapital() {
        assertEquals("So I think", removeFillerWords("Um, so I think", "en"))
        assertEquals("So I think we should ship it.", removeFillerWords("Um, so I think we should ship it.", "en"))
        assertEquals("That works. Let me check.", removeFillerWords("That works. Um, let me check.", "en"))
    }

    @Test
    fun aMidSentenceFillerDoesNotCapitalize() {
        assertEquals("He said, not today.", removeFillerWords("He said, Um, not today.", "en"))
        assertEquals("I was thinking", removeFillerWords("I was uh thinking", null))
    }

    @Test
    fun fillersInsideWordsAreKept() {
        assertEquals("umbrella hummus Uhura ahead", removeFillerWords("umbrella hummus Uhura ahead", "en"))
    }

    @Test
    fun portugueseUmIsKept() {
        for (language in listOf(null, "pt", "pt-BR")) {
            assertEquals("eu vi um carro", removeFillerWords("eu vi um carro", language))
        }
    }

    @Test
    fun germanAndFrenchFillersNeedTheirLanguage() {
        assertEquals("Ich weiß nicht", removeFillerWords("Äh, ich weiß nicht", "de"))
        assertEquals("Ich weiß nicht", removeFillerWords("Äh, ich weiß nicht", "DE_at"))
        assertEquals("je pense", removeFillerWords("euh je pense", "fr"))
        assertEquals("äh, I see", removeFillerWords("äh, I see", "en"))
    }

    @Test
    fun cyrillicFillersAreWholeWords() {
        assertEquals("Я думаю", removeFillerWords("Хм, я думаю", null))
        assertEquals("да", removeFillerWords("ммм да", null))
        assertEquals("хмель", removeFillerWords("хмель", null))
    }

    @Test
    fun whitespaceCollapsesAndIsTrimmed() {
        assertEquals("Hello world test", removeFillerWords("Hello    world   test", null))
        assertEquals("Hello world", removeFillerWords("  Hello world  ", null))
    }

    @Test
    fun onlyFillersOrPunctuationLeftIsBlank() {
        assertEquals("", removeFillerWords("Uh. Hmm.", null))
        assertEquals("", removeFillerWords("Hmm?", null))
        assertEquals("?", removeFillerWords("?", null))
    }

    @Test
    fun gatedFillerCandidates() {
        assertTrue(hasGatedFiller("so um yes"))
        assertTrue(hasGatedFiller("Äh"))
        assertTrue(hasGatedFiller("euh"))
        assertFalse(hasGatedFiller("uh hmm"))
        assertFalse(hasGatedFiller("umbrella"))
    }

    @Test
    fun outputLanguageOfCloudProviders() {
        assertNull(outputLanguage(defaults))
        assertEquals("en", outputLanguage(defaults.copy(sttLanguage = "EN")))
        assertEquals("pt", outputLanguage(defaults.copy(sttLanguage = "pt-BR")))
        for (provider in listOf(Settings.STT_PROVIDER_DEEPGRAM, Settings.STT_PROVIDER_VERCEL_GATEWAY)) {
            assertEquals("de", outputLanguage(defaults.copy(sttProviderId = provider, sttLanguage = "de")))
        }
    }

    @Test
    fun outputLanguageOfSonioxNeedsExactlyOneCode() {
        val soniox = defaults.copy(sttProviderId = Settings.STT_PROVIDER_SONIOX, sttLanguage = "fr")
        assertEquals("en", outputLanguage(soniox.copy(sonioxLanguages = "en")))
        assertEquals("en", outputLanguage(soniox.copy(sonioxLanguages = "en, en")))
        assertNull(outputLanguage(soniox.copy(sonioxLanguages = "en, bn")))
        assertNull(outputLanguage(soniox.copy(sonioxLanguages = "")))
    }

    @Test
    fun outputLanguageOnDevice() {
        val onDevice = defaults.copy(sttProviderId = Settings.STT_PROVIDER_ON_DEVICE, sttLanguage = "de")
        for (id in listOf("nemotron-streaming-en", "parakeet-unified-en", "moonshine-base-en", "unknown")) {
            assertEquals(id, "en", outputLanguage(onDevice.copy(onDeviceSttModel = id)))
        }
        val parakeetV3 = onDevice.copy(onDeviceSttModel = "parakeet-tdt-v3")
        assertNull(outputLanguage(parakeetV3.copy(sttLanguage = "en")))
        val whisper = onDevice.copy(onDeviceSttModel = "whisper-small")
        assertEquals("de", outputLanguage(whisper))
        assertNull(outputLanguage(whisper.copy(sttLanguage = "")))
        assertNull(outputLanguage(whisper.copy(sttLanguage = "xx")))
    }
}
