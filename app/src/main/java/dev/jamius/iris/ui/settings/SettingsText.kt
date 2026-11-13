/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.settings

import dev.jamius.iris.cleanup.parseProviderOptions
import dev.jamius.iris.data.settings.Settings

/** The saved transcription model's mode, for the model field's helper; [streams] without [live]: text after stop. */
fun sttModeText(live: Boolean, streams: Boolean = live): String = when {
    live -> "Mode: live"
    streams -> "Mode: streams, text after stop"
    else -> "Mode: after stop"
}

fun reasoningEffortLabel(effort: String?): String = when (effort) {
    null -> "Model default"
    "xhigh" -> "Extra high"
    else -> effort.replaceFirstChar { it.uppercase() }
}

/** The transcription providers in menu order; an unknown stored id shows as OpenAI. */
val STT_PROVIDERS = listOf(
    Settings.STT_PROVIDER_OPENAI,
    Settings.STT_PROVIDER_VERCEL_GATEWAY,
    Settings.STT_PROVIDER_DEEPGRAM,
    Settings.STT_PROVIDER_SONIOX
)

fun sttProviderLabel(id: String): String = when (id) {
    Settings.STT_PROVIDER_VERCEL_GATEWAY -> "Vercel AI Gateway"
    Settings.STT_PROVIDER_DEEPGRAM -> "Deepgram"
    Settings.STT_PROVIDER_SONIOX -> "Soniox"
    else -> "OpenAI"
}

/** The cleanup providers in menu order; an unknown stored id shows as the Gateway. */
val CLEANUP_PROVIDERS = listOf(Settings.CLEANUP_PROVIDER_VERCEL_GATEWAY, Settings.CLEANUP_PROVIDER_OPENROUTER)

fun cleanupProviderLabel(id: String): String =
    if (id == Settings.CLEANUP_PROVIDER_OPENROUTER) "OpenRouter" else "Vercel AI Gateway"

/** "1.0x" .. "2.0x" (Float.toString: no locale comma). */
fun speedText(percent: Int): String = "${percent / 100f}x"

fun secondsText(seconds: Int): String = "$seconds s"

fun percentText(percent: Int): String = "$percent%"

/** The API key field's helper; [sharedWith] names the other page using the same key (the Gateway key). */
fun apiKeyHelper(isSet: Boolean, sharedWith: String? = null): String {
    val shared = sharedWith?.let { " (shared with $it)" }.orEmpty()
    return if (isSet) "API key saved$shared. Enter a new one to replace it." else "Not set$shared"
}

/** "New prompt", or "New prompt N" with the lowest N >= 2 not in [existing]. */
fun newPromptName(existing: List<String>): String {
    if (NEW_PROMPT !in existing) return NEW_PROMPT
    return generateSequence(2) { it + 1 }.map { "$NEW_PROMPT $it" }.first { it !in existing }
}

/** Why [text] can't be saved as provider options (blank is fine: it clears them), or null. */
fun providerOptionsError(text: String): String? = runCatching { parseProviderOptions(text) }.exceptionOrNull()?.message

private const val NEW_PROMPT = "New prompt"

const val GATEWAY_OPTIONS_HINT = "Sent as providerOptions, e.g. {\"gateway\": {\"order\": [\"azure\", \"openai\"]}}. " +
    "See the AI Gateway provider options docs. Leave blank to send none. Stored unencrypted: don't put API keys here."

const val OPENROUTER_OPTIONS_HINT = "Sent as provider (routing), e.g. {\"order\": [\"openai\"], " +
    "\"allow_fallbacks\": false}. See OpenRouter's provider routing docs. Leave blank to send none."
