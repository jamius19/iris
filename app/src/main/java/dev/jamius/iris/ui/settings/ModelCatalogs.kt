/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.settings

import android.util.Log
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jamius.iris.cleanup.CleanupException
import dev.jamius.iris.cleanup.GatewayModel
import dev.jamius.iris.cleanup.vercel.isChatModel
import dev.jamius.iris.stt.SttException
import dev.jamius.iris.stt.vercel.isStreamingTranscriptionModel
import dev.jamius.iris.stt.vercel.isTranscriptionModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The settings UI's model lists, in memory only (refetched with the activity), one load at a time each:
 * Vercel AI Gateway's catalog (Gateway STT model, cleanup model and reasoning level), OpenRouter's (its cleanup model
 * and reasoning level) and OpenAI's transcription models (listed with the user's key). Soniox's is app-level
 * (`SonioxCatalog`). Use on the main thread.
 */
class ModelCatalogs(
    private val scope: CoroutineScope,
    private val gatewayList: suspend () -> List<GatewayModel>,
    private val openRouterList: suspend () -> List<GatewayModel>,
    private val openAiList: suspend () -> List<String>,
) {
    var gatewayModels by mutableStateOf<List<GatewayModel>?>(null)
        private set
    var gatewayLoading by mutableStateOf(false)
        private set
    var gatewayError by mutableStateOf<String?>(null)
        private set

    val chatModelIds by derivedStateOf { gatewayModels?.filter(::isChatModel)?.map { it.id } }
    val sttModelIds by derivedStateOf { gatewayModels?.filter(::isTranscriptionModel)?.map { it.id } }
    val liveSttModelIds by derivedStateOf {
        gatewayModels?.filter { isTranscriptionModel(it) && isStreamingTranscriptionModel(it) }?.map { it.id }
    }

    var openRouterModels by mutableStateOf<List<GatewayModel>?>(null)
        private set
    var openRouterLoading by mutableStateOf(false)
        private set
    var openRouterError by mutableStateOf<String?>(null)
        private set

    val openRouterModelIds by derivedStateOf { openRouterModels?.map { it.id } }

    var openAiModels by mutableStateOf<List<String>?>(null)
        private set
    var openAiLoading by mutableStateOf(false)
        private set
    var openAiError by mutableStateOf<String?>(null)
        private set
    private var openAiJob: Job? = null

    fun loadGateway() {
        if (gatewayLoading) return
        gatewayLoading = true
        gatewayError = null
        scope.launch {
            try {
                gatewayModels = gatewayList()
            } catch (e: CleanupException) {
                gatewayError = e.message
            } finally {
                gatewayLoading = false
            }
        }
    }

    fun loadOpenRouter() {
        if (openRouterLoading) return
        openRouterLoading = true
        openRouterError = null
        scope.launch {
            try {
                openRouterModels = openRouterList()
            } catch (e: CleanupException) {
                openRouterError = e.message
            } finally {
                openRouterLoading = false
            }
        }
    }

    fun loadOpenAi() {
        if (openAiLoading) return
        openAiLoading = true
        openAiError = null
        openAiJob = scope.launch {
            try {
                openAiModels = openAiList()
            } catch (e: SttException) {
                openAiError = e.message
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // E.g. the stored key failed to decrypt. The exception never carries the key.
                Log.w(TAG, "Loading OpenAI models failed", e)
                openAiError = "Couldn't load models"
            } finally {
                // A load cancelled by a key change leaves the state to its replacement.
                if (openAiJob === coroutineContext[Job]) openAiLoading = false
            }
        }
    }

    /**
     * The OpenAI list depends on the key: a key change drops it and cancels a running load (its result is never
     * written), then reloads while OpenAI is selected (without a key that shows the "set your key" error, with no
     * request). With another provider selected, the list loads when OpenAI is selected again.
     */
    fun onOpenAiKeyChanged(openAiSelected: Boolean) {
        openAiJob?.cancel()
        openAiJob = null
        openAiLoading = false
        openAiError = null
        openAiModels = null
        if (openAiSelected) loadOpenAi()
    }
}

private const val TAG = "ModelCatalogs"
