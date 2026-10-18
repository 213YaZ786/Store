package com.yaz.store.data.net

// Follows the update kit's UpdatesTest protocol (https://github.com/213YaZ786/Calculator, app/src/test/java/com/yaz/calculator/core/update/UpdatesTest.kt):
// each address is asserted as allowed or refused, with the lookalikes of its host and its owner.
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

class ReleaseNetTest {

    private val list = "https://api.github.com/users/213YaZ786/repos?per_page=100&type=owner"
    private fun latest(repo: String) = "https://api.github.com/repos/213YaZ786/$repo/releases/latest"
    private fun file(repo: String, tag: String = "v1.0.0", name: String = "a.apk") =
        "https://github.com/213YaZ786/$repo/releases/download/$tag/$name"

    @Test
    fun `the three addresses of our account are allowed`() {
        assertTrue(ReleaseNet.startAllowed(list))
        assertTrue(ReleaseNet.startAllowed(latest("Calculator")))
        assertTrue(ReleaseNet.startAllowed(file("Calculator", name = "Calculator-1.0.0.apk")))
        assertTrue(ReleaseNet.startAllowed(file("Calculator", name = "SHA256SUMS.txt")))
        assertTrue(ReleaseNet.startAllowed(file("Calculator", tag = "v1.2.3-b45", name = "app.json")))
        assertTrue(ReleaseNet.startAllowed("https://api.github.com:443/repos/213YaZ786/Calculator/releases/latest"))
        assertEquals(list, ReleaseNet.repoListUrl())
        assertEquals(latest("Mushaf"), ReleaseNet.latestUrl("Mushaf"))
    }

    @Test
    fun `repo names follow GitHub's rule`() {
        assertTrue(ReleaseNet.startAllowed(latest("My.Repo_1-x")))
        assertTrue(ReleaseNet.validRepo("a".repeat(100)))
        assertFalse(ReleaseNet.validRepo("a".repeat(101)))
        assertFalse(ReleaseNet.validRepo(""))
        assertFalse(ReleaseNet.validRepo("."))
        assertFalse(ReleaseNet.validRepo(".."))
        assertFalse(ReleaseNet.validRepo("a b"))
        assertFalse(ReleaseNet.validRepo("café"))
        assertFalse(ReleaseNet.validRepo("a/b"))
        assertFalse(ReleaseNet.startAllowed(latest("a".repeat(101))))
        assertFalse(ReleaseNet.startAllowed(latest("caf%C3%A9")))
    }

    @Test
    fun `http is refused`() {
        assertFalse(ReleaseNet.startAllowed(list.replace("https", "http")))
        assertFalse(ReleaseNet.startAllowed(latest("Calculator").replace("https", "http")))
        assertFalse(ReleaseNet.startAllowed(file("Calculator").replace("https", "http")))
        assertFalse(ReleaseNet.startAllowed("ftp://github.com/213YaZ786/Calculator/releases/download/v1/a.apk"))
        assertFalse(ReleaseNet.startAllowed("file:///etc/passwd"))
        assertFalse(ReleaseNet.startAllowed(""))
        assertFalse(ReleaseNet.startAllowed("not a url"))
    }

    @Test
    fun `another owner is refused`() {
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/users/someone/repos?per_page=100&type=owner"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/repos/someone/Calculator/releases/latest"))
        assertFalse(ReleaseNet.startAllowed("https://github.com/someone/Calculator/releases/download/v1/a.apk"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/repos/213yaz786/Calculator/releases/latest"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/repos/213YaZ786x/Calculator/releases/latest"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/repos/213YaZ786/releases/latest"))
    }

    @Test
    fun `a lookalike host is refused`() {
        assertFalse(ReleaseNet.startAllowed("https://api.github.com.evil.example/repos/213YaZ786/Calculator/releases/latest"))
        assertFalse(ReleaseNet.startAllowed("https://github.com.evil.example/213YaZ786/Calculator/releases/download/v1/a.apk"))
        assertFalse(ReleaseNet.startAllowed("https://evilgithub.com/213YaZ786/Calculator/releases/download/v1/a.apk"))
        assertFalse(ReleaseNet.startAllowed("https://api-github.com/repos/213YaZ786/Calculator/releases/latest"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com@evil.example/repos/213YaZ786/Calculator/releases/latest"))
        assertFalse(ReleaseNet.startAllowed("https://evil.example@api.github.com/repos/213YaZ786/Calculator/releases/latest"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com\\@evil.example/repos/213YaZ786/Calculator/releases/latest"))
        assertFalse(ReleaseNet.startAllowed("https://evil.example/https://api.github.com/repos/213YaZ786/Calculator/releases/latest"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com:8443/repos/213YaZ786/Calculator/releases/latest"))
        assertFalse(ReleaseNet.startAllowed("https://raw.githubusercontent.com/213YaZ786/Calculator/main/a.apk"))
        assertFalse(ReleaseNet.startAllowed("https://objects.githubusercontent.com/213YaZ786/Calculator/releases/download/v1/a.apk"))
        // The API host does not serve release files, nor the pages host the API.
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/213YaZ786/Calculator/releases/download/v1/a.apk"))
        assertFalse(ReleaseNet.startAllowed("https://github.com/repos/213YaZ786/Calculator/releases/latest"))
    }

    @Test
    fun `path tricks are refused`() {
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/repos/213YaZ786/../releases/latest"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/repos/213YaZ786/x/../Calculator/releases/latest"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/repos/213YaZ786/./releases/latest"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/repos/213YaZ786/%2e%2e/releases/latest"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/repos/213YaZ786/%2E%2E/releases/latest"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/repos/213YaZ786/Calculator%2fx/releases/latest"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/repos/%2e%2e/213YaZ786/Calculator/releases/latest"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/repos/213YaZ786/Calculator/releases/latest/"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/repos/213YaZ786/Calculator/releases/latest/../latest"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com//repos/213YaZ786/Calculator/releases/latest"))
        assertFalse(ReleaseNet.startAllowed("https://api.github.com/repos/213YaZ786/Calculator/releases/latest;x"))
        assertFalse(ReleaseNet.startAllowed(file("Calculator", tag = "..", name = "a.apk")))
        assertFalse(ReleaseNet.startAllowed(file("Calculator", name = "..")))
        assertFalse(ReleaseNet.startAllowed(file("Calculator", name = "%2e%2e")))
        assertFalse(ReleaseNet.startAllowed(file("Calculator", name = "a%2fb.apk")))
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
        assertFalse(ReleaseNet.startAllowed("${latest("Calculator")}?x=1"))
        assertFalse(ReleaseNet.startAllowed("${latest("Calculator")}#x"))
        assertFalse(ReleaseNet.startAllowed("${file("Calculator")}?token=1"))
        assertFalse(ReleaseNet.startAllowed("${file("Calculator")}#x"))
    }

    @Test
    fun `a release file belongs to its own repo only`() {
        assertTrue(ReleaseNet.isReleaseFileOf("Calculator", file("Calculator")))
        assertFalse(ReleaseNet.isReleaseFileOf("Calculator", file("SMS")))
        assertFalse(ReleaseNet.isReleaseFileOf("Calculator", latest("Calculator")))
        assertFalse(ReleaseNet.isReleaseFileOf("Calculator", file("Calculator").replace("https", "http")))
        assertFalse(ReleaseNet.isReleaseFileOf("Calculator", "https://github.com/someone/Calculator/releases/download/v1/a.apk"))
        assertFalse(ReleaseNet.isReleaseFileOf("Calculator", ""))
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
}
