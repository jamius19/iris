/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.components

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Runs settings writes on [scope] (the app scope), so a write started on leaving a page, ON_STOP or rotation
 * outlives the activity. A failed write (e.g. a Keystore error in `SecretStore.put`) is logged without the value and
 * reported to `onResult` as a user-readable message; success reports null.
 */
class Saver(private val scope: CoroutineScope) {

    fun save(onResult: (String?) -> Unit = {}, errorMessage: String = SAVE_ERROR, block: suspend () -> Unit) {
        scope.launch {
            val error = try {
                block()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Saving failed", e)
                errorMessage
            }
            onResult(error)
        }
    }
}

/** A control's last save error (shown in its helper slot until the next save). */
class SaveState(private val saver: Saver) {
    var error by mutableStateOf<String?>(null)

    fun save(errorMessage: String = SAVE_ERROR, block: suspend () -> Unit) {
        saver.save({ error = it }, errorMessage, block)
    }
}

@Composable
fun rememberSaveState(saver: Saver): SaveState = remember(saver) { SaveState(saver) }

const val SAVE_ERROR = "Couldn't save. Try again."
const val API_KEY_SAVE_ERROR = "Couldn't save the API key. Try again."
const val API_KEY_DELETE_ERROR = "Couldn't delete the API key. Try again."

private const val TAG = "Saver"
