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
        description: String = "{}",
        apks: String = "[\"Calculator-0.3.6.apk\"]"
    ) = """{"package":"$pkg","versionCode":$code,"versionName":"$name","label":"$label","minSdk":$sdk,"description":$description,"apks":$apks}"""

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
        val app = CatalogRules.parseAppJson("""{"package":"a.b","versionCode":1,"versionName":"1","label":"A","minSdk":21,"extra":{"x":1},"apks":["A-1.apk"]}""")
        assertNotNull(app)
        assertTrue(app!!.descriptions.isEmpty())
    }

    @Test
    fun `a missing field leaves the app out`() {
        for (field in listOf("package", "versionCode", "versionName", "label", "minSdk", "apks")) {
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

    // The apks list of app.json (JUnit 4, https://github.com/junit-team/junit4/wiki/Assertions).
    private fun apks(vararg names: String) = names.joinToString(",", "[", "]") { "\"$it\"" }

    @Test
    fun `one to ten apks are listed`() {
        assertEquals(listOf("A-1.apk"), CatalogRules.parseAppJson(appJson(apks = apks("A-1.apk")))!!.apks)
        val five = listOf("A-1-arm64-v8a.apk", "A-1-armeabi-v7a.apk", "A-1-universal.apk", "A-1-x86.apk", "A-1-x86_64.apk")
        assertEquals(five, CatalogRules.parseAppJson(appJson(apks = apks(*five.toTypedArray())))!!.apks)
        val ten = (1..10).map { "A-$it.apk" }
        assertEquals(ten, CatalogRules.parseAppJson(appJson(apks = apks(*ten.toTypedArray())))!!.apks)
        assertNull(CatalogRules.parseAppJson(appJson(apks = apks(*(1..11).map { "A-$it.apk" }.toTypedArray()))))
    }

    @Test
    fun `no apk listed leaves the app out`() {
        assertNull(CatalogRules.parseAppJson(appJson(apks = "[]")))
        assertNull(CatalogRules.parseAppJson(appJson(apks = "null")))
        assertNull(CatalogRules.parseAppJson(appJson(apks = "\"A-1.apk\"")))
        assertNull(CatalogRules.parseAppJson(appJson(apks = "[3]")))
        assertNull(CatalogRules.parseAppJson(appJson(apks = "[null]")))
    }

    @Test
    fun `an apk name must be a plain release apk`() {
        for (bad in listOf("A-1-debug.apk", "A-1.APK", "A-1.apk.sig", "A-1", ".apk.txt", "a/b.apk", "../a.apk", "a b.apk", "é.apk", "a%2fb.apk", "a+b.apk", "")) {
            assertNull(bad, CatalogRules.parseAppJson(appJson(apks = apks(bad))))
        }
        // One bad name among good ones leaves the app out.
        assertNull(CatalogRules.parseAppJson(appJson(apks = apks("A-1.apk", "A-1-debug.apk"))))
        assertNotNull(CatalogRules.parseAppJson(appJson(apks = apks("a".repeat(196) + ".apk"))))
        assertNull(CatalogRules.parseAppJson(appJson(apks = apks("a".repeat(197) + ".apk"))))
        assertTrue(CatalogRules.validApk("My.App_1-2.apk"))
        assertFalse(CatalogRules.validApk("x-debug.apk"))
    }

    @Test
    fun `a repeated apk name is listed once`() {
        assertEquals(listOf("A-1.apk"), CatalogRules.parseAppJson(appJson(apks = apks("A-1.apk", "A-1.apk")))!!.apks)
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

    // The date and sizes of a tag (JUnit 4, https://github.com/junit-team/junit4/wiki/Assertions; the answer of
    // https://docs.github.com/en/rest/releases/releases#get-a-release-by-tag-name). A body in the answer is ignored.
    private fun release(tag: String = "v1.0.0", published: String = "2026-10-18T10:00:00Z") =
        """{"tag_name":"$tag","published_at":"$published","body":"Fixes","assets":[{"name":"A-1.apk","size":1234},{"name":"SHA256SUMS.txt","size":0},{"name":"","size":5}]}"""

    @Test
    fun `the date and sizes of a tag are read`() {
        val details = CatalogRules.parseDetails(release(), "v1.0.0")!!
        assertEquals(java.time.Instant.parse("2026-10-18T10:00:00Z"), details.publishedAt)
        assertEquals(mapOf("A-1.apk" to 1234L), details.sizes)
    }

    @Test
    fun `another tag's answer, a bad date or no json is not that release`() {
        assertNull(CatalogRules.parseDetails(release(tag = "v2"), "v1.0.0"))
        assertNull(CatalogRules.parseDetails("<html>", "v1.0.0"))
        assertNull(CatalogRules.parseDetails("[]", "v1.0.0"))
        assertNull(CatalogRules.parseDetails(release(published = "yesterday"), "v1.0.0")!!.publishedAt)
        assertEquals(emptyMap<String, Long>(), CatalogRules.parseDetails("""{"tag_name":"v1","body":null}""", "v1")!!.sizes)
    }

    @Test
    fun `stored details read back as they were written`() {
        val details = ReleaseDetails(java.time.Instant.parse("2026-10-18T10:00:00Z"), mapOf("A-1.apk" to 1234L))
        assertEquals(details, CatalogRules.parseStored(CatalogRules.storeDetails(details)))
        assertEquals(ReleaseDetails(null, emptyMap()), CatalogRules.parseStored(CatalogRules.storeDetails(ReleaseDetails(null, emptyMap()))))
        // A file kept by an earlier build still carries its notes: they are ignored.
        assertEquals(details, CatalogRules.parseStored("""{"notes":"old","publishedAt":"2026-10-18T10:00:00Z","sizes":{"A-1.apk":1234}}"""))
        assertNull(CatalogRules.parseStored("not json"))
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

    // The shapes of GitHub's list repositories for a user: "description" is a string or null (https://docs.github.com/en/rest/repos/repos#list-repositories-for-a-user).
    @Test
    fun `a repo's description is one clean line of at most 300 characters, empty when GitHub has none`() {
        val long = "x".repeat(400)
        val text = """[
          {"name":"A","owner":{"login":"213YaZ786"},"description":"Phone calls\u0007 and ‮history"},
          {"name":"B","owner":{"login":"213YaZ786"},"description":null},
          {"name":"C","owner":{"login":"213YaZ786"}},
          {"name":"D","owner":{"login":"213YaZ786"},"description":"   "},
          {"name":"E","owner":{"login":"213YaZ786"},"description":"$long"}
        ]"""
        val repos = CatalogRules.parseRepoList(text)!!.associate { it.name to it.description }
        assertEquals("Phone calls and history", repos["A"])
        assertEquals("", repos["B"])
        assertEquals("", repos["C"])
        assertEquals("", repos["D"])
        assertEquals(300, repos["E"]!!.length)
    }
}
