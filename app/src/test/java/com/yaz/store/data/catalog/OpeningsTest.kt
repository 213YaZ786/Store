package com.yaz.store.data.catalog

// JUnit 4 (https://github.com/junit-team/junit4/wiki/Assertions) and its TemporaryFolder rule, over a canned conditional read, in the protocol of
// RepoFilesTest and RepoDescriptionsTest. What Openings must give the readers is GitHub's conditional request once per opening
// (https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api#use-conditional-requests-if-appropriate): after a new
// opening the same repo is asked again with the stored ETag (a 304 keeps the stored file); without one, it is not asked.
import com.yaz.store.data.net.ReleaseNet
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpeningsTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** A canned server: each address answers its text with an ETag, or 304 when the ETag sent is the current one. Every call is noted. */
    private class Server(var text: String = "one", var etag: String = "\"v1\"") {
        val calls = mutableListOf<Pair<String, String?>>()
        fun fetch(url: String, max: Long, sent: String?): ReleaseNet.Fresh? {
            calls += url to sent
            return if (sent == etag) null else ReleaseNet.Fresh(text.toByteArray(), etag)
        }
    }

    private fun entry(repo: String) = StoreEntry(
        repo = repo, tag = "v1", packageName = "com.yaz.${repo.lowercase()}", label = repo, description = "", versionCode = 1, versionName = "1",
        minSdk = 26, apkName = null, iconUrl = null
    )

    private fun store(server: Server, openings: Openings, name: String) =
        IconStore(ReleaseNet(folder.newFolder("net-$name")), folder.newFolder(name), null, openings, server::fetch)

    @Test
    fun `a new opening makes the same repo's icon asked again with the stored ETag, a 304 keeps the file`() {
        val server = Server()
        val openings = Openings()
        val icons = store(server, openings, "icons")
        assertArrayEquals("one".toByteArray(), icons.bytes(entry("Dialer")))
        assertEquals(listOf(ReleaseNet.repoIconUrl("Dialer") to null), server.calls)
        openings.begin()
        assertArrayEquals("one".toByteArray(), icons.bytes(entry("Dialer")))
        assertEquals(2, server.calls.size)
        assertEquals("\"v1\"", server.calls[1].second)
    }

    @Test
    fun `without a new opening the repo is not asked again`() {
        val server = Server()
        val icons = store(server, Openings(), "icons")
        icons.bytes(entry("Dialer"))
        icons.bytes(entry("Dialer"))
        icons.bytes(entry("Dialer"))
        assertEquals(1, server.calls.size)
    }

    @Test
    fun `a new opening asks the layers list again too, and a changed answer replaces the stored one`() {
        val server = Server()
        val openings = Openings()
        val icons = store(server, openings, "icons")
        // the list is not a valid icon-layers.json: layers() answers none, but the request is made and counted
        assertEquals(null, icons.layers(entry("Contacts")))
        assertEquals(1, server.calls.count { it.first == ReleaseNet.repoLayersUrl("Contacts") })
        assertEquals(null, icons.layers(entry("Contacts")))
        assertEquals(1, server.calls.count { it.first == ReleaseNet.repoLayersUrl("Contacts") })
        server.text = "two"
        server.etag = "\"v2\""
        openings.begin()
        assertEquals(null, icons.layers(entry("Contacts")))
        val layers = server.calls.filter { it.first == ReleaseNet.repoLayersUrl("Contacts") }
        assertEquals(2, layers.size)
        assertEquals("\"v1\"", layers[1].second)
    }

    @Test
    fun `a new opening asks the words again with the stored ETag`() {
        val server = Server("Calls and history")
        val openings = Openings()
        val words = RepoDescriptions(RepoFiles(folder.newFolder("w"), server::fetch), openings)
        assertEquals("Calls and history", words.description("Dialer", listOf("en-US")))
        assertEquals("Calls and history", words.description("Dialer", listOf("en-US")))
        assertEquals(1, server.calls.size)
        openings.begin()
        assertEquals("Calls and history", words.description("Dialer", listOf("en-US")))
        assertEquals(2, server.calls.size)
        assertEquals("\"v1\"", server.calls[1].second)
    }

    @Test
    fun `the generation moves at each opening and the asked sets are emptied before it does`() {
        val openings = Openings()
        val asked = openings.asked()
        asked.add("Dialer")
        assertEquals(0, openings.generation.value)
        openings.begin()
        assertEquals(1, openings.generation.value)
        assertTrue(asked.isEmpty())
        openings.begin()
        assertEquals(2, openings.generation.value)
    }
}
