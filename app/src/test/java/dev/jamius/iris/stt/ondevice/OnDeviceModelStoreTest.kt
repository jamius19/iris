/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.stt.ondevice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
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

    private fun CoroutineScope.store(
        freeBytes: Long = Long.MAX_VALUE,
        beforeDelete: suspend (String) -> Unit = { deleted += it },
    ) = OnDeviceModelStore(
        OkHttpClient(),
        root,
        this,
        beforeDelete = beforeDelete,
        freeBytes = { freeBytes },
        models = models,
        url = { m, f -> server.url("/${m.id}/${f.name}").toString() }
    )

    private fun ok(bytes: ByteArray) = MockResponse.Builder().body(Buffer().write(bytes)).build()

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

    private fun tmpFiles() = dir.listFiles { f -> f.name.endsWith(".tmp") }.orEmpty().toList()

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
        server.enqueue(
            MockResponse.Builder()
                .body(Buffer().write(encoderBytes))
                .throttleBody(500, 200, TimeUnit.MILLISECONDS)
                .build()
        )
        val store = store()
        store.download("m")
        val tmp = File(dir, "encoder.ort.tmp")
        withTimeout(5_000) {
            while (tmp.length() == 0L) delay(10)
        }

        store.cancel()
        val state = withTimeout(5_000) { store.state.first { it.downloading == null } }

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

        store.delete("m")
        withTimeout(5_000) {
            while (dir.exists()) delay(10)
        }

        assertEquals(listOf("m"), deleted)
        assertFalse(store.isDownloaded("m"))
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
        assertFalse(dir.exists())
        assertEquals(3, server.requestCount)
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
