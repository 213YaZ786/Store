package com.yaz.store.data.door

// An update downloaded and checked when its app opens, kept for the moment the app is left, one per package. Sources:
// - The idea is Google Play's In-App Updates, where the store downloads while the app runs and installs when the app asks or is left
//   (https://developer.android.com/guide/playcore/in-app-updates); the checks are the install queue's own (Fetch.checked), and the commit is
//   the queue's step 3: GrapheneOS' App Store installs with USER_ACTION_NOT_REQUIRED, STORE as the package source, ownership claimed
//   (https://github.com/GrapheneOS/AppStore, app/src/main/java/app/grapheneos/apps/core/InstallTask.kt).
// - A staged update is a download kept after its checks instead of deleted, under the cache's "downloads" folder that Store empties
//   at each start (StoreApplication); the file of a package is replaced by a newer one, never added to.
// - The commit waits for Android's answer as StoreBackend.install does; a STATUS_PENDING_USER_ACTION (InstallState.Confirm) is not
//   shown, the session is abandoned (Installer.cancel) and the update stays for Home.
// - kotlinx.coroutines' select with onAwait to wait for the first of two results
//   (https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.selects/select.html) and withTimeoutOrNull.
import com.yaz.store.core.install.InstallOrigin
import com.yaz.store.core.install.InstallRequest
import com.yaz.store.core.install.InstallState
import com.yaz.store.data.catalog.StoreEntry
import com.yaz.store.data.install.Fetch
import com.yaz.store.data.install.InstallBackend
import com.yaz.store.data.install.Refused
import com.yaz.store.data.net.NetError
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/** What staging an update came to. */
enum class StageResult { STAGED, NONE, REFUSED }

/** What committing a staged update came to. */
enum class CommitResult { INSTALLED, PENDING, FAILED, NONE }

class Staging(
    private val backend: InstallBackend,
    /** The downloads folder of the cache; the staged files live in its "staged" folder. */
    private val dir: File,
    /** The versionCode installed for a package, null when it is not installed. */
    private val installedVersion: (String) -> Long?,
    private val commitTimeoutMs: Long = 120_000
) {

    private class Staged(val entry: StoreEntry, val file: File)

    private val staged = ConcurrentHashMap<String, Staged>()

    // A package being staged or committed: a second call on it does nothing.
    private val busy: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun fileOf(pkg: String) = File(File(dir, "staged"), "$pkg.apk")

    /** The version staged for [pkg], or null. */
    fun stagedVersion(pkg: String): Long? = staged[pkg]?.takeIf { it.file.isFile }?.entry?.versionCode

    /**
     * Downloads and checks [entry]'s update and keeps the file for its package. STAGED when it is kept (or was already, for the same
     * version), REFUSED when a check failed (the file is deleted), NONE when it could not be tried now: a second call meanwhile,
     * no network, "Only on Wi-Fi" on a metered one, GitHub's limit.
     */
    suspend fun stage(entry: StoreEntry): StageResult {
        val pkg = entry.packageName
        if (!busy.add(pkg)) return StageResult.NONE
        try {
            val kept = staged[pkg]
            if (kept != null && kept.file.isFile && kept.entry.versionCode >= entry.versionCode && kept.entry.tag == entry.tag) return StageResult.STAGED
            drop(pkg)
            val file = fileOf(pkg)
            file.parentFile?.mkdirs()
            return try {
                Fetch.checked(backend, entry, file)
                staged[pkg] = Staged(entry, file)
                StageResult.STAGED
            } catch (e: CancellationException) {
                file.delete()
                throw e
            } catch (e: Refused) {
                file.delete()
                StageResult.REFUSED
            } catch (e: NetError) {
                file.delete()
                StageResult.NONE
            } catch (e: Exception) {
                file.delete()
                StageResult.NONE
            }
        } finally {
            busy.remove(pkg)
        }
    }

    /**
     * Installs what is staged for [pkg], silently as Store: NONE when nothing is staged (so a second call while it installs does nothing),
     * INSTALLED once Android says so, PENDING when Android wants the person's yes (dropped, shown nowhere), FAILED otherwise.
     * The file is gone afterwards in every case.
     */
    suspend fun commit(pkg: String): CommitResult {
        if (!busy.add(pkg)) return CommitResult.NONE
        try {
            val item = staged.remove(pkg) ?: return CommitResult.NONE
            try {
                if (!item.file.isFile) return CommitResult.NONE
                // Installed meanwhile (from Home, or by the app itself): nothing to do.
                val installed = installedVersion(pkg)
                if (installed != null && installed >= item.entry.versionCode) return CommitResult.NONE
                val request = InstallRequest(
                    packageName = pkg,
                    files = listOf(item.file),
                    source = InstallOrigin.STORE,
                    silent = true,
                    claimOwnership = true
                )
                val asked = CompletableDeferred<Unit>()
                val outcome: InstallState? = withTimeoutOrNull(commitTimeoutMs) {
                    coroutineScope {
                        val install = async { backend.install(request) { if (it is InstallState.Confirm) asked.complete(Unit) } }
                        val first: InstallState? = select {
                            install.onAwait { it }
                            asked.onAwait { null }
                        }
                        install.cancel()
                        first
                    }
                }
                return when (outcome) {
                    is InstallState.Done -> CommitResult.INSTALLED
                    null -> if (asked.isCompleted) {
                        backend.cancelInstall()
                        CommitResult.PENDING
                    } else {
                        CommitResult.FAILED
                    }
                    else -> CommitResult.FAILED
                }
            } finally {
                item.file.delete()
            }
        } finally {
            busy.remove(pkg)
        }
    }

    private fun drop(pkg: String) {
        staged.remove(pkg)
        fileOf(pkg).delete()
    }
}
