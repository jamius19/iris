/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.stt.ondevice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class OnDeviceModelStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private val encoderBytes = Random(1).nextBytes(3_000)
    private val decoderBytes = Random(2).nextBytes(2_000)
    private val tokensBytes = Random(3).nextBytes(100)

    private fun file(name: String, bytes: ByteArray) = ModelFile(name, bytes.size.toLong(), sha256(bytes))

    private fun model(id: String) = OnDeviceModel(
        id = id,
        label = id,
        languages = "English",
        engine = OnDeviceEngine.MOONSHINE_V2,
        repo = "owner/repo",
        commit = "0".repeat(40),
        encoder = file("encoder.ort", encoderBytes),
        decoder = file("decoder.ort", decoderBytes),
        joiner = null,
        tokens = file("tokens.txt", tokensBytes),
        licence = "MIT",
        licenceUrl = "https://example.com"
    )

    private val models = listOf(model("m"), model("n"))
    private val deleted = mutableListOf<String>()
    private val root get() = File(folder.root, "models")
    private val dir get() = File(root, "m")

    /**
     * A store whose initial scan has ended: the scan runs on another thread, so a late result could otherwise land
     * after the test's download or delete (e.g. re-add a model the test just deleted).
     */
    private suspend fun CoroutineScope.store(
        freeBytes: Long = Long.MAX_VALUE,
        beforeDelete: suspend (String) -> Unit = { deleted += it },
    ): OnDeviceModelStore {
        val store = OnDeviceModelStore(
            OkHttpClient(),
            root,
            this,
            beforeDelete = beforeDelete,
            freeBytes = { freeBytes },
            models = models,
            url = { m, f -> server.url("/${m.id}/${f.name}").toString() }
        )
        this.coroutineContext.job.children.forEach { it.join() } // The store's only coroutine so far: the scan.
        return store
    }

    private fun ok(bytes: ByteArray) = MockResponse.Builder().body(Buffer().write(bytes)).build()

    /** Sends [bytes] up to [gate], then the rest only once [release] opens. */
    private class GatedBody(
        private val bytes: ByteArray,
        private val gate: Int,
        private val release: CountDownLatch,
    ) : MockResponseBody {
        override val contentLength = bytes.size.toLong()

        override fun writeTo(sink: BufferedSink) {
            sink.write(bytes, 0, gate).flush()
            release.await(10, TimeUnit.SECONDS)
            sink.write(bytes, gate, bytes.size - gate)
        }
    }

    /** Downloads [id] and waits until it ends. */
    private suspend fun OnDeviceModelStore.downloadAndWait(id: String = "m"): OnDeviceModelStore.State {
        download(id)
        return withTimeout(10_000) { state.first { it.downloading == null } }
    }

    private fun assertComplete() {
        assertArrayEquals(encoderBytes, File(dir, "encoder.ort").readBytes())
        assertArrayEquals(decoderBytes, File(dir, "decoder.ort").readBytes())
        assertArrayEquals(tokensBytes, File(dir, "tokens.txt").readBytes())
        assertTrue(tmpFiles().isEmpty())
    }

    /**
     * The model's files the store writes ([partial] only: `<name>.tmp`) that exist. Only these names: on Windows an
     * on-access scanner can briefly add its own file to the directory (seen: `ENCODER.ORT.TMP.tmp`, 0 bytes).
     */
    private fun modelFiles(partial: Boolean = false) = models[0].files
        .flatMap { f -> listOfNotNull(File(dir, f.name + ".tmp"), File(dir, f.name).takeUnless { partial }) }
        .filter { it.exists() }

    private fun tmpFiles() = modelFiles(partial = true)

    private fun enqueueDecoderAndTokens() {
        server.enqueue(ok(decoderBytes))
        server.enqueue(ok(tokensBytes))
    }

    @Test
    fun freshDownloadVerifiesAndRenamesEveryFile() = runBlocking {
        server.enqueue(ok(encoderBytes))
        enqueueDecoderAndTokens()
        val store = store()

        val state = store.downloadAndWait()

        assertNull(state.error)
        assertTrue(store.isDownloaded("m"))
        assertFalse(store.isDownloaded("n"))
        assertComplete()
        assertEquals("/m/encoder.ort", server.takeRequest().url.encodedPath)
        assertNull(server.takeRequest().headers["Range"])
    }

    @Test
    fun resumeRequestsTheRestAndAppendsA206() = runBlocking {
        dir.mkdirs()
        File(dir, "encoder.ort.tmp").writeBytes(encoderBytes.copyOf(1_000))
        server.enqueue(
            MockResponse.Builder()
                .code(206)
                .addHeader("Content-Range", "bytes 1000-2999/3000")
                .body(Buffer().write(encoderBytes.copyOfRange(1_000, 3_000)))
                .build()
        )
        enqueueDecoderAndTokens()

        val state = store().downloadAndWait()

        assertNull(state.error)
        assertEquals("bytes=1000-", server.takeRequest().headers["Range"])
        assertComplete()
    }

    @Test
    fun partialContentAtAnotherStartRestartsTheFileFromZero() = runBlocking {
        dir.mkdirs()
        File(dir, "encoder.ort.tmp").writeBytes(encoderBytes.copyOf(1_000))
        server.enqueue(
            MockResponse.Builder()
                .code(206)
                .addHeader("Content-Range", "bytes 500-2999/3000")
                .body(Buffer().write(encoderBytes.copyOfRange(500, 3_000)))
                .build()
        )
        server.enqueue(ok(encoderBytes))
        enqueueDecoderAndTokens()

        val state = store().downloadAndWait()

        assertNull(state.error)
        assertEquals("bytes=1000-", server.takeRequest().headers["Range"])
        assertNull(server.takeRequest().headers["Range"])
        assertComplete()
    }

    @Test
    fun startingADownloadDeletesOnlyOtherModelsPartialFiles() = runBlocking {
        dir.mkdirs()
        File(dir, "encoder.ort.tmp").writeBytes(encoderBytes.copyOf(1_000))
        val other = File(root, "n")
        other.mkdirs()
        File(other, "encoder.ort.tmp").writeBytes(ByteArray(500))
        File(other, "tokens.txt").writeBytes(tokensBytes)
        server.enqueue(
            MockResponse.Builder()
                .code(206)
                .addHeader("Content-Range", "bytes 1000-2999/3000")
                .body(Buffer().write(encoderBytes.copyOfRange(1_000, 3_000)))
                .build()
        )
        enqueueDecoderAndTokens()

        val state = store().downloadAndWait()

        assertNull(state.error)
        assertEquals("bytes=1000-", server.takeRequest().headers["Range"]) // Its own partial was resumed.
        assertComplete()
        assertFalse(File(other, "encoder.ort.tmp").exists())
        assertTrue(File(other, "tokens.txt").exists())
    }

    @Test
    fun ignoredRangeRestartsTheFile() = runBlocking {
        dir.mkdirs()
        File(dir, "encoder.ort.tmp").writeBytes(ByteArray(1_000) { 7 })
        server.enqueue(ok(encoderBytes))
        enqueueDecoderAndTokens()

        val state = store().downloadAndWait()

        assertNull(state.error)
        assertEquals("bytes=1000-", server.takeRequest().headers["Range"])
        assertComplete()
    }

    @Test
    fun rangeNotSatisfiableWithACompleteTmpIsVerified() = runBlocking {
        dir.mkdirs()
        File(dir, "encoder.ort.tmp").writeBytes(encoderBytes)
        server.enqueue(MockResponse.Builder().code(416).build())
        enqueueDecoderAndTokens()

        val state = store().downloadAndWait()

        assertNull(state.error)
        assertEquals("bytes=3000-", server.takeRequest().headers["Range"])
        assertComplete()
    }

    @Test
    fun hashMismatchFailsAndKeepsNothing() = runBlocking {
        server.enqueue(ok(ByteArray(3_000)))
        val store = store()

        val state = store.downloadAndWait()

        assertEquals(OnDeviceModelStore.Error("m", "Download was corrupted. Try again."), state.error)
        assertFalse(store.isDownloaded("m"))
        assertFalse(File(dir, "encoder.ort").exists())
        assertTrue(tmpFiles().isEmpty())
    }

    @Test
    fun notEnoughStorageFailsWithoutARequest() = runBlocking {
        val state = store(freeBytes = 1_000_000).downloadAndWait()

        assertEquals(OnDeviceModelStore.Error("m", "Not enough storage: needs 51 MB free."), state.error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun httpErrorIsShown() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).build())

        val state = store().downloadAndWait()

        assertEquals(OnDeviceModelStore.Error("m", "Download failed (HTTP 404)."), state.error)
    }

    @Test
    fun bodyCutOffIsANetworkErrorAndKeepsTheTmp() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body(Buffer().write(encoderBytes))
                .onResponseBody(SocketEffect.ShutdownConnection)
                .build()
        )

        val state = store().downloadAndWait()

        assertEquals(
            OnDeviceModelStore.Error("m", "Download failed (network error). Tap Download to resume."),
            state.error
        )
        assertTrue(File(dir, "encoder.ort.tmp").exists())
    }

    @Test
    fun cancelDeletesTheTmpWithoutAnError() = runBlocking {
        val release = CountDownLatch(1)
        server.enqueue(MockResponse.Builder().body(GatedBody(encoderBytes, 1_000, release)).build())
        val store = store()
        store.download("m")
        val tmp = File(dir, "encoder.ort.tmp")
        // The rest of the body waits for the gate, so the download stays mid-file until the cancel.
        withTimeout(10_000) {
            while (tmp.length() < 1_000L) delay(10)
        }

        store.cancel()
        val state = withTimeout(10_000) { store.state.first { it.downloading == null } }
        release.countDown()

        assertNull(state.error)
        assertFalse(store.isDownloaded("m"))
        assertTrue(tmpFiles().isEmpty())
    }

    @Test
    fun scanMarksOnlyCompleteModels() = runBlocking {
        dir.mkdirs()
        File(dir, "encoder.ort").writeBytes(encoderBytes)
        File(dir, "decoder.ort").writeBytes(decoderBytes)
        File(dir, "tokens.txt").writeBytes(tokensBytes)
        File(root, "n").mkdirs()
        File(root, "n/encoder.ort").writeBytes(encoderBytes)

        val store = store()
        val state = withTimeout(5_000) { store.state.first { it.downloaded.isNotEmpty() } }

        assertEquals(setOf("m"), state.downloaded)
    }

    @Test
    fun deleteReleasesTheModelThenRemovesItsFiles() = runBlocking {
        server.enqueue(ok(encoderBytes))
        enqueueDecoderAndTokens()
        val store = store()
        store.downloadAndWait()

        store.delete("m") // Sets deleting at once, so the wait below can't see a stale null.
        withTimeout(10_000) { store.state.first { it.deleting == null } }

        assertEquals(listOf("m"), deleted)
        assertFalse(store.isDownloaded("m"))
        assertTrue(modelFiles().isEmpty())
    }

    @Test
    fun downloadIsIgnoredWhileTheModelIsDeleting() = runBlocking {
        server.enqueue(ok(encoderBytes))
        enqueueDecoderAndTokens()
        val released = CompletableDeferred<Unit>()
        val store = store(beforeDelete = { released.await() })
        store.downloadAndWait()

        store.delete("m")
        // The delete waits for the recognizer; the files are still there.
        withTimeout(5_000) { store.state.first { "m" !in it.downloaded } }
        assertEquals("m", store.state.value.deleting)
        store.download("m")
        assertNull(store.state.value.downloading)

        released.complete(Unit)
        val state = withTimeout(5_000) { store.state.first { it.deleting == null } }

        assertFalse("m" in state.downloaded)
        assertTrue(modelFiles().isEmpty())
        assertEquals(3, server.requestCount)
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
