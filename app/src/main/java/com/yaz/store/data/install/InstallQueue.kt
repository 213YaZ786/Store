package com.yaz.store.data.install

// The queue of installs and updates: every entry downloads and is checked at the same time, then the checked ones are handed to the
// install kit's Installer one at a time, the first ready being the first installed.
// Sources:
// - The shape (a StateFlow of the jobs not over, jobs run in the application's own scope, a cancel by coroutine, onQueued telling the
//   foreground service to start) is Files' FileJobs (YAZ/Files at HEAD, data/files/FileJobs.kt), itself Material Files'
//   filejob/FileJob.kt and FileJobService.kt (https://github.com/zhanghai/MaterialFiles); a cancel is the coroutine's
//   (https://kotlinlang.org/docs/cancellation-and-timeouts.html).
// - "Update all" starts every entry at once, each its own coroutine, with no cap on the downloads, and the entry of the app itself commits
//   last, after every other one has ended: GrapheneOS' App Store, MIT, app/src/main/java/app/grapheneos/apps/core/InstallStart.kt
//   (startPackageUpdate: `regularGroups.map { startInstallTaskInner(it, params) }`, each `CoroutineScope(Dispatchers.IO).async`, and the
//   self-update's callbackBeforeCommit = { jobs.awaitAll().awaitAll() }); GrapheneOS caps the downloads at three (core/Globals.kt,
//   httpDownloadSemaphore = Semaphore(3)), Store does not. GrapheneOS lets Android install in parallel; Store, as Google Play does
//   (downloads together, installs one by one), takes a fair kotlinx Mutex around the install: first come, first served
//   (https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.sync/-mutex/).
// - The order of the steps is the update kit's Updates.stage (Modules shared/update): the sum first, the APK opened only after it
//   matched, then GrapheneOS' certificate check before the session is committed (see StoreChecks).
// - A file is deleted after each try, whatever its end (the update kit's stage: `if (!done) file.delete()`, here always, as the session holds its own copy).
import android.content.Intent
import com.yaz.store.core.apk.ApkInfo
import com.yaz.store.core.install.InstallFailure
import com.yaz.store.core.install.InstallOrigin
import com.yaz.store.core.install.InstallRequest
import com.yaz.store.core.install.InstallState
import com.yaz.store.data.catalog.AppState
import com.yaz.store.data.catalog.AppStatus
import com.yaz.store.data.catalog.StoreEntry
import com.yaz.store.data.net.NetError
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class JobStep(val active: Boolean) {
    /** Only until its coroutine starts; an entry that has downloaded and waits for its install turn shows CHECKING. */
    QUEUED(true), DOWNLOADING(true), CHECKING(true), INSTALLING(true),
    /** Android asks the person's yes in its own screen (an app Store does not hold yet). */
    CONFIRM(true),
    DONE(false), FAILED(false)
}

/** One entry of the queue and where it stands. [why] says in plain words what stopped a FAILED one. */
data class InstallJob(
    val entry: StoreEntry,
    val handOver: Boolean,
    /** Tells two queueings of the same app apart, so a cancelled entry still winding down never touches its successor. */
    val serial: Long = 0,
    val step: JobStep = JobStep.QUEUED,
    /** 0..1 while DOWNLOADING. */
    val progress: Float = 0f,
    val why: String? = null,
    /** Android's confirmation screen while CONFIRM; the screen starts it. */
    val confirm: Intent? = null
)

/** What the queue needs from the phone and the network; the real one is StoreBackend, the tests give their own. */
interface InstallBackend {
    /** The Android level of this phone. */
    val sdk: Int

    /** SHA256SUMS.txt of the release [tag] of [repo]. Throws NetError. */
    fun sums(repo: String, tag: String): String

    /** Downloads [apkName] of the release into [into]; [beforeRead] runs before each read and may throw to stop. Throws NetError. Called by several entries at once. */
    fun download(repo: String, tag: String, apkName: String, into: File, onProgress: (Float) -> Unit, beforeRead: () -> Unit)

    /** The kit's isolated inspector on [file]; null when it could not read it. Called by several entries at once. */
    suspend fun inspect(file: File): ApkInfo?

    /** The signers (SHA-256, lower case) of the installed [packageName], null when it is not installed. */
    fun installedSigners(packageName: String): Set<String>?

