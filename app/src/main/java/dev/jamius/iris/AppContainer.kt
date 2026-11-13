/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.jamius.iris.audio.AudioRecorder
import dev.jamius.iris.audio.SileroVad
import dev.jamius.iris.cleanup.CleanupProvider
import dev.jamius.iris.cleanup.GatewayModel
import dev.jamius.iris.cleanup.openrouter.OpenRouterCleanup
import dev.jamius.iris.cleanup.vercel.VercelGatewayCleanup
import dev.jamius.iris.data.settings.Secret
import dev.jamius.iris.data.settings.SecretStore
import dev.jamius.iris.data.settings.Settings
import dev.jamius.iris.data.settings.SettingsRepository
import dev.jamius.iris.dictation.DictationController
import dev.jamius.iris.dictation.DictationState
import dev.jamius.iris.stt.SpeechToTextProvider
import dev.jamius.iris.stt.deepgram.DeepgramFileTranscription
import dev.jamius.iris.stt.deepgram.DeepgramSpeechToText
import dev.jamius.iris.stt.openai.OpenAiFileTranscription
import dev.jamius.iris.stt.openai.OpenAiRealtimeSpeechToText
import dev.jamius.iris.stt.openai.isRealtimeTranscriptionModel
import dev.jamius.iris.stt.openai.listOpenAiTranscriptionModels
import dev.jamius.iris.stt.soniox.SonioxCatalog
import dev.jamius.iris.stt.soniox.SonioxFileTranscription
import dev.jamius.iris.stt.soniox.SonioxRegion
import dev.jamius.iris.stt.soniox.SonioxSpeechToText
import dev.jamius.iris.stt.soniox.listSonioxModels
import dev.jamius.iris.stt.soniox.sonioxRegion
import dev.jamius.iris.stt.soniox.sonioxStreams
import dev.jamius.iris.stt.vercel.VercelGatewayFileTranscription
import dev.jamius.iris.stt.vercel.VercelGatewaySpeechToText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Manual DI: app-wide singletons, created once by [IrisApp]. */
class AppContainer(context: Context) {

