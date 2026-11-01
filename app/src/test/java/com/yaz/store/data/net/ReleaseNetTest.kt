package com.yaz.store.data.net

// Follows the update kit's UpdatesTest protocol (https://github.com/213YaZ786/Calculator, app/src/test/java/com/yaz/calculator/core/update/UpdatesTest.kt):
// each address is asserted as allowed or refused, with the lookalikes of its host and its owner.
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

class ReleaseNetTest {

    private val list = "https://api.github.com/users/213YaZ786/repos?per_page=100&type=owner"
    private fun notes(repo: String, tag: String = "v1.0.0") = "https://api.github.com/repos/213YaZ786/$repo/releases/tags/$tag"
    private fun latest(repo: String) = "https://github.com/213YaZ786/$repo/releases/latest/download/SHA256SUMS.txt"
    private fun file(repo: String, tag: String = "v1.0.0", name: String = "a.apk") =
        "https://github.com/213YaZ786/$repo/releases/download/$tag/$name"

    @Test
    fun `the four addresses of our account are allowed`() {
        assertTrue(ReleaseNet.startAllowed(list))
        assertTrue(ReleaseNet.startAllowed(notes("Calculator")))
        assertTrue(ReleaseNet.startAllowed(latest("Calculator")))
        assertTrue(ReleaseNet.startAllowed(file("Calculator", name = "Calculator-1.0.0.apk")))
        assertTrue(ReleaseNet.startAllowed(file("Calculator", name = "SHA256SUMS.txt")))
        assertTrue(ReleaseNet.startAllowed(file("Calculator", name = "icon.png")))
        assertTrue(ReleaseNet.startAllowed(file("Calculator", tag = "v1.2.3-b45", name = "app.json")))
        assertTrue(ReleaseNet.startAllowed(file("Calculator", tag = "v1.2.3+4", name = "app.json")))
        assertTrue(ReleaseNet.startAllowed(notes("Calculator", "v1.2.3+4")))
        assertTrue(ReleaseNet.startAllowed("https://api.github.com:443/repos/213YaZ786/Calculator/releases/tags/v1"))
        assertEquals(list, ReleaseNet.repoListUrl())
        assertEquals(notes("Mushaf", "v2"), ReleaseNet.tagsUrl("Mushaf", "v2"))
        assertEquals(latest("Mushaf"), ReleaseNet.latestSumsUrl("Mushaf"))
        assertEquals(file("Mushaf", "v2", "x.apk"), ReleaseNet.downloadUrl("Mushaf", "v2", "x.apk"))
    }

