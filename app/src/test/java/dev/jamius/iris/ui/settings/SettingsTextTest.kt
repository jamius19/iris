/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.settings

import dev.jamius.iris.data.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals("OpenRouter", cleanupProviderLabel(Settings.CLEANUP_PROVIDER_OPENROUTER))
        assertEquals("Vercel AI Gateway", cleanupProviderLabel("unknown"))
    }
}
