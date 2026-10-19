package com.yaz.store.feature.home

// Fixed data for the screens' tests: apps in every state, as Now in Android's screenshot tests draw their screens from fixed fake data
// (https://github.com/android/nowinandroid, core/screenshot-testing and its testing module's test data).
import com.yaz.store.data.catalog.AppState
import com.yaz.store.data.catalog.AppStatus
import com.yaz.store.data.catalog.InstalledFacts
import com.yaz.store.data.catalog.ReleaseDetails
import com.yaz.store.data.catalog.StoreEntry
import java.time.Instant

object Fixtures {

    fun status(
        repo: String,
        label: String,
        state: AppState,
        installed: String?,
        offered: String,
        handOver: Boolean = false,
        packageName: String = "com.yaz.${repo.lowercase()}",
        description: String = "",
        minSdk: Int = 31
    ) = AppStatus(
        StoreEntry(
            repo = repo, tag = "v$offered", packageName = packageName, label = label, description = description, versionCode = 2,
            versionName = offered, minSdk = minSdk, apkName = "$repo.apk".takeIf { state != AppState.NO_APK }, iconUrl = null
        ),
        state,
        installed?.let { InstalledFacts(1, it, emptySet(), null, null) },
        handOver
    )

    val calculator = status(
        "Calculator", "Calculator", AppState.UPDATE, "0.3.5", "0.3.6", handOver = true,
        description = "Calculator, converter and sheets"
    )
    val dialer = status("Dialer", "Dialer", AppState.UP_TO_DATE, "0.14.20", "0.14.20", description = "Phone calls, history and speed dial")
    val sms = status("SMS", "SMS", AppState.UP_TO_DATE, "0.15.6", "0.15.6", description = "Messages and encrypted chat")
    val files = status("Files", "Files", AppState.NOT_INSTALLED, null, "0.2.0", description = "Files, APKs and storage cleaning")
    val mushaf = status("Mushaf", "Mushaf", AppState.UP_TO_DATE, "0.9.27", "0.9.27", packageName = "org.mushaf.app", description = "The Quran")
    val mtga = status("MTGA", "MTGA", AppState.FOREIGN, "2.15.15", "2.15.15", packageName = "com.mtga.app", description = "A reader for X")
    val keyboard = status("Keyboard", "Keyboard", AppState.TOO_NEW, null, "0.1.0", minSdk = 37, description = "A keyboard")
    val voice = status("Voice", "Voice", AppState.NO_APK, null, "0.1.0", description = "Speech recognition for the phone")
    val sms2 = status("Contacts", "Contacts", AppState.HAND_OVER, "0.9.11", "0.9.11", handOver = true, description = "People on the phone")

    /** A phone with an update waiting, an app to install, and each reason an app cannot be had. */
    val everything = listOf(calculator, dialer, sms, files, mushaf, mtga, keyboard, voice, sms2)

    /** Every installed app is current. */
    val current = listOf(dialer, sms, files, mushaf)

    val notes = ReleaseDetails(
        notes = "Faster sums on long expressions.\nThe unit converter remembers its last pair.\nFixed the sheet's column width after a turn of the screen.\n" +
            "Fixed a crash on an empty cell.\nSee https://github.com/213YaZ786/Calculator/releases for older versions.",
        publishedAt = Instant.parse("2026-10-17T12:00:00Z"),
        sizes = mapOf("Calculator.apk" to 4_200_000L, "Files.apk" to 6_800_000L)
    )

    val details = mapOf("Calculator/v0.3.6" to notes)
}
