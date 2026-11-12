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
import dev.jamius.iris.cleanup.vercel.VercelGatewayCleanup
import dev.jamius.iris.data.settings.Secret
import dev.jamius.iris.data.settings.SecretStore
import dev.jamius.iris.data.settings.Settings
import dev.jamius.iris.data.settings.SettingsRepository
import dev.jamius.iris.dictation.DictationController
import dev.jamius.iris.dictation.DictationState
import dev.jamius.iris.stt.SpeechToTextProvider
import dev.jamius.iris.stt.openai.OpenAiFileTranscription
import dev.jamius.iris.stt.openai.OpenAiRealtimeSpeechToText
import dev.jamius.iris.stt.openai.isRealtimeTranscriptionModel
import dev.jamius.iris.stt.openai.listOpenAiTranscriptionModels
import dev.jamius.iris.stt.vercel.VercelGatewayFileTranscription
import dev.jamius.iris.stt.vercel.VercelGatewaySpeechToText
import kotlinx.coroutines.CoroutineScope
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

    /**
     * The speech-to-text provider [settings] select: the model picks streaming or upload after stop (OpenAI: the
     * Realtime models stream; Gateway: the catalog's tag, stored at save).
     */
    fun speechToText(settings: Settings): SpeechToTextProvider = when {
        settings.sttGateway -> if (settings.gatewaySttLive) gatewayStt else gatewayFileStt
        isRealtimeTranscriptionModel(settings.sttModel) -> openAiStt
        else -> openAiFileStt
    }

    /** The OpenAI transcription models the stored key can use, for the settings' model menu. */
    suspend fun openAiTranscriptionModels(): List<String> =
        listOpenAiTranscriptionModels(httpClient, secretStore.get(Secret.OPENAI_API_KEY))

    val cleanup: CleanupProvider = VercelGatewayCleanup(
        httpClient,
        apiKey = { secretStore.get(Secret.GATEWAY_API_KEY) }
    )

    /** One per accessibility service; [scope] is the service's main-thread scope. */
    fun dictationController(
        scope: CoroutineScope,
        onFinalTranscript: suspend (transcript: String) -> DictationState.Done,
    ) = DictationController(
        scope = scope,
        audio = audioRecorder::chunks,
        stt = ::speechToText,
        settings = settingsRepository.settings,
        warmUpCleanup = cleanup::warmUp,
        onFinalTranscript = onFinalTranscript,
        vadModel = sileroVad::newStream,
    )
}
