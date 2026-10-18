package com.yaz.store.data.catalog

// JUnit 4 (https://github.com/junit-team/junit4/wiki/Assertions) and kotlinx.coroutines' runBlocking
// (https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/run-blocking.html) over a fake JsonSource, so the whole
// listing runs on canned GitHub answers: the shapes of GitHub's REST answers (https://docs.github.com/en/rest/releases/releases#get-the-latest-release).
import com.yaz.store.data.net.JsonSource
import com.yaz.store.data.net.NetError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class CatalogTest {

    private val appJson = javaClass.getResource("/catalog/app.json")!!.readText()

    private fun url(repo: String, name: String, tag: String = "v1.0.0") = "https://github.com/213YaZ786/$repo/releases/download/$tag/$name"

    private fun release(repo: String, vararg names: String, body: String = "Notes", page: String = "https://github.com/213YaZ786/$repo/releases/tag/v1.0.0"): String {
        val assets = names.joinToString(",") { """{"name":"$it","browser_download_url":"${url(repo, it)}","size":1234}""" }
        return """{"html_url":"$page","published_at":"2026-10-18T10:00:00Z","body":"$body","assets":[$assets]}"""
    }

    private fun full(repo: String) = release(repo, "$repo-1.0.0.apk", "$repo-1.0.0-debug.apk", "SHA256SUMS.txt", "app.json", "icon.png")

    private fun repos(vararg names: String) =
        names.joinToString(",", "[", "]") { """{"name":"$it","owner":{"login":"213YaZ786"}}""" }

    private class Fake(val answers: Map<String, Any>) : JsonSource {
        val asked = mutableListOf<String>()
        val running = AtomicInteger()
        val most = AtomicInteger()
        override fun getJson(url: String): String {
            synchronized(asked) { asked += url }
            val now = running.incrementAndGet()
            most.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                Thread.sleep(15)
                val answer = answers[url] ?: throw NetError(NetError.Kind.NOT_FOUND, message = "404")
                if (answer is NetError) throw answer
                return answer as String
            } finally {
                running.decrementAndGet()
            }
        }
    }

    private val list = "https://api.github.com/users/213YaZ786/repos?per_page=100&type=owner"
    private fun latest(repo: String) = "https://api.github.com/repos/213YaZ786/$repo/releases/latest"

    private fun load(fake: Fake, locales: List<String> = listOf("fr-FR")) = runBlocking { Catalog(fake) { locales }.load() }

    @Test
    fun `an app with its release files is listed with everything it carries`() {
        val fake = Fake(mapOf(list to repos("Calculator"), latest("Calculator") to full("Calculator"), url("Calculator", "app.json") to appJson))
        val result = load(fake)
        assertNull(result.error)
        val entry = result.entries.single()
        assertEquals("Calculator", entry.repo)
        assertEquals("com.yaz.calculator", entry.packageName)
        assertEquals("Calculator", entry.label)
        assertEquals("Installe et met à jour les apps 213YaZ786 depuis leurs releases", entry.description)
        assertEquals(14L, entry.versionCode)
        assertEquals("0.3.6", entry.versionName)
        assertEquals(31, entry.minSdk)
        assertEquals("Calculator-1.0.0.apk", entry.apkName)
        assertEquals(url("Calculator", "Calculator-1.0.0.apk"), entry.apkUrl)
        assertEquals(1234L, entry.apkSize)
        assertEquals(url("Calculator", "SHA256SUMS.txt"), entry.sumsUrl)
        assertEquals(url("Calculator", "icon.png"), entry.iconUrl)
        assertEquals("Notes", entry.notes)
        assertEquals(Instant.parse("2026-10-18T10:00:00Z"), entry.publishedAt)
        assertEquals("https://github.com/213YaZ786/Calculator/releases/tag/v1.0.0", entry.pageUrl)
    }

    @Test
    fun `today every release lacks app json, so the list is empty and says nothing is wrong`() {
        val bare = { repo: String -> release(repo, "$repo-1.0.0.apk", "$repo-1.0.0-debug.apk", "SHA256SUMS.txt") }
        val fake = Fake(mapOf(list to repos("A", "B"), latest("A") to bare("A"), latest("B") to bare("B")))
        val result = load(fake)
        assertTrue(result.entries.isEmpty())
        assertNull(result.error)
    }

    @Test
    fun `repos that are not apps of ours are left out`() {
        val answers = mapOf<String, Any>(
            list to """[
              {"name":"Good","owner":{"login":"213YaZ786"}},
              {"name":"Archived","archived":true,"owner":{"login":"213YaZ786"}},
              {"name":"Forked","fork":true,"owner":{"login":"213YaZ786"}},
              {"name":"NoRelease","owner":{"login":"213YaZ786"}},
              {"name":"TwoApks","owner":{"login":"213YaZ786"}},
              {"name":"DebugOnly","owner":{"login":"213YaZ786"}},
              {"name":"BadJson","owner":{"login":"213YaZ786"}},
              {"name":"Borrowed","owner":{"login":"213YaZ786"}},
              {"name":"Plain","owner":{"login":"213YaZ786"}}
            ]""",
            latest("Good") to full("Good"), url("Good", "app.json") to appJson,
            latest("Archived") to full("Archived"), url("Archived", "app.json") to appJson,
            latest("Forked") to full("Forked"), url("Forked", "app.json") to appJson,
            latest("TwoApks") to release("TwoApks", "a.apk", "b.apk", "SHA256SUMS.txt", "app.json"), url("TwoApks", "app.json") to appJson,
            latest("DebugOnly") to release("DebugOnly", "a-debug.apk", "SHA256SUMS.txt", "app.json"), url("DebugOnly", "app.json") to appJson,
            latest("BadJson") to full("BadJson"), url("BadJson", "app.json") to """{"package":"calculator"}""",
            // An asset address that points into another repo is dropped before anything is fetched.
            latest("Borrowed") to full("Borrowed").replace("/Borrowed/", "/Good/"), url("Good", "app.json") to appJson,
            latest("Plain") to "<html>not json</html>"
        )
        val fake = Fake(answers)
        val result = load(fake)
        assertEquals(listOf("Good"), result.entries.map { it.repo })
        assertNull(result.error)
        // Archived repos and forks are never even asked for.
        assertTrue(fake.asked.none { it.contains("/Archived/") || it.contains("/Forked/") })
    }

    @Test
    fun `an address that is not ours is never fetched`() {
        val outside = """{"html_url":"","assets":[
          {"name":"a.apk","browser_download_url":"http://github.com/213YaZ786/X/releases/download/v1/a.apk","size":1},
          {"name":"SHA256SUMS.txt","browser_download_url":"https://evil.example/213YaZ786/X/releases/download/v1/SHA256SUMS.txt","size":1},
          {"name":"app.json","browser_download_url":"https://github.com/213YaZ786/X/releases/download/v1/../../../x/app.json","size":1}]}"""
        val fake = Fake(mapOf(list to repos("X"), latest("X") to outside))
        val result = load(fake)
        assertTrue(result.entries.isEmpty())
        assertEquals(listOf(list, latest("X")), fake.asked)
    }

    @Test
    fun `the description follows the phone and the notes are plain and capped`() {
        val long = "n".repeat(30_000)
        val fake = Fake(mapOf(list to repos("A"), latest("A") to release("A", "A.apk", "SHA256SUMS.txt", "app.json", body = "$long"), url("A", "app.json") to appJson))
        assertEquals("Installs and updates the 213YaZ786 apps from their releases", load(fake, listOf("de-DE")).entries.single().description)
        assertEquals(20_000, load(fake, listOf("fr-CA")).entries.single().notes.length)
        assertNull(load(fake).entries.single().iconUrl)
    }

    @Test
    fun `entries are sorted by label`() {
        fun app(label: String) = appJson.replace("\"Calculator\"", "\"$label\"")
        val fake = Fake(
            mapOf(
                list to repos("Z", "M", "A"),
                latest("Z") to full("Z"), url("Z", "app.json") to app("zebra"),
                latest("M") to full("M"), url("M", "app.json") to app("Mushaf"),
                latest("A") to full("A"), url("A", "app.json") to app("Amehrug")
            )
        )
        assertEquals(listOf("Amehrug", "Mushaf", "zebra"), load(fake).entries.map { it.label })
    }

    @Test
    fun `at most four releases are read at a time`() {
        val names = (1..14).map { "R$it" }
        val answers = mutableMapOf<String, Any>(list to repos(*names.toTypedArray()))
        for (n in names) {
            answers[latest(n)] = full(n)
            answers[url(n, "app.json")] = appJson
        }
        val fake = Fake(answers)
        val result = load(fake)
        assertEquals(14, result.entries.size)
        assertTrue("most at once: ${fake.most.get()}", fake.most.get() in 1..4)
    }

    @Test
    fun `offline gives an empty list and the reason`() {
        val result = load(Fake(mapOf(list to NetError(NetError.Kind.OFFLINE, message = "offline"))))
        assertTrue(result.entries.isEmpty())
        assertEquals(CatalogError.Offline, result.error)
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
        assertEquals(CatalogError.Refused, load(Fake(emptyMap())).error)
    }

    @Test
    fun `a limit reached half way keeps what was read`() {
        val fake = Fake(
            mapOf(
                list to repos("A", "B"),
                latest("A") to full("A"), url("A", "app.json") to appJson,
                latest("B") to NetError(NetError.Kind.RATE_LIMITED, 99L, "rate limited")
            )
        )
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
}
