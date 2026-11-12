/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.stt.soniox

import android.util.Log
import dev.jamius.iris.stt.SttException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Soniox model catalogue for the whole app: the settings' model menu and, per session, the mode and the
 * strict-hints check (the start frame needs `supports_language_hints_strict`). In memory only; [list] fetches it
 * with the stored key and region. Use on the main thread ([scope]'s).
 */
class SonioxCatalog(private val scope: CoroutineScope, private val list: suspend () -> List<SonioxModel>) {

    data class State(val models: List<SonioxModel>? = null, val loading: Boolean = false, val error: String? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    /** The key or region changed: cancels a running load (its result is never written), drops the list, loads. */
    fun reload() {
        job?.cancel()
        job = null
        _state.value = State()
        refresh()
    }

    /** Loads unless a load is running; a loaded list stays shown meanwhile. */
    fun refresh() {
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true, error = null)
        // Started once assigned, so the check in `finally` sees it even if the load ends without suspending.
        val load = scope.launch(start = CoroutineStart.LAZY) {
            try {
                _state.value = State(models = list())
            } catch (e: SttException) {
                _state.value = _state.value.copy(error = e.message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // E.g. the stored key failed to decrypt. The exception never carries the key.
                Log.w(TAG, "Loading Soniox models failed", e)
                _state.value = _state.value.copy(error = "Couldn't load models")
            } finally {
                // A load cancelled by a reload leaves the state to its replacement.
                if (job === coroutineContext[Job]) _state.value = _state.value.copy(loading = false)
            }
        }
        job = load
        load.start()
    }

    /**
     * [model]'s entry, or null if the catalogue doesn't list it (no refetch once loaded). Not loaded: loads it and
     * waits up to [CATALOG_WAIT_MS]; past that (or on failure) null, while the load keeps running.
     */
    suspend fun entry(model: String): SonioxModel? {
        val models = _state.value.models ?: run {
            refresh()
            withTimeoutOrNull(CATALOG_WAIT_MS) { state.first { !it.loading }.models }
        }
        return models?.firstOrNull { it.id == model }
    }

    private companion object {
        const val TAG = "SonioxCatalog"

        /** The session's audio waits in the controller's buffers meanwhile (>= 6.4 s of 50 ms chunks). */
        const val CATALOG_WAIT_MS = 5_000L
    }
}
