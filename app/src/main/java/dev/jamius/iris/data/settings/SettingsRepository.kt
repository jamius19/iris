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

    suspend fun setSttProvider(id: String) {
        dataStore.edit { it[Keys.STT_PROVIDER_ID] = id }
    }

    suspend fun setSttModel(model: String) {
        dataStore.edit { it[Keys.STT_MODEL] = model.trim() }
    }

    /** [live]: the model streams (see [Settings.gatewaySttLive]). */
    suspend fun setGatewaySttModel(model: String, live: Boolean) {
        dataStore.edit {
            it[Keys.GATEWAY_STT_MODEL] = model.trim()
            it[Keys.GATEWAY_STT_LIVE] = live
        }
    }

    suspend fun setDeepgramSttModel(model: String) {
        dataStore.edit { it[Keys.DEEPGRAM_STT_MODEL] = model.trim() }
    }

    suspend fun setDeepgramUpload(enabled: Boolean) {
        dataStore.edit { it[Keys.DEEPGRAM_UPLOAD] = enabled }
    }

    suspend fun setSonioxSttModel(model: String) {
        dataStore.edit { it[Keys.SONIOX_STT_MODEL] = model.trim() }
    }

    suspend fun setSonioxRegion(id: String) {
        dataStore.edit { it[Keys.SONIOX_REGION] = id }
    }

    suspend fun setSonioxLanguages(languages: String) {
        dataStore.edit { it[Keys.SONIOX_LANGUAGES] = languages.trim() }
    }

    suspend fun setOnDeviceSttModel(id: String) {
        dataStore.edit { it[Keys.ON_DEVICE_STT_MODEL] = id }
    }

    suspend fun setOnDeviceUnloadMinutes(minutes: Int) {
        dataStore.edit { it[Keys.ON_DEVICE_UNLOAD_MINUTES] = minutes }
    }

    suspend fun setSttLanguage(language: String) {
        dataStore.edit { it[Keys.STT_LANGUAGE] = language.trim() }
    }

    suspend fun setSttTimeoutSeconds(seconds: Int) {
        dataStore.edit { it[Keys.STT_TIMEOUT_SECONDS] = seconds }
    }

    suspend fun setVadEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.VAD_ENABLED] = enabled }
    }

    suspend fun setSttSpeedPercent(percent: Int) {
        dataStore.edit { it[Keys.STT_SPEED_PERCENT] = percent }
    }

    suspend fun setCleanupEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.CLEANUP_ENABLED] = enabled }
    }

    suspend fun setCleanupProvider(id: String) {
        dataStore.edit { it[Keys.CLEANUP_PROVIDER_ID] = id }
    }

    suspend fun setCleanupModel(model: String) {
        dataStore.edit { it[Keys.CLEANUP_MODEL] = model.trim() }
    }

    /** Null = model default, stored as "" (a missing key means the default level). */
    suspend fun setCleanupReasoningEffort(effort: String?) {
        dataStore.edit { it[Keys.CLEANUP_REASONING_EFFORT] = effort ?: "" }
    }

    /** Stored as typed (trimmed); "" clears. */
    suspend fun setCleanupProviderOptions(options: String) {
        dataStore.edit { it[Keys.CLEANUP_PROVIDER_OPTIONS] = options.trim() }
    }

    suspend fun setOpenRouterCleanupModel(model: String) {
        dataStore.edit { it[Keys.OPENROUTER_CLEANUP_MODEL] = model.trim() }
    }

    /** Null = model default, stored as "". */
    suspend fun setOpenRouterCleanupReasoningEffort(effort: String?) {
        dataStore.edit { it[Keys.OPENROUTER_CLEANUP_REASONING_EFFORT] = effort ?: "" }
    }

    /** Stored as typed (trimmed); "" clears. */
    suspend fun setOpenRouterCleanupProviderOptions(options: String) {
        dataStore.edit { it[Keys.OPENROUTER_CLEANUP_PROVIDER_OPTIONS] = options.trim() }
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

    suspend fun setCleanupTimeoutSeconds(seconds: Int) {
        dataStore.edit { it[Keys.CLEANUP_TIMEOUT_SECONDS] = seconds }
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

    /**
     * Saves the custom cleanup provider [id] (adds it, or updates the name and base URL of the existing one, keeping
     * its model settings) and selects it. A non-null [apiKey] is stored first.
     */
    suspend fun saveCustomCleanupProvider(id: String, name: String, baseUrl: String, apiKey: String?) {
        if (apiKey != null) secretStore.put(Secret.customCleanupKey(id), apiKey.trim())
        val trimmedName = name.trim()
        val url = baseUrl.trim().trimEnd('/')
        dataStore.edit { prefs ->
            val providers = prefs.customCleanupProviders()
            prefs.setCustomCleanupProviders(
                if (providers.any { it.id == id }) {
                    providers.map { if (it.id == id) it.copy(name = trimmedName, baseUrl = url) else it }
                } else {
                    providers + CustomCleanupProvider(id = id, name = trimmedName, baseUrl = url)
                }
            )
            prefs[Keys.CLEANUP_PROVIDER_ID] = id
        }
    }

    /** Applies [update] to the custom cleanup provider [id] (its model settings); nothing if it is gone. */
    suspend fun updateCustomCleanupProvider(id: String, update: (CustomCleanupProvider) -> CustomCleanupProvider) {
        dataStore.edit { prefs ->
            val providers = prefs.customCleanupProviders()
            if (providers.any { it.id == id }) {
                prefs.setCustomCleanupProviders(providers.map { if (it.id == id) update(it) else it })
            }
        }
    }

    /**
     * Deletes the custom cleanup provider [id] and its key; if it was selected, the Gateway is. The key goes first,
     * so a failure leaves a keyless provider (retryable), never an orphaned key.
     */
    suspend fun deleteCustomCleanupProvider(id: String) {
        secretStore.clear(Secret.customCleanupKey(id))
        dataStore.edit { prefs ->
            prefs.setCustomCleanupProviders(prefs.customCleanupProviders().filterNot { it.id == id })
            if (prefs[Keys.CLEANUP_PROVIDER_ID] == id) {
                prefs[Keys.CLEANUP_PROVIDER_ID] = Settings.CLEANUP_PROVIDER_VERCEL_GATEWAY
            }
        }
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

    private fun MutablePreferences.setCustomCleanupProviders(providers: List<CustomCleanupProvider>) {
        this[Keys.CUSTOM_CLEANUP_PROVIDERS] = encodeCustomProviders(providers)
    }
}

internal object Keys {
    val STT_PROVIDER_ID = stringPreferencesKey("stt_provider_id")
    val STT_MODEL = stringPreferencesKey("stt_model")
    val GATEWAY_STT_MODEL = stringPreferencesKey("gateway_stt_model")
    val GATEWAY_STT_LIVE = booleanPreferencesKey("gateway_stt_live")
    val DEEPGRAM_STT_MODEL = stringPreferencesKey("deepgram_stt_model")
    val DEEPGRAM_UPLOAD = booleanPreferencesKey("deepgram_upload")
    val SONIOX_STT_MODEL = stringPreferencesKey("soniox_stt_model")
    val SONIOX_REGION = stringPreferencesKey("soniox_region")
    val SONIOX_LANGUAGES = stringPreferencesKey("soniox_languages")
    val ON_DEVICE_STT_MODEL = stringPreferencesKey("on_device_stt_model")
    val ON_DEVICE_UNLOAD_MINUTES = intPreferencesKey("on_device_unload_minutes")
    val STT_LANGUAGE = stringPreferencesKey("stt_language")
    val STT_TIMEOUT_SECONDS = intPreferencesKey("stt_timeout_seconds")
    val VAD_ENABLED = booleanPreferencesKey("vad_enabled")
    val STT_SPEED_PERCENT = intPreferencesKey("stt_speed_percent")
    val CLEANUP_ENABLED = booleanPreferencesKey("cleanup_enabled")
    val CLEANUP_PROVIDER_ID = stringPreferencesKey("cleanup_provider_id")
    val CLEANUP_MODEL = stringPreferencesKey("cleanup_model")
    val CLEANUP_REASONING_EFFORT = stringPreferencesKey("cleanup_reasoning_effort")
    val CLEANUP_PROVIDER_OPTIONS = stringPreferencesKey("cleanup_provider_options")
    val OPENROUTER_CLEANUP_MODEL = stringPreferencesKey("openrouter_cleanup_model")
    val OPENROUTER_CLEANUP_REASONING_EFFORT = stringPreferencesKey("openrouter_cleanup_reasoning_effort")
    val OPENROUTER_CLEANUP_PROVIDER_OPTIONS = stringPreferencesKey("openrouter_cleanup_provider_options")
    val CLEANUP_TIMEOUT_SECONDS = intPreferencesKey("cleanup_timeout_seconds")
    val PROMPTS = stringPreferencesKey("cleanup_prompts")
    val ACTIVE_PROMPT_ID = stringPreferencesKey("cleanup_active_prompt_id")
    val CUSTOM_CLEANUP_PROVIDERS = stringPreferencesKey("custom_cleanup_providers")
    val BUBBLE_SIDE = stringPreferencesKey("bubble_side")
    val BUBBLE_Y_FRACTION = floatPreferencesKey("bubble_y_fraction")
    val PANEL_LINES = intPreferencesKey("panel_lines")
    val PANEL_WIDTH_PERCENT = intPreferencesKey("panel_width_percent")
}

private val json = Json { ignoreUnknownKeys = true }

internal fun encodePrompts(prompts: List<CleanupPrompt>): String = json.encodeToString(prompts)

internal fun decodePrompts(encoded: String): List<CleanupPrompt> = json.decodeFromString(encoded)

internal fun encodeCustomProviders(providers: List<CustomCleanupProvider>): String = json.encodeToString(providers)

internal fun decodeCustomProviders(encoded: String): List<CustomCleanupProvider> = json.decodeFromString(encoded)

/** Stored custom cleanup providers; none if never saved. */
internal fun Preferences.customCleanupProviders(): List<CustomCleanupProvider> =
    this[Keys.CUSTOM_CLEANUP_PROVIDERS]?.let(::decodeCustomProviders).orEmpty()

/** Stored prompts, or the seeded default list if prompts were never saved. */
internal fun Preferences.prompts(): List<CleanupPrompt> =
    this[Keys.PROMPTS]?.let(::decodePrompts) ?: listOf(Settings.DEFAULT_PROMPT)

internal fun Preferences.toSettings(storedSecrets: Set<Secret>): Settings {
    val prompts = prompts()
    val activePromptId = this[Keys.ACTIVE_PROMPT_ID] ?: Settings.DEFAULT_PROMPT.id
    val customProviders = customCleanupProviders()
    // A stored id that is neither built in nor a saved custom provider (e.g. deleted) means the Gateway.
    val cleanupProviderId = this[Keys.CLEANUP_PROVIDER_ID]
        ?.takeIf { id -> id == Settings.CLEANUP_PROVIDER_OPENROUTER || customProviders.any { it.id == id } }
        ?: Settings.CLEANUP_PROVIDER_VERCEL_GATEWAY
    return Settings(
        sttProviderId = this[Keys.STT_PROVIDER_ID] ?: Settings.STT_PROVIDER_OPENAI,
        sttModel = this[Keys.STT_MODEL] ?: Settings.DEFAULT_STT_MODEL,
        gatewaySttModel = this[Keys.GATEWAY_STT_MODEL] ?: Settings.DEFAULT_GATEWAY_STT_MODEL,
        gatewaySttLive = this[Keys.GATEWAY_STT_LIVE] ?: true,
        deepgramSttModel = this[Keys.DEEPGRAM_STT_MODEL] ?: Settings.DEFAULT_DEEPGRAM_STT_MODEL,
        deepgramUpload = this[Keys.DEEPGRAM_UPLOAD] ?: false,
        sonioxSttModel = this[Keys.SONIOX_STT_MODEL] ?: Settings.DEFAULT_SONIOX_STT_MODEL,
        sonioxRegion = this[Keys.SONIOX_REGION] ?: Settings.DEFAULT_SONIOX_REGION,
        sonioxLanguages = this[Keys.SONIOX_LANGUAGES] ?: "",
        onDeviceSttModel = this[Keys.ON_DEVICE_STT_MODEL] ?: Settings.DEFAULT_ON_DEVICE_STT_MODEL,
        onDeviceUnloadMinutes = this[Keys.ON_DEVICE_UNLOAD_MINUTES]?.takeIf { it in Settings.ON_DEVICE_UNLOAD_MINUTES }
            ?: Settings.DEFAULT_ON_DEVICE_UNLOAD_MINUTES,
        sttLanguage = this[Keys.STT_LANGUAGE] ?: "",
        sttTimeoutSeconds = this[Keys.STT_TIMEOUT_SECONDS] ?: Settings.DEFAULT_STT_TIMEOUT_SECONDS,
        vadEnabled = this[Keys.VAD_ENABLED] ?: true,
        sttSpeedPercent = this[Keys.STT_SPEED_PERCENT] ?: Settings.DEFAULT_STT_SPEED_PERCENT,
        cleanupEnabled = this[Keys.CLEANUP_ENABLED] ?: false,
        cleanupProviderId = cleanupProviderId,
        cleanupModel = this[Keys.CLEANUP_MODEL] ?: Settings.DEFAULT_CLEANUP_MODEL,
        cleanupReasoningEffort = reasoningEffort(Keys.CLEANUP_REASONING_EFFORT),
        cleanupProviderOptions = this[Keys.CLEANUP_PROVIDER_OPTIONS] ?: "",
        openRouterCleanupModel = this[Keys.OPENROUTER_CLEANUP_MODEL] ?: Settings.DEFAULT_CLEANUP_MODEL,
        openRouterCleanupReasoningEffort = reasoningEffort(Keys.OPENROUTER_CLEANUP_REASONING_EFFORT),
        openRouterCleanupProviderOptions = this[Keys.OPENROUTER_CLEANUP_PROVIDER_OPTIONS] ?: "",
        cleanupTimeoutSeconds = this[Keys.CLEANUP_TIMEOUT_SECONDS] ?: Settings.DEFAULT_CLEANUP_TIMEOUT_SECONDS,
        prompts = prompts,
        activePrompt = prompts.firstOrNull { it.id == activePromptId } ?: prompts.firstOrNull(),
        openAiKeySet = Secret.OPENAI_API_KEY in storedSecrets,
        gatewayKeySet = Secret.GATEWAY_API_KEY in storedSecrets,
        deepgramKeySet = Secret.DEEPGRAM_API_KEY in storedSecrets,
        sonioxKeySet = Secret.SONIOX_API_KEY in storedSecrets,
        openRouterKeySet = Secret.OPENROUTER_API_KEY in storedSecrets,
        customCleanupProviders = customProviders,
        customCleanupKeyIds = customProviders.mapNotNullTo(HashSet()) { p ->
            p.id.takeIf { Secret.customCleanupKey(it) in storedSecrets }
        },
        bubbleSide = this[Keys.BUBBLE_SIDE]?.let(BubbleSide::valueOf) ?: Settings.DEFAULT_BUBBLE_SIDE,
        bubbleYFraction = this[Keys.BUBBLE_Y_FRACTION] ?: Settings.DEFAULT_BUBBLE_Y_FRACTION,
        panelLines = this[Keys.PANEL_LINES] ?: Settings.DEFAULT_PANEL_LINES,
        panelWidthPercent = this[Keys.PANEL_WIDTH_PERCENT] ?: Settings.DEFAULT_PANEL_WIDTH_PERCENT,
    )
}

/** A stored reasoning effort: missing = the default level, "" = model default (null). */
private fun Preferences.reasoningEffort(key: Preferences.Key<String>): String? = when (val effort = this[key]) {
    null -> Settings.DEFAULT_REASONING_EFFORT
    "" -> null
    else -> effort
}
