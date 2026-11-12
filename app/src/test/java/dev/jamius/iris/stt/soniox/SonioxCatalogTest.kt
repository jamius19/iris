/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.stt.soniox

import dev.jamius.iris.stt.SttException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonioxCatalogTest {

    private val rt = SonioxModel("stt-rt-v6", "real_time", true)
    private val async = SonioxModel("stt-async-v6", "async", false)

    @Test
    fun reloadLoads() = runTest {
        var fetches = 0
        val catalog = SonioxCatalog(backgroundScope) {
            fetches++
            listOf(async, rt)
        }

        catalog.reload()
        assertTrue(catalog.state.value.loading)
        runCurrent()

        assertEquals(SonioxCatalog.State(models = listOf(async, rt)), catalog.state.value)
        assertEquals(1, fetches)
    }

    @Test
    fun loadedEntryDoesNotFetchAgain() = runTest {
        var fetches = 0
        val catalog = SonioxCatalog(backgroundScope) {
            fetches++
            listOf(rt)
        }
        catalog.reload()
        runCurrent()

        assertEquals(rt, catalog.entry("stt-rt-v6"))
        assertNull(catalog.entry("typed-model"))
        assertEquals(1, fetches)
    }

    @Test
    fun unloadedEntryFetchesOnce() = runTest {
        var fetches = 0
        val catalog = SonioxCatalog(backgroundScope) {
            fetches++
            delay(100)
            listOf(rt)
        }

        assertEquals(rt, catalog.entry("stt-rt-v6"))
        assertEquals(1, fetches)
        assertEquals(rt, catalog.entry("stt-rt-v6"))
        assertEquals(1, fetches)
    }

    @Test
    fun slowFetchGivesNullAfterTheWaitAndStillFillsTheState() = runTest {
        val catalog = SonioxCatalog(backgroundScope) {
            delay(8_000)
            listOf(rt)
        }

        val entry = async { catalog.entry("stt-rt-v6") }
        advanceTimeBy(4_999)
        assertFalse(entry.isCompleted)
        advanceTimeBy(2)
        assertTrue(entry.isCompleted)
        assertNull(entry.await())
        assertTrue(catalog.state.value.loading)

        advanceTimeBy(3_000)
        assertEquals(SonioxCatalog.State(models = listOf(rt)), catalog.state.value)
    }

    @Test
    fun failureSetsTheError() = runTest {
        var error: Exception = SttException("Set your Soniox API key to load models")
        val catalog = SonioxCatalog(backgroundScope) { throw error }

        catalog.reload()
        runCurrent()
        assertEquals(
            SonioxCatalog.State(error = "Set your Soniox API key to load models"),
            catalog.state.value
        )

        error = IllegalStateException("decrypt failed")
        catalog.refresh()
        runCurrent()
        assertEquals(SonioxCatalog.State(error = "Couldn't load models"), catalog.state.value)
        assertNull(catalog.entry("stt-rt-v6")) // Fails again: null, no wait.
    }

    @Test
    fun reloadMidLoadCancelsItAndRestarts() = runTest {
        val first = CompletableDeferred<List<SonioxModel>>()
        var fetches = 0
        val catalog = SonioxCatalog(backgroundScope) {
            if (++fetches == 1) first.await() else listOf(async)
        }
        catalog.reload()
        runCurrent()

        catalog.reload() // E.g. the key changed.
        first.complete(listOf(rt))
        runCurrent()

        assertEquals(2, fetches)
        assertEquals(SonioxCatalog.State(models = listOf(async)), catalog.state.value)
    }

    @Test
    fun refreshWhileLoadingDoesNotRestart() = runTest {
        var fetches = 0
        val catalog = SonioxCatalog(backgroundScope) {
            fetches++
            delay(100)
            listOf(rt)
        }
        catalog.reload()
        runCurrent()

        catalog.refresh()
        advanceTimeBy(101)

        assertEquals(1, fetches)
        assertEquals(SonioxCatalog.State(models = listOf(rt)), catalog.state.value)
    }

    @Test
    fun refreshKeepsTheLoadedListWhileLoading() = runTest {
        val catalog = SonioxCatalog(backgroundScope) {
            delay(100)
            listOf(rt)
        }
        catalog.reload()
        advanceTimeBy(101)

        catalog.refresh()

        assertEquals(SonioxCatalog.State(models = listOf(rt), loading = true), catalog.state.value)
    }
}
