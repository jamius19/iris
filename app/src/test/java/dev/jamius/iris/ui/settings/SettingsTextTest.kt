/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.settings

import dev.jamius.iris.data.settings.CustomCleanupProvider
import dev.jamius.iris.data.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTextTest {

    @Test
    fun modeText() {
        assertEquals("Mode: live", sttModeText(live = true))
        assertEquals("Mode: streams, text after stop", sttModeText(live = false, streams = true))
        assertEquals("Mode: after stop", sttModeText(live = false))
    }

    @Test
    fun reasoningLabels() {
        assertEquals("Model default", reasoningEffortLabel(null))
        assertEquals("Extra high", reasoningEffortLabel("xhigh"))
        assertEquals("Low", reasoningEffortLabel("low"))
    }

    @Test
    fun speedSecondsAndPercent() {
        assertEquals("1.0x", speedText(100))
        assertEquals("1.5x", speedText(150))
        assertEquals("2.0x", speedText(200))
        assertEquals("10 s", secondsText(10))
        assertEquals("50%", percentText(50))
    }

    @Test
    fun apiKeyHelpers() {
        assertEquals("API key saved. Enter a new one to replace it.", apiKeyHelper(true))
        assertEquals("Not set", apiKeyHelper(false))
        assertEquals(
            "API key saved (shared with transcription). Enter a new one to replace it.",
            apiKeyHelper(true, "transcription")
        )
        assertEquals("Not set (shared with post processing)", apiKeyHelper(false, "post processing"))
    }

    @Test
    fun newPromptNames() {
        assertEquals("New prompt", newPromptName(emptyList()))
        assertEquals("New prompt", newPromptName(listOf("Clean up dictation")))
        assertEquals("New prompt 2", newPromptName(listOf("New prompt")))
        assertEquals("New prompt 3", newPromptName(listOf("New prompt", "New prompt 2", "New prompt 4")))
    }

    @Test
    fun providerOptionsErrors() {
        assertNull(providerOptionsError(""))
        assertNull(providerOptionsError("{\"order\": [\"openai\"]}"))
        assertEquals("Not valid JSON", providerOptionsError("{order: 1"))
        assertEquals("Must be a JSON object: { ... }", providerOptionsError("[1]"))
    }

    @Test
    fun providerLabels() {
        assertEquals("OpenAI", sttProviderLabel(Settings.STT_PROVIDER_OPENAI))
        assertEquals("Vercel AI Gateway", sttProviderLabel(Settings.STT_PROVIDER_VERCEL_GATEWAY))
        assertEquals("Deepgram", sttProviderLabel(Settings.STT_PROVIDER_DEEPGRAM))
        assertEquals("Soniox", sttProviderLabel(Settings.STT_PROVIDER_SONIOX))
        assertEquals("OpenAI", sttProviderLabel("unknown"))
    }

    @Test
    fun cleanupProviderLabels() {
        val custom = listOf(
            CustomCleanupProvider(id = "c1", name = "Groq", baseUrl = "https://api.groq.com/openai/v1"),
            CustomCleanupProvider(id = "c2", name = " ", baseUrl = "https://x.example"),
        )
        assertEquals("Vercel AI Gateway", cleanupProviderLabel(Settings.CLEANUP_PROVIDER_VERCEL_GATEWAY, custom))
        assertEquals("OpenRouter", cleanupProviderLabel(Settings.CLEANUP_PROVIDER_OPENROUTER, custom))
        assertEquals("Groq", cleanupProviderLabel("c1", custom))
        assertEquals("Unnamed Provider", cleanupProviderLabel("c2", custom))
        assertEquals("Unnamed Provider", cleanupProviderLabel("unknown", custom))
    }

    @Test
    fun baseUrlErrors() {
        assertEquals("Enter a base URL.", baseUrlError("  "))
        assertEquals("Base URL must start with https://", baseUrlError("http://localhost:11434/v1"))
        assertEquals("Base URL must start with https://", baseUrlError("api.groq.com/openai/v1"))
        assertEquals("Not a valid URL.", baseUrlError("https://"))
        assertEquals("Not a valid URL.", baseUrlError("https://exa mple.com"))
        assertNull(baseUrlError(" https://api.groq.com/openai/v1/ "))
    }

    @Test
    fun providerDraftErrorsCheckEveryField() {
        assertTrue(providerDraftErrors("Groq", "https://api.groq.com/openai/v1", "gsk-1", keyStored = false).ok)

        val blank = providerDraftErrors(" ", "", "", keyStored = false)
        assertFalse(blank.ok)
        assertEquals("Enter a provider name.", blank.name)
        assertEquals("Enter a base URL.", blank.baseUrl)
        assertEquals("Enter an API key.", blank.apiKey)

        // A stored key needs no input; one marked for removal counts as not stored.
        assertNull(providerDraftErrors("Groq", "https://a.example", "", keyStored = true).apiKey)
        assertEquals(
            "Enter an API key.",
            providerDraftErrors("Groq", "https://a.example", " ", keyStored = false).apiKey
        )
    }

    @Test
    fun sameHosts() {
        assertTrue(sameHost("https://api.groq.com/openai/v1", " https://API.groq.com/v2/ "))
        assertFalse(sameHost("https://api.groq.com/openai/v1", "https://evil.example/openai/v1"))
        assertFalse(sameHost("https://api.groq.com/v1", "https://groq.com/v1"))
        assertFalse(sameHost("not a url", "not a url"))
    }

    @Test
    fun faviconDomains() {
        val registrable = mapOf("api.groq.com" to "groq.com")
        assertEquals("groq.com", faviconDomain("https://api.groq.com/openai/v1") { registrable[it.host] })
        // No registrable domain (IP, localhost, internal name): no fetch.
        assertNull(faviconDomain("https://llm.internal/v1") { null })
        assertNull(faviconDomain("not a url") { "x.com" })
    }
}
