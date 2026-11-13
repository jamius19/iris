/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SecretStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // File-backed, like the app's. One write per test: DataStore's File.renameTo can't replace an existing file on a
    // Windows host. Prefs are written directly: storedSecrets never decrypts, so no Keystore is needed.
    private val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
        folder.root.resolve("secrets.preferences_pb")
    }

    private val store = SecretStore(dataStore)

    private val builtIns = setOf(
        Secret.OPENAI_API_KEY,
        Secret.GATEWAY_API_KEY,
        Secret.DEEPGRAM_API_KEY,
        Secret.SONIOX_API_KEY,
        Secret.OPENROUTER_API_KEY,
    )

    @After
    fun tearDown() = scope.cancel()

    private suspend fun store(secrets: Set<Secret>) =
        dataStore.edit { prefs -> secrets.forEach { prefs[it.prefKey] = "ciphertext" } }

    @Test
    fun storedSecretsReportsEveryStoredPref() = runBlocking {
        assertEquals(emptySet<Secret>(), store.storedSecrets.first())

        val all = builtIns + Secret.customCleanupKey("c1")
        store(all)

        assertEquals(all, store.storedSecrets.first())
    }

    @Test
    fun storedSecretsLeavesOutAbsentOnes() = runBlocking {
        val some = setOf(Secret.GATEWAY_API_KEY, Secret.customCleanupKey("c2"))
        store(some)

        val stored = store.storedSecrets.first()
        assertEquals(some, stored)
        (builtIns - Secret.GATEWAY_API_KEY).forEach { assertFalse(it in stored) }
        assertFalse(Secret.customCleanupKey("c1") in stored)
    }
}
