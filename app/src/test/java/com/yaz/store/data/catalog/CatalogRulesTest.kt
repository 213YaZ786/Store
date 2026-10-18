package com.yaz.store.data.catalog

// JUnit 4 as the app's other unit tests (https://github.com/junit-team/junit4/wiki/Assertions, core/apk/ZipGuardTest and the rest).
// app.json is the one the shared CI writes (Modules shared/base/root/.github/workflows/build.yml.tpl): the recorded file in
// src/test/resources/catalog was made by that script's own commands (aapt2 dump badging of a released APK, jq over the fastlane
// short descriptions, https://docs.fastlane.tools/actions/supply/#images-and-screenshots).
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRulesTest {

    private fun recorded() = javaClass.getResource("/catalog/app.json")!!.readText()

    private fun appJson(
        pkg: String = "com.yaz.calculator",
        code: String = "14",
        name: String = "0.3.6",
        label: String = "Calculator",
        sdk: String = "31",
        description: String = "{}"
    ) = """{"package":"$pkg","versionCode":$code,"versionName":"$name","label":"$label","minSdk":$sdk,"description":$description}"""

    // app.json

    @Test
    fun `the recorded app json is read`() {
        val app = CatalogRules.parseAppJson(recorded())!!
        assertEquals("com.yaz.calculator", app.packageName)
        assertEquals(14L, app.versionCode)
        assertEquals("0.3.6", app.versionName)
        assertEquals("Calculator", app.label)
        assertEquals(31, app.minSdk)
        assertEquals(setOf("en-US", "fr-FR"), app.descriptions.keys)
        assertEquals("Installe et met à jour les apps 213YaZ786 depuis leurs releases", app.descriptions["fr-FR"])
    }

    @Test
    fun `unknown keys are ignored and the description may be absent`() {
        val app = CatalogRules.parseAppJson("""{"package":"a.b","versionCode":1,"versionName":"1","label":"A","minSdk":21,"extra":{"x":1}}""")
        assertNotNull(app)
        assertTrue(app!!.descriptions.isEmpty())
    }

    @Test
    fun `a missing field leaves the app out`() {
        for (field in listOf("package", "versionCode", "versionName", "label", "minSdk")) {
            val text = recorded().replace("\"$field\"", "\"x$field\"")
            assertNull(field, CatalogRules.parseAppJson(text))
        }
    }

    @Test
    fun `a wrong type leaves the app out`() {
        assertNull(CatalogRules.parseAppJson(appJson(code = "\"14\"")))
        assertNull(CatalogRules.parseAppJson(appJson(code = "14.5")))
        assertNull(CatalogRules.parseAppJson(appJson(code = "null")))
        assertNull(CatalogRules.parseAppJson(appJson(code = "99999999999999999999")))
        assertNull(CatalogRules.parseAppJson(appJson(sdk = "\"31\"")))
        assertNull(CatalogRules.parseAppJson(appJson(sdk = "31.5")))
        assertNull(CatalogRules.parseAppJson(appJson(sdk = "99999999999")))
        assertNull(CatalogRules.parseAppJson(appJson(description = "[\"x\"]")))
        assertNull(CatalogRules.parseAppJson(appJson(description = "{\"en-US\":3}")))
        assertNull(CatalogRules.parseAppJson(appJson(label = "A").replace("\"label\":\"A\"", "\"label\":{\"a\":1}")))
    }

    @Test
    fun `not json at all leaves the app out`() {
        for (text in listOf("", " ", "[]", "null", "42", "<html>404</html>", "{", "{\"package\":", "\u0000")) {
            assertNull(text, CatalogRules.parseAppJson(text))
        }
    }

    @Test
    fun `versionCode must be positive and minSdk a real level`() {
        assertNotNull(CatalogRules.parseAppJson(appJson(code = "1")))
        assertNotNull(CatalogRules.parseAppJson(appJson(code = Long.MAX_VALUE.toString())))
        assertNull(CatalogRules.parseAppJson(appJson(code = "0")))
        assertNull(CatalogRules.parseAppJson(appJson(code = "-3")))
        assertNull(CatalogRules.parseAppJson(appJson(sdk = "0")))
        assertNull(CatalogRules.parseAppJson(appJson(sdk = "-1")))
        assertNull(CatalogRules.parseAppJson(appJson(sdk = "1001")))
    }

    @Test
    fun `a package that is no package leaves the app out`() {
        assertNotNull(CatalogRules.parseAppJson(appJson(pkg = "org.mushaf.app")))
        assertNotNull(CatalogRules.parseAppJson(appJson(pkg = "com.yaz.store.debug")))
        assertNotNull(CatalogRules.parseAppJson(appJson(pkg = "a.b_c.D9")))
        for (bad in listOf("calculator", "", ".com.x", "com.", "com..x", "1com.x", "com.1x", "com.x-y", "com.x y", "com.x/..", "com.é.x")) {
            assertNull(bad, CatalogRules.parseAppJson(appJson(pkg = bad)))
        }
        val long = "a." + "b".repeat(253)
        assertEquals(255, long.length)
        assertNotNull(CatalogRules.parseAppJson(appJson(pkg = long)))
        assertNull(CatalogRules.parseAppJson(appJson(pkg = long + "b")))
        assertTrue(CatalogRules.validPackage("com.yaz.store"))
        assertFalse(CatalogRules.validPackage("store"))
    }

    @Test
    fun `huge strings leave the app out`() {
        assertNotNull(CatalogRules.parseAppJson(appJson(label = "L".repeat(60))))
        assertNull(CatalogRules.parseAppJson(appJson(label = "L".repeat(61))))
        assertNull(CatalogRules.parseAppJson(appJson(label = "L".repeat(1_000_000))))
        assertNull(CatalogRules.parseAppJson(appJson(name = "9".repeat(101))))
        assertNotNull(CatalogRules.parseAppJson(appJson(description = "{\"en-US\":\"${"d".repeat(300)}\"}")))
        assertNull(CatalogRules.parseAppJson(appJson(description = "{\"en-US\":\"${"d".repeat(301)}\"}")))
        assertNull(CatalogRules.parseAppJson(appJson(description = "{\"en-US\":\"${"d".repeat(2_000_000)}\"}")))
        val many = (1..101).joinToString(",", "{", "}") { "\"l$it\":\"x\"" }
        assertNull(CatalogRules.parseAppJson(appJson(description = many)))
        assertNull(CatalogRules.parseAppJson(appJson(description = "{\"${"k".repeat(36)}\":\"x\"}")))
        assertNull(CatalogRules.parseAppJson(appJson(description = "{\"../x\":\"x\"}")))
    }

    @Test
    fun `control characters and bidirectional overrides are dropped from shown texts`() {
        val app = CatalogRules.parseAppJson(appJson(label = "Cal\\u0007c\\u202Eulator", name = "1.0\\u0000", description = "{\"en-US\":\"a\\nb\\u202Ec\"}"))!!
        assertEquals("Calculator", app.label)
        assertEquals("1.0", app.versionName)
        assertEquals("abc", app.descriptions["en-US"])
        assertNull(CatalogRules.parseAppJson(appJson(label = "\\u0007\\u202E ")))
    }

    // Assets

    private fun asset(name: String) = ReleaseAsset(name, "https://github.com/213YaZ786/App/releases/download/v1/$name", 10)

    @Test
    fun `one release apk with its files is an app`() {
        val chosen = CatalogRules.chooseAssets(
            listOf("App-1.0.apk", "App-1.0-debug.apk", "SHA256SUMS.txt", "app.json", "icon.png").map(::asset)
        )!!
        assertEquals("App-1.0.apk", chosen.apk.name)
        assertEquals("SHA256SUMS.txt", chosen.sums.name)
        assertEquals("app.json", chosen.appJson.name)
        assertEquals("icon.png", chosen.icon?.name)
    }

    @Test
    fun `the icon is optional`() {
        val chosen = CatalogRules.chooseAssets(listOf("App-1.0.apk", "SHA256SUMS.txt", "app.json").map(::asset))!!
        assertNull(chosen.icon)
    }

    @Test
    fun `a debug apk alone is not an app`() {
        assertNull(CatalogRules.chooseAssets(listOf("App-1.0-debug.apk", "SHA256SUMS.txt", "app.json").map(::asset)))
    }

    @Test
    fun `two release apks are not guessed between`() {
        assertNull(CatalogRules.chooseAssets(listOf("App-1.0.apk", "App-1.0-arm64.apk", "SHA256SUMS.txt", "app.json").map(::asset)))
    }

    @Test
    fun `no apk is not an app`() {
        assertNull(CatalogRules.chooseAssets(listOf("SHA256SUMS.txt", "app.json", "icon.png").map(::asset)))
        assertNull(CatalogRules.chooseAssets(emptyList()))
    }

    @Test
    fun `a release without its sums or its app json is not an app`() {
        assertNull(CatalogRules.chooseAssets(listOf("App-1.0.apk", "app.json").map(::asset)))
        assertNull(CatalogRules.chooseAssets(listOf("App-1.0.apk", "SHA256SUMS.txt").map(::asset)))
    }

    @Test
    fun `names are exact`() {
        assertNull(CatalogRules.chooseAssets(listOf("App-1.0.APK", "SHA256SUMS.txt", "app.json").map(::asset)))
        assertNull(CatalogRules.chooseAssets(listOf("App-1.0.apk", "sha256sums.txt", "app.json").map(::asset)))
        assertNull(CatalogRules.chooseAssets(listOf("App-1.0.apk", "SHA256SUMS.txt", "App.json").map(::asset)))
        assertNull(CatalogRules.chooseAssets(listOf("App-1.0.apk.sig", "SHA256SUMS.txt", "app.json").map(::asset)))
    }

    // Locale choice

    private val descriptions = mapOf("en-US" to "English", "fr-FR" to "Français", "pt-PT" to "Português", "pt-BR" to "Brasileiro")

    @Test
    fun `the exact tag wins`() {
        assertEquals("Français", CatalogRules.pickDescription(descriptions, listOf("fr-FR", "en-US")))
        assertEquals("Brasileiro", CatalogRules.pickDescription(descriptions, listOf("pt-BR")))
        assertEquals("Português", CatalogRules.pickDescription(descriptions, listOf("pt-PT")))
    }

    @Test
    fun `the language comes next`() {
        assertEquals("Français", CatalogRules.pickDescription(descriptions, listOf("fr-CA")))
        assertEquals("Français", CatalogRules.pickDescription(descriptions, listOf("fr")))
        assertEquals("Français", CatalogRules.pickDescription(mapOf("fr" to "Français"), listOf("fr-CA")))
        assertEquals("Português", CatalogRules.pickDescription(mapOf("pt-PT" to "Português"), listOf("pt-BR")))
    }

    @Test
    fun `English comes last, then nothing`() {
        assertEquals("English", CatalogRules.pickDescription(descriptions, listOf("de-DE")))
        assertEquals("English", CatalogRules.pickDescription(descriptions, emptyList()))
        assertEquals("", CatalogRules.pickDescription(mapOf("fr-FR" to "Français"), listOf("de-DE")))
        assertEquals("", CatalogRules.pickDescription(emptyMap(), listOf("fr-FR")))
    }

    @Test
    fun `the phone's first language that the app has wins`() {
        assertEquals("Français", CatalogRules.pickDescription(descriptions, listOf("de-DE", "fr-CA", "pt-BR")))
        assertEquals("Brasileiro", CatalogRules.pickDescription(descriptions, listOf("pt-BR", "fr-FR")))
    }

    @Test
    fun `tags compare without case and with underscores`() {
        assertEquals("Français", CatalogRules.pickDescription(mapOf("fr_FR" to "Français"), listOf("FR-fr")))
        assertEquals("English", CatalogRules.pickDescription(mapOf("EN-us" to "English"), listOf("de")))
    }

    // Plain text

    @Test
    fun `a release body becomes plain text`() {
        assertEquals("a\nb\nc\td", CatalogRules.plainText("a\r\nb\rc\td\u0000\u0007", 100))
        assertEquals("ab", CatalogRules.plainText("a‮b⁦", 100))
        assertEquals("", CatalogRules.plainText("  \u0000 ", 100))
    }

    @Test
    fun `a release body is cut without splitting a pair`() {
        assertEquals("a😀", CatalogRules.plainText("a😀b", 2))
        assertEquals(20_000, CatalogRules.plainText("x".repeat(5_000_000), CatalogRules.MAX_NOTES).length)
    }

    @Test
    fun `only our own release page is kept`() {
        assertEquals("https://github.com/213YaZ786/App/releases/tag/v1", CatalogRules.pageOf("App", "https://github.com/213YaZ786/App/releases/tag/v1"))
        val fallback = "https://github.com/213YaZ786/App/releases/latest"
        assertEquals(fallback, CatalogRules.pageOf("App", "https://evil.example/213YaZ786/App/releases/tag/v1"))
        assertEquals(fallback, CatalogRules.pageOf("App", "https://github.com/someone/App/releases/tag/v1"))
        assertEquals(fallback, CatalogRules.pageOf("App", "https://github.com/213YaZ786/Other/releases/tag/v1"))
        assertEquals(fallback, CatalogRules.pageOf("App", ""))
        assertEquals(fallback, CatalogRules.pageOf("App", "https://github.com/213YaZ786/App/releases/tag/v1 x"))
    }

    // Repo list

    @Test
    fun `archived repos, forks, other owners and odd names are skipped`() {
        val text = """[
          {"name":"Calculator","owner":{"login":"213YaZ786"}},
          {"name":"Old","archived":true,"owner":{"login":"213YaZ786"}},
          {"name":"Forked","fork":true,"owner":{"login":"213YaZ786"}},
          {"name":"Theirs","owner":{"login":"someone"}},
          {"name":"No owner"},
          {"name":"..","owner":{"login":"213YaZ786"}},
          {"name":"a b","owner":{"login":"213YaZ786"}},
          {"name":"Mushaf","archived":false,"fork":false,"owner":{"login":"213yaz786"},"extra":1}
        ]"""
        assertEquals(listOf("Calculator", "Mushaf"), CatalogRules.parseRepos(text))
        assertNull(CatalogRules.parseRepos("{}"))
        assertNull(CatalogRules.parseRepos("<html>"))
        assertEquals(emptyList<String>(), CatalogRules.parseRepos("[]"))
    }
}
