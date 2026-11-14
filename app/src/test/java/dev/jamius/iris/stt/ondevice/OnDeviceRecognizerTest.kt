/*
* Author: Jamius Siam
* Since: 14/11/2026
*/

package dev.jamius.iris.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnDeviceRecognizerTest {

    @Test
    fun neverReleases() {
        assertNull(idleReleaseWaitMs(-1, idleSince = 0, lastUse = 0, now = NOW))
    }

    @Test
    fun immediatelyReleasesNow() {
        assertEquals(0L, idleReleaseWaitMs(0, idleSince = NOW, lastUse = NOW - MIN, now = NOW))
    }

    @Test
    fun countsFromWhenItBecameIdle() {
        assertEquals(4 * MIN, idleReleaseWaitMs(5, idleSince = NOW - MIN, lastUse = NOW - 10 * MIN, now = NOW))
    }

    @Test
    fun aLaterUseCountsFromThatUse() {
        assertEquals(4 * MIN, idleReleaseWaitMs(5, idleSince = NOW - 4 * MIN, lastUse = NOW - MIN, now = NOW))
    }

    @Test
    fun aShorterDelayThanTheIdleTimeReleasesNow() {
        assertEquals(-2 * MIN, idleReleaseWaitMs(2, idleSince = NOW - 4 * MIN, lastUse = 0, now = NOW))
    }

    private companion object {
        const val MIN = 60_000L
        const val NOW = 100 * MIN
    }
}
