/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.data.settings

import kotlinx.serialization.Serializable

@Serializable
data class CleanupPrompt(
    val id: String,
    val name: String,
    val systemPrompt: String,
)

enum class BubbleSide { LEFT, RIGHT }

data class Settings(
    val sttProviderId: String,
    /** The OpenAI (direct) model. */
    val sttModel: String,
    /** The Vercel AI Gateway transcription model. */
    val gatewaySttModel: String,
    /**
     * [gatewaySttModel] streams while recording (live text); false = uploaded after stop. Decided from the catalog
     * when the model is saved.
     */
    val gatewaySttLive: Boolean,
    /** The Deepgram model. */
    val deepgramSttModel: String,
    /** Deepgram uploads the recording after stop (pre-recorded API, cheaper, no live text) instead of streaming. */
    val deepgramUpload: Boolean,
    /** The Soniox model: `real_time` models stream, `async` ones upload after stop. */
    val sonioxSttModel: String,
    /** The Soniox project's region id (us, eu, jp, in; unknown = us): its key works only on that region's domains. */
    val sonioxRegion: String,
    /** Soniox language hints: comma-separated codes; blank means auto-detect. Soniox only (not [sttLanguage]). */
    val sonioxLanguages: String,
    /** ISO-639-1 code; blank means auto-detect. */
    val sttLanguage: String,
    /** How long to wait for the final transcript once the audio is sent; past it the session fails. */
    val sttTimeoutSeconds: Int,
    /** Send only audio around detected speech (on-device Silero VAD). */
    val vadEnabled: Boolean,
    /** Speed-up of the audio sent to the STT service, in percent (100 = as recorded). */
    val sttSpeedPercent: Int,
    val cleanupEnabled: Boolean,
    val cleanupProviderId: String,
    /** The Vercel AI Gateway cleanup model. */
    val cleanupModel: String,
    /** Gateway `reasoning_effort`; null = model default (not sent). */
    val cleanupReasoningEffort: String?,
    /** Gateway provider options as typed (a JSON object, sent as `providerOptions`); blank = not sent. */
    val cleanupProviderOptions: String,
    /** The OpenRouter cleanup model. */
    val openRouterCleanupModel: String,
    /** OpenRouter `reasoning_effort`; null = model default (not sent). */
    val openRouterCleanupReasoningEffort: String?,
    /** OpenRouter provider routing options as typed (a JSON object, sent as `provider`); blank = not sent. */
    val openRouterCleanupProviderOptions: String,
    /** Overall cleanup deadline; past it the original transcript is inserted. */
    val cleanupTimeoutSeconds: Int,
    val prompts: List<CleanupPrompt>,
    /** The selected prompt, falling back to the first one if the selection no longer exists. */
    val activePrompt: CleanupPrompt?,
    val openAiKeySet: Boolean,
    val gatewayKeySet: Boolean,
    val deepgramKeySet: Boolean,
    val sonioxKeySet: Boolean,
    val openRouterKeySet: Boolean,
    /** Screen edge the overlay bubble is snapped to. */
    val bubbleSide: BubbleSide,
    /** Bubble's vertical position as a fraction (0 = top, 1 = bottom) of the usable screen height. */
    val bubbleYFraction: Float,
    /** Lines of live text the panel shows (its last ones); notices show up to max(this, 4). */
    val panelLines: Int,
    /** Panel's max width as a percent of the display width. */
    val panelWidthPercent: Int,
) {
    /** Speech-to-text goes through Vercel AI Gateway (else OpenAI direct). */
    val sttGateway: Boolean get() = sttProviderId == STT_PROVIDER_VERCEL_GATEWAY

    /** Speech-to-text goes through Deepgram (direct). */
    val sttDeepgram: Boolean get() = sttProviderId == STT_PROVIDER_DEEPGRAM

    /** Speech-to-text goes through Soniox (direct). */
    val sttSoniox: Boolean get() = sttProviderId == STT_PROVIDER_SONIOX

    /** The selected provider's model (an unknown provider id means OpenAI). */
    val activeSttModel: String
        get() = when {
            sttGateway -> gatewaySttModel
            sttDeepgram -> deepgramSttModel
            sttSoniox -> sonioxSttModel
            else -> sttModel
        }

    /** The language setting the selected provider uses: Soniox has its own ([sonioxLanguages]). */
    val activeSttLanguage: String
        get() = if (sttSoniox) sonioxLanguages else sttLanguage

    /** The selected speech-to-text provider's API key is set. */
    val sttKeySet: Boolean
        get() = when {
            sttGateway -> gatewayKeySet
            sttDeepgram -> deepgramKeySet
            sttSoniox -> sonioxKeySet
            else -> openAiKeySet
        }

    /** Cleanup goes through OpenRouter (else Vercel AI Gateway; an unknown id means the Gateway). */
    val cleanupOpenRouter: Boolean get() = cleanupProviderId == CLEANUP_PROVIDER_OPENROUTER

    val activeCleanupModel: String get() = if (cleanupOpenRouter) openRouterCleanupModel else cleanupModel

    val activeCleanupReasoningEffort: String?
        get() = if (cleanupOpenRouter) openRouterCleanupReasoningEffort else cleanupReasoningEffort

    val activeCleanupProviderOptions: String
        get() = if (cleanupOpenRouter) openRouterCleanupProviderOptions else cleanupProviderOptions

    companion object {
        const val STT_PROVIDER_OPENAI = "openai"
        const val STT_PROVIDER_VERCEL_GATEWAY = "vercel-gateway"
        const val STT_PROVIDER_DEEPGRAM = "deepgram"
        const val STT_PROVIDER_SONIOX = "soniox"
        const val CLEANUP_PROVIDER_VERCEL_GATEWAY = "vercel-gateway"
        const val CLEANUP_PROVIDER_OPENROUTER = "openrouter"

        const val DEFAULT_STT_MODEL = "gpt-live-transcribe"
        const val DEFAULT_GATEWAY_STT_MODEL = "openai/gpt-live-transcribe"
        const val DEFAULT_DEEPGRAM_STT_MODEL = "nova-3"
        const val DEFAULT_SONIOX_STT_MODEL = "stt-rt-v6"
        const val DEFAULT_SONIOX_REGION = "us"
        /** Both cleanup providers (same id on OpenRouter). */
        const val DEFAULT_CLEANUP_MODEL = "openai/gpt-6-luna"

        const val DEFAULT_STT_TIMEOUT_SECONDS = 10
        val STT_TIMEOUT_RANGE = 5..30

        const val DEFAULT_STT_SPEED_PERCENT = 100
        val STT_SPEED_PERCENT_RANGE = 100..200

        const val DEFAULT_REASONING_EFFORT = "low"

        /** The values both cleanup APIs accept, for when a model's own list is unknown (also the display order). */
        val REASONING_EFFORTS = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

        const val DEFAULT_CLEANUP_TIMEOUT_SECONDS = 5
        val CLEANUP_TIMEOUT_RANGE = 1..30

        val DEFAULT_BUBBLE_SIDE = BubbleSide.RIGHT
        const val DEFAULT_BUBBLE_Y_FRACTION = 0.4f

        const val DEFAULT_PANEL_LINES = 3
        val PANEL_LINES_RANGE = 1..6
        const val DEFAULT_PANEL_WIDTH_PERCENT = 50
        val PANEL_WIDTH_PERCENT_RANGE = 30..90

        val DEFAULT_PROMPT = CleanupPrompt(
            id = "default",
            name = "Clean up dictation",
            systemPrompt = """
                You clean up dictated text. Remove filler words (um, uh, like, you know), false starts and
                repetitions. Fix punctuation, capitalization and grammar. Keep the speaker's meaning, wording,
                tone and language; do not translate, summarize, add content or answer questions in the text.
                Output only the cleaned text.
            """.trimIndent().replace("\n", " "),
        )
    }
}
