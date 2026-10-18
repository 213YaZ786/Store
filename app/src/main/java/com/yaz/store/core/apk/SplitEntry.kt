package com.yaz.store.core.apk

import java.util.Locale

// Ported from App Manager (GPL-3.0-or-later):
// - ApkFile.Entry, its type, required flag, feature and rank:
//   https://github.com/MuntashirAkon/AppManager/blob/master/app/src/main/java/io/github/muntashirakon/AppManager/apk/ApkFile.java
// - StaticDataset, the ABI and density tables and the locale ranking:
//   https://github.com/MuntashirAkon/AppManager/blob/master/app/src/main/java/io/github/muntashirakon/AppManager/StaticDataset.java
// Changes: pure Kotlin, the device (ABIs, density, languages) passed in instead of read from Build and Resources,
// and the manifest read by the isolated inspector (ApkInfo).

/** The kinds of APK of a bundle, in the order App Manager sorts them (APK_BASE ... APK_SPLIT_UNKNOWN). */
enum class SplitType { BASE, FEATURE, ABI, DENSITY, LOCALE, UNKNOWN }

/**
 * What this phone offers, to rank the splits: its ABIs in Build.SUPPORTED_ABIS order, its screen density
 * in dpi, and the languages of its locale list in order.
 */
data class DeviceProfile(val abis: List<String>, val density: Int, val languages: List<String>)

/** App Manager's StaticDataset tables. */
object SplitTables {
    /** Split suffix to ABI, StaticDataset.ALL_ABIS. */
    val ABIS: Map<String, String> = mapOf(
        "armeabi_v7a" to "armeabi-v7a",
        "arm64_v8a" to "arm64-v8a",
        "x86" to "x86",
        "x86_64" to "x86_64"
    )

    /** Split suffix to dpi, StaticDataset.DENSITY_NAME_TO_DENSITY (DisplayMetrics.DENSITY_*). */
    val DENSITIES: Map<String, Int> = mapOf(
        "ldpi" to 120,
        "mdpi" to 160,
        "tvdpi" to 213,
        "hdpi" to 240,
        "xhdpi" to 320,
        "xxhdpi" to 480,
        "xxxhdpi" to 640
    )

    /**
     * LangUtils.isValidLocale of App Manager (utils/LangUtils.java): a tag Java knows. Added: a tag Java cannot read
     * comes out as the root locale, which is in the list, so an empty language is refused.
     */
    fun isValidLocale(tag: String): Boolean {
        val locale = Locale.forLanguageTag(tag)
        return locale.language.isNotEmpty() && Locale.getAvailableLocales().any { it == locale }
    }
}

/**
 * One APK of a bundle: the base, or a split. [id] is the file's name in the install folder.
 * [rank] is smallest for the best fit within a type, as in App Manager; a split that does not fit
 * the phone keeps [Int.MAX_VALUE].
 */
data class SplitEntry(
    val id: String,
    val name: String,
    val type: SplitType,
    val required: Boolean,
    /** The feature this split belongs to: its own name for a feature split, configForSplit otherwise, null for the base. */
    val feature: String?,
    val suffix: String?,
    val rank: Int,
    val size: Long
) {
    val isBase: Boolean get() = type == SplitType.BASE

    /** ApkFile.Entry.supported(): an ABI split the phone cannot run is not supported. */
    val supported: Boolean get() = type != SplitType.ABI || rank != Int.MAX_VALUE

    /** ApkFile.Entry.isForFeature(). */
    val isForFeature: Boolean get() = type != SplitType.BASE && type != SplitType.FEATURE && feature != null

    /** A row the person cannot toggle: required, or not for this phone (SplitApkChooser.getUnsupportedOrRequiredSplitIds). */
    val locked: Boolean get() = required || !supported

    companion object {
        private const val CONFIG_PREFIX = "config."

        /** ApkFile.Entry's constructor: the type and rank from the manifest's split attributes. */
        fun of(id: String, size: Long, info: ApkInfo, device: DeviceProfile): SplitEntry {
            val split = info.splitName
            if (split == null) {
                return SplitEntry(id, "base", SplitType.BASE, true, null, null, Int.MAX_VALUE, size)
            }
            val forFeature = info.configForSplit?.takeIf { it.isNotEmpty() }
            if (info.isFeatureSplit) {
                return SplitEntry(id, split, SplitType.FEATURE, info.isSplitRequired, split, null, Int.MAX_VALUE, size)
            }
            val at = split.lastIndexOf(CONFIG_PREFIX)
            if (at == -1 || (at != 0 && split[at - 1] != '.')) {
                return SplitEntry(id, split, SplitType.UNKNOWN, info.isSplitRequired, forFeature, null, Int.MAX_VALUE, size)
            }
            val suffix = split.substring(at + CONFIG_PREFIX.length)
            // A split for the base app ranks 1000 ahead of the splits of features.
            val base = if (forFeature == null) 1000 else 0
            val abi = SplitTables.ABIS[suffix]
            val dpi = SplitTables.DENSITIES[suffix]
            return when {
                abi != null -> {
                    val index = device.abis.indexOf(abi)
                    val rank = if (index != -1) index - base else Int.MAX_VALUE
                    SplitEntry(id, split, SplitType.ABI, info.isSplitRequired, forFeature, suffix, rank, size)
                }
                dpi != null -> {
                    val rank = Math.abs(device.density - dpi) - base
                    SplitEntry(id, split, SplitType.DENSITY, info.isSplitRequired, forFeature, suffix, rank, size)
                }
                SplitTables.isValidLocale(suffix) -> {
                    val index = device.languages.indexOf(suffix)
                    val rank = if (index != -1) index - base else Int.MAX_VALUE
                    SplitEntry(id, split, SplitType.LOCALE, info.isSplitRequired, forFeature, suffix, rank, size)
                }
                else -> SplitEntry(id, split, SplitType.UNKNOWN, info.isSplitRequired, forFeature, suffix, Int.MAX_VALUE, size)
            }
        }

        /** ApkFile's sort: by type, then by rank. */
        val ORDER: Comparator<SplitEntry> = compareBy<SplitEntry> { it.type.ordinal }.thenBy { it.rank }
    }
}
