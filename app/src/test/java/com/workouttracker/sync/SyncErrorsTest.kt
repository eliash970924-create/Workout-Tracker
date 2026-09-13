package com.workouttracker.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The point of these messages is that a permanent setup mistake never reads
 * like a transient blip, so that is what the tests check.
 */
class SyncErrorsTest {

    @Test
    fun `signature mismatch names the signing key and says waiting will not help`() {
        val error = SyncErrors.fromStatusCode(10) // DEVELOPER_ERROR

        assertTrue(error.hint!!.contains("signingReport"))
        assertTrue(error.hint!!.contains("Waiting will not fix this"))
    }

    @Test
    fun `a disabled Drive API passes Google's project detail through`() {
        val detail = "Access Not Configured. Drive API has not been used in project 12345 before."

        val error = SyncErrors.fromHttp("list", 403, detail, "accessNotConfigured")

        assertTrue(error.message.contains("isn't enabled"))
        // The project number is the whole reason to show Google's own text: the
        // API is often enabled in a different project than the OAuth client.
        assertTrue(error.hint!!.contains("12345"))
    }

    @Test
    fun `a disabled Drive API is recognised from the detail text alone`() {
        val error = SyncErrors.fromHttp(
            "list",
            403,
            "Drive API has not been used in project 999 before or it is disabled.",
            null,
        )

        assertTrue(error.message.contains("isn't enabled"))
    }

    @Test
    fun `a revoked token tells the user to reconnect`() {
        val error = SyncErrors.fromHttp("download", 401, "Invalid Credentials", "authError")

        assertTrue(error.hint!!.contains("Connect Google Drive"))
    }

    @Test
    fun `server errors and rate limits read as temporary`() {
        assertTrue(SyncErrors.fromHttp("upload", 503, null, null).hint!!.contains("Temporary"))
        assertTrue(SyncErrors.fromHttp("upload", 429, null, null).hint!!.contains("Temporary"))
        assertTrue(SyncErrors.fromHttp("list", 403, null, "rateLimitExceeded").hint!!.contains("Temporary"))
    }

    @Test
    fun `a missing backup file is reported without a hint`() {
        val error = SyncErrors.fromHttp("download", 404, null, null)

        assertTrue(error.message.contains("no longer exists"))
        assertNull(error.hint)
    }

    @Test
    fun `an unmapped status code still names the number`() {
        val error = SyncErrors.fromStatusCode(9999)

        assertTrue(error.message.contains("9999"))
        assertNotNull(error.hint)
    }

    @Test
    fun `an empty error body does not produce a dangling hint`() {
        val error = SyncErrors.fromHttp("upload", 418, "", null)

        assertNull(error.hint)
    }

    @Test
    fun `a plain network failure is not mistaken for a setup problem`() {
        val error = SyncErrors.fromException(java.io.IOException("connection reset"))

        assertTrue(error.hint!!.contains("try again"))
    }

    @Test
    fun `a mapped Drive failure survives being thrown and caught`() {
        val original = SyncErrors.fromHttp("list", 403, "nope", "accessNotConfigured")

        val recovered = SyncErrors.fromException(DriveHttpException(403, original))

        assertEquals(original, recovered)
    }
}
