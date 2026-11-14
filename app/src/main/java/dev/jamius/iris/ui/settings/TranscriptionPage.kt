/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.jamius.iris.R
import dev.jamius.iris.data.settings.Secret
import dev.jamius.iris.data.settings.Settings
import dev.jamius.iris.data.settings.SettingsRepository
import dev.jamius.iris.data.settings.sttProviderLabel
import dev.jamius.iris.stt.deepgram.DEEPGRAM_MODELS
import dev.jamius.iris.stt.ondevice.OnDeviceEngine
import dev.jamius.iris.stt.ondevice.OnDeviceModelStore
import dev.jamius.iris.stt.ondevice.onDeviceModel
import dev.jamius.iris.stt.openai.hasLiveText
import dev.jamius.iris.stt.openai.isRealtimeTranscriptionModel
import dev.jamius.iris.stt.soniox.SonioxCatalog
import dev.jamius.iris.stt.soniox.SonioxRegion
import dev.jamius.iris.stt.soniox.sonioxRegion
import dev.jamius.iris.stt.soniox.sonioxStreams
import dev.jamius.iris.stt.vercel.gatewaySttLive
import dev.jamius.iris.ui.components.ApiKeyField
import dev.jamius.iris.ui.components.AutosaveTextField
import dev.jamius.iris.ui.components.BrandIcon
import dev.jamius.iris.ui.components.Card
import dev.jamius.iris.ui.components.IconRow
import dev.jamius.iris.ui.components.ModelCombobox
import dev.jamius.iris.ui.components.PageScaffold
import dev.jamius.iris.ui.components.ProviderIconBox
import dev.jamius.iris.ui.components.Saver
import dev.jamius.iris.ui.components.Section
import dev.jamius.iris.ui.components.SelectField
import dev.jamius.iris.ui.components.Separator
import dev.jamius.iris.ui.components.SliderGroup
import dev.jamius.iris.ui.components.SwitchRow

/**
 * Transcription Provider: the provider, its key (and Soniox region), model, language and timeout, then the audio
 * options (Skip Silence, Audio Speed). Each provider's fields are separate calls, so unsaved input never carries over.
 * On device has no key or timeout: its model, download, licence and Unload Model instead (language for Whisper only).
 */
