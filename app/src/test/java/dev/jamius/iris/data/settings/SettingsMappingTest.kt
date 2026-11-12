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
        assertEquals(Settings.DEFAULT_GATEWAY_STT_MODEL, settings.gatewaySttModel)
        assertTrue(settings.gatewaySttLive)
        assertEquals(Settings.DEFAULT_DEEPGRAM_STT_MODEL, settings.deepgramSttModel)
        assertEquals("nova-3", settings.deepgramSttModel)
        assertFalse(settings.deepgramUpload)
        assertFalse(settings.sttGateway)
        assertFalse(settings.sttDeepgram)
        assertEquals(Settings.DEFAULT_STT_MODEL, settings.activeSttModel)
        assertEquals("", settings.sttLanguage)
        assertEquals(Settings.DEFAULT_STT_TIMEOUT_SECONDS, settings.sttTimeoutSeconds)
        assertTrue(settings.vadEnabled)
        assertEquals(Settings.DEFAULT_STT_SPEED_PERCENT, settings.sttSpeedPercent)
        assertFalse(settings.cleanupEnabled)
        assertEquals(Settings.CLEANUP_PROVIDER_VERCEL_GATEWAY, settings.cleanupProviderId)
        assertEquals(Settings.DEFAULT_CLEANUP_MODEL, settings.cleanupModel)
        assertEquals(Settings.DEFAULT_REASONING_EFFORT, settings.cleanupReasoningEffort)
        assertEquals(Settings.DEFAULT_CLEANUP_TIMEOUT_SECONDS, settings.cleanupTimeoutSeconds)
        assertEquals(listOf(Settings.DEFAULT_PROMPT), settings.prompts)
        assertEquals(Settings.DEFAULT_PROMPT, settings.activePrompt)
        assertFalse(settings.openAiKeySet)
        assertFalse(settings.gatewayKeySet)
        assertFalse(settings.deepgramKeySet)
        assertEquals(Settings.DEFAULT_BUBBLE_SIDE, settings.bubbleSide)
        assertEquals(Settings.DEFAULT_BUBBLE_Y_FRACTION, settings.bubbleYFraction)
        assertEquals(Settings.DEFAULT_PANEL_LINES, settings.panelLines)
        assertEquals(Settings.DEFAULT_PANEL_WIDTH_PERCENT, settings.panelWidthPercent)
    }

    @Test
    fun storedValuesAreMapped() {
        val prefs = mutablePreferencesOf(
            Keys.STT_PROVIDER_ID to Settings.STT_PROVIDER_VERCEL_GATEWAY,
            Keys.STT_MODEL to "gpt-transcribe",
            Keys.GATEWAY_STT_MODEL to "google/gemini-3.5-transcribe-live",
            Keys.GATEWAY_STT_LIVE to false,
            Keys.DEEPGRAM_STT_MODEL to "nova-3-medical",
            Keys.DEEPGRAM_UPLOAD to true,
            Keys.STT_LANGUAGE to "fr",
            Keys.STT_TIMEOUT_SECONDS to 20,
            Keys.VAD_ENABLED to false,
            Keys.STT_SPEED_PERCENT to 150,
            Keys.CLEANUP_ENABLED to true,
            Keys.CLEANUP_MODEL to "google/gemini-2.5-flash",
            Keys.CLEANUP_REASONING_EFFORT to "high",
            Keys.CLEANUP_TIMEOUT_SECONDS to 12,
            Keys.PROMPTS to encodePrompts(prompts),
            Keys.ACTIVE_PROMPT_ID to "b",
            Keys.BUBBLE_SIDE to "LEFT",
            Keys.BUBBLE_Y_FRACTION to 0.75f,
            Keys.PANEL_LINES to 5,
            Keys.PANEL_WIDTH_PERCENT to 70,
        )

        val settings = prefs.toSettings(setOf(Secret.GATEWAY_API_KEY, Secret.DEEPGRAM_API_KEY))

        assertEquals(Settings.STT_PROVIDER_VERCEL_GATEWAY, settings.sttProviderId)
        assertEquals("gpt-transcribe", settings.sttModel)
        assertEquals("google/gemini-3.5-transcribe-live", settings.gatewaySttModel)
        assertFalse(settings.gatewaySttLive)
        assertEquals("nova-3-medical", settings.deepgramSttModel)
        assertTrue(settings.deepgramUpload)
        assertEquals("fr", settings.sttLanguage)
        assertEquals(20, settings.sttTimeoutSeconds)
        assertFalse(settings.vadEnabled)
        assertEquals(150, settings.sttSpeedPercent)
        assertTrue(settings.cleanupEnabled)
        assertEquals("google/gemini-2.5-flash", settings.cleanupModel)
        assertEquals("high", settings.cleanupReasoningEffort)
        assertEquals(12, settings.cleanupTimeoutSeconds)
        assertEquals(prompts, settings.prompts)
        assertEquals(prompts[1], settings.activePrompt)
        assertFalse(settings.openAiKeySet)
        assertTrue(settings.gatewayKeySet)
        assertTrue(settings.deepgramKeySet)
        assertEquals(BubbleSide.LEFT, settings.bubbleSide)
        assertEquals(0.75f, settings.bubbleYFraction)
        assertEquals(5, settings.panelLines)
        assertEquals(70, settings.panelWidthPercent)
    }

    @Test
    fun activeSttModelAndKeyFollowTheProvider() {
        val prefs = mutablePreferencesOf(
            Keys.STT_MODEL to "gpt-transcribe",
            Keys.GATEWAY_STT_MODEL to "spacexai/grok-stt",
            Keys.DEEPGRAM_STT_MODEL to "nova-2",
        )

        val openAi = prefs.toSettings(setOf(Secret.OPENAI_API_KEY))
        assertFalse(openAi.sttGateway)
        assertEquals("gpt-transcribe", openAi.activeSttModel)
        assertTrue(openAi.sttKeySet)

        prefs[Keys.STT_PROVIDER_ID] = Settings.STT_PROVIDER_VERCEL_GATEWAY
        val gateway = prefs.toSettings(setOf(Secret.OPENAI_API_KEY))
        assertTrue(gateway.sttGateway)
        assertEquals("spacexai/grok-stt", gateway.activeSttModel)
        assertFalse(gateway.sttKeySet)
        assertTrue(prefs.toSettings(setOf(Secret.GATEWAY_API_KEY)).sttKeySet)

        prefs[Keys.STT_PROVIDER_ID] = Settings.STT_PROVIDER_DEEPGRAM
        val deepgram = prefs.toSettings(setOf(Secret.OPENAI_API_KEY, Secret.GATEWAY_API_KEY))
        assertTrue(deepgram.sttDeepgram)
        assertFalse(deepgram.sttGateway)
        assertEquals("nova-2", deepgram.activeSttModel)
        assertFalse(deepgram.sttKeySet)
        assertTrue(prefs.toSettings(setOf(Secret.DEEPGRAM_API_KEY)).sttKeySet)

        prefs[Keys.STT_PROVIDER_ID] = "unknown"
        val unknown = prefs.toSettings(setOf(Secret.OPENAI_API_KEY))
        assertEquals("gpt-transcribe", unknown.activeSttModel)
        assertTrue(unknown.sttKeySet)
    }

    @Test
    fun emptyReasoningEffortIsModelDefault() {
        val settings = mutablePreferencesOf(Keys.CLEANUP_REASONING_EFFORT to "").toSettings(emptySet())

        assertNull(settings.cleanupReasoningEffort)
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
