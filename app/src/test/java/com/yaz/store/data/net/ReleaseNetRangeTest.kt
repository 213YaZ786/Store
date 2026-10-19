package com.yaz.store.data.net

// The rules of a ranged read, each one asserted as accepted or refused (the protocol of ReleaseNetTest, itself the update kit's UpdatesTest,
// https://github.com/213YaZ786/Calculator, app/src/test/java/com/yaz/calculator/core/update/UpdatesTest.kt), after RFC 9110 section 14.4
// (Content-Range, https://www.rfc-editor.org/rfc/rfc9110#section-14.4) and section 15.3 (200 and 206).
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNetRangeTest {

    private fun refused(header: String?, start: Long, end: Long, known: Long = -1) {
        val e = assertThrows(NetError::class.java) { ReleaseNet.checkRange(header, start, end, known) }
        assertEquals(NetError.Kind.REFUSED, e.kind)
    }

    @Test
    fun `a content range that names what was asked gives the length`() {
        assertEquals(38_774_502L, ReleaseNet.checkRange("bytes 0-0/38774502", 0, 0, -1))
        assertEquals(38_774_502L, ReleaseNet.checkRange("bytes 38700000-38774501/38774502", 38_700_000, 38_774_501, 38_774_502))
        assertEquals(100L, ReleaseNet.checkRange(" bytes 5-9/100 ", 5, 9, 100))
    }

    @Test
    fun `a content range that differs from what was asked is refused`() {
        refused("bytes 1-1/100", 0, 0)
        refused("bytes 0-1/100", 0, 0)
        refused("bytes 0-0/100", 0, 5)
        // The length changed between two reads: another file.
        refused("bytes 0-0/101", 0, 0, known = 100)
        // Ends at or past the length.
        refused("bytes 0-100/100", 0, 100)
    }

    @Test
    fun `no content range, a wrong unit, a star and noise are refused`() {
        refused(null, 0, 0)
        refused("", 0, 0)
        refused("bytes */100", 0, 0)
        refused("items 0-0/100", 0, 0)
        refused("bytes 0-0/*", 0, 0)
        refused("bytes -0-0/100", 0, 0)
        refused("bytes 0-0/100; x", 0, 0)
        refused("bytes 0-0/1" + "0".repeat(30), 0, 0)
    }

    @Test
    fun `a ranged read accepts 206 only and a plain read 200 only`() {
        assertTrue(ReleaseNet.acceptable(206, ranged = true))
        // The server ignored the range and would send the whole file.
        assertFalse(ReleaseNet.acceptable(200, ranged = true))
        assertTrue(ReleaseNet.acceptable(200, ranged = false))
        assertFalse(ReleaseNet.acceptable(206, ranged = false))
        assertFalse(ReleaseNet.acceptable(416, ranged = true))
        assertFalse(ReleaseNet.acceptable(304, ranged = true))
    }

    @Test
    fun `only the release files of our account are read by ranges`() {
        val ok = "https://github.com/213YaZ786/Calculator/releases/download/v0.3.7/Calculator-0.3.7.apk"
        assertTrue(ReleaseNet.startAllowed(ok))
        for (bad in listOf(
            "https://github.com/someone/Calculator/releases/download/v1/a.apk",
            "http://github.com/213YaZ786/Calculator/releases/download/v1/a.apk",
            "https://release-assets.githubusercontent.com/github-production-release-asset/1/x",
            "https://github.com/213YaZ786/Calculator/releases/download/../a.apk"
        )) {
            assertFalse(bad, ReleaseNet.startAllowed(bad))
            assertThrows(NetError::class.java) { ReleaseNet(java.io.File("/nonexistent")).ranges(bad) }
        }
    }
}
