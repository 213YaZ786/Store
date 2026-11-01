package com.yaz.store.data.catalog

// JUnit 4 (https://github.com/junit-team/junit4/wiki/Assertions) and its TemporaryFolder rule, over a canned conditional read, in the protocol of
// CatalogTest. What RepoFiles must keep of IconStore's former `conditional` is GitHub's conditional request
// (https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api#use-conditional-requests-if-appropriate):
// one request per key and opening, the ETag sent back, 304 keeps the stored file, 200 replaces it, 404 deletes it, a failure keeps it.
import com.yaz.store.data.net.NetError
import com.yaz.store.data.net.ReleaseNet
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RepoFilesTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** A canned server: [answers] are used in order, each a [ReleaseNet.Fresh], null (304) or a NetError; every call is noted. */
    private class Server(vararg answers: Any?) {
        private val queue = ArrayDeque(answers.toList())
        val calls = mutableListOf<Triple<String, Long, String?>>()
        fun fetch(url: String, max: Long, etag: String?): ReleaseNet.Fresh? {
            calls += Triple(url, max, etag)
            return when (val a = queue.removeFirst()) {
                null -> null
                is NetError -> throw a
                else -> a as ReleaseNet.Fresh
            }
        }
    }

    private fun bytes(text: String) = text.toByteArray()
    private fun fresh(text: String, etag: String? = "\"v1\"") = ReleaseNet.Fresh(bytes(text), etag)
    private fun error(kind: NetError.Kind) = NetError(kind, message = kind.name)

    private fun read(files: RepoFiles, seen: MutableSet<String>, key: String = "Calc", max: Long = 100) =
        files.conditional(key, "name-$key", "txt", "https://github.com/213YaZ786/$key/raw/HEAD/x", max, seen)

    @Test
    fun `the first read stores the file and its ETag, a second read of the same opening does not ask`() {
        val dir = folder.newFolder("a")
        val server = Server(fresh("one"))
        val files = RepoFiles(dir, server::fetch)
        val seen = mutableSetOf<String>()
        assertArrayEquals(bytes("one"), read(files, seen))
        assertEquals("\"v1\"", File(dir, "name-Calc.etag").readText())
        assertArrayEquals(bytes("one"), read(files, seen))
        assertEquals(1, server.calls.size)
        assertEquals(null, server.calls.single().third)
        // the cap goes to the read as it was given
        assertEquals(100L, server.calls.single().second)
    }

    @Test
    fun `a new opening sends the ETag, a 304 keeps the stored file, a 200 replaces it`() {
        val dir = folder.newFolder("b")
        val server = Server(fresh("one"), null, fresh("two", "\"v2\""))
        val files = RepoFiles(dir, server::fetch)
        assertArrayEquals(bytes("one"), read(files, mutableSetOf()))
        assertArrayEquals(bytes("one"), read(files, mutableSetOf()))
        assertEquals("\"v1\"", server.calls[1].third)
        assertArrayEquals(bytes("two"), read(files, mutableSetOf()))
        assertEquals("\"v1\"", server.calls[2].third)
        assertEquals("\"v2\"", File(dir, "name-Calc.etag").readText())
        assertEquals("two", File(dir, "name-Calc.txt").readText())
    }

    @Test
    fun `an answer with no ETag leaves none stored`() {
        val dir = folder.newFolder("c")
        val files = RepoFiles(dir, Server(fresh("one"), fresh("two", null))::fetch)
        read(files, mutableSetOf())
        assertTrue(File(dir, "name-Calc.etag").isFile)
        read(files, mutableSetOf())
        assertFalse(File(dir, "name-Calc.etag").exists())
    }

    @Test
    fun `a 404 deletes the stored file and answers none`() {
        val dir = folder.newFolder("d")
        val files = RepoFiles(dir, Server(fresh("one"), error(NetError.Kind.NOT_FOUND))::fetch)
        read(files, mutableSetOf())
        assertNull(read(files, mutableSetOf()))
        assertFalse(File(dir, "name-Calc.txt").exists())
        assertFalse(File(dir, "name-Calc.etag").exists())
    }

    @Test
    fun `offline, limited or metered keeps the stored file and asks again in the same opening, a refusal keeps it and does not`() {
        for (kind in listOf(NetError.Kind.OFFLINE, NetError.Kind.RATE_LIMITED, NetError.Kind.METERED)) {
            val dir = folder.newFolder("e-$kind")
            val server = Server(fresh("one"), error(kind), fresh("two"))
            val files = RepoFiles(dir, server::fetch)
            assertArrayEquals(bytes("one"), read(files, mutableSetOf()))
            val seen = mutableSetOf<String>()
            assertArrayEquals(kind.name, bytes("one"), read(files, seen))
            assertFalse(kind.name, "Calc" in seen)
            assertArrayEquals(kind.name, bytes("two"), read(files, seen))
        }
        val dir = folder.newFolder("refused")
        val server = Server(fresh("one"), error(NetError.Kind.REFUSED), fresh("two"))
        val files = RepoFiles(dir, server::fetch)
        read(files, mutableSetOf())
        val seen = mutableSetOf<String>()
        assertArrayEquals(bytes("one"), read(files, seen))
        assertTrue("Calc" in seen)
        assertArrayEquals(bytes("one"), read(files, seen))
        assertEquals(2, server.calls.size)
    }

    @Test
    fun `with nothing stored an offline read answers none`() {
        val files = RepoFiles(folder.newFolder("f"), Server(error(NetError.Kind.OFFLINE))::fetch)
        assertNull(read(files, mutableSetOf()))
    }

    @Test
    fun `a stored file over the cap or empty is not served and sends no ETag`() {
        val dir = folder.newFolder("g")
        File(dir, "name-Calc.txt").writeText("x".repeat(50))
        File(dir, "name-Calc.etag").writeText("\"old\"")
        val server = Server(fresh("one"))
        val files = RepoFiles(dir, server::fetch)
        assertArrayEquals(bytes("one"), read(files, mutableSetOf(), max = 10))
        assertNull(server.calls.single().third)
    }

    @Test
    fun `two keys are asked one request each and a file per name`() {
        val dir = folder.newFolder("h")
        val server = Server(fresh("calc"), fresh("sms"))
        val files = RepoFiles(dir, server::fetch)
        val seen = mutableSetOf<String>()
        assertArrayEquals(bytes("calc"), read(files, seen, "Calc"))
        assertArrayEquals(bytes("sms"), read(files, seen, "SMS"))
        assertArrayEquals(bytes("calc"), read(files, seen, "Calc"))
        assertEquals(2, server.calls.size)
    }
}
