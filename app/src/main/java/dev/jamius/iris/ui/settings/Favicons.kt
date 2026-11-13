/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.settings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * The domain whose favicon stands for [baseUrl]: its registrable domain (api.groq.com -> groq.com); null if
 * [baseUrl] isn't a URL or its host has no registrable domain (an IP, localhost, an internal name), so no fetch.
 * [topPrivateDomain] is injectable for JVM tests (OkHttp's Android public suffix list loads from app assets).
 */
fun faviconDomain(baseUrl: String, topPrivateDomain: (HttpUrl) -> String? = { it.topPrivateDomain() }): String? {
    val url = baseUrl.trim().toHttpUrlOrNull() ?: return null
    return topPrivateDomain(url)
}

/**
 * Custom providers' favicons from Google's favicon service, cached as `<domain>.png` in [dir] (the cache dir: may be
 * evicted, then refetched) and in memory. Definitive failures (a non-2xx response, undecodable bytes) are cached too,
 * so a bad domain isn't refetched in a loop; network errors aren't, so they retry on the next load. Main thread.
 */
class Favicons(private val httpClient: OkHttpClient, private val dir: File) {

    private val memory = mutableMapOf<String, Bitmap?>()

    /** The favicon for [baseUrl], or null (no domain, network or decode failure). */
    suspend fun load(baseUrl: String): Bitmap? {
        val domain = withContext(Dispatchers.IO) { faviconDomain(baseUrl) } ?: return null
        if (domain in memory) return memory[domain]
        return try {
            withContext(Dispatchers.IO) { fetch(domain) }.also { memory[domain] = it }
        } catch (e: IOException) {
            Log.d(TAG, "Favicon for $domain failed: $e")
            null
        }
    }

    /** The decoded favicon, or null for a definitive failure; throws [IOException] on a network error. */
    private fun fetch(domain: String): Bitmap? {
        val file = File(dir, "$domain.png")
        if (file.exists()) {
            val bytes = file.readBytes()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it }
            file.delete()
        }
        val request = Request.Builder().url("$FAVICON_URL?domain=$domain&sz=64").build()
        // Only a 2xx counts: an unknown domain gets a 404 with a generic globe.
        val bytes = httpClient.newCall(request).execute().use { if (it.isSuccessful) it.body.bytes() else null }
        val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (bytes == null || bitmap == null) {
            Log.d(TAG, "No favicon for $domain")
            return null
        }
        dir.mkdirs()
        val tmp = File(dir, "$domain.tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(file)
        return bitmap
    }

    private companion object {
        const val TAG = "Favicons"
        const val FAVICON_URL = "https://www.google.com/s2/favicons"
    }
}

/** [baseUrl]'s favicon once loaded; null while loading or if there is none. */
@Composable
fun rememberFavicon(baseUrl: String, favicons: Favicons): ImageBitmap? =
    produceState<ImageBitmap?>(null, baseUrl) {
        value = null
        value = favicons.load(baseUrl)?.asImageBitmap()
    }.value
