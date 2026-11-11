/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.jamius.iris.audio.AudioRecorder
import dev.jamius.iris.cleanup.CleanupProvider
import dev.jamius.iris.cleanup.vercel.VercelGatewayCleanup
import dev.jamius.iris.data.settings.Secret
import dev.jamius.iris.data.settings.SecretStore
import dev.jamius.iris.data.settings.SettingsRepository
import dev.jamius.iris.dictation.DictationController
import dev.jamius.iris.dictation.DictationState
import dev.jamius.iris.stt.SpeechToTextProvider
import dev.jamius.iris.stt.openai.OpenAiRealtimeSpeechToText
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

    val speechToText: SpeechToTextProvider = OpenAiRealtimeSpeechToText(
        httpClient,
        apiKey = { secretStore.get(Secret.OPENAI_API_KEY) }
    )

    val cleanup: CleanupProvider = VercelGatewayCleanup(
        httpClient,
        apiKey = { secretStore.get(Secret.GATEWAY_API_KEY) }
    )

    /** One per accessibility service; [scope] is the service's main-thread scope. */
    fun dictationController(
        scope: CoroutineScope,
        onFinalTranscript: suspend (transcript: String, skipCleanup: Boolean) -> DictationState.Done,
    ) = DictationController(
        scope = scope,
        audio = audioRecorder.chunks(),
        stt = speechToText,
        settings = settingsRepository.settings,
        warmUpCleanup = cleanup::warmUp,
        onFinalTranscript = onFinalTranscript,
    )
}
