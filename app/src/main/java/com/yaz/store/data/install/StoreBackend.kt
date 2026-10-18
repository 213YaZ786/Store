package com.yaz.store.data.install

// The queue's phone and network: the release files through ReleaseNet (one door, its caps and every redirect checked), the kit's
// isolated ApkInspector, the installed app's signers (Installed.read, PackageManager.getPackageInfo with GET_SIGNING_CERTIFICATES), and the
// kit's Installer, whose state flow is awaited until Android answers Done or Failed (the pattern of Files' InstallViewModel at HEAD,
// feature/install/InstallViewModel.kt, which collects Installer.state; AOSP's ApiDemos InstallApkSessionApi waits for the same status:
// https://android.googlesource.com/platform/development/+/refs/heads/main/samples/ApiDemos/src/com/example/android/apis/content/InstallApkSessionApi.java).
import android.os.Build
import com.yaz.store.core.apk.ApkInfo
import com.yaz.store.core.apk.ApkInspector
import com.yaz.store.core.install.InstallRequest
import com.yaz.store.core.install.InstallState
import com.yaz.store.core.install.Installer
import com.yaz.store.data.catalog.Installed
import com.yaz.store.data.net.ReleaseNet
import java.io.File
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class StoreBackend(
    private val context: android.content.Context,
    private val net: ReleaseNet,
    private val inspector: ApkInspector,
    private val installer: Installer
) : InstallBackend {

    override val sdk: Int get() = Build.VERSION.SDK_INT

    override fun sums(repo: String, tag: String): String = net.getJson(ReleaseNet.downloadUrl(repo, tag, "SHA256SUMS.txt"))

    override fun download(repo: String, tag: String, apkName: String, into: File, onProgress: (Float) -> Unit, beforeRead: () -> Unit) =
        net.download(ReleaseNet.downloadUrl(repo, tag, apkName), into, onProgress, beforeRead)

    override suspend fun inspect(file: File): ApkInfo? = inspector.inspect(listOf(file)).single()

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
}
