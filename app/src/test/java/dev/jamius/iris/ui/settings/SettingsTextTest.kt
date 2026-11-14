/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.settings

import dev.jamius.iris.data.settings.CustomCleanupProvider
import dev.jamius.iris.data.settings.Settings
import dev.jamius.iris.data.settings.cleanupProviderLabel
import dev.jamius.iris.data.settings.sttProviderLabel
import dev.jamius.iris.stt.ondevice.SpeedVerdict
import dev.jamius.iris.stt.ondevice.onDeviceModel
import dev.jamius.iris.stt.ondevice.speedVerdict
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
    fun onDeviceTexts() {
        assertEquals("663 MB", megabytesText(663_043_117))
        assertEquals("141 MB", megabytesText(141_300_566))
        assertEquals("0 MB", megabytesText(0))
        assertEquals("120 / 663 MB", downloadProgressText(120_400_000, 663_043_117))
        val parakeet = onDeviceModel("parakeet-unified-en")
        val nemotron = onDeviceModel("nemotron-streaming-en")
        assertEquals("This streaming model only supports English.", onDeviceModelHelper(nemotron))
        assertEquals("English. Runs offline on this phone.", onDeviceModelHelper(parakeet))
    }

    @Test
    fun skipSilenceSubtitles() {
        assertEquals("Detects speech on-device and sends only that, to cut cost.", skipSilenceSubtitle(null))
        assertEquals(
            "Detects speech on-device and transcribes only that.",
            skipSilenceSubtitle(onDeviceModel("nemotron-streaming-en"))
        )
        assertEquals(
            "Detects speech on-device and transcribes each phrase as you pause.",
            skipSilenceSubtitle(onDeviceModel("whisper-small"))
        )
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
        assertEquals(Settings.STT_PROVIDER_ON_DEVICE, STT_PROVIDERS.first())
        assertEquals("On device", sttProviderLabel(Settings.STT_PROVIDER_ON_DEVICE))
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
    fun speedFactorTexts() {
        assertEquals("7.1× faster than you speak", speedFactorText(7.098))
        assertEquals("2.0× slower than you speak", speedFactorText(0.5))
        assertEquals("As fast as you speak", speedFactorText(1.02))
        assertEquals("As fast as you speak", speedFactorText(0.98))
    }

    @Test
    fun theVerdictAgreesWithTheSpeedTextAtTheThresholds() {
        val cases = mapOf(
            0.94 to ("Falls behind" to "1.1× slower than you speak"),
            0.95 to ("Borderline" to "As fast as you speak"),
            0.98 to ("Borderline" to "As fast as you speak"),
            1.44 to ("Borderline" to "1.4× faster than you speak"),
            1.45 to ("Keeps up" to "1.5× faster than you speak"),
            1.499 to ("Keeps up" to "1.5× faster than you speak"),
        )
        for ((factor, expected) in cases) {
            assertEquals("$factor", expected, verdictLabel(speedVerdict(factor)) to speedFactorText(factor))
        }
    }

    @Test
    fun tenthsTexts() {
        assertEquals("0.2 s", tenthsText(191))
        assertEquals("4.8 s", tenthsText(4_849))
        assertEquals("12.3 s", tenthsText(12_340))
        assertEquals("0.1 s", tenthsText(50))
        assertEquals("0.0 s", tenthsText(0))
    }

    @Test
    fun verdictLabels() {
        assertEquals("Keeps up", verdictLabel(SpeedVerdict.KEEPS_UP))
        assertEquals("Borderline", verdictLabel(SpeedVerdict.BORDERLINE))
        assertEquals("Falls behind", verdictLabel(SpeedVerdict.FALLS_BEHIND))
    }

    @Test
    fun statsBasisTexts() {
        assertEquals("From your last dictation.", statsBasisText(1))
        assertEquals("From your last 12 dictations.", statsBasisText(12))
        assertEquals("From your last post processing request.", statsBasisText(1, "post processing request"))
        assertEquals("From your last 5 post processing requests.", statsBasisText(5, "post processing request"))
    }

    @Test
    fun minutesTexts() {
        assertEquals("0.0 min", minutesText(0))
        assertEquals("12.4 min", minutesText(744_000))
        assertEquals("0.5 min", minutesText(30_000))
    }

    @Test
    fun savedPercents() {
        assertEquals(0, savedPercent(10_000, 10_000))
        assertEquals(38, savedPercent(10_000, 6_200))
        assertEquals(100, savedPercent(10_000, 0))
        // Floored and capped: anything billed is under 100, any saving is at least 1.
        assertEquals(99, savedPercent(10_000, 30))
        assertEquals(1, savedPercent(10_000, 9_960))
        // Clamped: billed over recorded (e.g. Sonic's tail) saves nothing.
        assertEquals(0, savedPercent(10_000, 10_500))
        assertEquals(0, savedPercent(0, 0))
    }

    @Test
    fun audioSavedTexts() {
        assertEquals("38% less than recorded.", audioSavedText(38))
        assertEquals("Same as recorded duration. Nothing saved.", audioSavedText(0))
    }

    @Test
    fun durationTexts() {
        assertEquals("0 s", durationText(0))
        assertEquals("35 s", durationText(35_000))
        assertEquals("59 s", durationText(59_999))
        assertEquals("1 min", durationText(60_000))
        assertEquals("47 min", durationText(47 * 60_000L + 59_000))
        assertEquals("3 h 12 min", durationText((3 * 60 + 12) * 60_000L))
        assertEquals("2 h", durationText(2 * 3_600_000L))
        // The summary card's net Time Saved may be negative: shown as nothing saved.
        assertEquals("0 s", durationText(-90_000))
    }

    @Test
    fun faviconDomains() {
        val registrable = mapOf("api.groq.com" to "groq.com")
        assertEquals("groq.com", faviconDomain("https://api.groq.com/openai/v1") { registrable[it.host] })
        // No registrable domain (IP, localhost, internal name): no fetch.
        assertNull(faviconDomain("https://llm.internal/v1") { null })
        assertNull(faviconDomain("not a url") { "x.com" })
    }

    @Test
    fun batteryModes() {
        assertEquals(BatteryMode.UNRESTRICTED, batteryMode(ignoringOptimizations = true, backgroundRestricted = false))
        assertEquals(BatteryMode.OPTIMIZED, batteryMode(ignoringOptimizations = false, backgroundRestricted = false))
        assertEquals(BatteryMode.RESTRICTED, batteryMode(ignoringOptimizations = false, backgroundRestricted = true))
        // Restricted is checked first.
        assertEquals(BatteryMode.RESTRICTED, batteryMode(ignoringOptimizations = true, backgroundRestricted = true))
    }

    @Test
    fun dontKillMyAppUrls() {
        assertEquals("https://dontkillmyapp.com/xiaomi?app=Iris", dontKillMyAppUrl("Xiaomi"))
        assertEquals("https://dontkillmyapp.com/samsung?app=Iris", dontKillMyAppUrl("samsung"))
        assertEquals("https://dontkillmyapp.com/hmd-global?app=Iris", dontKillMyAppUrl("HMD Global"))
        assertEquals("https://dontkillmyapp.com/xiaomi?app=Iris", dontKillMyAppUrl(" Xiaomi "))
        // No page on the site (404): the home page.
        assertEquals("https://dontkillmyapp.com/?app=Iris", dontKillMyAppUrl("HONOR"))
        assertEquals("https://dontkillmyapp.com/?app=Iris", dontKillMyAppUrl("unknown"))
        assertEquals("https://dontkillmyapp.com/?app=Iris", dontKillMyAppUrl(""))
    }
}
