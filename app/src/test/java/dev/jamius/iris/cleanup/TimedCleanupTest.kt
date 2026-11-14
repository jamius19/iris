/*
* Author: Jamius Siam
* Since: 14/11/2026
*/

package dev.jamius.iris.cleanup

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimedCleanupTest {

    private val recorded = mutableListOf<Long>()

    private suspend fun TestScope.timed(call: suspend () -> String) =
        timedCleanup(5_000, { currentTime }, { recorded += it }, call)

    @Test
    fun successReturnsTheTextAndRecordsItsTime() = runTest {
        val text = timed {
            delay(1_200)
            "Cleaned"
        }

        assertEquals("Cleaned", text)
        assertEquals(listOf(1_200L), recorded)
    }

    @Test
    fun timeoutIsNullAndRecordsNothing() = runTest {
        val text = timed {
            delay(6_000)
            "Too late"
        }

        assertNull(text)
        assertEquals(5_000L, currentTime)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun failurePropagatesAndRecordsNothing() = runTest {
        val error = runCatching {
            timed {
                delay(300)
                throw CleanupException("Bad key")
            }
        }.exceptionOrNull()

        assertTrue(error is CleanupException)
        assertEquals("Bad key", error?.message)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun otherFailurePropagatesAndRecordsNothing() = runTest {
        // E.g. parseProviderOptions' IllegalArgumentException: the service's generic "Cleanup failed" path.
        val error = runCatching {
            timed {
                delay(300)
                throw IllegalStateException("Boom")
            }
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(recorded.isEmpty())
    }
}
