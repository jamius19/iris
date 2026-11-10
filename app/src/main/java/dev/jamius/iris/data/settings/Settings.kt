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

data class Settings(
    val sttProviderId: String,
    val sttModel: String,
    /** ISO-639-1 code; blank means auto-detect. */
    val sttLanguage: String,
    val cleanupEnabled: Boolean,
    val cleanupProviderId: String,
    val cleanupModel: String,
    val prompts: List<CleanupPrompt>,
    /** The selected prompt, falling back to the first one if the selection no longer exists. */
    val activePrompt: CleanupPrompt?,
    val openAiKeySet: Boolean,
    val gatewayKeySet: Boolean,
) {
    companion object {
        const val STT_PROVIDER_OPENAI = "openai"
        const val CLEANUP_PROVIDER_VERCEL_GATEWAY = "vercel-gateway"

        const val DEFAULT_STT_MODEL = "gpt-live-transcribe"
        const val DEFAULT_CLEANUP_MODEL = "openai/gpt-6-luna"

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
