/*
* Author: Jamius Siam
* Since: 14/11/2026
*/

package dev.jamius.iris.dictation

import android.content.Context
import android.util.Log
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/** The system's on-device text language detector (TextClassifier); the filler words' fallback evidence. */
class LanguageDetector(context: Context) {
    private val manager = context.getSystemService(TextClassificationManager::class.java)

    /**
     * [text]'s language (ISO 639-1) if the top hypothesis has >= [MIN_LANGUAGE_CONFIDENCE], else null. Never throws;
     * blocking IPC, so it runs on IO and is interrupted when the caller is cancelled (e.g. its timeout).
     */
    suspend fun detect(text: String): String? = try {
        runInterruptible(Dispatchers.IO) {
            val result = manager.textClassifier.detectLanguage(TextLanguage.Request.Builder(text).build())
            if (result.localeHypothesisCount == 0) return@runInterruptible null
            val locale = result.getLocale(0)
            locale.language.takeIf {
                it.isNotEmpty() && result.getConfidenceScore(locale) >= MIN_LANGUAGE_CONFIDENCE
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Language detection failed", e)
        null
    }

    private companion object {
        const val TAG = "LanguageDetector"
    }
}
