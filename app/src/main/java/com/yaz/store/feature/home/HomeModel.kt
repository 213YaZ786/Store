package com.yaz.store.feature.home

// What Home shows, decided apart from drawing so a test reads it. The two lists (an app installed or not, GrapheneOS' App Store shows
// every app once with one state button, https://github.com/GrapheneOS/AppStore, app/src/main/java/app/grapheneos/apps/ui/MainScreen.kt),
// the family groups (YAZ first, then the others, each alphabetical: Kotlin's sortedWith,
// https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/sorted-with.html) and the state button of a card. Android's names of
// the API levels are Build.VERSION_CODES' (https://developer.android.com/reference/android/os/Build.VERSION_CODES).
import androidx.annotation.StringRes
import com.yaz.store.R
import com.yaz.store.data.catalog.AppState
import com.yaz.store.data.catalog.AppStatus
import com.yaz.store.data.catalog.ReleaseDetails
import com.yaz.store.data.catalog.StoreEntry
import com.yaz.store.data.install.InstallJob

/**
 * The key of a release's notes, date and sizes: its repo and its tag, so a new release never shows the old one's notes
 * (a Kotlin extension property, https://kotlinlang.org/docs/extensions.html#extension-properties).
 */
val StoreEntry.detailsKey: String get() = "$repo/$tag"

/** A heading and its apps. */
data class AppGroup(@StringRes val title: Int, val apps: List<AppStatus>)

object HomeModel {

    /** Apps of the suite carry this prefix (the YAZ apps, one signing key). */
    private const val YAZ = "com.yaz."

    /** The apps found on this phone, and the ones that are not. A foreign copy is installed, left alone. */
    fun installed(apps: List<AppStatus>): List<AppStatus> = apps.filter { it.installed != null }

    fun available(apps: List<AppStatus>): List<AppStatus> = apps.filter { it.installed == null }

    /** com.yaz.* apps under "YAZ", the others under "More", each alphabetical; an empty group is left out. */
    fun groups(apps: List<AppStatus>): List<AppGroup> {
        val order = compareBy<AppStatus>({ it.entry.label.lowercase() }, { it.entry.packageName })
        val (yaz, more) = apps.partition { it.entry.packageName.startsWith(YAZ) }
        return listOf(
            AppGroup(R.string.group_yaz, yaz.sortedWith(order)),
            AppGroup(R.string.group_more, more.sortedWith(order))
        ).filter { it.apps.isNotEmpty() }
    }

    /** The updates the Updates zone lists, in the lists' order. */
    fun updates(apps: List<AppStatus>): List<AppStatus> = groups(apps).flatMap { it.apps }.filter { it.state == AppState.UPDATE }

    /** Every installed app is current and Store holds it: the state the easter egg waits in. */
    fun allCurrent(apps: List<AppStatus>): Boolean =
        apps.any { it.state == AppState.UP_TO_DATE } && apps.none { it.state == AppState.UPDATE || it.state == AppState.HAND_OVER }

    /** The sum of the downloads of [updates], or null when one size is not known yet. */
    fun totalSize(updates: List<AppStatus>, details: Map<String, ReleaseDetails>): Long? {
        if (updates.isEmpty()) return null
        var sum = 0L
        for (u in updates) sum += sizeOf(u, details) ?: return null
        return sum
    }

    /** The size of the APK this phone downloads, from the release's file list. */
    fun sizeOf(status: AppStatus, details: Map<String, ReleaseDetails>): Long? =
        status.entry.apkName?.let { details[status.entry.detailsKey]?.sizes?.get(it) }

    /** What a card's one button does, or why it cannot. */
    sealed interface Action {
        data object Open : Action
        data object Update : Action
        data object Install : Action
        data object HandOver : Action
        data class Disabled(@StringRes val reason: Int, val sdk: Int = 0) : Action
    }

    fun action(status: AppStatus): Action = when (status.state) {
        AppState.UP_TO_DATE -> Action.Open
        AppState.UPDATE -> Action.Update
        AppState.NOT_INSTALLED -> Action.Install
        AppState.HAND_OVER -> Action.HandOver
        AppState.FOREIGN -> Action.Disabled(R.string.reason_foreign)
        AppState.TOO_NEW -> Action.Disabled(R.string.reason_too_new, status.entry.minSdk)
        AppState.NO_APK -> Action.Disabled(R.string.reason_no_apk)
    }

    /** The Android version a release needs, as people name it: 31 is 12, 36 is 16; an unknown level is "API n". */
    fun androidName(sdk: Int): String = when (sdk) {
        31 -> "12"
        32 -> "12L"
        33 -> "13"
        34 -> "14"
        35 -> "15"
        36 -> "16"
        37 -> "17"
        else -> "API $sdk"
    }

    /** The entry of the queue that is working on [status]'s app, if any. */
    fun jobOf(status: AppStatus, jobs: List<InstallJob>): InstallJob? = jobs.firstOrNull { it.entry.packageName == status.entry.packageName }
}
