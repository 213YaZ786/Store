package com.yaz.store.core.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

// The receiver Android answers a committed session to. Written after AOSP's ApiDemos InstallApkSessionApi, which reads
// EXTRA_STATUS, EXTRA_STATUS_MESSAGE and, for STATUS_PENDING_USER_ACTION, EXTRA_INTENT in onNewIntent:
// https://android.googlesource.com/platform/development/+/refs/heads/main/samples/ApiDemos/src/com/example/android/apis/content/InstallApkSessionApi.java
// and App Manager's PackageInstallerBroadcastReceiver (GPL-3.0-or-later), which takes only the status of its own
// session and passes the confirmation on:
// https://github.com/MuntashirAkon/AppManager/blob/master/app/src/main/java/io/github/muntashirakon/AppManager/apk/installer/PackageInstallerBroadcastReceiver.java
// Declared in the manifest with android:exported="false"; the PendingIntent names it explicitly.

/** One answer of Android's installer to a session of ours. */
class InstallEvent(
    val sessionId: Int,
    val status: Int,
    val message: String?,
    val otherPackage: String?,
    val packageName: String?,
    /** For STATUS_PENDING_USER_ACTION, the confirmation screen; the installer checks it before starting it. */
    val confirm: Intent?
)

/** Where the receiver hands the answers to the installer. The last one is kept for a collector that comes late. */
object InstallEvents {
    private val flow = MutableSharedFlow<InstallEvent>(replay = 1, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<InstallEvent> = flow.asSharedFlow()

    fun post(event: InstallEvent) {
        flow.tryEmit(event)
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val confirm = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        } else null
        InstallEvents.post(
            InstallEvent(
                sessionId = session,
                status = status,
                message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                otherPackage = intent.getStringExtra(PackageInstaller.EXTRA_OTHER_PACKAGE_NAME),
                packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME),
                confirm = confirm
            )
        )
    }

    companion object {
        const val ACTION = "com.yaz.store.action.INSTALL_RESULT"
    }
}
