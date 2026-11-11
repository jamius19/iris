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
    /** The Vercel AI Gateway streaming transcription model. */
    val gatewaySttModel: String,
    /** ISO-639-1 code; blank means auto-detect. */
    val sttLanguage: String,
    val cleanupEnabled: Boolean,
    val cleanupProviderId: String,
    val cleanupModel: String,
    /** Gateway `reasoning_effort`; null = model default (not sent). */
    val cleanupReasoningEffort: String?,
    /** Overall cleanup deadline; past it the original transcript is inserted. */
    val cleanupTimeoutSeconds: Int,
    val prompts: List<CleanupPrompt>,
    /** The selected prompt, falling back to the first one if the selection no longer exists. */
    val activePrompt: CleanupPrompt?,
    val openAiKeySet: Boolean,
    val gatewayKeySet: Boolean,
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

    /** The selected provider's model. */
    val activeSttModel: String get() = if (sttGateway) gatewaySttModel else sttModel

    /** The selected speech-to-text provider's API key is set. */
    val sttKeySet: Boolean get() = if (sttGateway) gatewayKeySet else openAiKeySet

    companion object {
        const val STT_PROVIDER_OPENAI = "openai"
        const val STT_PROVIDER_VERCEL_GATEWAY = "vercel-gateway"
        const val CLEANUP_PROVIDER_VERCEL_GATEWAY = "vercel-gateway"

        const val DEFAULT_STT_MODEL = "gpt-live-transcribe"
        const val DEFAULT_GATEWAY_STT_MODEL = "openai/gpt-live-transcribe"
        const val DEFAULT_CLEANUP_MODEL = "openai/gpt-6-luna"

        const val DEFAULT_REASONING_EFFORT = "low"

        /** The values the Gateway accepts, for when a model's own list is unknown. */
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
