/*
* Author: Jamius Siam
* Since: 14/11/2026
*/

package dev.jamius.iris.ui.settings

import android.util.Log
import dev.jamius.iris.stt.await
import dev.jamius.iris.ui.components.UpdateStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Whether release tag [tag] ("v1.2.0") is a higher version than [current] ("1.1.0"), compared number by number (a
 * missing number is 0). False when either isn't dot-separated numbers.
 */
fun isNewerVersion(tag: String, current: String): Boolean {
    val latest = versionNumbers(tag.removePrefix("v")) ?: return false
    val installed = versionNumbers(current) ?: return false
    for (i in 0 until maxOf(latest.size, installed.size)) {
        val difference = latest.getOrElse(i) { 0 } - installed.getOrElse(i) { 0 }
        if (difference != 0) return difference > 0
    }
    return false
}

private fun versionNumbers(version: String): List<Int>? = version.split('.').map { it.toIntOrNull() ?: return null }

/** The `tag_name` of a GitHub release response, or null if [body] isn't one. */
fun parseReleaseTag(body: String): String? = try {
    Json.parseToJsonElement(body).jsonObject["tag_name"]?.jsonPrimitive?.contentOrNull
} catch (e: IllegalArgumentException) {
    null
}

/**
 * Compares [currentVersion] with the repo's latest GitHub release (the newest that is neither a draft nor a
 * pre-release; its tag is `v<versionName>`, see release.yml). No release yet (404) is up to date; a network error or
 * any other response is [UpdateStatus.UNKNOWN].
 */
suspend fun checkForUpdate(
    httpClient: OkHttpClient,
    currentVersion: String,
    url: String = LATEST_RELEASE_URL,
): UpdateStatus {
    val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()
    val (code, body) = try {
        httpClient.newCall(request).await()
    } catch (e: IOException) {
        Log.d(TAG, "Update check failed: $e")
        return UpdateStatus.UNKNOWN
    }
    if (code == 404) return UpdateStatus.UP_TO_DATE
    val tag = if (code in 200..299) parseReleaseTag(body) else null
    return when {
        tag == null -> UpdateStatus.UNKNOWN
        isNewerVersion(tag, currentVersion) -> UpdateStatus.AVAILABLE
        else -> UpdateStatus.UP_TO_DATE
    }
}

private const val TAG = "UpdateCheck"
private const val LATEST_RELEASE_URL = "https://api.github.com/repos/jamius19/Iris/releases/latest"