    @Test
    fun `the API's latest release and its other shapes are no longer asked`() {
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/repos/213YaZ786/Calculator/releases/latest"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/repos/213YaZ786/Calculator/releases"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/repos/213YaZ786/Calculator"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/repos/213YaZ786/Calculator/releases/tags"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/repos/213YaZ786/Calculator/releases/tags/v1/extra"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/rate_limit"))
    }

    @Test
    fun `only SHA256SUMS txt is read through latest download`() {
        assertFalse(ReleaseNet.startAllowed("https://github.com/213YaZ786/Calculator/releases/latest/download/app.json"))
        assertFalse(ReleaseNet.startAllowed("https://github.com/213YaZ786/Calculator/releases/latest/download/Calculator.apk"))
        assertFalse(ReleaseNet.startAllowed("https://github.com/213YaZ786/Calculator/releases/latest/download/"))
        assertFalse(ReleaseNet.startAllowed("https://github.com/213YaZ786/Calculator/releases/latest/download/SHA256SUMS.txt/x"))
        assertFalse(ReleaseNet.startAllowed("https://github.com/213YaZ786/Calculator/releases/latest/download/sha256sums.txt"))
        assertFalse(ReleaseNet.startAllowed("https://github.com/213YaZ786/Calculator/releases/latest"))
        assertFalse(ReleaseNet.startAllowed("https://github.com/someone/Calculator/releases/latest/download/SHA256SUMS.txt"))
        assertFalse(ReleaseNet.startAllowed("https://github.com/213YaZ786/../Calculator/releases/latest/download/SHA256SUMS.txt"))
        assertFalse(ReleaseNet.startAllowed("https://github.com/213YaZ786/%2e%2e/releases/latest/download/SHA256SUMS.txt"))
        assertFalse(ReleaseNet.startAllowed("${latest("Calculator")}?x=1"))
        assertFalse(ReleaseNet.startAllowed("${latest("Calculator")}#x"))
        assertFalse(ReleaseNet.startAllowed(latest("Calculator").replace("https", "http")))
        assertFalse(ReleaseNet.startAllowed("https://github.com.evil.example/213YaZ786/Calculator/releases/latest/download/SHA256SUMS.txt"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/213YaZ786/Calculator/releases/latest/download/SHA256SUMS.txt"))
    }

    @Test
    fun `the first hop of latest download must name this repo's own SHA256SUMS txt`() {
        val sums = "SHA256SUMS.txt"
        assertEquals("v0.3.6", ReleaseNet.tagOfLocation("Calculator", file("Calculator", "v0.3.6", sums)))
        assertEquals("v1.2.3+4", ReleaseNet.tagOfLocation("Calculator", file("Calculator", "v1.2.3+4", sums)))
        assertEquals("a".repeat(100), ReleaseNet.tagOfLocation("Calculator", file("Calculator", "a".repeat(100), sums)))
        // another repo, another owner, another host, another file, a lookalike
        assertNull(ReleaseNet.tagOfLocation("Calculator", file("SMS", "v1", sums)))
        assertNull(ReleaseNet.tagOfLocation("Calculator", "https://github.com/someone/Calculator/releases/download/v1/$sums"))
        assertNull(ReleaseNet.tagOfLocation("Calculator", "https://github.com/213yaz786/Calculator/releases/download/v1/$sums"))
        assertNull(ReleaseNet.tagOfLocation("Calculator", "https://evil.example/213YaZ786/Calculator/releases/download/v1/$sums"))
        assertNull(ReleaseNet.tagOfLocation("Calculator", "https://github.com.evil.example/213YaZ786/Calculator/releases/download/v1/$sums"))
        assertNull(ReleaseNet.tagOfLocation("Calculator", "https://github.com@evil.example/213YaZ786/Calculator/releases/download/v1/$sums"))
        assertNull(ReleaseNet.tagOfLocation("Calculator", file("Calculator", "v1", sums).replace("https", "http")))
        assertNull(ReleaseNet.tagOfLocation("Calculator", file("Calculator", "v1", "app.json")))
        assertNull(ReleaseNet.tagOfLocation("Calculator", file("Calculator", "v1", sums) + "?x=1"))
        assertNull(ReleaseNet.tagOfLocation("Calculator", file("Calculator", "v1", sums) + "/"))
        // a tag with a slash, an encoded slash, a dots segment, nothing, too long
        assertNull(ReleaseNet.tagOfLocation("Calculator", file("Calculator", "v1/x", sums)))
        assertNull(ReleaseNet.tagOfLocation("Calculator", file("Calculator", "v1%2fx", sums)))
        assertNull(ReleaseNet.tagOfLocation("Calculator", file("Calculator", "v1%2Fx", sums)))
        assertNull(ReleaseNet.tagOfLocation("Calculator", file("Calculator", "%2e%2e", sums)))
        assertNull(ReleaseNet.tagOfLocation("Calculator", file("Calculator", "..", sums)))
        assertNull(ReleaseNet.tagOfLocation("Calculator", file("Calculator", ".", sums)))
        assertNull(ReleaseNet.tagOfLocation("Calculator", "https://github.com/213YaZ786/Calculator/releases/download//$sums"))
        assertNull(ReleaseNet.tagOfLocation("Calculator", file("Calculator", "a".repeat(101), sums)))
        assertNull(ReleaseNet.tagOfLocation("Calculator", file("Calculator", "v 1", sums)))
        assertNull(ReleaseNet.tagOfLocation("Calculator", ""))
        assertNull(ReleaseNet.tagOfLocation("Calculator", null))
        assertNull(ReleaseNet.tagOfLocation("..", "https://github.com/213YaZ786/../releases/download/v1/$sums"))
    }

    @Test
    fun `tags follow the alphabet of a release address`() {
        assertTrue(ReleaseNet.validTag("v1.2.3-b45"))
        assertTrue(ReleaseNet.validTag("v1.2.3+4"))
        assertFalse(ReleaseNet.validTag(""))
        assertFalse(ReleaseNet.validTag("."))
        assertFalse(ReleaseNet.validTag(".."))
        assertFalse(ReleaseNet.validTag("a/b"))
        assertFalse(ReleaseNet.validTag("a%2fb"))
        assertFalse(ReleaseNet.validTag("a b"))
        assertFalse(ReleaseNet.validTag("é"))
        assertFalse(ReleaseNet.startAllowed(notes("Calculator", "v1%2fx")))
        assertFalse(ReleaseNet.startAllowed(notes("Calculator", "..")))
        assertFalse(ReleaseNet.startAllowed(notes("Calculator", "a".repeat(101))))
    }

    @Test
    fun `repo names follow GitHub's rule`() {
        assertTrue(ReleaseNet.startAllowed(notes("My.Repo_1-x")))
        assertTrue(ReleaseNet.validRepo("a".repeat(100)))
        assertFalse(ReleaseNet.validRepo("a".repeat(101)))
        assertFalse(ReleaseNet.validRepo(""))
        assertFalse(ReleaseNet.validRepo("."))
        assertFalse(ReleaseNet.validRepo(".."))
        assertFalse(ReleaseNet.validRepo("a b"))
        assertFalse(ReleaseNet.validRepo("café"))
        assertFalse(ReleaseNet.validRepo("a/b"))
        assertFalse(ReleaseNet.startAllowed(notes("a".repeat(101))))
        assertFalse(ReleaseNet.startAllowed(notes("caf%C3%A9")))
        assertFalse(ReleaseNet.startAllowed(latest("a".repeat(101))))
    }

    @Test
    fun `http is refused`() {
        assertFalse(ReleaseNet.startAllowed(list.replace("https", "http")))
        assertFalse(ReleaseNet.startAllowed(notes("Calculator").replace("https", "http")))
        assertFalse(ReleaseNet.startAllowed(file("Calculator").replace("https", "http")))
        assertFalse(ReleaseNet.startAllowed("ftp://github.com/213YaZ786/Calculator/releases/download/v1/a.apk"))
        assertFalse(ReleaseNet.startAllowed("file:///etc/passwd"))
        assertFalse(ReleaseNet.startAllowed(""))
        assertFalse(ReleaseNet.startAllowed("not a url"))
    }

    @Test
    fun `another owner is refused`() {
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/users/someone/repos?per_page=100&type=owner"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/repos/someone/Calculator/releases/tags/v1"))
        assertFalse(ReleaseNet.startAllowed("https://github.com/someone/Calculator/releases/download/v1/a.apk"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/repos/213yaz786/Calculator/releases/tags/v1"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/repos/213YaZ786x/Calculator/releases/tags/v1"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/repos/213YaZ786/releases/tags/v1"))
    }

    @Test
    fun `a lookalike host is refused`() {
        assertFalse(ReleaseNet.startAllowed("https://api.github.com.evil.example/repos/213YaZ786/Calculator/releases/tags/v1"))
        assertFalse(ReleaseNet.startAllowed("https://github.com.evil.example/213YaZ786/Calculator/releases/download/v1/a.apk"))
        assertFalse(ReleaseNet.startAllowed("https://evilgithub.com/213YaZ786/Calculator/releases/download/v1/a.apk"))
        assertFalse(ReleaseNet.startAllowed("https://api-github.com/repos/213YaZ786/Calculator/releases/tags/v1"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com@evil.example/repos/213YaZ786/Calculator/releases/tags/v1"))
        assertFalse(ReleaseNet.startAllowed("https://evil.example@api.github.com/repos/213YaZ786/Calculator/releases/tags/v1"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com\\@evil.example/repos/213YaZ786/Calculator/releases/tags/v1"))
        assertFalse(ReleaseNet.startAllowed("https://evil.example/https://api.github.com/repos/213YaZ786/Calculator/releases/tags/v1"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com:8443/repos/213YaZ786/Calculator/releases/tags/v1"))
        assertFalse(ReleaseNet.startAllowed("https://raw.githubusercontent.com/213YaZ786/Calculator/main/a.apk"))
        assertFalse(ReleaseNet.startAllowed("https://objects.githubusercontent.com/213YaZ786/Calculator/releases/download/v1/a.apk"))
        assertFalse(ReleaseNet.startAllowed("https://release-assets.githubusercontent.com/213YaZ786/Calculator/releases/download/v1/a.apk"))
        // The API host does not serve release files, nor the pages host the API.
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/213YaZ786/Calculator/releases/download/v1/a.apk"))
        assertFalse(ReleaseNet.startAllowed("https://github.com/repos/213YaZ786/Calculator/releases/tags/v1"))
    }

    @Test
    fun `path tricks are refused`() {
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/repos/213YaZ786/../releases/tags/v1"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/repos/213YaZ786/x/../Calculator/releases/tags/v1"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/repos/213YaZ786/./releases/tags/v1"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/repos/213YaZ786/%2e%2e/releases/tags/v1"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/repos/213YaZ786/%2E%2E/releases/tags/v1"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/repos/213YaZ786/Calculator%2fx/releases/tags/v1"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/repos/%2e%2e/213YaZ786/Calculator/releases/tags/v1"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/repos/213YaZ786/Calculator/releases/tags/v1/"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/repos/213YaZ786/Calculator/releases/tags/v1/../v2"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com//repos/213YaZ786/Calculator/releases/tags/v1"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/repos/213YaZ786/Calculator/releases/tags/v1;x"))
        assertFalse(ReleaseNet.startAllowed(file("Calculator", tag = "..", name = "a.apk")))
        assertFalse(ReleaseNet.startAllowed(file("Calculator", name = "..")))
        assertFalse(ReleaseNet.startAllowed(file("Calculator", name = "%2e%2e")))
        assertFalse(ReleaseNet.startAllowed(file("Calculator", name = "a%2fb.apk")))
        assertFalse(ReleaseNet.startAllowed(file("Calculator", tag = "v1%2fx")))
        assertFalse(ReleaseNet.startAllowed("https://github.com/213YaZ786/Calculator/releases/download/v1/x/a.apk"))
        assertFalse(ReleaseNet.startAllowed("https://github.com/213YaZ786/Calculator/releases/download/v1"))
        assertFalse(ReleaseNet.startAllowed("https://github.com/213YaZ786/Calculator/archive/refs/heads/main.zip"))
        assertFalse(ReleaseNet.startAllowed("https://github.com/213YaZ786/../someone/Calculator/releases/download/v1/a.apk"))
    }

    @Test
    fun `queries and fragments are refused except the repo list's own`() {
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/users/213YaZ786/repos"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/users/213YaZ786/repos?per_page=100"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/users/213YaZ786/repos?per_page=100&type=all"))
        assertFalse(ReleaseNet.startAllowed("$list&page=2"))
        assertFalse(ReleaseNet.startAllowed("$list#x"))
        assertFalse(ReleaseNet.startAllowed("${notes("Calculator")}?x=1"))
        assertFalse(ReleaseNet.startAllowed("${notes("Calculator")}#x"))
        assertFalse(ReleaseNet.startAllowed("${file("Calculator")}?token=1"))
        assertFalse(ReleaseNet.startAllowed("${file("Calculator")}#x"))
    }

    @Test
    fun `every hop of a redirect must be HTTPS to one of GitHub's hosts`() {
        for (host in listOf("api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")) {
            assertEquals(host, ReleaseNet.checked(URL("https://$host/x")).host)
        }
        assertEquals("GitHub.com", ReleaseNet.checked(URL("https://GitHub.com/x")).host)
        for (bad in listOf(
            "http://github.com/x",
            "https://github.com.evil.example/x",
            "https://evil.example/x",
            "https://user@github.com/x",
            "https://github.com:8443/x",
            "https://raw.githubusercontent.com/x",
            "https://githubusercontent.com/x",
            "ftp://github.com/x"
        )) {
            val error = assertThrows(NetError::class.java) { ReleaseNet.checked(URL(bad)) }
            assertEquals(NetError.Kind.REFUSED, error.kind)
        }
    }

    // Source: UpdatesTest of the update kit (https://github.com/213YaZ786/Calculator, app/src/test/java/com/yaz/calculator/core/update/UpdatesTest.kt):
    // each shape asserted allowed or refused, with the lookalikes of its host, owner and path.
    private fun icon(repo: String = "Calculator", owner: String = "213YaZ786", ref: String = "HEAD", path: String = "fastlane/metadata/android/en-US/images/icon.png") =
        "https://github.com/$owner/$repo/raw/$ref/$path"

    // Source: UpdatesTest of the update kit (https://github.com/213YaZ786/Calculator, app/src/test/java/com/yaz/calculator/core/update/UpdatesTest.kt)
    @Test
    fun `the repo icon address is allowed in exactly one shape`() {
        assertEquals(icon(), ReleaseNet.repoIconUrl("Calculator"))
        assertTrue(ReleaseNet.startAllowed(icon()))
        assertTrue(ReleaseNet.startAllowed(icon("Yid")))
        assertFalse(ReleaseNet.startAllowed(icon(owner = "someone")))
        assertFalse(ReleaseNet.startAllowed(icon(owner = "213yaz786")))
        assertFalse(ReleaseNet.startAllowed(icon(repo = "..")))
        assertFalse(ReleaseNet.startAllowed(icon(repo = "a%2fb")))
        assertFalse(ReleaseNet.startAllowed(icon(ref = "main")))
        assertFalse(ReleaseNet.startAllowed(icon(ref = "b93fcc0740e6767393e3544382ea38d126ac76de")))
        assertFalse(ReleaseNet.startAllowed(icon(path = "fastlane/metadata/android/fr-FR/images/icon.png")))
        assertFalse(ReleaseNet.startAllowed(icon(path = "fastlane/metadata/android/en-US/images/featureGraphic.png")))
        assertFalse(ReleaseNet.startAllowed(icon(path = "README.md")))
        assertFalse(ReleaseNet.startAllowed(icon(path = "fastlane/metadata/android/en-US/images/icon.png/x")))
        assertFalse(ReleaseNet.startAllowed(icon(path = "fastlane/../app/build.gradle.kts")))
        assertFalse(ReleaseNet.startAllowed(icon().replace("https", "http")))
        assertFalse(ReleaseNet.startAllowed(icon() + "?x=1"))
        assertFalse(ReleaseNet.startAllowed(icon() + "#x"))
        assertFalse(ReleaseNet.startAllowed(icon().replace("github.com", "github.com.evil.example")))
        assertFalse(ReleaseNet.startAllowed(icon().replace("github.com", "raw.githubusercontent.com")))
        assertFalse(ReleaseNet.startAllowed("https://raw.githubusercontent.com/213YaZ786/Calculator/HEAD/fastlane/metadata/android/en-US/images/icon.png"))
    }

    // Source: UpdatesTest of the update kit (https://github.com/213YaZ786/Calculator, app/src/test/java/com/yaz/calculator/core/update/UpdatesTest.kt)
    @Test
    fun `the raw host is reached only from a repo icon, for the same owner and repo`() {
        val start = URL(icon("Calculator"))
        val path = "fastlane/metadata/android/en-US/images/icon.png"
        val sha = "b93fcc0740e6767393e3544382ea38d126ac76de"
        assertEquals("raw.githubusercontent.com", ReleaseNet.hop(start, URL("https://raw.githubusercontent.com/213YaZ786/Calculator/$sha/$path")).host)
        assertEquals("raw.githubusercontent.com", ReleaseNet.hop(start, URL("https://raw.githubusercontent.com/213YaZ786/Calculator/refs/heads/main/$path")).host)
        for (bad in listOf(
            "https://raw.githubusercontent.com/213YaZ786/SMS/$sha/$path",
            "https://raw.githubusercontent.com/someone/Calculator/$sha/$path",
            "https://raw.githubusercontent.com/213YaZ786/Calculator/$sha/README.md",
            "https://raw.githubusercontent.com/213YaZ786/Calculator/$sha/fastlane/metadata/android/fr-FR/images/icon.png",
            "https://raw.githubusercontent.com/213YaZ786/Calculator/../SMS/$sha/$path",
            "https://raw.githubusercontent.com/213YaZ786/Calculator//$path",
            "https://raw.githubusercontent.com/213YaZ786/Calculator/$path",
            "https://raw.githubusercontent.com/213YaZ786/Calculator/$sha/$path?x=1",
            "https://raw.githubusercontent.com/213YaZ786/Calculator/$sha/$path#x",
            "http://raw.githubusercontent.com/213YaZ786/Calculator/$sha/$path",
            "https://raw.githubusercontent.com:8443/213YaZ786/Calculator/$sha/$path",
            "https://user@raw.githubusercontent.com/213YaZ786/Calculator/$sha/$path",
            "https://raw.githubusercontent.com.evil.example/213YaZ786/Calculator/$sha/$path",
            "https://gist.githubusercontent.com/213YaZ786/Calculator/$sha/$path"
        )) {
            val error = assertThrows(bad, NetError::class.java) { ReleaseNet.hop(start, URL(bad)) }
            assertEquals(NetError.Kind.REFUSED, error.kind)
        }
        // from any other start, the raw host is refused, even for a good path
        val good = URL("https://raw.githubusercontent.com/213YaZ786/Calculator/$sha/$path")
        for (other in listOf(
            file("Calculator", "v1", "icon.png"), latest("Calculator"), icon("SMS"),
            "https://api.github.com/users/213YaZ786/repos?per_page=100&type=owner"
        )) {
            val error = assertThrows(other, NetError::class.java) { ReleaseNet.hop(URL(other), good) }
            assertEquals(NetError.Kind.REFUSED, error.kind)
        }
        // other hosts still follow the old rule
        assertEquals("objects.githubusercontent.com", ReleaseNet.hop(start, URL("https://objects.githubusercontent.com/x")).host)
    }

    private fun words(repo: String = "Calculator", owner: String = "213YaZ786", ref: String = "HEAD", path: String = "fastlane/metadata/android/fr-FR/short_description.txt") =
        "https://github.com/$owner/$repo/raw/$ref/$path"

    // Source: UpdatesTest of the update kit (https://github.com/213YaZ786/Calculator, app/src/test/java/com/yaz/calculator/core/update/UpdatesTest.kt),
    // the shape of the repo icon test above, for the short description of one locale (https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots).
    @Test
    fun `the short description address is allowed for a fastlane locale and nothing else`() {
        for (locale in listOf("en-US", "fr-FR", "pt-BR", "es-419", "de", "fil", "zh-CN")) {
            val url = words(path = "fastlane/metadata/android/$locale/short_description.txt")
            assertEquals(url, ReleaseNet.repoDescriptionUrl("Calculator", locale))
            assertTrue(locale, ReleaseNet.startAllowed(url))
        }
        assertTrue(ReleaseNet.startAllowed(words("Yid")))
        // a bad locale: refused by the builder and by the allow-list
        for (locale in listOf("", "EN-US", "en-us", "en_US", "e", "english", "en-USA", "en-U", "en-41", "en-4192", "fr-FR/..", "..", "../x", "en-US/images", "fr FR", "fr-FR\n", "é", "%2e%2e")) {
            assertThrows(locale, IllegalArgumentException::class.java) { ReleaseNet.repoDescriptionUrl("Calculator", locale) }
            assertFalse(locale, ReleaseNet.startAllowed(words(path = "fastlane/metadata/android/$locale/short_description.txt")))
        }
        assertFalse(ReleaseNet.startAllowed(words(path = "fastlane/metadata/android/fr-FR/../../../../README.md")))
        assertFalse(ReleaseNet.startAllowed(words(path = "fastlane/metadata/android/fr-FR/full_description.txt")))
        assertFalse(ReleaseNet.startAllowed(words(path = "fastlane/metadata/android/fr-FR/title.txt")))
        assertFalse(ReleaseNet.startAllowed(words(path = "fastlane/metadata/android/fr-FR/short_description.txt/x")))
        assertFalse(ReleaseNet.startAllowed(words(path = "fastlane/metadata/android/fr-FR/Short_description.txt")))
        assertFalse(ReleaseNet.startAllowed(words(path = "fastlane/metadata/ios/fr-FR/short_description.txt")))
        assertFalse(ReleaseNet.startAllowed(words(path = "fastlane/metadata/android/short_description.txt")))
        assertFalse(ReleaseNet.startAllowed(words(path = "metadata/android/fr-FR/short_description.txt")))
        assertFalse(ReleaseNet.startAllowed(words(owner = "someone")))
        assertFalse(ReleaseNet.startAllowed(words(owner = "213yaz786")))
        assertFalse(ReleaseNet.startAllowed(words(repo = "..")))
        assertFalse(ReleaseNet.startAllowed(words(repo = "a%2fb")))
        assertFalse(ReleaseNet.startAllowed(words(ref = "main")))
        assertFalse(ReleaseNet.startAllowed(words(ref = "b93fcc0740e6767393e3544382ea38d126ac76de")))
        assertFalse(ReleaseNet.startAllowed(words() + "?x=1"))
        assertFalse(ReleaseNet.startAllowed(words() + "#x"))
        assertFalse(ReleaseNet.startAllowed(words().replace("https", "http")))
        assertFalse(ReleaseNet.startAllowed(words().replace("github.com", "github.com.evil.example")))
        assertFalse(ReleaseNet.startAllowed(words().replace("github.com", "raw.githubusercontent.com")))
    }

    @Test
    fun `the raw host is reached from a short description only for the same owner, repo and file`() {
        val path = "fastlane/metadata/android/fr-FR/short_description.txt"
        val sha = "b93fcc0740e6767393e3544382ea38d126ac76de"
        val start = URL(words("Calculator"))
        assertEquals("raw.githubusercontent.com", ReleaseNet.hop(start, URL("https://raw.githubusercontent.com/213YaZ786/Calculator/$sha/$path")).host)
        assertEquals("raw.githubusercontent.com", ReleaseNet.hop(start, URL("https://raw.githubusercontent.com/213YaZ786/Calculator/refs/heads/main/$path")).host)
        for (bad in listOf(
            "https://raw.githubusercontent.com/213YaZ786/SMS/$sha/$path",
            "https://raw.githubusercontent.com/someone/Calculator/$sha/$path",
            "https://raw.githubusercontent.com/213YaZ786/Calculator/$sha/fastlane/metadata/android/en-US/short_description.txt",
            "https://raw.githubusercontent.com/213YaZ786/Calculator/$sha/fastlane/metadata/android/fr-FR/full_description.txt",
            "https://raw.githubusercontent.com/213YaZ786/Calculator/$sha/README.md",
            "https://raw.githubusercontent.com/213YaZ786/Calculator/../SMS/$sha/$path",
            "https://raw.githubusercontent.com/213YaZ786/Calculator/$sha/$path?x=1",
            "https://raw.githubusercontent.com/213YaZ786/Calculator/$sha/$path#x",
            "http://raw.githubusercontent.com/213YaZ786/Calculator/$sha/$path"
        )) {
            val error = assertThrows(bad, NetError::class.java) { ReleaseNet.hop(start, URL(bad)) }
            assertEquals(NetError.Kind.REFUSED, error.kind)
        }
        // the icon's start does not let the description's file through, nor the other way
        val icon = URL(icon("Calculator"))
        val error = assertThrows(NetError::class.java) { ReleaseNet.hop(icon, URL("https://raw.githubusercontent.com/213YaZ786/Calculator/$sha/$path")) }
        assertEquals(NetError.Kind.REFUSED, error.kind)
    }

    @Test
    fun `an address that is not allowed is never opened, for each door`() {
        val net = ReleaseNet(java.io.File(System.getProperty("java.io.tmpdir"), "store-net-test"))
        for (call in listOf<() -> Any?>(
            { net.getJson("http://api.github.com/users/213YaZ786/repos?per_page=100&type=owner") },
            { net.getJson("https://evil.example/x") },
            { net.latestTag("..") },
            { net.latestTag("a/b") },
            { net.exists("https://api.github.com/users/213YaZ786/repos?per_page=100&type=owner") },
            { net.exists("https://github.com/someone/App/releases/download/v1/icon.png") },
            { net.download("https://evil.example/a.apk", java.io.File.createTempFile("store-test", ".apk"), onProgress = {}) }
        )) {
            val error = assertThrows(NetError::class.java) { call() }
            assertEquals(NetError.Kind.REFUSED, error.kind)
        }
    }
}
