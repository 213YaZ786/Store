package com.yaz.store.data.catalog

// The layers of an app's icon, as each app publishes them (icon-layers.json beside its PNG layers, fastlane/metadata/android/en-US/images/layers/,
// written by Modules' icons/publish.py): the grey pictures of its adaptive icon and, per layer, the Material You tone the launcher tints it with
// (AOSP Launcher3 draws our adaptive icons from their layers the same way). The file comes from outside: it is read only by this strict parser,
// every name, hash, tone and number checked, anything off gives null and the app keeps its icon.png.
// Read as CatalogRules.parseAppJson reads app.json: kotlinx.serialization's tree, so a number must be a JSON number and a string a string
// (JsonPrimitive.isString, https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/kotlinx.serialization.json/-json-primitive/is-string.html).
// org.json is not used: its getters turn a string into a number and stand in for a missing key, and the unit tests run with Android's
// stubs (isReturnDefaultValues), where it parses nothing.
import com.yaz.store.data.net.ReleaseNet
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The colour of a layer or of the ground by day and by night: a TONE of the file (a system_* name or a hex colour). */
data class LayerTones(val day: String, val night: String)

/** One picture of the icon: its file name, its SHA-256 and its tones (null: drawn untinted). */
data class IconLayer(val png: String, val sha256: String, val tones: LayerTones?)

/** The fractions of the 108 dp canvas an InsetDrawable foreground is shrunk by on each side, each in [0, 0.5). */
data class LayerInset(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** The icon of an app: the [ground] colour under the [layers], bottom first; the layers are drawn inside [inset] when it has one. */
data class IconLayers(val ground: LayerTones, val layers: List<IconLayer>, val inset: LayerInset? = null) {

    companion object {
        /** The published file is a few hundred bytes. */
        const val MAX_JSON = 16 * 1024
        const val MAX_LAYERS = 12

        // The public android.R.color.system_* names (API 31, https://developer.android.com/reference/android/R.color#system_accent1_0): five palettes, 13 steps each.
        private val SYSTEM_TONE = Regex("system_(accent1|accent2|accent3|neutral1|neutral2)_(0|10|50|100|200|300|400|500|600|700|800|900|1000)")
        private val HEX_TONE = Regex("#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})")
        private val SHA256 = Regex("[0-9a-f]{64}")

        /** Whether [tone] is a name or a colour the file may carry. */
        fun validTone(tone: String): Boolean = SYSTEM_TONE.matches(tone) || HEX_TONE.matches(tone)

        // Source: CatalogRules.parseAppJson (this app's data/catalog/Catalog.kt), the same strict reading, with the allow-lists of ReleaseNet.
        /**
         * The layers of [text], or null when anything is not exactly as published: version 1, a ground, 1 to 12 layers, each name, hash and tone
         * valid, a layer with both tones or none, an inset (when there is one) with its four sides.
         */
        fun parse(text: String): IconLayers? {
            if (text.length > MAX_JSON) return null
            val tree = try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            } ?: return null
            val version = tree["v"] as? JsonPrimitive ?: return null
            if (version.isString || version.content != "1") return null
            val ground = tones(tree["ground"]) ?: return null
            val inset = if (tree.containsKey("inset")) inset(tree["inset"]) ?: return null else null
            val array = tree["layers"] as? JsonArray ?: return null
            if (array.size !in 1..MAX_LAYERS) return null
            val layers = array.map { item ->
                val layer = item as? JsonObject ?: return null
                val png = layer.string("png")?.takeIf(ReleaseNet::validLayerPng) ?: return null
                val sha = layer.string("sha256")?.takeIf { SHA256.matches(it) } ?: return null
                val colours = if (layer.containsKey("day") || layer.containsKey("night")) tones(layer) ?: return null else null
                IconLayer(png, sha, colours)
            }
            return IconLayers(ground, layers, inset)
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        // Both day and night, each a valid tone (a layer carries both or neither: the caller asks only when one of them is there).
        private fun tones(element: JsonElement?): LayerTones? {
            val obj = element as? JsonObject ?: return null
            val day = obj.string("day")?.takeIf(::validTone) ?: return null
            val night = obj.string("night")?.takeIf(::validTone) ?: return null
            return LayerTones(day, night)
        }

        // Source: AOSP InsetDrawable, whose percentage insets are fractions of its bounds
        // (https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/graphics/java/android/graphics/drawable/InsetDrawable.java, onBoundsChange).
        // All four sides, each a JSON number, finite, from 0 up to but not including 0.5, else null.
        private fun inset(element: JsonElement?): LayerInset? {
            val obj = element as? JsonObject ?: return null
            fun side(key: String): Float? {
                val number = obj[key] as? JsonPrimitive ?: return null
                if (number.isString) return null
                val value = number.content.toDoubleOrNull() ?: return null
                return if (value.isFinite() && value >= 0.0 && value < 0.5) value.toFloat() else null
            }
            return LayerInset(side("left") ?: return null, side("top") ?: return null, side("right") ?: return null, side("bottom") ?: return null)
        }
    }
}
