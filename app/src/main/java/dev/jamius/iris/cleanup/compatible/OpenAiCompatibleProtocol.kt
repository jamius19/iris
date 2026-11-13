/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.cleanup.compatible

// URLs of a user-added OpenAI-compatible server. The base URL includes the version path (e.g.
// `https://api.groq.com/openai/v1`); `/v1` is never added.

internal fun chatCompletionsUrl(baseUrl: String) = baseUrl.trimEnd('/') + "/chat/completions"

internal fun modelsUrl(baseUrl: String) = baseUrl.trimEnd('/') + "/models"

private val LEADING_THINK = Regex(
    """^\s*<think>.*?</think>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)

/** [content] without a leading `<think>…</think>` block (reasoning models that inline it), trimmed. */
internal fun withoutThinkBlock(content: String) = content.replaceFirst(LEADING_THINK, "").trim()
