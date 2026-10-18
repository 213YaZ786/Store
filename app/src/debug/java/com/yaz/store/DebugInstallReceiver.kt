package com.yaz.store

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yaz.store.data.catalog.AbiChoice
import com.yaz.store.data.catalog.CatalogRules
import com.yaz.store.data.catalog.StoreEntry
import com.yaz.store.data.catalog.phoneAbis
import com.yaz.store.data.install.InstallQueue
import com.yaz.store.data.net.ReleaseNet
import java.io.File
import org.koin.core.context.GlobalContext

/**
 * Debug builds only: puts one entry in the install queue, built from a repo, a tag and an app.json read from a file in the app's own
 * files folder, so a real release can be installed before any release carries app.json. It goes the same way as a tap on Install:
 * sums, download, checks, install. It does not exist in the release build (src/debug).
 * Ported from Files (YAZ/Files at HEAD, app/src/debug/java/com/yaz/files/DebugUpdateReceiver.kt), itself from Calculator
 * (https://github.com/213YaZ786/Calculator, app/src/debug/java/com/yaz/calculator/DebugUpdateReceiver.kt); the entry is made as
 * Catalog.entryOf makes it, from CatalogRules.parseAppJson and AbiChoice.
 *
 * adb shell am broadcast -n com.yaz.store.debug/com.yaz.store.DebugInstallReceiver --es repo Calculator --es tag v0.3.6 --es json app.json [--ez handover true]
 * adb shell am broadcast -n com.yaz.store.debug/com.yaz.store.DebugInstallReceiver --es cancel all|<package>
 */
class DebugInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val queue = GlobalContext.get().get<InstallQueue>()
        intent.getStringExtra("cancel")?.let { target ->
            if (target == "all") queue.cancelAll() else queue.cancel(target)
            return
        }
        val repo = intent.getStringExtra("repo")?.takeIf(ReleaseNet::validRepo) ?: return
        val tag = intent.getStringExtra("tag")?.takeIf(ReleaseNet::validTag) ?: return
        val name = intent.getStringExtra("json") ?: return
        val source = File(context.filesDir, name).takeIf { it.isFile && it.parentFile == context.filesDir } ?: return
        val app = CatalogRules.parseAppJson(source.readText()) ?: return
        val entry = StoreEntry(
            repo = repo,
            tag = tag,
            packageName = app.packageName,
            label = app.label,
            description = "",
            versionCode = app.versionCode,
            versionName = app.versionName,
            minSdk = app.minSdk,
            apkName = AbiChoice.choose(app.apks, phoneAbis()),
            iconUrl = null
        )
        queue.enqueue(entry, handOver = intent.getBooleanExtra("handover", false))
    }
}