    /** Installs through the kit; [onState] sees each state; returns Done or Failed. A cancel abandons the session. Never called by two entries at once. */
    suspend fun install(request: InstallRequest, onState: (InstallState) -> Unit): InstallState

    /** Abandons the session in progress. */
    fun cancelInstall()
}

class InstallQueue(
    private val backend: InstallBackend,
    /** Where the downloads are kept until checked; emptied at each start of the app. */
    private val dir: File,
    private val scope: CoroutineScope,
    /** Store's own package: its entry is installed after every other one has ended. */
    private val ownPackage: String,
    /** Told after an entry is queued: the foreground service starts from it. */
    private val onQueued: () -> Unit = {}
) {
    private val mutable = MutableStateFlow<List<InstallJob>>(emptyList())
    private val lock = Any()

    /** An entry's coroutine, kept under [lock] until it has ended. */
    private class Run(val packageName: String, val job: Job)

    private val running = mutableMapOf<Long, Run>()
    private val serials = AtomicLong()

    // One install at a time: kotlinx' Mutex is fair (waiters are served in the order they asked), so the entry that is ready first is
    // installed first.
    private val installLock = Mutex()

    /** Every entry the queue knows, in the order it came: the active ones, then the finished ones until they are dismissed or queued again. */
    val jobs: StateFlow<List<InstallJob>> = mutable.asStateFlow()

    /**
     * Queues [entry] and starts it at once. False, and nothing changes, when the same app is queued or running already (a second tap) or
     * when the entry has no APK for this phone. A finished entry of the same app is replaced.
     */
    // Source: GrapheneOS' App Store, core/InstallStart.kt, startPackageUpdate and startInstallTaskInner: one coroutine per package, all
    // started together (https://github.com/GrapheneOS/AppStore/blob/main/app/src/main/java/app/grapheneos/apps/core/InstallStart.kt).
    fun enqueue(entry: StoreEntry, handOver: Boolean = false): Boolean {
        if (entry.apkName == null) return false
        var added = false
        synchronized(lock) {
            mutable.update { list ->
                if (list.any { it.entry.packageName == entry.packageName && it.step.active }) {
                    added = false
                    list
                } else {
                    added = true
                    list.filterNot { it.entry.packageName == entry.packageName } + InstallJob(entry, handOver, serials.incrementAndGet())
                }
            }
            if (added) {
                val job = mutable.value.last { it.entry.packageName == entry.packageName }
                // An earlier entry of the same app, cancelled and still winding down, owns the same file: it ends first.
                val before = running.values.filter { it.packageName == entry.packageName }.map { it.job }
                val child = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        before.joinAll()
                        process(job)
                    } finally {
                        // An entry of this run still in a step that is not over was cancelled while it ran.
                        mutable.update { list -> list.filterNot { it.serial == job.serial && it.step.active } }
                        synchronized(lock) { running.remove(job.serial) }
                    }
                }
                running[job.serial] = Run(entry.packageName, child)
                child.start()
            }
        }
        if (added) onQueued()
        return added
    }

    /** "Update all": every entry whose state is UPDATE, each as [enqueue] takes it. How many were queued. */
    fun enqueueUpdates(statuses: List<AppStatus>): Int =
        statuses.filter { it.state == AppState.UPDATE }.count { enqueue(it.entry, it.handOver) }

    /** Cancels the entry of [packageName], whatever it is doing: it is abandoned and its file deleted. */
    fun cancel(packageName: String) {
        synchronized(lock) {
            running.values.filter { it.packageName == packageName }.forEach { it.job.cancel() }
            mutable.update { list -> list.filterNot { it.entry.packageName == packageName && it.step.active } }
        }
    }

    fun cancelAll() {
        synchronized(lock) {
            running.values.forEach { it.job.cancel() }
            mutable.update { list -> list.filterNot { it.step.active } }
        }
    }

    /** Forgets a finished entry once the person has seen it. */
    fun dismiss(packageName: String) {
        mutable.update { list -> list.filterNot { it.entry.packageName == packageName && !it.step.active } }
    }

    private fun change(serial: Long, transform: (InstallJob) -> InstallJob) {
        mutable.update { list -> list.map { if (it.serial == serial && it.step.active) transform(it) else it } }
    }

    // Store's own update commits last: it waits for every other entry to have ended before it asks for the install turn. An entry queued
    // meanwhile is waited for too, as the loop looks again.
    // Source: GrapheneOS' App Store, core/InstallStart.kt, startPackageUpdate: callbackBeforeCommit = { jobs.awaitAll().awaitAll() }.
    private suspend fun waitForOthers(serial: Long) {
        while (true) {
            val others = synchronized(lock) { running.filterKeys { it != serial }.values.map { it.job }.filter { it.isActive } }
            if (others.isEmpty()) return
            others.joinAll()
        }
    }

    private suspend fun process(job: InstallJob) {
        val entry = job.entry
        val name = entry.packageName
        dir.mkdirs()
        val file = File(dir, "$name.apk")
        // The end of the entry is shown once its file is gone.
        var end: (InstallJob) -> InstallJob = { it.copy(step = JobStep.DONE, confirm = null) }
        try {
            run(job, file)
        } catch (e: CancellationException) {
            // The session is abandoned by run itself, only if this entry held the install turn.
            throw e
        } catch (e: Refused) {
            end = { it.copy(step = JobStep.FAILED, why = e.why, confirm = null) }
        } catch (e: NetError) {
            end = { it.copy(step = JobStep.FAILED, why = netWords(e), confirm = null) }
        } catch (e: Exception) {
            // Whatever else goes wrong on this entry (a full disk, a refused service) must not stop the others.
            end = { it.copy(step = JobStep.FAILED, why = "The installation could not be done.", confirm = null) }
        } finally {
            file.delete()
        }
        change(job.serial, end)
    }

    private suspend fun run(job: InstallJob, file: File) {
        val entry = job.entry
        val name = entry.packageName
        val serial = job.serial
        change(serial) { it.copy(step = JobStep.DOWNLOADING, progress = 0f) }
        // 1 and 2: the sum, the file, the sum of the file, the isolated inspector and the checks (Fetch.checked, shared with the door's staging).
        // Every entry does this at the same time as the others.
        Fetch.checked(
            backend, entry, file,
            onProgress = { p -> change(serial) { it.copy(progress = p) } },
            onChecking = { change(serial) { it.copy(step = JobStep.CHECKING, progress = 1f) } }
        )
        // Checked: it waits for its turn, still shown as CHECKING with the download full. Store's own entry lets every other one end first.
        if (name == ownPackage) waitForOthers(serial)
        installLock.withLock {
            // 3. The kit's session, one at a time: no question, Store as the source, the update ownership claimed on a first install.
            change(serial) { it.copy(step = JobStep.INSTALLING) }
            val request = InstallRequest(
                packageName = name,
                files = listOf(file),
                source = InstallOrigin.STORE,
                silent = true,
                claimOwnership = true
            )
            val result = try {
                backend.install(request) { state ->
                    when (state) {
                        is InstallState.Confirm -> change(serial) { it.copy(step = JobStep.CONFIRM, confirm = state.intent) }
                        is InstallState.Working, is InstallState.Writing -> change(serial) { if (it.step == JobStep.CONFIRM) it else it.copy(step = JobStep.INSTALLING) }
                        else -> Unit
                    }
                }
            } catch (e: CancellationException) {
                // Only the entry that holds the install turn abandons the session, before the next one may open its own.
                backend.cancelInstall()
                throw e
            }
            when (result) {
                is InstallState.Done -> Unit
                is InstallState.Failed -> throw Refused(failureWords(result.failure))
                else -> throw Refused("The installation did not finish.")
            }
        }
    }

    private fun failureWords(failure: InstallFailure): String = failure.message

    private fun netWords(e: NetError): String = when (e.kind) {
        NetError.Kind.OFFLINE -> "No connection."
        NetError.Kind.RATE_LIMITED -> "GitHub's limit is reached. Try again later."
        NetError.Kind.NOT_FOUND -> "The release has no such file."
        NetError.Kind.REFUSED -> "GitHub's answer was refused."
        // Settings' "Only on Wi-Fi", checked by StoreBackend.download (https://developer.android.com/training/basics/network-ops/managing).
        NetError.Kind.METERED -> "Waiting for Wi-Fi."
    }
}