@Composable
fun TranscriptionPage(
    settings: Settings,
    repository: SettingsRepository,
    saver: Saver,
    catalogs: ModelCatalogs,
    sonioxState: SonioxCatalog.State,
    sonioxCatalog: SonioxCatalog,
    onDeviceModels: OnDeviceModelStore,
    onDeviceState: OnDeviceModelStore.State,
    onBack: () -> Unit,
) {
    val provider = when {
        settings.sttOnDevice -> Settings.STT_PROVIDER_ON_DEVICE
        settings.sttGateway -> Settings.STT_PROVIDER_VERCEL_GATEWAY
        settings.sttDeepgram -> Settings.STT_PROVIDER_DEEPGRAM
        settings.sttSoniox -> Settings.STT_PROVIDER_SONIOX
        else -> Settings.STT_PROVIDER_OPENAI
    }
    val sonioxLive =
        settings.sttSoniox && sonioxStreams(settings.sonioxSttModel, sonioxState.models)

    PageScaffold(title = "Transcription Provider", onBack = onBack) {
        Card(verticalPadding = 15.dp) {
            IconRow(R.drawable.lucide_audio_lines, iconTopPadding = 0.dp) {
                SelectField(
                    label = "Transcription Provider",
                    options = STT_PROVIDERS,
                    selected = provider,
                    optionLabel = ::sttProviderLabel,
                    onSave = { repository.setSttProvider(it) },
                    saver = saver,
                    spacing = 9.dp,
                    optionIcon = { ProviderIconBox { BrandIcon(sttProviderIcon(it)) } }
                )
            }
        }

        // On device has no key.
        if (!settings.sttOnDevice) Section("Provider Settings") {
            // The Gateway key is the same one the Post Processing page shows.
            when (provider) {
                Settings.STT_PROVIDER_VERCEL_GATEWAY -> ApiKeyField(
                    isSet = settings.gatewayKeySet,
                    placeholder = KEY_PLACEHOLDER,
                    setHelper = apiKeyHelper(true, "post processing"),
                    unsetHelper = apiKeyHelper(false, "post processing"),
                    onSave = { repository.setApiKey(Secret.GATEWAY_API_KEY, it) },
                    onClear = { repository.clearApiKey(Secret.GATEWAY_API_KEY) },
                    saver = saver
                )

                Settings.STT_PROVIDER_DEEPGRAM -> ApiKeyField(
                    isSet = settings.deepgramKeySet,
                    placeholder = KEY_PLACEHOLDER,
                    setHelper = apiKeyHelper(true),
                    unsetHelper = apiKeyHelper(false),
                    onSave = { repository.setApiKey(Secret.DEEPGRAM_API_KEY, it) },
                    onClear = { repository.clearApiKey(Secret.DEEPGRAM_API_KEY) },
                    saver = saver
                )
                // The catalogue reloads itself on a key change.
                Settings.STT_PROVIDER_SONIOX -> ApiKeyField(
                    isSet = settings.sonioxKeySet,
                    placeholder = KEY_PLACEHOLDER,
                    setHelper = apiKeyHelper(true),
                    unsetHelper = apiKeyHelper(false),
                    onSave = { repository.setApiKey(Secret.SONIOX_API_KEY, it) },
                    onClear = { repository.clearApiKey(Secret.SONIOX_API_KEY) },
                    saver = saver
                )

                else -> ApiKeyField(
                    isSet = settings.openAiKeySet,
                    placeholder = "sk-…",
                    setHelper = apiKeyHelper(true),
                    unsetHelper = apiKeyHelper(false),
                    onSave = {
                        repository.setApiKey(Secret.OPENAI_API_KEY, it)
                        catalogs.onOpenAiKeyChanged(openAiSelected = true)
                    },
                    onClear = {
                        repository.clearApiKey(Secret.OPENAI_API_KEY)
                        catalogs.onOpenAiKeyChanged(openAiSelected = true)
                    },
                    saver = saver
                )
            }
            if (settings.sttSoniox) {
                Separator()
                SelectField(
                    label = "Region",
                    options = SonioxRegion.entries,
                    selected = sonioxRegion(settings.sonioxRegion),
                    optionLabel = { it.label },
                    onSave = { repository.setSonioxRegion(it.id) },
                    saver = saver,
                    helper = "Your Soniox project's region. The API key has to be region-specific as well."
                )
            }
        }

        Section("Model Settings") {
            // The mode always describes the saved model, not the text being typed.
            when (provider) {
                Settings.STT_PROVIDER_ON_DEVICE -> OnDeviceModelSettings(
                    settings,
                    repository,
                    saver,
                    onDeviceModels,
                    onDeviceState
                )

                Settings.STT_PROVIDER_VERCEL_GATEWAY -> ModelCombobox(
                    label = MODEL_LABEL,
                    stored = settings.gatewaySttModel,
                    models = catalogs.sttModelIds,
                    loading = catalogs.gatewayLoading,
                    loadError = catalogs.gatewayError,
                    helper = "Live models show text as you speak; others transcribe after you stop.",
                    onLoad = catalogs::loadGateway,
                    onSave = {
                        repository.setGatewaySttModel(
                            it,
                            gatewaySttLive(it, catalogs.gatewayModels)
                        )
                    },
                    saver = saver,
                    streams = { it in catalogs.liveSttModelIds.orEmpty() }
                )
                // A static list: no refresh.
                Settings.STT_PROVIDER_DEEPGRAM -> ModelCombobox(
                    label = MODEL_LABEL,
                    stored = settings.deepgramSttModel,
                    models = DEEPGRAM_MODELS,
                    loading = false,
                    loadError = null,
                    helper = "Deepgram models stream live by default unless realtime streaming is disabled below.",
                    onLoad = null,
                    onSave = { repository.setDeepgramSttModel(it) },
                    saver = saver,
                    streams = { !settings.deepgramUpload }
                )

                Settings.STT_PROVIDER_SONIOX -> ModelCombobox(
                    label = MODEL_LABEL,
                    stored = settings.sonioxSttModel,
                    models = sonioxState.models?.map { it.id },
                    loading = sonioxState.loading,
                    loadError = sonioxState.error,
                    helper = "Real-time models stream; other models upload after you stop. " +
                            sttModeText(sonioxLive),
                    onLoad = sonioxCatalog::refresh,
                    onSave = { repository.setSonioxSttModel(it) },
                    saver = saver,
                    streams = { sonioxStreams(it, sonioxState.models) }
                )

                else -> ModelCombobox(
                    label = MODEL_LABEL,
                    stored = settings.sttModel,
                    models = catalogs.openAiModels,
                    loading = catalogs.openAiLoading,
                    loadError = catalogs.openAiError,
                    helper = "Realtime models stream; other models upload after you stop.",
                    onLoad = catalogs::loadOpenAi,
                    onSave = { repository.setSttModel(it) },
                    saver = saver,
                    streams = ::isRealtimeTranscriptionModel
                )
            }
            if (settings.sttDeepgram) {
                Separator()
                SwitchRow(
                    title = "Disable Realtime Streaming",
                    subtitle = "Transcribe after you stop, about 44% cheaper.",
                    checked = settings.deepgramUpload,
                    onSave = { repository.setDeepgramUpload(it) },
                    saver = saver
                )
            }
            // On device: only Whisper takes a language.
            val takesLanguage =
                !settings.sttOnDevice || onDeviceModel(settings.onDeviceSttModel).engine == OnDeviceEngine.WHISPER
            if (takesLanguage) Separator()
            // Soniox has its own setting: it takes several languages, which other providers may not.
            if (settings.sttSoniox) {
                AutosaveTextField(
                    label = "Language Codes",
                    stored = settings.sonioxLanguages,
                    onSave = { repository.setSonioxLanguages(it) },
                    saver = saver,
                    placeholder = "en, es, …",
                    helper = "Comma-separated language codes. Leave blank to auto-detect."
                )
            } else if (takesLanguage) {
                AutosaveTextField(
                    label = "Language Code",
                    stored = settings.sttLanguage,
                    onSave = { repository.setSttLanguage(it) },
                    saver = saver,
                    placeholder = "en",
                    helper = if (settings.sttOnDevice) {
                        "Leave blank to auto-detect. Codes Whisper doesn't know auto-detect too."
                    } else {
                        "Setting a language can improve accuracy. Leave blank to auto-detect."
                    }
                )
            }
            // No network to wait for on device, and a native decode can't be stopped.
            if (!settings.sttOnDevice) {
                Separator()
                SliderGroup(
                    label = "Timeout",
                    subtitle = "How long to wait for the final transcript after you stop.",
                    value = settings.sttTimeoutSeconds,
                    range = Settings.STT_TIMEOUT_RANGE,
                    step = 1,
                    valueText = ::secondsText,
                    onSave = { repository.setSttTimeoutSeconds(it) },
                    saver = saver
                )
            } else {
                Separator()
                SelectField(
                    label = "Unload Model",
                    options = Settings.ON_DEVICE_UNLOAD_MINUTES,
                    selected = settings.onDeviceUnloadMinutes,
                    optionLabel = ::onDeviceUnloadLabel,
                    onSave = { repository.setOnDeviceUnloadMinutes(it) },
                    saver = saver,
                    helper = UNLOAD_HELPER
                )
            }
        }

        Section("Audio") {
            SwitchRow(
                title = "Skip Silence",
                subtitle = skipSilenceSubtitle(
                    if (settings.sttOnDevice) onDeviceModel(settings.onDeviceSttModel) else null
                ),
                checked = settings.vadEnabled,
                onSave = { repository.setVadEnabled(it) },
                saver = saver
            )
            // Soniox real-time bills wall-clock stream time: a speed-up saves nothing, so it's never applied.
            if (!sonioxLive) {
                Separator()
                SliderGroup(
                    label = "Audio Speed",
                    subtitle = if (settings.sttOnDevice) {
                        "Speeds up the audio (pitch kept), so it transcribes faster."
                    } else {
                        "Speeds up the audio sent (pitch kept), so less is billed."
                    },
                    value = settings.sttSpeedPercent,
                    range = Settings.STT_SPEED_PERCENT_RANGE,
                    step = 10,
                    valueText = ::speedText,
                    onSave = { repository.setSttSpeedPercent(it) },
                    saver = saver,
                    helper = if (settings.sttOnDevice) {
                        "Higher speeds can lower accuracy."
                    } else {
                        "Higher speeds can lower accuracy; services with a per-request minimum may not save."
                    }
                )
            }
        }
    }
}

private const val KEY_PLACEHOLDER = "Paste your API key"
private const val MODEL_LABEL = "Speech-to-text Model"
private const val UNLOAD_HELPER = "Frees the model's memory if you haven't dictated for this long.\n" +
    "8 GB+ RAM: Never or 15 minutes+.\n" +
    "6 GB: 5–10 minutes.\n" +
    "4 GB or less: Immediately or 2 minutes."

/** A transcription provider's brand mark in the provider menu. */
@DrawableRes
private fun sttProviderIcon(id: String): Int = when (id) {
    Settings.STT_PROVIDER_ON_DEVICE -> R.drawable.lucide_smartphone
    Settings.STT_PROVIDER_VERCEL_GATEWAY -> R.drawable.provider_vercel
    Settings.STT_PROVIDER_DEEPGRAM -> R.drawable.provider_deepgram
    Settings.STT_PROVIDER_SONIOX -> R.drawable.provider_soniox
    else -> R.drawable.provider_openai
}
