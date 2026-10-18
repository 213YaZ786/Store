package com.yaz.store.core.install

import android.net.Uri
import com.yaz.store.core.apk.SplitEntry
import com.yaz.store.core.apk.SplitTables
import com.yaz.store.core.apk.SplitType
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

// What the install screen shows of a set of APKs, and the hand-over from the activity to the screen.
// The groups follow the types App Manager sorts a bundle's entries by (ApkFile.Entry type: ABI, density, locale,
// feature, unknown; GPL-3.0-or-later):
// https://github.com/MuntashirAkon/AppManager/blob/master/app/src/main/java/io/github/muntashirakon/AppManager/apk/ApkFile.java
// and the short signer form is the colon separated start of the SHA-256 that apksigner and Android's own
// package installer show for a certificate:
// https://developer.android.com/studio/command-line/apksigner

/** The sections of the splits list, in the order shown. */
enum class SplitGroup(val title: String) {
    PROCESSOR("Processor"),
    DENSITY("Screen density"),
    LANGUAGES("Languages"),
    FEATURES("Features"),
    OTHER("Other")
}

object InstallPlan {

    fun groupOf(type: SplitType): SplitGroup? = when (type) {
        SplitType.BASE -> null
        SplitType.ABI -> SplitGroup.PROCESSOR
        SplitType.DENSITY -> SplitGroup.DENSITY
        SplitType.LOCALE -> SplitGroup.LANGUAGES
        SplitType.FEATURE -> SplitGroup.FEATURES
        SplitType.UNKNOWN -> SplitGroup.OTHER
    }

    /** The entries of [entries] by section, empty sections left out, the base apart. */
    fun groups(entries: List<SplitEntry>): List<Pair<SplitGroup, List<SplitEntry>>> =
        SplitGroup.entries.mapNotNull { group ->
            val rows = entries.filter { groupOf(it.type) == group }
            if (rows.isEmpty()) null else group to rows
        }

    /** The name of a row. */
    fun title(entry: SplitEntry): String {
        val suffix = entry.suffix
        val base = when (entry.type) {
            SplitType.ABI -> SplitTables.ABIS[suffix] ?: entry.name
            SplitType.DENSITY -> "$suffix (${SplitTables.DENSITIES[suffix]} dpi)"
            SplitType.LOCALE -> Locale.forLanguageTag(suffix.orEmpty()).let { locale ->
                locale.getDisplayName(Locale.getDefault()).replaceFirstChar { it.uppercase() }.ifEmpty { entry.name }
            }
            else -> entry.name
        }
        // A split of a feature says which.
        return if (entry.isForFeature) "$base, ${entry.feature}" else base
    }

    /** "3A:F2:..." the first 8 bytes of a SHA-256 given as 64 hex digits. */
    fun shortSigner(hex: String): String = hex.take(16).chunked(2).joinToString(":").uppercase()
}

// A file comes from a content:// or from a path, as App Manager's ApkFile is built from either (a Uri or a Path,
// https://github.com/MuntashirAkon/AppManager/blob/master/app/src/main/java/io/github/muntashirakon/AppManager/apk/ApkFile.java);
// the two kinds are a sealed interface (https://kotlinlang.org/docs/sealed-classes.html).

/** What waits for the install screen: a content:// of another app, or the path of a file the app holds. */
sealed interface InstallSource {
    class FromUri(val uri: Uri) : InstallSource

    /** Checked against the storage volumes when it is read (ApkSource.openFile), not when it is offered. */
    class FromPath(val path: String) : InstallSource
}

/**
 * The file waiting for the install screen. A content:// reaches it only if it is another app's (MainActivity checks);
 * a path is checked when it is read; only the screen takes it.
 */
class InstallInbox {
    private val _pending = MutableStateFlow<InstallSource?>(null)
    val pending: StateFlow<InstallSource?> = _pending.asStateFlow()

    fun offer(uri: Uri) {
        _pending.value = InstallSource.FromUri(uri)
    }

    fun offerPath(path: String) {
        _pending.value = InstallSource.FromPath(path)
    }

    /** Takes the waiting file, once. */
    fun take(): InstallSource? = _pending.getAndUpdate { null }
}
