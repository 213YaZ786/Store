package com.yaz.store.data.install

// The steps that bring a release's APK to the point where it may be installed, taken out of InstallQueue.run so the queue and the door's
// staging (data/door/Staging) run the very same checks. Sources, as in InstallQueue and StoreChecks: the order of the update kit's
// Updates.stage (Modules shared/update: the sum of the exact APK first, then the file, then its sum, and only then the file opened), and
// GrapheneOS' App Store, which checks package, version and certificate digests of the downloaded file before the session is committed
// (https://github.com/GrapheneOS/AppStore, app/src/main/java/app/grapheneos/apps/core/InstallTask.kt, findPackage).
import com.yaz.store.data.catalog.StoreEntry
import com.yaz.store.data.net.ReleaseNet
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job

/** A reason that stops an entry; its words are what the person reads. */
class Refused(val why: String) : Exception(why)

object Fetch {

    /**
     * Downloads [entry]'s APK into [file] and checks it: the sum, the isolated inspector's reading, the package, version, signers and
     * Android level (StoreChecks.refusal). Throws [Refused] with the reason, or NetError; a cancel stops the download at its next read.
     * [onChecking] runs when the download has ended. The file is left in place for the caller to keep or delete.
     */
    suspend fun checked(backend: InstallBackend, entry: StoreEntry, file: File, onProgress: (Float) -> Unit = {}, onChecking: () -> Unit = {}) {
        val apkName = entry.apkName ?: throw Refused("No APK for this phone.")
        // 1. The sum of this exact APK, then the file, then the sum of the file.
        val expected = StoreChecks.expectedSum(backend.sums(entry.repo, entry.tag), apkName) ?: throw Refused(StoreChecks.SUM_MISSING)
        val self = currentCoroutineContext().job
        backend.download(entry.repo, entry.tag, apkName, file, onProgress = onProgress, beforeRead = { self.ensureActive() })
        self.ensureActive()
        onChecking()
        if (ReleaseNet.sha256(file) != expected) throw Refused(StoreChecks.SUM_WRONG)
        // 2. Only now is the file opened, by the kit's isolated inspector.
        val info = backend.inspect(file) ?: throw Refused(StoreChecks.UNREADABLE)
        StoreChecks.refusal(entry, info, backend.installedSigners(entry.packageName), backend.sdk)?.let { throw Refused(it) }
    }
}
