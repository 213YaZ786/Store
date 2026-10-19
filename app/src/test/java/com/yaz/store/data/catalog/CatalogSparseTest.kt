package com.yaz.store.data.catalog

// The list of releases that carry no app.json, on canned GitHub answers: the fake ReleaseSource and the protocol of CatalogTest (JUnit 4 and
// kotlinx.coroutines' runBlocking, https://github.com/junit-team/junit4/wiki/Assertions), the shapes of GitHub's answers
// (https://docs.github.com/en/repositories/releasing-projects-on-github/linking-to-releases) and of the CI's SHA256SUMS.txt
// (sha256sum on "./<file>", GNU coreutils, https://www.gnu.org/software/coreutils/manual/html_node/md5sum-invocation.html).
import com.yaz.store.data.net.NetError
import com.yaz.store.data.net.RangeSource
import com.yaz.store.data.net.ReleaseNet
import com.yaz.store.data.net.ReleaseSource
import com.yaz.store.data.sparse.SparseApk
import com.yaz.store.data.sparse.SparseInfo
import com.yaz.store.data.sparse.SparseInspect
import com.yaz.store.data.sparse.SparseProbe
import com.yaz.store.data.sparse.SparseSession
import com.yaz.store.data.sparse.SparseStepper
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CatalogSparseTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val digest = "a".repeat(64)
    private val appJson = javaClass.getResource("/catalog/app.json")!!.readText()
    private val phone = listOf("arm64-v8a", "armeabi-v7a", "armeabi")

    private fun url(repo: String, name: String, tag: String = "v1.0.0") = ReleaseNet.downloadUrl(repo, tag, name)

    private class Fake(val answers: Map<String, Any>, val tags: Map<String, String>) : ReleaseSource {
        val asked = mutableListOf<String>()
        override fun getJson(url: String): String {
            synchronized(asked) { asked += url }
            val a = answers[url] ?: throw NetError(NetError.Kind.NOT_FOUND, message = "404")
            if (a is NetError) throw a
            return a as String
        }
        override fun cached(url: String): String? = null
        override fun latestTag(repo: String): String = tags[repo] ?: throw NetError(NetError.Kind.NOT_FOUND, message = "404")
        override fun exists(url: String): Boolean = false
    }

    private fun list(vararg names: String) =
        names.joinToString(",", "[", "]") { """{"name":"$it","owner":{"login":"213YaZ786"}}""" }

    private fun sums(vararg names: String) = names.joinToString("\n") { "$digest  ./$it" } + "\n"

    private class Probed(val answers: Map<String, SparseInfo?> = emptyMap()) : ApkProbe {
        val asked = mutableListOf<String>()
        override suspend fun probe(repo: String, tag: String, apkName: String): SparseInfo? {
            synchronized(asked) { asked += "$repo/$tag/$apkName" }
            return answers["$repo/$apkName"]
        }
    }

    private fun load(fake: Fake, probe: ApkProbe?, abis: List<String> = phone) =
        runBlocking { Catalog(fake, null, { listOf("fr-FR") }, { abis }, probe = probe).load() }

    private val dialerInfo = SparseInfo("com.yaz.dialer", 46, "0.14.21", "Dialer", 31)

    @Test
    fun `a release with no app json is listed from its apk read by ranges`() {
        val fake = Fake(
            mapOf(
                ReleaseNet.repoListUrl() to list("Dialer"),
                url("Dialer", "SHA256SUMS.txt") to sums("Dialer-0.14.21-debug.apk", "Dialer-0.14.21.apk")
            ),
            mapOf("Dialer" to "v1.0.0")
        )
        val probe = Probed(mapOf("Dialer/Dialer-0.14.21.apk" to dialerInfo))
        val result = load(fake, probe)
        val entry = result.entries.single()
        assertNull(result.error)
        assertEquals("com.yaz.dialer", entry.packageName)
        assertEquals("Dialer", entry.label)
        assertEquals(46L, entry.versionCode)
        assertEquals("0.14.21", entry.versionName)
        assertEquals(31, entry.minSdk)
        assertEquals("Dialer-0.14.21.apk", entry.apkName)
        assertTrue(entry.sparse)
        assertFalse(entry.unread)
        // The debug APK is never the one read.
        assertEquals(listOf("Dialer/v1.0.0/Dialer-0.14.21.apk"), probe.asked)
    }

    @Test
    fun `the apk names come from the sums with or without the dot slash, never the debug ones`() {
        val text = listOf(
            "$digest  ./A-1.apk", "$digest *B-1.apk", "$digest  C-1-debug.apk", "$digest  ./D-1.zip", "short  ./E.apk",
            "$digest  ./sub/F.apk", "$digest  ./A-1.apk", "", "garbage"
        ).joinToString("\n")
        assertEquals(listOf("A-1.apk", "B-1.apk"), CatalogRules.apkNames(text))
        assertEquals(emptyList<String>(), CatalogRules.apkNames(""))
    }

    @Test
    fun `the apk for the phone's ABI is the one read`() {
        val names = arrayOf("Amehrug-1-arm64-v8a.apk", "Amehrug-1-armeabi-v7a.apk", "Amehrug-1-universal.apk", "Amehrug-1-x86.apk", "Amehrug-1-x86_64.apk")
        val fake = Fake(mapOf(ReleaseNet.repoListUrl() to list("Amehrug"), url("Amehrug", "SHA256SUMS.txt") to sums(*names)), mapOf("Amehrug" to "v1.0.0"))
        val probe = Probed(mapOf("Amehrug/Amehrug-1-arm64-v8a.apk" to SparseInfo("org.amehrug.app", 5, "1", "Amehrug", 31)))
        assertEquals("Amehrug-1-arm64-v8a.apk", load(fake, probe).entries.single().apkName)
        val emulator = Probed(mapOf("Amehrug/Amehrug-1-x86_64.apk" to SparseInfo("org.amehrug.app", 5, "1", "Amehrug", 31)))
        assertEquals("Amehrug-1-x86_64.apk", load(fake, emulator, abis = listOf("x86_64", "x86", "arm64-v8a")).entries.single().apkName)
    }

    @Test
    fun `an apk that cannot be read is listed by its repo name with the apk to install`() {
        val fake = Fake(
            mapOf(ReleaseNet.repoListUrl() to list("Odd-Repo"), url("Odd-Repo", "SHA256SUMS.txt", "v1.2.0") to sums("Odd-1.apk")),
            mapOf("Odd-Repo" to "v1.2.0")
        )
        for (probe in listOf(Probed(), null)) {
            val entry = load(fake, probe).entries.single()
            assertTrue(entry.unread)
            assertEquals("Odd-Repo", entry.label)
            assertEquals("Odd-1.apk", entry.apkName)
            assertEquals("1.2.0", entry.versionName)
            assertEquals("unread.odd_repo", entry.packageName)
            assertEquals(0L, entry.versionCode)
        }
    }

    @Test
    fun `app json still wins when the release has one`() {
        val fake = Fake(
            mapOf(
                ReleaseNet.repoListUrl() to list("Calculator"),
                url("Calculator", "app.json") to appJson,
                url("Calculator", "SHA256SUMS.txt") to sums("Other-9.apk")
            ),
            mapOf("Calculator" to "v1.0.0")
        )
        val probe = Probed()
        val entry = load(fake, probe).entries.single()
        assertEquals("Calculator-0.3.6.apk", entry.apkName)
        assertFalse(entry.sparse)
        assertEquals(emptyList<String>(), probe.asked)
        assertFalse(fake.asked.contains(url("Calculator", "SHA256SUMS.txt")))
    }

    @Test
    fun `a release with neither app json nor an apk in its sums is no app`() {
        val none = Fake(mapOf(ReleaseNet.repoListUrl() to list("GutapK")), mapOf("GutapK" to "v1.0.0"))
        assertEquals(emptyList<StoreEntry>(), load(none, Probed()).entries)
        val debugOnly = Fake(
            mapOf(ReleaseNet.repoListUrl() to list("X"), url("X", "SHA256SUMS.txt") to sums("X-1-debug.apk", "X.AppImage")),
            mapOf("X" to "v1.0.0")
        )
        assertEquals(emptyList<StoreEntry>(), load(debugOnly, Probed()).entries)
        val noRelease = Fake(mapOf(ReleaseNet.repoListUrl() to list("Y")), emptyMap())
        assertEquals(emptyList<StoreEntry>(), load(noRelease, Probed()).entries)
    }

    @Test
    fun `the install path checks the real file for an entry that was not read`() {
        val entry = StoreEntry("R", "v1", "unread.r", "R", "", 0, "1", 1, "R.apk", null, unread = true, sparse = true)
        val info = com.yaz.store.core.apk.ApkInfo(
            "com.real.app", 7, "1", null, false, null, false, 31, emptyList(), emptyList(), true, listOf("f006b86d".padEnd(64, '0')), "Real"
        )
        // The package and the version of the file are not compared with the placeholders; the signature is still required.
        val refusal = com.yaz.store.data.install.StoreChecks.refusal(entry, info, null, 36)
        assertEquals("The file is signed with a key that is not ours.", refusal)
        val unsigned = info.copy(verified = false)
        assertEquals("The file is not signed, or its signature is broken.", com.yaz.store.data.install.StoreChecks.refusal(entry, unsigned, null, 36))
        // An entry that was read still compares both.
        val read = entry.copy(unread = false)
        assertEquals("The file is another app than the release says.", com.yaz.store.data.install.StoreChecks.refusal(read, info, null, 36))
    }

    @Test
    fun `the answer is kept per repo, tag and file, and read once`() {
        val apk = File(checkNotNull(javaClass.classLoader?.getResource("apk/editor.apk")).toURI())
        var opened = 0
        val inProcess = object : SparseSession {
            override suspend fun <T> open(block: suspend (SparseStepper) -> T): T =
                block { stage, file -> FileInputStream(file).use { SparseInspect.step(stage, it.channel) } }
        }
        val source = { _: String ->
            opened++
            object : RangeSource {
                override fun length() = apk.length()
                override fun read(start: Long, endInclusive: Long): ByteArray = RandomAccessFile(apk, "r").use {
                    ByteArray((endInclusive - start + 1).toInt()).also { b -> it.seek(start); it.readFully(b) }
                }
            }
        }
        val probe = SparseProbe(source, SparseApk(inProcess, folder.newFolder()), folder.newFolder())
        val first = runBlocking { probe.probe("Editor", "v1", "Editor-1.apk") }
        assertEquals("org.billthefarmer.editor", first?.packageName)
        val second = runBlocking { probe.probe("Editor", "v1", "Editor-1.apk") }
        assertEquals(first, second)
        assertEquals(1, opened)
        // Another tag is another release.
        runBlocking { probe.probe("Editor", "v2", "Editor-1.apk") }
        assertEquals(2, opened)
        // A kept answer that is not an answer is read again.
        val dir = folder.newFolder()
        val again = SparseProbe(source, SparseApk(inProcess, folder.newFolder()), dir)
        runBlocking { again.probe("Editor", "v1", "Editor-1.apk") }
        dir.listFiles()!!.single().writeText("""{"packageName":"not a package","versionCode":1,"versionName":"1","minSdk":1}""")
        val before = opened
        assertEquals("org.billthefarmer.editor", runBlocking { again.probe("Editor", "v1", "Editor-1.apk") }?.packageName)
        assertEquals(before + 1, opened)
    }
}
