/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.settings

import dev.jamius.iris.cleanup.parseProviderOptions
import dev.jamius.iris.data.settings.Settings
import dev.jamius.iris.stt.ondevice.OnDeviceModel
import dev.jamius.iris.stt.ondevice.SpeedVerdict
import dev.jamius.iris.stt.ondevice.roundToTenth
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The saved model's mode, for Soniox's model field helper; [streams] without [live]: text after stop. */
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
    Settings.STT_PROVIDER_ON_DEVICE,
    Settings.STT_PROVIDER_OPENAI,
    Settings.STT_PROVIDER_VERCEL_GATEWAY,
    Settings.STT_PROVIDER_DEEPGRAM,
    Settings.STT_PROVIDER_SONIOX
)

/** The built-in cleanup providers in menu order (the custom ones follow). */
val CLEANUP_PROVIDERS = listOf(Settings.CLEANUP_PROVIDER_VERCEL_GATEWAY, Settings.CLEANUP_PROVIDER_OPENROUTER)

/** Why a custom provider's Save can't go ahead, per field; all null = [ok]. */
data class ProviderDraftErrors(val name: String?, val baseUrl: String?, val apiKey: String?) {
    val ok: Boolean get() = name == null && baseUrl == null && apiKey == null
}

/**
 * Checks a custom provider draft: a name, an https base URL, and a key ([keyInput], or one [keyStored] and not marked
 * for removal).
 */
fun providerDraftErrors(name: String, baseUrl: String, keyInput: String, keyStored: Boolean) = ProviderDraftErrors(
    name = if (name.isBlank()) "Enter a provider name." else null,
    baseUrl = baseUrlError(baseUrl),
    apiKey = if (keyInput.isBlank() && !keyStored) "Enter an API key." else null
)

/** Both URLs parse and have the same host (a provider's stored key is kept only for its own host). */
fun sameHost(a: String, b: String): Boolean {
    val host = a.trim().toHttpUrlOrNull()?.host ?: return false
    return host == b.trim().toHttpUrlOrNull()?.host
}

/** Why [baseUrl] can't be a custom provider's base URL (blank, not https, unparseable), or null. */
fun baseUrlError(baseUrl: String): String? {
    val url = baseUrl.trim()
    return when {
        url.isEmpty() -> "Enter a base URL."
        !url.startsWith("https://", ignoreCase = true) -> "Base URL must start with https://"
        url.toHttpUrlOrNull() == null -> "Not a valid URL."
        else -> null
    }
}

/** "1.0x" .. "2.0x" (Float.toString: no locale comma). */
fun speedText(percent: Int): String = "${percent / 100f}x"

fun secondsText(seconds: Int): String = "$seconds s"

/** Whole megabytes (10^6 bytes, rounded): "663 MB". */
fun megabytesText(bytes: Long): String = "${(bytes + 500_000) / 1_000_000} MB"

/** A download's progress: "120 / 663 MB". */
fun downloadProgressText(doneBytes: Long, totalBytes: Long): String =
    "${(doneBytes + 500_000) / 1_000_000} / ${megabytesText(totalBytes)}"

/** An on-device model in the model menu: its size, or "Downloaded". */
fun onDeviceModelLabel(model: OnDeviceModel, downloaded: Boolean): String =
    "${model.label} · ${if (downloaded) "Downloaded" else megabytesText(model.sizeBytes)}"

/** The helper under the on-device model menu. */
fun onDeviceModelHelper(model: OnDeviceModel): String = if (model.streams) {
    "This streaming model only supports English."
} else {
    "${model.languages}. Runs offline on this phone."
}

/** The Skip Silence switch's subtitle; [onDeviceModel] is the selected one with On device, else null. */
fun skipSilenceSubtitle(onDeviceModel: OnDeviceModel?): String = when {
    onDeviceModel == null -> "Detects speech on-device and sends only that, to cut cost."
    onDeviceModel.streams -> "Detects speech on-device and transcribes only that."
    else -> "Detects speech on-device and transcribes each phrase as you pause."
}

fun percentText(percent: Int): String = "$percent%"

/** An on-device model's speed against real time, rounded to one decimal: "7.1× faster than you speak". */
fun speedFactorText(factor: Double): String = when {
    oneDecimal(factor) == "1.0" -> "As fast as you speak"
    factor > 1 -> "${oneDecimal(factor)}× faster than you speak"
    else -> "${oneDecimal(1 / factor)}× slower than you speak"
}

/** Locale-free (Double.toString: no comma). */
private fun oneDecimal(x: Double): String = roundToTenth(x).toString()

fun verdictLabel(verdict: SpeedVerdict): String = when (verdict) {
    SpeedVerdict.KEEPS_UP -> "Keeps up"
    SpeedVerdict.BORDERLINE -> "Borderline"
    SpeedVerdict.FALLS_BEHIND -> "Falls behind"
}

/** Milliseconds as seconds, rounded to tenths: "4.8 s". */
fun tenthsText(ms: Long): String {
    val tenths = (ms + 50) / 100
    return "${tenths / 10}.${tenths % 10} s"
}

/** How many dictations (or cleanups: [noun]) a section's statistics come from. */
fun statsBasisText(count: Int, noun: String = "dictation"): String =
    if (count == 1) "From your last $noun." else "From your last $count ${noun}s."

/** Milliseconds as minutes, rounded to tenths: "12.4 min". */
fun minutesText(ms: Long): String {
    val tenths = (ms + 3_000) / 6_000
    return "${tenths / 10}.${tenths % 10} min"
}

/**
 * How much less than [recordedMs] was billed, in percent, floored: 0 only when nothing was saved (or no recording),
 * 100 only when nothing was billed, else 1..99.
 */
fun savedPercent(recordedMs: Long, billedMs: Long): Int {
    if (recordedMs <= 0 || billedMs >= recordedMs) return 0
    if (billedMs <= 0) return 100
    return ((recordedMs - billedMs) * 100 / recordedMs).toInt().coerceIn(1, 99)
}

/** The Audio Sent row's subtitle. */
fun audioSavedText(percent: Int): String =
    if (percent > 0) "$percent% less than recorded (Skip Silence, Audio Speed)." else "As recorded: nothing saved."

/** A duration, floored: "35 s", "47 min", "3 h 12 min", "2 h"; a negative one is "0 s". */
fun durationText(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1_000
    if (seconds < 60) return "$seconds s"
    val minutes = seconds / 60
    if (minutes < 60) return "$minutes min"
    val hours = minutes / 60
    return if (minutes % 60 == 0L) "$hours h" else "$hours h ${minutes % 60} min"
}

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
