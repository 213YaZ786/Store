package com.yaz.store.data.catalog

// JUnit 4 (https://github.com/junit-team/junit4/wiki/Assertions) over a canned conditional read keyed by address, in the protocol of RepoFilesTest and
// CatalogTest. The files are the F-Droid layout's fastlane/metadata/android/<locale>/short_description.txt
// (https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots); the phone's languages are BCP 47 tags as Android's LocaleList gives them.
import com.yaz.store.data.net.NetError
import com.yaz.store.data.net.ReleaseNet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RepoDescriptionsTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** The repo's files by address: a text, a NetError, or nothing (a 404). Every address asked is noted. */
    private class Tree(val files: Map<String, Any> = emptyMap()) {
        val asked = mutableListOf<String>()
        fun fetch(url: String, max: Long, etag: String?): ReleaseNet.Fresh? {
            asked += url
            return when (val answer = files[url]) {
                null -> throw NetError(NetError.Kind.NOT_FOUND, message = "404")
                is NetError -> throw answer
                else -> ReleaseNet.Fresh((answer as String).toByteArray(), null)
            }
        }
    }

    private fun at(repo: String, locale: String) = ReleaseNet.repoDescriptionUrl(repo, locale)

    private fun descriptions(tree: Tree, name: String = "w") = RepoDescriptions(RepoFiles(folder.newFolder(name), tree::fetch))

    @Test
    fun `the phone's first language is asked first, then en-US, the first found wins`() {
        val tree = Tree(mapOf(at("Dialer", "fr-FR") to "Appels et historique\n", at("Dialer", "en-US") to "Calls and history"))
        assertEquals("Appels et historique", descriptions(tree).description("Dialer", listOf("fr-FR", "de-DE")))
        assertEquals(listOf(at("Dialer", "fr-FR")), tree.asked)
    }

    @Test
    fun `a missing locale falls to en-US, and two locales are the most that are asked`() {
        val tree = Tree(mapOf(at("Dialer", "en-US") to "Calls and history"))
        assertEquals("Calls and history", descriptions(tree).description("Dialer", listOf("fr-FR", "de-DE", "es-ES")))
        assertEquals(listOf(at("Dialer", "fr-FR"), at("Dialer", "en-US")), tree.asked)
        val none = Tree()
        assertNull(descriptions(none, "x").description("Dialer", listOf("fr-FR")))
        assertEquals(2, none.asked.size)
    }

    @Test
    fun `an English phone or no language asks en-US once`() {
        val tree = Tree(mapOf(at("Dialer", "en-US") to "Calls"))
        assertEquals("Calls", descriptions(tree).description("Dialer", listOf("en-US")))
        assertEquals(listOf(at("Dialer", "en-US")), tree.asked)
        val bare = Tree(mapOf(at("Dialer", "en-US") to "Calls"))
        assertEquals("Calls", descriptions(bare, "y").description("Dialer", emptyList()))
        assertEquals(listOf(at("Dialer", "en-US")), bare.asked)
    }

    @Test
    fun `a repo and locale are asked once per opening`() {
        val tree = Tree(mapOf(at("Dialer", "fr-FR") to "Appels"))
        val reader = descriptions(tree)
        assertEquals("Appels", reader.description("Dialer", listOf("fr-FR")))
        assertEquals("Appels", reader.description("Dialer", listOf("fr-FR")))
        assertEquals(1, tree.asked.size)
        // another opening: a new object asks again, and the stored text serves when the network is gone
        val offline = Tree(mapOf(at("Dialer", "fr-FR") to NetError(NetError.Kind.OFFLINE, message = "offline")))
        val again = RepoDescriptions(RepoFiles(java.io.File(folder.root, "w"), offline::fetch))
        assertEquals("Appels", again.description("Dialer", listOf("fr-FR")))
    }

    @Test
    fun `a 404 on the first locale, then offline on the second, answers none, and rate limited keeps the stored text`() {
        val tree = Tree(mapOf(at("Dialer", "fr-FR") to "Appels"))
        assertEquals("Appels", descriptions(tree, "z").description("Dialer", listOf("fr-FR")))
        val limited = Tree(mapOf(at("Dialer", "fr-FR") to NetError(NetError.Kind.RATE_LIMITED, message = "limit")))
        assertEquals("Appels", RepoDescriptions(RepoFiles(java.io.File(folder.root, "z"), limited::fetch)).description("Dialer", listOf("fr-FR")))
        val gone = Tree()
        assertNull(RepoDescriptions(RepoFiles(java.io.File(folder.root, "z"), gone::fetch)).description("Dialer", listOf("fr-FR")))
    }

    @Test
    fun `the text is one clean line cut to the description limit`() {
        assertEquals("Calls and history", RepoDescriptions.oneLine("  Calls\r\nand history \n"))
        assertEquals("Calls and history", RepoDescriptions.oneLine("Calls and‮ history\u0000"))
        assertEquals(CatalogRules.MAX_DESCRIPTION, RepoDescriptions.oneLine("a".repeat(CatalogRules.MAX_DESCRIPTION + 50)).length)
        assertEquals("", RepoDescriptions.oneLine("\n \n"))
        // an empty file counts as none: the next locale is tried
        val tree = Tree(mapOf(at("Dialer", "fr-FR") to " \n", at("Dialer", "en-US") to "Calls"))
        assertEquals("Calls", descriptions(tree, "v").description("Dialer", listOf("fr-FR")))
    }

    @Test
    fun `a phone's tag becomes a fastlane folder name`() {
        assertEquals("fr-FR", RepoDescriptions.fastlaneLocale("fr-FR"))
        assertEquals("pt-BR", RepoDescriptions.fastlaneLocale("pt_BR"))
        assertEquals("es-419", RepoDescriptions.fastlaneLocale("es-419"))
        assertEquals("zh-CN", RepoDescriptions.fastlaneLocale("zh-Hans-CN"))
        assertEquals("de", RepoDescriptions.fastlaneLocale("de"))
        assertEquals("en-US", RepoDescriptions.fastlaneLocale("en-US-u-nu-latn"))
        assertNull(RepoDescriptions.fastlaneLocale(""))
        assertNull(RepoDescriptions.fastlaneLocale("und"))
        assertNull(RepoDescriptions.fastlaneLocale("!!"))
        assertEquals(listOf("fr-FR", "en-US"), RepoDescriptions.directories(listOf("fr-FR", "en-US")))
        assertEquals(listOf("en-US"), RepoDescriptions.directories(listOf("en-US")))
        assertEquals(listOf("en-US"), RepoDescriptions.directories(listOf("und", "fr-FR")))
    }
}
