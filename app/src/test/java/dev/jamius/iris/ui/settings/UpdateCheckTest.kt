/*
* Author: Jamius Siam
* Since: 14/11/2026
*/

package dev.jamius.iris.ui.settings

import dev.jamius.iris.ui.components.UpdateStatus
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UpdateCheckTest {

    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun respond(code: Int, body: String) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun check(current: String = "0.9.0") = runBlocking {
        checkForUpdate(OkHttpClient(), current, server.url("/repos/jamius19/Iris/releases/latest").toString())
    }

    @Test
    fun `a higher tag is newer`() {
        assertTrue(isNewerVersion("v0.9.1", "0.9.0"))
        assertTrue(isNewerVersion("v1.0.0", "0.9.9"))
        assertTrue(isNewerVersion("v0.10.0", "0.9.0"))
        assertTrue(isNewerVersion("0.9.1", "0.9.0"))
        assertTrue(isNewerVersion("v0.9.0.1", "0.9.0"))
    }

    @Test
    fun `the same or a lower tag is not newer`() {
        assertFalse(isNewerVersion("v0.9.0", "0.9.0"))
        assertFalse(isNewerVersion("v0.9", "0.9.0"))
        assertFalse(isNewerVersion("v0.8.9", "0.9.0"))
        assertFalse(isNewerVersion("v0.9.0", "0.10.0"))
    }

    @Test
    fun `a version that isn't numbers is never newer`() {
        assertFalse(isNewerVersion("v1.0.0-beta", "0.9.0"))
        assertFalse(isNewerVersion("latest", "0.9.0"))
        assertFalse(isNewerVersion("v1.0.0", ""))
    }

    @Test
    fun `the tag is read from a release response`() {
        assertEquals("v1.2.0", parseReleaseTag("""{"tag_name":"v1.2.0","name":"Iris 1.2.0"}"""))
        assertNull(parseReleaseTag("""{"name":"Iris 1.2.0"}"""))
        assertNull(parseReleaseTag("""{"tag_name":null}"""))
        assertNull(parseReleaseTag("[]"))
        assertNull(parseReleaseTag("not json"))
    }

    @Test
    fun `a newer release is available`() {
        respond(200, """{"tag_name":"v0.9.1"}""")
        assertEquals(UpdateStatus.AVAILABLE, check())
        assertEquals("/repos/jamius19/Iris/releases/latest", server.takeRequest().target)
    }

    @Test
    fun `the installed release is up to date`() {
        respond(200, """{"tag_name":"v0.9.0"}""")
        assertEquals(UpdateStatus.UP_TO_DATE, check())
    }

    @Test
    fun `no release yet is up to date`() {
        respond(404, """{"message":"Not Found"}""")
        assertEquals(UpdateStatus.UP_TO_DATE, check())
    }

    @Test
    fun `an error or unexpected response is unknown`() {
        respond(403, """{"message":"API rate limit exceeded"}""")
        assertEquals(UpdateStatus.UNKNOWN, check())
        respond(200, "not json")
        assertEquals(UpdateStatus.UNKNOWN, check())
    }

    @Test
    fun `a network error is unknown`() {
        server.close()
        assertEquals(UpdateStatus.UNKNOWN, check())
    }
}
