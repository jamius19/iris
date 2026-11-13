/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.stt.ondevice

import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.coroutines.CoroutineContext

/**
 * Downloads on-device models from Hugging Face into [root] (`noBackupFilesDir/models/<modelId>/`), one at a time.
 * Each file goes to `<name>.tmp`, is checked against its catalogue size and SHA-256, then renamed. A model is
 * downloaded when all its final files exist with their catalogue sizes. Failures keep the `.tmp` files, so the next
 * [download] resumes with a Range request (also after an app restart); [cancel] deletes them.
 *
 * @param scope where downloads run (the app scope: they outlive the settings screen).
 * @param beforeDelete waits until the recognizer has released the model.
 */
class OnDeviceModelStore(
    private val httpClient: OkHttpClient,
    private val root: File,
    private val scope: CoroutineScope,
    private val beforeDelete: suspend (modelId: String) -> Unit,
    private val freeBytes: (File) -> Long = { StatFs(it.path).availableBytes },
    private val models: List<OnDeviceModel> = ON_DEVICE_MODELS,
    private val url: (OnDeviceModel, ModelFile) -> String = { m, f -> m.url(f) },
    private val io: CoroutineContext = Dispatchers.IO,
) {
    /**
     * [downloading]'s progress is [doneBytes] of [totalBytes]; [error] is the last failed download's; [deleting] is
     * the model whose files are being deleted (it can't be downloaded meanwhile).
     */
    data class State(
        val downloaded: Set<String> = emptySet(),
        val downloading: String? = null,
        val deleting: String? = null,
        val doneBytes: Long = 0,
        val totalBytes: Long = 0,
        val error: Error? = null,
    )

    data class Error(val modelId: String, val message: String)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val client = httpClient.newBuilder().readTimeout(30, SECONDS).build()
    private var job: Job? = null

    init {
        // Off main; until it ends (a few ms after app start) no model counts as downloaded.
        scope.launch {
            val found = withContext(io) { models.filter(::isComplete).map { it.id } }
            _state.update { it.copy(downloaded = it.downloaded + found) }
        }
    }

    fun isDownloaded(id: String): Boolean = id in _state.value.downloaded

    fun dir(id: String): File = File(root, id)

    /**
     * Starts downloading (or resuming) [id] and deletes other models' partial files; ignored while any download
     * runs or while [id] is being deleted.
     */
    fun download(id: String) {
        if (_state.value.downloading != null || _state.value.deleting == id) return
        val model = models.first { it.id == id }
        _state.update { it.copy(downloading = id, doneBytes = 0, totalBytes = model.sizeBytes, error = null) }
        job = scope.launch {
            val started = SystemClock.elapsedRealtime()
            val error = try {
                withContext(io) {
                    // An abandoned model's partial download would otherwise use storage until app data is cleared.
                    models.filter { it.id != id }.forEach { other -> deleteTmpFiles(other.id) }
                    try {
                        fetch(model)
                    } catch (e: IOException) {
                        ensureActive() // A cancelled call fails with an IOException too.
                        throw DownloadException(NETWORK_ERROR)
                    }
                }
                null
            } catch (e: CancellationException) {
                // The state too: a cancelled coroutine never resumes after withContext (prompt cancellation).
                withContext(NonCancellable + io) {
                    deleteTmpFiles(id)
                    _state.update { it.copy(downloading = null, doneBytes = 0, totalBytes = 0) }
                }
                throw e
            } catch (e: DownloadException) {
                e.message
            }
            if (error == null) Log.d(TAG, "Downloaded $id in ${(SystemClock.elapsedRealtime() - started) / 1000} s")
            _state.update {
                it.copy(
                    downloaded = if (error == null) it.downloaded + id else it.downloaded,
                    downloading = null,
                    doneBytes = 0,
                    totalBytes = 0,
                    error = error?.let { message -> Error(id, message) }
                )
            }
        }
    }

    /** Stops the running download and deletes its partial files. */
    fun cancel() {
        job?.cancel()
    }

    /** Deletes [id]'s files (cancelling its download), once the recognizer has released it. */
    fun delete(id: String) {
        _state.update { it.copy(deleting = id) }
        scope.launch {
            try {
                if (_state.value.downloading == id) job?.cancelAndJoin()
                _state.update { it.copy(downloaded = it.downloaded - id) }
                beforeDelete(id)
                withContext(io) { dir(id).deleteRecursively() }
            } finally {
                _state.update { if (it.deleting == id) it.copy(deleting = null) else it }
            }
        }
    }

    private fun deleteTmpFiles(id: String) {
        dir(id).listFiles { f -> f.name.endsWith(TMP) }?.forEach { it.delete() }
    }

    private fun isComplete(model: OnDeviceModel): Boolean =
        model.files.all { File(dir(model.id), it.name).let { f -> f.isFile && f.length() == it.sizeBytes } }

    /** Downloads every missing file of [model] (blocking I/O; runs on [io]). */
    private suspend fun fetch(model: OnDeviceModel) {
        val dir = dir(model.id)
        dir.mkdirs()
        val needed = model.files.sumOf { f ->
            val final = File(dir, f.name)
            if (final.length() == f.sizeBytes) 0 else f.sizeBytes - File(dir, f.name + TMP).length()
        }
        val required = needed + FREE_MARGIN_BYTES
        if (freeBytes(root) < required) {
            throw DownloadException("Not enough storage: needs ${(required + 999_999) / 1_000_000} MB free.")
        }
        var done = 0L
        for (file in model.files) {
            val final = File(dir, file.name)
            if (final.exists()) {
                if (final.length() == file.sizeBytes) {
                    done += file.sizeBytes
                    progress(done)
                    continue
                }
                final.delete()
            }
            val tmp = File(dir, file.name + TMP)
            if (tmp.length() > file.sizeBytes) tmp.delete()
            fetchFile(model, file, tmp, done)
            if (!tmp.renameTo(final)) throw DownloadException(STORAGE_ERROR)
            done += file.sizeBytes
        }
    }

    /** Downloads [file] into [tmp] (resuming what it holds) and verifies it; [base] = bytes of the files before it. */
    private suspend fun fetchFile(model: OnDeviceModel, file: ModelFile, tmp: File, base: Long) {
        val digest = MessageDigest.getInstance("SHA-256")
        var retried = false
        while (true) {
            digest.reset()
            val tmpLength = tmp.length()
            val request = Request.Builder()
                .url(url(model, file))
                .apply { if (tmpLength > 0) header("Range", "bytes=$tmpLength-") }
                .build()
            val call = client.newCall(request)
            val restart = call.cancelledWithCoroutine {
                call.execute().use { response ->
                    val append = when (response.code) {
                        206 -> if (rangeStart(response.header("Content-Range")) == tmpLength) {
                            true
                        } else if (!retried) {
                            // Another start: restart this file from 0 (no Range header).
                            tmp.delete()
                            return@cancelledWithCoroutine true
                        } else {
                            throw DownloadException("Download failed (HTTP 206).")
                        }
                        200 -> false
                        416 -> if (tmpLength == file.sizeBytes) {
                            // Complete already: only the check below is left.
                            storage { tmp.inputStream().use { digest.update(it) } }
                            return@cancelledWithCoroutine false
                        } else if (!retried) {
                            tmp.delete()
                            return@cancelledWithCoroutine true
                        } else {
                            throw DownloadException("Download failed (HTTP 416).")
                        }
                        else -> throw DownloadException("Download failed (HTTP ${response.code}).")
                    }
                    if (append) storage { tmp.inputStream().use { digest.update(it) } }
                    var written = if (append) tmpLength else 0L
                    var reported = written
                    progress(base + written) // A resume shows its start at once.
                    val source = response.body.byteStream()
                    storage { FileOutputStream(tmp, append) }.use { out ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val n = source.read(buffer) // A network IOException.
                            if (n == -1) break
                            if (written + n > file.sizeBytes) break // Longer than the file: the checks below decide.
                            digest.update(buffer, 0, n)
                            storage { out.write(buffer, 0, n) }
                            written += n
                            currentCoroutineContext().ensureActive()
                            if (written - reported >= PROGRESS_BYTES) {
                                reported = written
                                progress(base + written)
                            }
                        }
                    }
                    false
                }
            }
            if (!restart) break
            retried = true
        }
        if (tmp.length() != file.sizeBytes || digest.digest().toHex() != file.sha256) {
            tmp.delete()
            throw DownloadException("Download was corrupted. Try again.")
        }
        progress(base + file.sizeBytes)
    }

    private fun progress(doneBytes: Long) {
        _state.update { it.copy(doneBytes = doneBytes) }
    }

    /** The start of a `Content-Range: bytes <start>-<end>/<size>` header, or null. */
    private fun rangeStart(header: String?): Long? =
        header?.substringAfter("bytes ", "")?.substringBefore('-')?.trim()?.toLongOrNull()

    /** Runs [block] (blocking I/O on [this]) and cancels [this] if the coroutine is cancelled meanwhile. */
    private suspend fun <T> Call.cancelledWithCoroutine(block: suspend () -> T): T = coroutineScope {
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                this@cancelledWithCoroutine.cancel()
            }
        }
        try {
            block()
        } finally {
            watcher.cancel()
        }
    }

    /** A user-readable download failure. */
    private class DownloadException(message: String) : Exception(message)

    private companion object {
        const val TAG = "OnDeviceModelStore"
        const val TMP = ".tmp"
        const val NETWORK_ERROR = "Download failed (network error). Tap Download to resume."
        const val STORAGE_ERROR = "Couldn't save the model (storage error)."
        const val FREE_MARGIN_BYTES = 50_000_000L
        const val BUFFER_BYTES = 64 * 1024
        const val PROGRESS_BYTES = 512 * 1024

        /** [block]'s file I/O failures as the storage error (not the network one). */
        inline fun <T> storage(block: () -> T): T = try {
            block()
        } catch (e: IOException) {
            throw DownloadException(STORAGE_ERROR)
        }

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}

/** Feeds all of [input] into this digest. */
private fun MessageDigest.update(input: InputStream) {
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buffer)
        if (n == -1) return
        update(buffer, 0, n)
    }
}
