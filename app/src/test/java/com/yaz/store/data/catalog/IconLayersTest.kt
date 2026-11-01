package com.yaz.store.data.catalog

// JUnit 4 as the app's other unit tests (https://github.com/junit-team/junit4/wiki/Assertions, CatalogRulesTest): a file as publish.py writes it,
// then hostile ones (a version that lies, too many layers, a path in a name, a hash in capitals, a tone out of the list, half of the tones,
// text that is no JSON, numbers too big, an inset out of range).
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class IconLayersTest {

    private val sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    private fun layer(png: String = "dial_body.png", hash: String = sha, tones: String = ""","day":"system_accent1_600","night":"system_accent1_200"""") =
        """{"png":"$png","sha256":"$hash"$tones}"""

    private fun file(
        v: String = "1",
        ground: String = """{"day":"system_accent2_100","night":"#FF202020"}""",
        layers: String = layer() + "," + layer("dial_shine.png", tones = ""),
        extra: String = ""
    ) = """{"v":$v,"ground":$ground,$extra"layers":[$layers]}"""

    @Test
    fun `a good file is read`() {
        val icon = IconLayers.parse(file())!!
        assertEquals(LayerTones("system_accent2_100", "#FF202020"), icon.ground)
        assertEquals(listOf("dial_body.png", "dial_shine.png"), icon.layers.map { it.png })
        assertEquals(LayerTones("system_accent1_600", "system_accent1_200"), icon.layers[0].tones)
        assertNull(icon.layers[1].tones)
        assertEquals(sha, icon.layers[0].sha256)
        assertNull(icon.inset)
    }

    @Test
    fun `hex tones and twelve layers are allowed`() {
        val twelve = (1..12).joinToString(",") { layer("l$it.png", tones = ""","day":"#112233","night":"#80112233"""") }
        assertEquals(12, IconLayers.parse(file(layers = twelve))!!.layers.size)
    }

    @Test
    fun `every public system tone is allowed and no other`() {
        for (palette in listOf("accent1", "accent2", "accent3", "neutral1", "neutral2")) {
            for (step in listOf(0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000)) {
                assertNotNull("system_${palette}_$step", IconLayers.parse(file(ground = """{"day":"system_${palette}_$step","night":"#000000"}""")))
            }
        }
        for (bad in listOf("system_accent1_150", "system_accent4_100", "system_accent1_", "System_accent1_100", "system_accent1_100 ", "accent1_100", "#12345", "#GGGGGG", "#1234567", "red", "")) {
            assertNull(bad, IconLayers.parse(file(ground = """{"day":"$bad","night":"#000000"}""")))
        }
    }

    @Test
    fun `a bad version leaves the file out`() {
        for (v in listOf("2", "0", "\"1\"", "1.0", "01", "null", "true", "[1]", "99999999999999999999999", "1e999")) {
            assertNull(v, IconLayers.parse(file(v = v)))
        }
        assertNull(IconLayers.parse(file().replace("\"v\"", "\"version\"")))
    }

    @Test
    fun `a missing or half a ground leaves the file out`() {
        assertNull(IconLayers.parse("""{"v":1,"layers":[${layer()}]}"""))
        assertNull(IconLayers.parse(file(ground = """{"day":"system_accent2_100"}""")))
        assertNull(IconLayers.parse(file(ground = """{"night":"system_accent2_100"}""")))
        assertNull(IconLayers.parse(file(ground = "\"system_accent2_100\"")))
        assertNull(IconLayers.parse(file(ground = """{"day":1,"night":2}""")))
    }

    @Test
    fun `no layer or thirteen layers leave the file out`() {
        assertNull(IconLayers.parse(file(layers = "")))
        val thirteen = (1..13).joinToString(",") { layer("l$it.png") }
        assertNull(IconLayers.parse(file(layers = thirteen)))
        assertNull(IconLayers.parse("""{"v":1,"ground":{"day":"#000000","night":"#000000"},"layers":{}}"""))
        assertNull(IconLayers.parse(file(layers = "1")))
    }

    @Test
    fun `a name that is not a plain png leaves the file out`() {
        for (name in listOf("../x.png", "a/b.png", "a\\b.png", "X.png", "x.PNG", "x.png.png", "x.jpg", ".png", "x", "", "icon-layers.json", "%2e%2e.png", "x.png\n",
            "a".repeat(49) + ".png")) {
            assertNull(name, IconLayers.parse(file(layers = layer(png = name))))
        }
        assertNotNull(IconLayers.parse(file(layers = layer(png = "a".repeat(48) + ".png"))))
    }

    @Test
    fun `a hash that is not 64 lower case hex leaves the file out`() {
        for (hash in listOf(sha.uppercase(), sha.dropLast(1), sha + "0", "g" + sha.drop(1), "", " $sha")) {
            assertNull(hash, IconLayers.parse(file(layers = layer(hash = hash))))
        }
    }

    @Test
    fun `a layer has both tones or none`() {
        assertNull(IconLayers.parse(file(layers = layer(tones = ""","day":"system_accent1_600""""))))
        assertNull(IconLayers.parse(file(layers = layer(tones = ""","night":"system_accent1_600""""))))
        assertNull(IconLayers.parse(file(layers = layer(tones = ""","day":"system_accent1_600","night":"blue""""))))
        assertNull(IconLayers.parse(file(layers = layer(tones = ""","day":"system_accent1_600","night":null"""))))
        assertNull(IconLayers.parse(file(layers = layer(tones = ""","day":5,"night":6"""))))
    }

    @Test
    fun `text that is no file leaves it out`() {
        for (text in listOf("", "not json", "[]", "null", "\"x\"", "{", "{\"v\":1", "{\"v\":1,}", "[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[")) {
            assertNull(text, IconLayers.parse(text))
        }
    }

    @Test
    fun `a file past 16 KiB is refused before it is read`() {
        assertNull(IconLayers.parse(file(extra = "\"pad\":\"" + "x".repeat(IconLayers.MAX_JSON) + "\",")))
    }

    @Test
    fun `huge numbers leave the file out`() {
        assertNull(IconLayers.parse(file(v = "1" + "0".repeat(5000))))
        assertNull(IconLayers.parse(file(extra = "\"inset\":{\"left\":1e999,\"top\":0,\"right\":0,\"bottom\":0},")))
    }

    private fun inset(l: String = "0.07", t: String = "0.055", r: String = "0.07", b: String = "0.085") =
        "\"inset\":{\"left\":$l,\"top\":$t,\"right\":$r,\"bottom\":$b},"

    @Test
    fun `a good inset is read`() {
        assertEquals(LayerInset(0.07f, 0.055f, 0.07f, 0.085f), IconLayers.parse(file(extra = inset()))!!.inset)
        assertEquals(LayerInset(0f, 0f, 0f, 0.49f), IconLayers.parse(file(extra = inset("0", "0", "0", "0.49")))!!.inset)
    }

    @Test
    fun `a bad inset leaves the file out`() {
        for (bad in listOf("0.5", "0.51", "-0.01", "1", "\"0.1\"", "null", "true", "1e999", "-1e999", "[]", "{}")) {
            assertNull(bad, IconLayers.parse(file(extra = inset(l = bad))))
            assertNull(bad, IconLayers.parse(file(extra = inset(b = bad))))
        }
        assertNull(IconLayers.parse(file(extra = "\"inset\":{\"left\":0.1,\"top\":0.1,\"right\":0.1},")))
        assertNull(IconLayers.parse(file(extra = "\"inset\":{},")))
        assertNull(IconLayers.parse(file(extra = "\"inset\":0.1,")))
        assertNull(IconLayers.parse(file(extra = "\"inset\":null,")))
        assertNull(IconLayers.parse(file(extra = "\"inset\":[0.1,0.1,0.1,0.1],")))
    }
}
