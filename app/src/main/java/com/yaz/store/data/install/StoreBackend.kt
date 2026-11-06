package com.yaz.store.data.install

// The queue's phone and network: the release files through ReleaseNet (one door, its caps and every redirect checked), the kit's
// isolated ApkInspector, the installed app's signers (Installed.read, PackageManager.getPackageInfo with GET_SIGNING_CERTIFICATES), and the
// kit's Installer, whose state flow is awaited until Android answers Done or Failed (the pattern of Files' InstallViewModel at HEAD,
// feature/install/InstallViewModel.kt, which collects Installer.state; AOSP's ApiDemos InstallApkSessionApi waits for the same status:
// https://android.googlesource.com/platform/development/+/refs/heads/main/samples/ApiDemos/src/com/example/android/apis/content/InstallApkSessionApi.java).
import android.app.job.JobInfo
import android.os.Build
import com.yaz.store.R
import com.yaz.store.core.apk.ApkInfo
import com.yaz.store.core.apk.ApkInspector
import com.yaz.store.core.install.InstallRequest
import com.yaz.store.core.install.InstallState
import com.yaz.store.core.install.Installer
import com.yaz.store.core.install.UserRestrictionException
import com.yaz.store.core.install.isAppInstallationAllowed
import com.yaz.store.data.catalog.Installed
import com.yaz.store.data.net.NetError
import com.yaz.store.data.net.ReleaseNet
import com.yaz.store.data.settings.NETWORK_ANY
import com.yaz.store.data.settings.NETWORK_NOT_ROAMING
import com.yaz.store.data.settings.NETWORK_UNMETERED
import java.io.File
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class StoreBackend(
    private val context: android.content.Context,
    private val net: ReleaseNet,
    private val inspector: ApkInspector,
    private val installer: Installer,
    /** Settings' "Download on" (NETWORK_ANY, NETWORK_UNMETERED or NETWORK_NOT_ROAMING), read at each download. */
    private val network: () -> Int = { NETWORK_ANY }
) : InstallBackend {

    override val sdk: Int get() = Build.VERSION.SDK_INT

    override fun sums(repo: String, tag: String): String = net.getJson(ReleaseNet.downloadUrl(repo, tag, "SHA256SUMS.txt"))

    // https://github.com/GrapheneOS/AppStore/blob/main/app/src/main/java/app/grapheneos/apps/autoupdate/AutoUpdatePrefs.kt#getAutoUpdateJobNetworkTypePref
    // The stored choice, read at each download.
    // not ported: prefs.getString(KEY_JOB_NETWORK_TYPE, null)?.toInt() ?: pref_def_network_type_for_bg_update_job (the choice comes from Settings through [network], whose default is any network, 1)
    private fun getAutoUpdateJobNetworkTypePref(): Int = network()

    // https://github.com/GrapheneOS/AppStore/blob/main/app/src/main/java/app/grapheneos/apps/autoupdate/AutoUpdatePrefs.kt#getAutoUpdateJobNetworkType
    private fun getAutoUpdateJobNetworkType(): Int {
        return when (getAutoUpdateJobNetworkTypePref()) {
            NETWORK_UNMETERED -> JobInfo.NETWORK_TYPE_UNMETERED
            NETWORK_NOT_ROAMING -> JobInfo.NETWORK_TYPE_NOT_ROAMING
            else -> JobInfo.NETWORK_TYPE_ANY
        }
    }

    // Unmetered: the active network without NET_CAPABILITY_NOT_METERED stops the download before any request
    // (ConnectivityManager and NetworkCapabilities, https://developer.android.com/training/basics/network-ops/managing).
    private fun metered(): Boolean {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return !capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    override fun download(repo: String, tag: String, apkName: String, into: File, onProgress: (done: Long, total: Long) -> Unit, beforeRead: () -> Unit) {
        // The job's constraint is the download's: unmetered refuses a metered network, not roaming one without NET_CAPABILITY_NOT_ROAMING
        // (https://developer.android.com/reference/android/net/NetworkCapabilities#NET_CAPABILITY_NOT_ROAMING); nothing is asked of GitHub then.
        when (getAutoUpdateJobNetworkType()) {
            JobInfo.NETWORK_TYPE_UNMETERED -> if (metered()) throw NetError(NetError.Kind.METERED, message = "metered")
            JobInfo.NETWORK_TYPE_NOT_ROAMING -> {
                val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
                val capabilities = manager?.getNetworkCapabilities(manager.activeNetwork)
                if (capabilities != null && !capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)) {
                    throw NetError(NetError.Kind.ROAMING, message = "roaming")
                }
            }
        }
        net.download(ReleaseNet.downloadUrl(repo, tag, apkName), into, onProgress, beforeRead)
    }

    // Several entries are checked at once and the inspector binds its isolated service per call: one call at a time, and the door's staging
    // (another StoreBackend, the same inspector) takes the same turn. Source: kotlinx.coroutines' Mutex, fair, withLock
    // (https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.sync/-mutex/).
    override suspend fun inspect(file: File): ApkInfo? = inspectLock.withLock { inspector.inspect(listOf(file)).single() }

    override fun installedSigners(packageName: String): Set<String>? = Installed.read(context, packageName)?.signers

    override suspend fun install(request: InstallRequest, onState: (InstallState) -> Unit): InstallState = coroutineScope {
        installer.reset()
        val watcher = launch { installer.state.collect(onState) }
        try {
            installer.install(request)
            installer.state.first { it is InstallState.Done || it is InstallState.Failed }
        } finally {
            watcher.cancel()
        }
    }

    override fun cancelInstall() = installer.cancel()

    // https://github.com/GrapheneOS/AppStore/blob/main/app/src/main/java/app/grapheneos/apps/util/PackageManagerUtils.kt#throwIfAppInstallationNotAllowed
    // The source's check: isAppInstallationAllowed() false throws UserRestrictionException (core/install/InstallAllowed).
    override fun throwIfAppInstallationNotAllowed() {
        if (!isAppInstallationAllowed(context)) {
            throw UserRestrictionException(context.getString(R.string.install_not_allowed))
        }
    }

    private companion object {
        val inspectLock = Mutex()
    }
}
