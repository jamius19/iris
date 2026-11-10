/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.data.settings

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMappingTest {

    private val prompts = listOf(
        CleanupPrompt(id = "a", name = "Formal", systemPrompt = "Make it \"formal\".\nKeep émojis 👍"),
        CleanupPrompt(id = "b", name = "Casual", systemPrompt = "Make it casual."),
    )

    @Test
    fun promptsRoundTrip() {
        assertEquals(prompts, decodePrompts(encodePrompts(prompts)))
    }

    @Test
    fun emptyPromptListRoundTrip() {
        assertEquals(emptyList<CleanupPrompt>(), decodePrompts(encodePrompts(emptyList())))
    }

    @Test
    fun decodeIgnoresUnknownFields() {
        val decoded = decodePrompts("""[{"id":"a","name":"n","systemPrompt":"s","extra":1}]""")
        assertEquals(listOf(CleanupPrompt("a", "n", "s")), decoded)
    }

    @Test
    fun emptyPreferencesMapToDefaults() {
        val settings = emptyPreferences().toSettings(emptySet())

        assertEquals(Settings.STT_PROVIDER_OPENAI, settings.sttProviderId)
        assertEquals(Settings.DEFAULT_STT_MODEL, settings.sttModel)
        assertEquals("", settings.sttLanguage)
        assertFalse(settings.cleanupEnabled)
        assertEquals(Settings.CLEANUP_PROVIDER_VERCEL_GATEWAY, settings.cleanupProviderId)
        assertEquals(Settings.DEFAULT_CLEANUP_MODEL, settings.cleanupModel)
        assertEquals(listOf(Settings.DEFAULT_PROMPT), settings.prompts)
        assertEquals(Settings.DEFAULT_PROMPT, settings.activePrompt)
        assertFalse(settings.openAiKeySet)
        assertFalse(settings.gatewayKeySet)
    }

    @Test
    fun storedValuesAreMapped() {
        val prefs = mutablePreferencesOf(
            Keys.STT_MODEL to "gpt-transcribe",
            Keys.STT_LANGUAGE to "fr",
            Keys.CLEANUP_ENABLED to true,
            Keys.CLEANUP_MODEL to "google/gemini-2.5-flash",
            Keys.PROMPTS to encodePrompts(prompts),
            Keys.ACTIVE_PROMPT_ID to "b",
        )

        val settings = prefs.toSettings(setOf(Secret.GATEWAY_API_KEY))

        assertEquals("gpt-transcribe", settings.sttModel)
        assertEquals("fr", settings.sttLanguage)
        assertTrue(settings.cleanupEnabled)
        assertEquals("google/gemini-2.5-flash", settings.cleanupModel)
        assertEquals(prompts, settings.prompts)
        assertEquals(prompts[1], settings.activePrompt)
        assertFalse(settings.openAiKeySet)
        assertTrue(settings.gatewayKeySet)
    }

    @Test
    fun savedEmptyPromptListIsNotReseeded() {
        val settings = mutablePreferencesOf(Keys.PROMPTS to "[]").toSettings(emptySet())

        assertEquals(emptyList<CleanupPrompt>(), settings.prompts)
        assertNull(settings.activePrompt)
    }

    @Test
    fun missingActivePromptFallsBackToFirst() {
        val prefs = mutablePreferencesOf(Keys.PROMPTS to encodePrompts(prompts), Keys.ACTIVE_PROMPT_ID to "deleted")

        assertEquals(prompts[0], prefs.toSettings(emptySet()).activePrompt)
    }
}
