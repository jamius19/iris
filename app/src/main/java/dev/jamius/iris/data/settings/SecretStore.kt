/*
* Author: Jamius Siam
* Since: 11/11/2026
*/

package dev.jamius.iris.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class Secret(val prefKey: Preferences.Key<String>) {
    OPENAI_API_KEY(stringPreferencesKey("openai_api_key")),
    GATEWAY_API_KEY(stringPreferencesKey("gateway_api_key")),
    DEEPGRAM_API_KEY(stringPreferencesKey("deepgram_api_key")),
    SONIOX_API_KEY(stringPreferencesKey("soniox_api_key")),
    OPENROUTER_API_KEY(stringPreferencesKey("openrouter_api_key")),
}

/**
 * Stores secrets encrypted with an Android Keystore AES/GCM key. Each value is persisted as
 * Base64(IV || ciphertext). The backing DataStore file is excluded from backup, since the Keystore key
 * does not survive a restore.
 */
class SecretStore(private val dataStore: DataStore<Preferences>) {

    val storedSecrets: Flow<Set<Secret>> = dataStore.data.map { prefs ->
        Secret.entries.filter { prefs.contains(it.prefKey) }.toSet()
    }

    /** Loaded once; `lazy` is synchronized, so get-or-generate can't race. */
    private val key: SecretKey by lazy {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as SecretKey?)?.let { return@lazy it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        generator.generateKey()
    }

    /**
     * [secret]'s stored ciphertext, never decrypted: null when cleared, and a new value on every [put] (fresh IV),
     * even of the same key. Tells when a key was saved, replaced or cleared (drives the Soniox catalogue reload).
     */
    fun changes(secret: Secret): Flow<String?> = dataStore.data.map { it[secret.prefKey] }.distinctUntilChanged()

    suspend fun get(secret: Secret): String? = dataStore.data.first()[secret.prefKey]?.let { decrypt(it) }

    suspend fun put(secret: Secret, value: String) {
        val encrypted = encrypt(value)
        dataStore.edit { it[secret.prefKey] = encrypted }
    }

    suspend fun clear(secret: Secret) {
        dataStore.edit { it.remove(secret.prefKey) }
    }

    private suspend fun encrypt(plain: String): String = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val payload = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.getEncoder().encodeToString(payload)
    }

    private suspend fun decrypt(encoded: String): String = withContext(Dispatchers.IO) {
        val payload = Base64.getDecoder().decode(encoded)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(TAG_LENGTH_BITS, payload, 0, IV_LENGTH_BYTES)
        )
        val plain = cipher.doFinal(payload, IV_LENGTH_BYTES, payload.size - IV_LENGTH_BYTES)
        String(plain, Charsets.UTF_8)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "iris_secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH_BYTES = 12
        const val TAG_LENGTH_BITS = 128
    }
}
