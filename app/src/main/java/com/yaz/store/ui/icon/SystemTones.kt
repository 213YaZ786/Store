package com.yaz.store.ui.icon

// The tones an app's icon-layers.json names, as colours of this phone. A tone is one of the 65 public Material You names (API 31, the palettes the
// launcher tints our adaptive icons with: https://developer.android.com/reference/android/R.color#system_accent1_0, android.R.color.system_accent1_0
// to system_neutral2_1000) or a hex colour. Nothing is looked up by a name from the file: the name must be in the table below, which IconLayers
// has already checked against the same list.
import android.content.Context
import android.graphics.Color

object SystemTones {

    // Source: the list of android.R.color constants (https://developer.android.com/reference/android/R.color), the 65 system_* ones.
    private fun resource(tone: String): Int? = when (tone) {
        "system_accent1_0" -> android.R.color.system_accent1_0
        "system_accent1_10" -> android.R.color.system_accent1_10
        "system_accent1_50" -> android.R.color.system_accent1_50
        "system_accent1_100" -> android.R.color.system_accent1_100
        "system_accent1_200" -> android.R.color.system_accent1_200
        "system_accent1_300" -> android.R.color.system_accent1_300
        "system_accent1_400" -> android.R.color.system_accent1_400
        "system_accent1_500" -> android.R.color.system_accent1_500
        "system_accent1_600" -> android.R.color.system_accent1_600
        "system_accent1_700" -> android.R.color.system_accent1_700
        "system_accent1_800" -> android.R.color.system_accent1_800
        "system_accent1_900" -> android.R.color.system_accent1_900
        "system_accent1_1000" -> android.R.color.system_accent1_1000
        "system_accent2_0" -> android.R.color.system_accent2_0
        "system_accent2_10" -> android.R.color.system_accent2_10
        "system_accent2_50" -> android.R.color.system_accent2_50
        "system_accent2_100" -> android.R.color.system_accent2_100
        "system_accent2_200" -> android.R.color.system_accent2_200
        "system_accent2_300" -> android.R.color.system_accent2_300
        "system_accent2_400" -> android.R.color.system_accent2_400
        "system_accent2_500" -> android.R.color.system_accent2_500
        "system_accent2_600" -> android.R.color.system_accent2_600
        "system_accent2_700" -> android.R.color.system_accent2_700
        "system_accent2_800" -> android.R.color.system_accent2_800
        "system_accent2_900" -> android.R.color.system_accent2_900
        "system_accent2_1000" -> android.R.color.system_accent2_1000
        "system_accent3_0" -> android.R.color.system_accent3_0
        "system_accent3_10" -> android.R.color.system_accent3_10
        "system_accent3_50" -> android.R.color.system_accent3_50
        "system_accent3_100" -> android.R.color.system_accent3_100
        "system_accent3_200" -> android.R.color.system_accent3_200
        "system_accent3_300" -> android.R.color.system_accent3_300
        "system_accent3_400" -> android.R.color.system_accent3_400
        "system_accent3_500" -> android.R.color.system_accent3_500
        "system_accent3_600" -> android.R.color.system_accent3_600
        "system_accent3_700" -> android.R.color.system_accent3_700
        "system_accent3_800" -> android.R.color.system_accent3_800
        "system_accent3_900" -> android.R.color.system_accent3_900
        "system_accent3_1000" -> android.R.color.system_accent3_1000
        "system_neutral1_0" -> android.R.color.system_neutral1_0
        "system_neutral1_10" -> android.R.color.system_neutral1_10
        "system_neutral1_50" -> android.R.color.system_neutral1_50
        "system_neutral1_100" -> android.R.color.system_neutral1_100
        "system_neutral1_200" -> android.R.color.system_neutral1_200
        "system_neutral1_300" -> android.R.color.system_neutral1_300
        "system_neutral1_400" -> android.R.color.system_neutral1_400
        "system_neutral1_500" -> android.R.color.system_neutral1_500
        "system_neutral1_600" -> android.R.color.system_neutral1_600
        "system_neutral1_700" -> android.R.color.system_neutral1_700
        "system_neutral1_800" -> android.R.color.system_neutral1_800
        "system_neutral1_900" -> android.R.color.system_neutral1_900
        "system_neutral1_1000" -> android.R.color.system_neutral1_1000
        "system_neutral2_0" -> android.R.color.system_neutral2_0
        "system_neutral2_10" -> android.R.color.system_neutral2_10
        "system_neutral2_50" -> android.R.color.system_neutral2_50
        "system_neutral2_100" -> android.R.color.system_neutral2_100
        "system_neutral2_200" -> android.R.color.system_neutral2_200
        "system_neutral2_300" -> android.R.color.system_neutral2_300
        "system_neutral2_400" -> android.R.color.system_neutral2_400
        "system_neutral2_500" -> android.R.color.system_neutral2_500
        "system_neutral2_600" -> android.R.color.system_neutral2_600
        "system_neutral2_700" -> android.R.color.system_neutral2_700
        "system_neutral2_800" -> android.R.color.system_neutral2_800
        "system_neutral2_900" -> android.R.color.system_neutral2_900
        "system_neutral2_1000" -> android.R.color.system_neutral2_1000
        else -> null
    }

    // Source: Context.getColor (https://developer.android.com/reference/android/content/Context#getColor(int)) for the system names,
    // Color.parseColor (https://developer.android.com/reference/android/graphics/Color#parseColor(java.lang.String)) for "#RRGGBB" and "#AARRGGBB".
    /** The colour (ARGB) of [tone] on this phone now, or null when it is neither a known system name nor a valid hex colour. */
    fun colour(context: Context, tone: String): Int? {
        resource(tone)?.let { return context.getColor(it) }
        if (!tone.startsWith("#")) return null
        return try {
            Color.parseColor(tone)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
