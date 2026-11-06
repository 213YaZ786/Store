package com.yaz.store.data.catalog

// The phone's installs and removals seen while Store's process lives, so the lists follow what another app (or Android's settings) does. Ported from
// GrapheneOS' App Store (MIT), app/src/main/java/app/grapheneos/apps/core/PackageStates.kt, init: the BroadcastReceiver on ACTION_PACKAGE_ADDED,
// ACTION_PACKAGE_CHANGED, ACTION_PACKAGE_REPLACED and ACTION_PACKAGE_REMOVED (data scheme "package"). Changes: the receiver does not read the package
// itself, it tells the screens which package changed (a flow HomeViewModel collects, then Installed.statuses reads the phone again, no network);
// registered with ContextCompat.registerReceiver and RECEIVER_NOT_EXPORTED (system broadcasts still arrive).
// not ported: updateRepo(repo), InstallerSessions.init() and the pruning of the package cache in init (no repo object, the install kit owns the sessions,
// Store empties its downloads at its start)
// not ported: ACTION_APPLICATION_LOCALE_CHANGED and its second IntentFilter (Store keeps no per-app locale), updateNumberOfOutdatedPackages (the
// Updates zone counts from the statuses), the Log.d lines
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class PackageChanges(private val context: Context) {
    private val mutable = MutableSharedFlow<String>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The package name of each install, update, change or removal seen since [init]. */
    val changed: SharedFlow<String> = mutable.asSharedFlow()

    private var registered = false

    // https://github.com/GrapheneOS/AppStore/blob/main/app/src/main/java/app/grapheneos/apps/core/PackageStates.kt#init
    /** Registers the receiver for the life of the process, once. Nothing is kept alive for it. */
    fun init() {
        if (registered) return
        registered = true

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent) {
                val packageName = intent.data?.schemeSpecificPart ?: return
                mutable.tryEmit(packageName)
            }
        }
        val filter = IntentFilter().apply {
            addDataScheme("package")
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
