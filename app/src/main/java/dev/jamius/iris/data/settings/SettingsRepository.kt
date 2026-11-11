/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.Json

class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val secretStore: SecretStore,
) {

    val settings: Flow<Settings> = combine(dataStore.data, secretStore.storedSecrets) { prefs, secrets ->
        prefs.toSettings(secrets)
    }

    suspend fun setSttModel(model: String) {
        dataStore.edit { it[Keys.STT_MODEL] = model.trim() }
    }

    suspend fun setSttLanguage(language: String) {
        dataStore.edit { it[Keys.STT_LANGUAGE] = language.trim() }
    }

    suspend fun setCleanupEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.CLEANUP_ENABLED] = enabled }
    }

    suspend fun setCleanupModel(model: String) {
        dataStore.edit { it[Keys.CLEANUP_MODEL] = model.trim() }
    }

    /** Null = model default, stored as "" (a missing key means the default level). */
    suspend fun setCleanupReasoningEffort(effort: String?) {
        dataStore.edit { it[Keys.CLEANUP_REASONING_EFFORT] = effort ?: "" }
    }

    /** Adds the prompt, or replaces the existing prompt with the same id. */
    suspend fun savePrompt(prompt: CleanupPrompt) {
        dataStore.edit { prefs ->
            val prompts = prefs.prompts()
            prefs.setPrompts(
                if (prompts.any { it.id == prompt.id }) prompts.map { if (it.id == prompt.id) prompt else it }
                else prompts + prompt
            )
        }
    }

    suspend fun deletePrompt(id: String) {
        dataStore.edit { prefs -> prefs.setPrompts(prefs.prompts().filterNot { it.id == id }) }
    }

    suspend fun setActivePrompt(id: String) {
        dataStore.edit { it[Keys.ACTIVE_PROMPT_ID] = id }
    }

    suspend fun setBubblePosition(side: BubbleSide, yFraction: Float) {
        dataStore.edit {
            it[Keys.BUBBLE_SIDE] = side.name
            it[Keys.BUBBLE_Y_FRACTION] = yFraction
        }
    }

    suspend fun setPanelLines(lines: Int) {
        dataStore.edit { it[Keys.PANEL_LINES] = lines }
    }

    suspend fun setPanelWidthPercent(percent: Int) {
        dataStore.edit { it[Keys.PANEL_WIDTH_PERCENT] = percent }
    }

    suspend fun setApiKey(secret: Secret, value: String) {
        secretStore.put(secret, value.trim())
    }

    suspend fun clearApiKey(secret: Secret) {
        secretStore.clear(secret)
    }

    private fun MutablePreferences.setPrompts(prompts: List<CleanupPrompt>) {
        this[Keys.PROMPTS] = encodePrompts(prompts)
    }
}

internal object Keys {
    val STT_PROVIDER_ID = stringPreferencesKey("stt_provider_id")
    val STT_MODEL = stringPreferencesKey("stt_model")
    val STT_LANGUAGE = stringPreferencesKey("stt_language")
    val CLEANUP_ENABLED = booleanPreferencesKey("cleanup_enabled")
    val CLEANUP_PROVIDER_ID = stringPreferencesKey("cleanup_provider_id")
    val CLEANUP_MODEL = stringPreferencesKey("cleanup_model")
    val CLEANUP_REASONING_EFFORT = stringPreferencesKey("cleanup_reasoning_effort")
    val PROMPTS = stringPreferencesKey("cleanup_prompts")
    val ACTIVE_PROMPT_ID = stringPreferencesKey("cleanup_active_prompt_id")
    val BUBBLE_SIDE = stringPreferencesKey("bubble_side")
    val BUBBLE_Y_FRACTION = floatPreferencesKey("bubble_y_fraction")
    val PANEL_LINES = intPreferencesKey("panel_lines")
    val PANEL_WIDTH_PERCENT = intPreferencesKey("panel_width_percent")
}

private val json = Json { ignoreUnknownKeys = true }

internal fun encodePrompts(prompts: List<CleanupPrompt>): String = json.encodeToString(prompts)

internal fun decodePrompts(encoded: String): List<CleanupPrompt> = json.decodeFromString(encoded)

/** Stored prompts, or the seeded default list if prompts were never saved. */
internal fun Preferences.prompts(): List<CleanupPrompt> =
    this[Keys.PROMPTS]?.let(::decodePrompts) ?: listOf(Settings.DEFAULT_PROMPT)

internal fun Preferences.toSettings(storedSecrets: Set<Secret>): Settings {
    val prompts = prompts()
    val activePromptId = this[Keys.ACTIVE_PROMPT_ID] ?: Settings.DEFAULT_PROMPT.id
    return Settings(
        sttProviderId = this[Keys.STT_PROVIDER_ID] ?: Settings.STT_PROVIDER_OPENAI,
        sttModel = this[Keys.STT_MODEL] ?: Settings.DEFAULT_STT_MODEL,
        sttLanguage = this[Keys.STT_LANGUAGE] ?: "",
        cleanupEnabled = this[Keys.CLEANUP_ENABLED] ?: false,
        cleanupProviderId = this[Keys.CLEANUP_PROVIDER_ID] ?: Settings.CLEANUP_PROVIDER_VERCEL_GATEWAY,
        cleanupModel = this[Keys.CLEANUP_MODEL] ?: Settings.DEFAULT_CLEANUP_MODEL,
        cleanupReasoningEffort = when (val effort = this[Keys.CLEANUP_REASONING_EFFORT]) {
            null -> Settings.DEFAULT_REASONING_EFFORT
            "" -> null
            else -> effort
        },
        prompts = prompts,
        activePrompt = prompts.firstOrNull { it.id == activePromptId } ?: prompts.firstOrNull(),
        openAiKeySet = Secret.OPENAI_API_KEY in storedSecrets,
        gatewayKeySet = Secret.GATEWAY_API_KEY in storedSecrets,
        bubbleSide = this[Keys.BUBBLE_SIDE]?.let(BubbleSide::valueOf) ?: Settings.DEFAULT_BUBBLE_SIDE,
        bubbleYFraction = this[Keys.BUBBLE_Y_FRACTION] ?: Settings.DEFAULT_BUBBLE_Y_FRACTION,
        panelLines = this[Keys.PANEL_LINES] ?: Settings.DEFAULT_PANEL_LINES,
        panelWidthPercent = this[Keys.PANEL_WIDTH_PERCENT] ?: Settings.DEFAULT_PANEL_WIDTH_PERCENT,
    )
}
