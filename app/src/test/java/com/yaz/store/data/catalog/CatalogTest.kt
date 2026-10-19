package com.yaz.store.data.catalog

// JUnit 4 (https://github.com/junit-team/junit4/wiki/Assertions) and kotlinx.coroutines' runBlocking
// (https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/run-blocking.html) over a fake ReleaseSource, so the whole
// listing runs on canned GitHub answers: the shapes of GitHub's REST answers (https://docs.github.com/en/rest/releases/releases#get-a-release-by-tag-name)
// and of "link to the latest release asset" (https://docs.github.com/en/repositories/releasing-projects-on-github/linking-to-releases).
import com.yaz.store.data.net.NetError
import com.yaz.store.data.net.ReleaseNet
import com.yaz.store.data.net.ReleaseSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class CatalogTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val appJson = javaClass.getResource("/catalog/app.json")!!.readText()
    private val pixel = listOf("arm64-v8a", "armeabi-v7a", "armeabi")

    private fun url(repo: String, name: String, tag: String = "v1.0.0") = ReleaseNet.downloadUrl(repo, tag, name)

    private fun repos(vararg names: String) =
        names.joinToString(",", "[", "]") { """{"name":"$it","owner":{"login":"213YaZ786"}}""" }

    /** The answers of GitHub, by address; a [NetError] is thrown, anything missing is a 404. */
    private class Fake(
        val answers: Map<String, Any> = emptyMap(),
        /** repo to its latest tag, or a NetError; a repo not named has no release. */
        val tags: Map<String, Any> = emptyMap(),
        val icons: Set<String> = emptySet(),
        val cachedList: String? = null
    ) : ReleaseSource {
        val asked = mutableListOf<String>()
        val running = AtomicInteger()
        val most = AtomicInteger()

        private fun note(what: String) {
            synchronized(asked) { asked += what }
        }

        private fun <T> slow(block: () -> T): T {
            val now = running.incrementAndGet()
            most.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                Thread.sleep(10)
                return block()
            } finally {
                running.decrementAndGet()
            }
        }

        override fun getJson(url: String): String = slow {
            note(url)
            val answer = answers[url] ?: throw NetError(NetError.Kind.NOT_FOUND, message = "404")
            if (answer is NetError) throw answer
            answer as String
        }

        override fun cached(url: String): String? = cachedList

        override fun latestTag(repo: String): String = slow {
            note("latest:$repo")
            val answer = tags[repo] ?: throw NetError(NetError.Kind.NOT_FOUND, message = "404")
            if (answer is NetError) throw answer
            answer as String
        }

        override fun exists(url: String): Boolean = slow {
            note("exists:$url")
            url in icons
        }
    }

    private val list = ReleaseNet.repoListUrl()

    private fun load(fake: Fake, locales: List<String> = listOf("fr-FR"), abis: List<String> = pixel, notes: java.io.File? = null) =
        runBlocking { Catalog(fake, notes, { locales }, { abis }).load() }

    /** A source where each named repo is an app of ours at tag v1.0.0 with the recorded app.json. */
    private fun apps(vararg names: String, extra: Map<String, Any> = emptyMap(), icons: Set<String> = emptySet()): Fake {
        val answers = mutableMapOf<String, Any>(list to repos(*names))
        val tags = mutableMapOf<String, Any>()
        for (n in names) {
            tags[n] = "v1.0.0"
            answers[url(n, "app.json")] = appJson
        }
        answers += extra
        return Fake(answers, tags, icons)
    }

    @Test
    fun `an app with its release files is listed with everything it carries`() {
        val fake = apps("Calculator", icons = setOf(url("Calculator", "icon.png")))
        val result = load(fake)
        assertNull(result.error)
        assertFalse(result.listCached)
        val entry = result.entries.single()
        assertEquals("Calculator", entry.repo)
        assertEquals("v1.0.0", entry.tag)
        assertEquals("com.yaz.calculator", entry.packageName)
        assertEquals("Calculator", entry.label)
        assertEquals("Installe et met à jour les apps 213YaZ786 depuis leurs releases", entry.description)
        assertEquals(14L, entry.versionCode)
        assertEquals("0.3.6", entry.versionName)
        assertEquals(31, entry.minSdk)
        assertEquals("Calculator-0.3.6.apk", entry.apkName)
        assertEquals(url("Calculator", "Calculator-0.3.6.apk"), entry.apkUrl)
        assertEquals(url("Calculator", "SHA256SUMS.txt"), entry.sumsUrl)
        assertEquals(url("Calculator", "icon.png"), entry.iconUrl)
        assertEquals("https://github.com/213YaZ786/Calculator/releases/tag/v1.0.0", entry.pageUrl)
    }

    @Test
    fun `the icon is only kept when the release has one`() {
        assertNull(load(apps("Calculator")).entries.single().iconUrl)
    }

    @Test
    fun `one opening asks the API once, for the repo list, and nothing else of it`() {
        val fake = apps("A", "B", "C", icons = setOf(url("A", "icon.png")))
        val result = load(fake)
        assertEquals(3, result.entries.size)
        val api = fake.asked.filter { it.startsWith("https://api.github.com/") }
        assertEquals(listOf(list), api)
        // Each repo: its tag, its app.json and its icon, all on github.com.
        for (n in listOf("A", "B", "C")) {
            assertTrue(fake.asked.contains("latest:$n"))
            assertTrue(fake.asked.contains(url(n, "app.json")))
            assertTrue(fake.asked.contains("exists:${url(n, "icon.png")}"))
        }
        assertEquals(1 + 3 * 3, fake.asked.size)
    }

    @Test
    fun `today every release lacks app json, so the list is empty and says nothing is wrong`() {
        val fake = Fake(mapOf(list to repos("A", "B")), mapOf("A" to "v0.3.6", "B" to "v0.1.0"))
        val result = load(fake)
        assertTrue(result.entries.isEmpty())
        assertNull(result.error)
        // A missing app.json ends that repo: no icon is looked for.
        assertTrue(fake.asked.none { it.startsWith("exists:") })
    }

    @Test
    fun `repos that are not apps of ours are left out`() {
        val old = appJson.replace(""","apks":["Calculator-0.3.6.apk"]""", "")
        val noApks = appJson.replace("\"Calculator-0.3.6.apk\"", "\"Calculator-0.3.6-debug.apk\"")
        val answers = mapOf<String, Any>(
            list to """[
              {"name":"Good","owner":{"login":"213YaZ786"}},
              {"name":"Archived","archived":true,"owner":{"login":"213YaZ786"}},
              {"name":"Forked","fork":true,"owner":{"login":"213YaZ786"}},
              {"name":"NoRelease","owner":{"login":"213YaZ786"}},
              {"name":"NoAppJson","owner":{"login":"213YaZ786"}},
              {"name":"BadJson","owner":{"login":"213YaZ786"}},
              {"name":"OldFormat","owner":{"login":"213YaZ786"}},
              {"name":"DebugOnly","owner":{"login":"213YaZ786"}},
              {"name":"Plain","owner":{"login":"213YaZ786"}}
            ]""",
            url("Good", "app.json") to appJson,
            url("Archived", "app.json") to appJson,
            url("Forked", "app.json") to appJson,
            url("BadJson", "app.json") to """{"package":"calculator"}""",
            url("OldFormat", "app.json") to old,
            url("DebugOnly", "app.json") to noApks,
            url("Plain", "app.json") to "<html>not json</html>"
        )
        val tags = listOf("Good", "Archived", "Forked", "NoAppJson", "BadJson", "OldFormat", "DebugOnly", "Plain").associateWith { "v1.0.0" as Any }
        val fake = Fake(answers, tags)
        val result = load(fake)
        assertEquals(listOf("Good"), result.entries.map { it.repo })
        assertNull(result.error)
        // Archived repos and forks are never even asked for.
        assertTrue(fake.asked.none { it.contains("Archived") || it.contains("Forked") })
    }

    @Test
    fun `several apks give the one for this phone, and none that fits is kept as no apk`() {
        val five = appJson.replace(
            "\"Calculator-0.3.6.apk\"",
            "\"A-arm64-v8a.apk\",\"A-armeabi-v7a.apk\",\"A-universal.apk\",\"A-x86.apk\",\"A-x86_64.apk\""
        )
        val armOnly = appJson.replace("\"Calculator-0.3.6.apk\"", "\"A-arm64-v8a.apk\"")
        val fake = apps("Five", "ArmOnly", extra = mapOf(url("Five", "app.json") to five, url("ArmOnly", "app.json") to armOnly))
        assertEquals("A-arm64-v8a.apk", load(fake, abis = pixel).entries.first { it.repo == "Five" }.apkName)
        assertEquals("A-x86_64.apk", load(fake, abis = listOf("x86_64", "x86")).entries.first { it.repo == "Five" }.apkName)
        val none = load(fake, abis = listOf("x86_64", "x86")).entries.first { it.repo == "ArmOnly" }
        assertNull(none.apkName)
        assertNull(none.apkUrl)
    }

    @Test
    fun `the description follows the phone`() {
        val fake = apps("A")
        assertEquals("Installs and updates the 213YaZ786 apps from their releases", load(fake, listOf("de-DE")).entries.single().description)
        assertEquals("Installe et met à jour les apps 213YaZ786 depuis leurs releases", load(fake, listOf("fr-CA")).entries.single().description)
    }

    @Test
    fun `entries are sorted by label`() {
        fun app(label: String) = appJson.replace("\"Calculator\"", "\"$label\"")
        val fake = apps("Z", "M", "A", extra = mapOf(url("Z", "app.json") to app("zebra"), url("M", "app.json") to app("Mushaf"), url("A", "app.json") to app("Amehrug")))
        assertEquals(listOf("Amehrug", "Mushaf", "zebra"), load(fake).entries.map { it.label })
    }

    @Test
    fun `at most four releases are read at a time`() {
        val fake = apps(*(1..14).map { "R$it" }.toTypedArray())
        val result = load(fake)
        assertEquals(14, result.entries.size)
        assertTrue("most at once: ${fake.most.get()}", fake.most.get() in 1..4)
    }

    @Test
    fun `offline gives an empty list and the reason`() {
        val result = load(Fake(mapOf(list to NetError(NetError.Kind.OFFLINE, message = "offline"))))
        assertTrue(result.entries.isEmpty())
        assertEquals(CatalogError.Offline, result.error)
        assertFalse(result.listCached)
    }

    @Test
    fun `GitHub's limit gives its reset time`() {
        val result = load(Fake(mapOf(list to NetError(NetError.Kind.RATE_LIMITED, 1_792_333_022L, "rate limited"))))
        assertTrue(result.entries.isEmpty())
        assertEquals(CatalogError.RateLimited(1_792_333_022L), result.error)
    }

    @Test
    fun `a refused answer and an unreadable list give refused`() {
        assertEquals(CatalogError.Refused, load(Fake(mapOf(list to NetError(NetError.Kind.REFUSED, message = "http 500")))).error)
        assertEquals(CatalogError.Refused, load(Fake(mapOf(list to "<html>"))).error)
        assertEquals(CatalogError.Refused, load(Fake()).error)
    }

    @Test
    fun `when GitHub's limit is reached the list kept at the last opening is used, and said so`() {
        val base = apps("A", "B")
        val fake = Fake(
            base.answers + (list to NetError(NetError.Kind.RATE_LIMITED, 99L, "rate limited")),
            base.tags,
            cachedList = repos("A", "B")
        )
        val result = load(fake)
        assertEquals(listOf("A", "B"), result.entries.map { it.repo })
        assertEquals(CatalogError.RateLimited(99L), result.error)
        assertTrue(result.listCached)
        // The releases were read from github.com, not from the API.
        assertTrue(fake.asked.none { it.startsWith("https://api.github.com/repos") })
    }

    @Test
    fun `offline the list kept at the last opening is used, then each release fails alike`() {
        val offline = NetError(NetError.Kind.OFFLINE, message = "offline")
        val fake = Fake(mapOf(list to offline), mapOf("A" to offline, "B" to offline), cachedList = repos("A", "B"))
        val result = load(fake)
        assertTrue(result.entries.isEmpty())
        assertEquals(CatalogError.Offline, result.error)
        assertTrue(result.listCached)
    }

    @Test
    fun `no list kept, a limit gives an empty list`() {
        val result = load(Fake(mapOf(list to NetError(NetError.Kind.RATE_LIMITED, 99L, "rate limited"))))
        assertTrue(result.entries.isEmpty())
        assertEquals(CatalogError.RateLimited(99L), result.error)
        assertFalse(result.listCached)
    }

    @Test
    fun `a refused list is not replaced by the kept one`() {
        val fake = Fake(mapOf(list to NetError(NetError.Kind.REFUSED, message = "http 500")), cachedList = repos("A"))
        val result = load(fake)
        assertTrue(result.entries.isEmpty())
        assertEquals(CatalogError.Refused, result.error)
        assertFalse(result.listCached)
    }

    @Test
    fun `a kept list that cannot be read gives refused`() {
        val fake = Fake(mapOf(list to NetError(NetError.Kind.OFFLINE, message = "offline")), cachedList = "<html>")
        val result = load(fake)
        assertEquals(CatalogError.Refused, result.error)
        assertTrue(result.entries.isEmpty())
    }

    @Test
    fun `a limit reached half way keeps what was read`() {
        val base = apps("A", "B")
        val fake = Fake(base.answers, mapOf("A" to "v1.0.0", "B" to NetError(NetError.Kind.RATE_LIMITED, 99L, "rate limited")))
        val result = load(fake)
        assertEquals(listOf("A"), result.entries.map { it.repo })
        assertEquals(CatalogError.RateLimited(99L), result.error)
    }

    @Test
    fun `an empty account gives an empty list without error`() {
        val result = load(Fake(mapOf(list to "[]")))
        assertTrue(result.entries.isEmpty())
        assertNull(result.error)
    }

    // Store's own package: out of the list, its release apart. Same fake source as the tests above, JUnit 4 asserts
    // (https://github.com/junit-team/junit4/wiki/Assertions).

    // https://github.com/junit-team/junit4/wiki/Assertions
    @Test
    fun `Store's own package, release and debug, is in no list and its newest release rides apart`() {
        val own = appJson.replace("com.yaz.calculator", "com.yaz.store").replace("\"versionCode\":14", "\"versionCode\":30").replace("0.3.6", "0.2.0")
        val debug = appJson.replace("com.yaz.calculator", "com.yaz.store.debug").replace("\"versionCode\":14", "\"versionCode\":40").replace("0.3.6", "0.3.0")
        val fake = apps("Calculator", "Store", "StoreDebug", extra = mapOf(url("Store", "app.json") to own, url("StoreDebug", "app.json") to debug))
        val result = load(fake)
        assertEquals(listOf("com.yaz.calculator"), result.entries.map { it.packageName })
        assertEquals("com.yaz.store.debug", result.own?.packageName)
        assertEquals(40L, result.own?.versionCode)
        assertNull(load(apps("Calculator")).own)
    }

    // https://github.com/junit-team/junit4/wiki/Assertions
    @Test
    fun `a Store release whose APK cannot be read is no row either`() {
        val base = apps("Calculator", extra = mapOf(
            list to repos("Calculator", "Store"),
            url("Store", "SHA256SUMS.txt") to ("0".repeat(64) + "  ./Store-0.1.0.apk")
        ))
        val result = load(Fake(base.answers, base.tags + ("Store" to "v1.0.0")))
        assertEquals(listOf("Calculator"), result.entries.map { it.repo })
        assertNull(result.own)
    }

    // The date and sizes of a tag

    private fun entry(repo: String = "A", tag: String = "v1.0.0") = load(apps(repo)).entries.single().copy(tag = tag)

    private fun release(tag: String, size: Long = 4321) =
        """{"tag_name":"$tag","published_at":"2026-10-18T10:00:00Z","body":"Fixes","assets":[{"name":"Calculator-0.3.6.apk","size":$size}]}"""

    @Test
    fun `the date and sizes of a tag are read from the API once and kept for good`() {
        val tags = ReleaseNet.tagsUrl("A", "v1.0.0")
        val fake = Fake(mapOf(tags to release("v1.0.0")))
        val catalog = Catalog(fake, folder.newFolder("notes"), { pixel }, { pixel })
        val first = catalog.details(entry())!!
        assertEquals(Instant.parse("2026-10-18T10:00:00Z"), first.publishedAt)
        assertEquals(4321L, first.sizes["Calculator-0.3.6.apk"])
        assertEquals(first, catalog.details(entry()))
        // A new Catalog over the same folder: still no second call.
        assertEquals(first, Catalog(fake, java.io.File(folder.root, "notes"), { pixel }, { pixel }).details(entry()))
        assertEquals(listOf(tags), fake.asked)
    }

    @Test
    fun `another tag is another read`() {
        val fake = Fake(mapOf(ReleaseNet.tagsUrl("A", "v1.0.0") to release("v1.0.0"), ReleaseNet.tagsUrl("A", "v1.1.0") to release("v1.1.0", 9000)))
        val catalog = Catalog(fake, folder.newFolder("notes"))
        assertEquals(4321L, catalog.details(entry())!!.sizes["Calculator-0.3.6.apk"])
        assertEquals(9000L, catalog.details(entry(tag = "v1.1.0"))!!.sizes["Calculator-0.3.6.apk"])
        assertEquals(2, fake.asked.size)
    }

    @Test
    fun `details that cannot be read now are null and asked again next time`() { // same test, renamed (JUnit 4, https://github.com/junit-team/junit4/wiki/Assertions)
        val tags = ReleaseNet.tagsUrl("A", "v1.0.0")
        val limited = Fake(mapOf(tags to NetError(NetError.Kind.RATE_LIMITED, 1L, "rate limited")))
        val catalog = Catalog(limited, folder.newFolder("notes"))
        assertNull(catalog.details(entry()))
        assertNull(Catalog(Fake(mapOf(tags to release("v9"))), folder.newFolder("notes2")).details(entry()))
        assertNull(Catalog(Fake(mapOf(tags to "<html>")), folder.newFolder("notes3")).details(entry()))
        assertNull(Catalog(Fake(), folder.newFolder("notes4")).details(entry()))
        // Nothing was kept from a failure: a later answer is read.
        val later = Fake(mapOf(tags to release("v1.0.0")))
        assertNotNull(Catalog(later, java.io.File(folder.root, "notes")).details(entry()))
    }

    @Test
    fun `a kept file that is not details is read again`() {
        val tags = ReleaseNet.tagsUrl("A", "v1.0.0")
        val dir = folder.newFolder("notes")
        val fake = Fake(mapOf(tags to release("v1.0.0")))
        val catalog = Catalog(fake, dir)
        catalog.details(entry())
        dir.listFiles()!!.forEach { it.writeText("garbage") }
        assertEquals(4321L, catalog.details(entry())!!.sizes["Calculator-0.3.6.apk"])
        assertEquals(2, fake.asked.size)
    }

    @Test
    fun `without a folder every read asks`() {
        val tags = ReleaseNet.tagsUrl("A", "v1.0.0")
        val fake = Fake(mapOf(tags to release("v1.0.0")))
        val catalog = Catalog(fake)
        catalog.details(entry())
        catalog.details(entry())
        assertEquals(2, fake.asked.size)
    }
}
