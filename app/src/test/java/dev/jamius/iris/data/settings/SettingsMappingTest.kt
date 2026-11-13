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

    private val customProviders = listOf(
        CustomCleanupProvider(
            id = "c1",
            name = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            model = "llama-4",
            reasoningEffort = "high",
            sendJsonSchema = false
        ),
        CustomCleanupProvider(id = "c2", name = "Local", baseUrl = "https://llm.example.com/v1"),
    )

    @Test
    fun customProvidersRoundTrip() {
        assertEquals(customProviders, decodeCustomProviders(encodeCustomProviders(customProviders)))
    }

    @Test
    fun customProviderDecodeIgnoresUnknownKeysAndFillsDefaults() {
        val decoded = decodeCustomProviders("""[{"id":"c","name":"n","baseUrl":"https://x.example","extra":1}]""")

        assertEquals(listOf(CustomCleanupProvider(id = "c", name = "n", baseUrl = "https://x.example")), decoded)
        assertEquals(CustomCleanupProvider.TYPE_OPENAI_COMPATIBLE, decoded[0].type)
        assertEquals("", decoded[0].model)
        assertNull(decoded[0].reasoningEffort)
        assertTrue(decoded[0].sendJsonSchema)
    }

    @Test
    fun emptyPreferencesMapToDefaults() {
        val settings = emptyPreferences().toSettings(emptySet())

        assertEquals(Settings.STT_PROVIDER_OPENAI, settings.sttProviderId)
        assertFalse(settings.sttOnDevice)
        assertEquals("nemotron-streaming-en", settings.onDeviceSttModel)
        assertEquals(Settings.DEFAULT_STT_MODEL, settings.sttModel)
        assertEquals(Settings.DEFAULT_GATEWAY_STT_MODEL, settings.gatewaySttModel)
        assertTrue(settings.gatewaySttLive)
        assertEquals(Settings.DEFAULT_DEEPGRAM_STT_MODEL, settings.deepgramSttModel)
        assertEquals("nova-3", settings.deepgramSttModel)
        assertFalse(settings.deepgramUpload)
        assertEquals(Settings.DEFAULT_SONIOX_STT_MODEL, settings.sonioxSttModel)
        assertEquals("stt-rt-v6", settings.sonioxSttModel)
        assertEquals(Settings.DEFAULT_SONIOX_REGION, settings.sonioxRegion)
        assertEquals("us", settings.sonioxRegion)
        assertEquals("", settings.sonioxLanguages)
        assertFalse(settings.sttGateway)
        assertFalse(settings.sttDeepgram)
        assertFalse(settings.sttSoniox)
        assertEquals(Settings.DEFAULT_STT_MODEL, settings.activeSttModel)
        assertEquals("", settings.sttLanguage)
        assertEquals(Settings.DEFAULT_STT_TIMEOUT_SECONDS, settings.sttTimeoutSeconds)
        assertTrue(settings.vadEnabled)
        assertEquals(Settings.DEFAULT_STT_SPEED_PERCENT, settings.sttSpeedPercent)
        assertFalse(settings.cleanupEnabled)
        assertEquals(Settings.CLEANUP_PROVIDER_VERCEL_GATEWAY, settings.cleanupProviderId)
        assertEquals(Settings.DEFAULT_CLEANUP_MODEL, settings.cleanupModel)
        assertEquals(Settings.DEFAULT_REASONING_EFFORT, settings.cleanupReasoningEffort)
        assertEquals("", settings.cleanupProviderOptions)
        assertFalse(settings.cleanupOpenRouter)
        assertEquals(Settings.DEFAULT_CLEANUP_MODEL, settings.openRouterCleanupModel)
        assertEquals("low", settings.openRouterCleanupReasoningEffort)
        assertEquals("", settings.openRouterCleanupProviderOptions)
        assertEquals(Settings.DEFAULT_CLEANUP_TIMEOUT_SECONDS, settings.cleanupTimeoutSeconds)
        assertEquals(listOf(Settings.DEFAULT_PROMPT), settings.prompts)
        assertEquals(Settings.DEFAULT_PROMPT, settings.activePrompt)
        assertFalse(settings.openAiKeySet)
        assertFalse(settings.gatewayKeySet)
        assertFalse(settings.deepgramKeySet)
        assertFalse(settings.sonioxKeySet)
        assertFalse(settings.openRouterKeySet)
        assertEquals(emptyList<CustomCleanupProvider>(), settings.customCleanupProviders)
        assertEquals(emptySet<String>(), settings.customCleanupKeyIds)
        assertNull(settings.activeCustomCleanup)
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
            Keys.SONIOX_STT_MODEL to "stt-async-v6",
            Keys.SONIOX_REGION to "eu",
            Keys.SONIOX_LANGUAGES to "en, bn",
            Keys.STT_LANGUAGE to "fr",
            Keys.STT_TIMEOUT_SECONDS to 20,
            Keys.VAD_ENABLED to false,
            Keys.STT_SPEED_PERCENT to 150,
            Keys.CLEANUP_ENABLED to true,
            Keys.CLEANUP_MODEL to "google/gemini-2.5-flash",
            Keys.CLEANUP_PROVIDER_ID to Settings.CLEANUP_PROVIDER_OPENROUTER,
            Keys.CLEANUP_REASONING_EFFORT to "high",
            Keys.CLEANUP_PROVIDER_OPTIONS to """{"gateway": {"order": ["azure"]}}""",
            Keys.OPENROUTER_CLEANUP_MODEL to "anthropic/claude-haiku-5",
            Keys.OPENROUTER_CLEANUP_REASONING_EFFORT to "medium",
            Keys.OPENROUTER_CLEANUP_PROVIDER_OPTIONS to """{"order": ["openai"]}""",
            Keys.CLEANUP_TIMEOUT_SECONDS to 12,
            Keys.PROMPTS to encodePrompts(prompts),
            Keys.ACTIVE_PROMPT_ID to "b",
            Keys.BUBBLE_SIDE to "LEFT",
            Keys.BUBBLE_Y_FRACTION to 0.75f,
            Keys.PANEL_LINES to 5,
            Keys.PANEL_WIDTH_PERCENT to 70,
            Keys.CUSTOM_CLEANUP_PROVIDERS to encodeCustomProviders(customProviders),
        )

        val settings = prefs.toSettings(
            setOf(
                Secret.GATEWAY_API_KEY,
                Secret.DEEPGRAM_API_KEY,
                Secret.SONIOX_API_KEY,
                Secret.OPENROUTER_API_KEY,
                Secret.customCleanupKey("c2"),
                Secret.customCleanupKey("deleted"),
            )
        )

        assertEquals(Settings.STT_PROVIDER_VERCEL_GATEWAY, settings.sttProviderId)
        assertEquals("gpt-transcribe", settings.sttModel)
        assertEquals("google/gemini-3.5-transcribe-live", settings.gatewaySttModel)
        assertFalse(settings.gatewaySttLive)
        assertEquals("nova-3-medical", settings.deepgramSttModel)
        assertTrue(settings.deepgramUpload)
        assertEquals("stt-async-v6", settings.sonioxSttModel)
        assertEquals("eu", settings.sonioxRegion)
        assertEquals("en, bn", settings.sonioxLanguages)
        assertEquals("fr", settings.sttLanguage)
        assertEquals(20, settings.sttTimeoutSeconds)
        assertFalse(settings.vadEnabled)
        assertEquals(150, settings.sttSpeedPercent)
        assertTrue(settings.cleanupEnabled)
        assertEquals("google/gemini-2.5-flash", settings.cleanupModel)
        assertEquals("high", settings.cleanupReasoningEffort)
        assertEquals(Settings.CLEANUP_PROVIDER_OPENROUTER, settings.cleanupProviderId)
        assertEquals("""{"gateway": {"order": ["azure"]}}""", settings.cleanupProviderOptions)
        assertEquals("anthropic/claude-haiku-5", settings.openRouterCleanupModel)
        assertEquals("medium", settings.openRouterCleanupReasoningEffort)
        assertEquals("""{"order": ["openai"]}""", settings.openRouterCleanupProviderOptions)
        assertEquals(12, settings.cleanupTimeoutSeconds)
        assertEquals(prompts, settings.prompts)
        assertEquals(prompts[1], settings.activePrompt)
        assertFalse(settings.openAiKeySet)
        assertTrue(settings.gatewayKeySet)
        assertTrue(settings.deepgramKeySet)
        assertTrue(settings.sonioxKeySet)
        assertTrue(settings.openRouterKeySet)
        assertEquals(customProviders, settings.customCleanupProviders)
        assertEquals(setOf("c2"), settings.customCleanupKeyIds)
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
            Keys.SONIOX_STT_MODEL to "stt-async-v6",
            Keys.STT_LANGUAGE to "fr",
            Keys.SONIOX_LANGUAGES to "en, bn",
        )

        val openAi = prefs.toSettings(setOf(Secret.OPENAI_API_KEY))
        assertFalse(openAi.sttGateway)
        assertEquals("gpt-transcribe", openAi.activeSttModel)
        assertTrue(openAi.sttKeySet)
        assertEquals("fr", openAi.activeSttLanguage)

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
        assertEquals("fr", deepgram.activeSttLanguage)

        prefs[Keys.STT_PROVIDER_ID] = Settings.STT_PROVIDER_SONIOX
        val soniox = prefs.toSettings(setOf(Secret.OPENAI_API_KEY, Secret.DEEPGRAM_API_KEY))
        assertTrue(soniox.sttSoniox)
        assertFalse(soniox.sttDeepgram)
        assertEquals("stt-async-v6", soniox.activeSttModel)
        assertFalse(soniox.sttKeySet)
        assertTrue(prefs.toSettings(setOf(Secret.SONIOX_API_KEY)).sttKeySet)
        assertEquals("en, bn", soniox.activeSttLanguage)

        prefs[Keys.STT_PROVIDER_ID] = Settings.STT_PROVIDER_ON_DEVICE
        val onDevice = prefs.toSettings(emptySet())
        assertTrue(onDevice.sttOnDevice)
        assertFalse(onDevice.sttSoniox)
        assertEquals(Settings.DEFAULT_ON_DEVICE_STT_MODEL, onDevice.activeSttModel)
        assertTrue(onDevice.sttKeySet) // No key.
        assertEquals("fr", onDevice.activeSttLanguage)
        prefs[Keys.ON_DEVICE_STT_MODEL] = "whisper-small"
        assertEquals("whisper-small", prefs.toSettings(emptySet()).activeSttModel)

        prefs[Keys.STT_PROVIDER_ID] = "unknown"
        val unknown = prefs.toSettings(setOf(Secret.OPENAI_API_KEY))
        assertEquals("gpt-transcribe", unknown.activeSttModel)
        assertTrue(unknown.sttKeySet)
    }

    @Test
    fun activeCleanupFieldsFollowTheProvider() {
        val prefs = mutablePreferencesOf(
            Keys.CLEANUP_MODEL to "openai/gpt-6-luna",
            Keys.CLEANUP_REASONING_EFFORT to "high",
            Keys.CLEANUP_PROVIDER_OPTIONS to """{"gateway":{}}""",
            Keys.OPENROUTER_CLEANUP_MODEL to "google/gemini-3-flash",
            Keys.OPENROUTER_CLEANUP_REASONING_EFFORT to "",
            Keys.OPENROUTER_CLEANUP_PROVIDER_OPTIONS to """{"order":["google"]}""",
        )

        val gateway = prefs.toSettings(emptySet())
        assertFalse(gateway.cleanupOpenRouter)
        assertEquals("openai/gpt-6-luna", gateway.activeCleanupModel)
        assertEquals("high", gateway.activeCleanupReasoningEffort)
        assertEquals("""{"gateway":{}}""", gateway.activeCleanupProviderOptions)

        prefs[Keys.CLEANUP_PROVIDER_ID] = Settings.CLEANUP_PROVIDER_OPENROUTER
        val openRouter = prefs.toSettings(emptySet())
        assertTrue(openRouter.cleanupOpenRouter)
        assertEquals("google/gemini-3-flash", openRouter.activeCleanupModel)
        assertNull(openRouter.activeCleanupReasoningEffort)
        assertEquals("""{"order":["google"]}""", openRouter.activeCleanupProviderOptions)

        prefs[Keys.CLEANUP_PROVIDER_ID] = "unknown"
        val unknown = prefs.toSettings(emptySet())
        assertEquals(Settings.CLEANUP_PROVIDER_VERCEL_GATEWAY, unknown.cleanupProviderId)
        assertFalse(unknown.cleanupOpenRouter)
        assertEquals("openai/gpt-6-luna", unknown.activeCleanupModel)
        assertEquals("high", unknown.activeCleanupReasoningEffort)
        assertEquals("""{"gateway":{}}""", unknown.activeCleanupProviderOptions)

        prefs[Keys.CUSTOM_CLEANUP_PROVIDERS] = encodeCustomProviders(customProviders)
        prefs[Keys.CLEANUP_PROVIDER_ID] = "c1"
        val custom = prefs.toSettings(emptySet())
        assertEquals("c1", custom.cleanupProviderId)
        assertEquals(customProviders[0], custom.activeCustomCleanup)
        assertFalse(custom.cleanupOpenRouter)
        assertEquals("llama-4", custom.activeCleanupModel)
        assertEquals("high", custom.activeCleanupReasoningEffort)
        assertEquals("", custom.activeCleanupProviderOptions)

        prefs[Keys.CLEANUP_PROVIDER_ID] = "c2"
        val defaults = prefs.toSettings(emptySet())
        assertEquals("", defaults.activeCleanupModel)
        assertNull(defaults.activeCleanupReasoningEffort)
    }

    @Test
    fun danglingCustomProviderIdMeansTheGateway() {
        val settings = mutablePreferencesOf(
            Keys.CUSTOM_CLEANUP_PROVIDERS to encodeCustomProviders(customProviders),
            Keys.CLEANUP_PROVIDER_ID to "deleted",
            Keys.CLEANUP_MODEL to "openai/gpt-6-luna",
            Keys.CLEANUP_PROVIDER_OPTIONS to """{"gateway":{}}""",
        ).toSettings(emptySet())

        assertEquals(Settings.CLEANUP_PROVIDER_VERCEL_GATEWAY, settings.cleanupProviderId)
        assertNull(settings.activeCustomCleanup)
        assertEquals("openai/gpt-6-luna", settings.activeCleanupModel)
        assertEquals("low", settings.activeCleanupReasoningEffort)
        assertEquals("""{"gateway":{}}""", settings.activeCleanupProviderOptions)
    }

    @Test
    fun emptyReasoningEffortIsModelDefault() {
        val settings = mutablePreferencesOf(
            Keys.CLEANUP_REASONING_EFFORT to "",
            Keys.OPENROUTER_CLEANUP_REASONING_EFFORT to "",
        ).toSettings(emptySet())

        assertNull(settings.cleanupReasoningEffort)
        assertNull(settings.openRouterCleanupReasoningEffort)
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