    // "secrets" is excluded from backup in res/xml/backup_rules.xml and data_extraction_rules.xml.
    val secretStore = SecretStore(
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("secrets") }
    )

    val settingsRepository = SettingsRepository(
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") },
        secretStore
    )

    private val httpClient = OkHttpClient()

    /** Process lifetime, main thread: app-wide state such as the Soniox catalogue. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val audioRecorder = AudioRecorder(context)

    // Nothing loads until the first session with VAD on.
    private val sileroVad = SileroVad(context)

    private val openAiStt = OpenAiRealtimeSpeechToText(
        httpClient,
        apiKey = { secretStore.get(Secret.OPENAI_API_KEY) }
    )

    private val openAiFileStt = OpenAiFileTranscription(
        httpClient,
        apiKey = { secretStore.get(Secret.OPENAI_API_KEY) }
    )

    // Both share the API key with cleanup.
    private val gatewayStt = VercelGatewaySpeechToText(
        httpClient,
        apiKey = { secretStore.get(Secret.GATEWAY_API_KEY) }
    )

    private val gatewayFileStt = VercelGatewayFileTranscription(
        httpClient,
        apiKey = { secretStore.get(Secret.GATEWAY_API_KEY) }
    )

    private val deepgramStt = DeepgramSpeechToText(
        httpClient,
        apiKey = { secretStore.get(Secret.DEEPGRAM_API_KEY) }
    )

    private val deepgramFileStt = DeepgramFileTranscription(
        httpClient,
        apiKey = { secretStore.get(Secret.DEEPGRAM_API_KEY) }
    )

    /**
     * Soniox's model catalogue for the stored key and region: shared by the settings screen and the sessions (mode
     * and strict-hints check).
     */
    val sonioxCatalog = SonioxCatalog(appScope) {
        listSonioxModels(
            httpClient,
            secretStore.get(Secret.SONIOX_API_KEY),
            sonioxRegion(settingsRepository.settings.first().sonioxRegion).restUrl
        )
    }

    init {
        // Loads at app start, and again whenever the key is saved, replaced or cleared or the region changes. No key:
        // the "set your key" error at once, no request.
        appScope.launch {
            combine(
                secretStore.changes(Secret.SONIOX_API_KEY),
                settingsRepository.settings.map { it.sonioxRegion },
                ::Pair
            ).distinctUntilChanged().collect { sonioxCatalog.reload() }
        }
    }

    // One pair per region: a provider's URLs are fixed.
    private val sonioxStt = SonioxRegion.entries.associateWith {
        SonioxSpeechToText(
            httpClient,
            apiKey = { secretStore.get(Secret.SONIOX_API_KEY) },
            modelInfo = sonioxCatalog::entry,
            url = it.webSocketUrl
        )
    }

    private val sonioxFileStt = SonioxRegion.entries.associateWith {
        SonioxFileTranscription(
            httpClient,
            apiKey = { secretStore.get(Secret.SONIOX_API_KEY) },
            modelInfo = sonioxCatalog::entry,
            baseUrl = it.restUrl
        )
    }

    /**
     * The speech-to-text provider [settings] select: the model picks streaming or upload after stop (OpenAI: the
     * Realtime models stream; Gateway: the catalog's tag, stored at save). Deepgram: every model streams, so its
     * own setting picks the cheaper upload instead. Soniox: the catalogue's `transcription_mode` (id rule `stt-rt-*`
     * until it loads), on the region's domains.
     */
    fun speechToText(settings: Settings): SpeechToTextProvider = when {
        settings.sttSoniox -> sonioxRegion(settings.sonioxRegion).let { region ->
            if (sonioxStreams(settings.sonioxSttModel, sonioxCatalog.state.value.models)) sonioxStt.getValue(region)
            else sonioxFileStt.getValue(region)
        }
        settings.sttDeepgram -> if (settings.deepgramUpload) deepgramFileStt else deepgramStt
        settings.sttGateway -> if (settings.gatewaySttLive) gatewayStt else gatewayFileStt
        isRealtimeTranscriptionModel(settings.sttModel) -> openAiStt
        else -> openAiFileStt
    }

    /** The OpenAI transcription models the stored key can use, for the settings' model menu. */
    suspend fun openAiTranscriptionModels(): List<String> =
        listOpenAiTranscriptionModels(httpClient, secretStore.get(Secret.OPENAI_API_KEY))

    private val gatewayCleanup = VercelGatewayCleanup(
        httpClient,
        apiKey = { secretStore.get(Secret.GATEWAY_API_KEY) }
    )

    private val openRouterCleanup = OpenRouterCleanup(
        httpClient,
        apiKey = { secretStore.get(Secret.OPENROUTER_API_KEY) }
    )

    /** The cleanup provider [settings] select (an unknown id means the Gateway). */
    fun cleanup(settings: Settings): CleanupProvider =
        if (settings.cleanupOpenRouter) openRouterCleanup else gatewayCleanup

    /** Vercel AI Gateway's catalog (cleanup and transcription models), for the settings' model menus. */
    suspend fun gatewayModels(): List<GatewayModel> = gatewayCleanup.listModels()

    /** OpenRouter's structured-output chat models, for the settings' cleanup model menu. */
    suspend fun openRouterModels(): List<GatewayModel> = openRouterCleanup.listModels()

    /** One per accessibility service; [scope] is the service's main-thread scope. */
    fun dictationController(
        scope: CoroutineScope,
        onFinalTranscript: suspend (transcript: String) -> DictationState.Done,
    ) = DictationController(
        scope = scope,
        audio = audioRecorder::chunks,
        stt = ::speechToText,
        settings = settingsRepository.settings,
        warmUpCleanup = { cleanup(it).warmUp() },
        onFinalTranscript = onFinalTranscript,
        vadModel = sileroVad::newStream,
    )
}
