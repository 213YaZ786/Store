package com.yaz.store.data.install

// The queue of installs and updates: one entry at a time, each downloaded, checked and handed to the install kit's Installer.
// Sources:
// - The shape (a StateFlow of the jobs not over, jobs run in the application's own scope, a cancel by coroutine, onQueued telling the
//   foreground service to start) is Files' FileJobs (YAZ/Files at HEAD, data/files/FileJobs.kt), itself Material Files'
//   filejob/FileJob.kt and FileJobService.kt (https://github.com/zhanghai/MaterialFiles); a cancel is the coroutine's
//   (https://kotlinlang.org/docs/cancellation-and-timeouts.html).
// - One install at a time, "Update all" as a queue with a cancel: Google Play's Manage apps and GrapheneOS' App Store, which holds one install task
//   at a time (https://github.com/GrapheneOS/AppStore, app/src/main/java/app/grapheneos/apps/core/InstallTask.kt).
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
import kotlinx.coroutines.launch

enum class JobStep(val active: Boolean) {
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

    /** Downloads [apkName] of the release into [into]; [beforeRead] runs before each read and may throw to stop. Throws NetError. */
    fun download(repo: String, tag: String, apkName: String, into: File, onProgress: (Float) -> Unit, beforeRead: () -> Unit)

    /** The kit's isolated inspector on [file]; null when it could not read it. */
    suspend fun inspect(file: File): ApkInfo?

    /** The signers (SHA-256, lower case) of the installed [packageName], null when it is not installed. */
    fun installedSigners(packageName: String): Set<String>?

    /** Installs through the kit; [onState] sees each state; returns Done or Failed. A cancel abandons the session. */
    suspend fun install(request: InstallRequest, onState: (InstallState) -> Unit): InstallState

    /** Abandons the session in progress. */
    fun cancelInstall()
}

class InstallQueue(
    private val backend: InstallBackend,
    /** Where the downloads are kept until checked; emptied at each start of the app. */
    private val dir: File,
    private val scope: CoroutineScope,
    /** Told after an entry is queued: the foreground service starts from it. */
    private val onQueued: () -> Unit = {}
) {
    private val mutable = MutableStateFlow<List<InstallJob>>(emptyList())
    private val lock = Any()
    private var worker: Job? = null
    private var running: Pair<String, Job>? = null
    private val serials = AtomicLong()

    /** Every entry the queue knows, in the order it came: the active ones, then the finished ones until they are dismissed or queued again. */
    val jobs: StateFlow<List<InstallJob>> = mutable.asStateFlow()

    /**
     * Queues [entry]. False, and nothing changes, when the same app is queued or running already (a second tap) or when the entry has
     * no APK for this phone. A finished entry of the same app is replaced.
     */
    fun enqueue(entry: StoreEntry, handOver: Boolean = false): Boolean {
        if (entry.apkName == null) return false
        var added = false
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
            onQueued()
            pump()
        }
        return added
    }

    /** "Update all": every entry whose state is UPDATE, each as [enqueue] takes it. How many were queued. */
    fun enqueueUpdates(statuses: List<AppStatus>): Int =
        statuses.filter { it.state == AppState.UPDATE }.count { enqueue(it.entry, it.handOver) }

    /** Cancels the entry of [packageName]: running, it is abandoned and its file deleted; waiting, it is dropped. */
    fun cancel(packageName: String) {
        synchronized(lock) {
            if (running?.first == packageName) running?.second?.cancel()
            mutable.update { list -> list.filterNot { it.entry.packageName == packageName && it.step.active } }
        }
    }

    fun cancelAll() {
        synchronized(lock) {
            running?.second?.cancel()
            mutable.update { list -> list.filterNot { it.step.active } }
        }
    }

    /** Forgets a finished entry once the person has seen it. */
    fun dismiss(packageName: String) {
        mutable.update { list -> list.filterNot { it.entry.packageName == packageName && !it.step.active } }
    }

    private fun pump() {
        synchronized(lock) {
            if (worker?.isActive != true) worker = scope.launch { drain() }
        }
    }

    // One entry at a time, in the order they came. The worker ends, under the lock, when none waits, so an entry queued at that
    // moment starts a new one (pump) and is never missed.
    private suspend fun drain() {
        while (true) {
            val next = synchronized(lock) {
                mutable.value.firstOrNull { it.step == JobStep.QUEUED } ?: run {
                    worker = null
                    return
                }
            }
            val name = next.entry.packageName
            val child = scope.launch(start = CoroutineStart.LAZY) { process(next) }
            synchronized(lock) { running = name to child }
            child.start()
            child.join()
            synchronized(lock) { running = null }
            // An entry of this run still in a step that is not over was cancelled while it ran.
            mutable.update { list -> list.filterNot { it.serial == next.serial && it.step.active } }
        }
    }

    private fun change(serial: Long, transform: (InstallJob) -> InstallJob) {
        mutable.update { list -> list.map { if (it.serial == serial && it.step.active) transform(it) else it } }
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
            backend.cancelInstall()
            throw e
        } catch (e: Refused) {
            end = { it.copy(step = JobStep.FAILED, why = e.why, confirm = null) }
        } catch (e: NetError) {
            end = { it.copy(step = JobStep.FAILED, why = netWords(e), confirm = null) }
        } catch (e: Exception) {
            // Whatever else goes wrong on this entry (a full disk, a refused service) must not stop the next one.
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
        Fetch.checked(
            backend, entry, file,
            onProgress = { p -> change(serial) { it.copy(progress = p) } },
            onChecking = { change(serial) { it.copy(step = JobStep.CHECKING, progress = 1f) } }
        )
        // 3. The kit's session: no question, Store as the source, the update ownership claimed on a first install.
        change(serial) { it.copy(step = JobStep.INSTALLING) }
        val request = InstallRequest(
            packageName = name,
            files = listOf(file),
            source = InstallOrigin.STORE,
            silent = true,
            claimOwnership = true
        )
        val result = backend.install(request) { state ->
            when (state) {
                is InstallState.Confirm -> change(serial) { it.copy(step = JobStep.CONFIRM, confirm = state.intent) }
                is InstallState.Working, is InstallState.Writing -> change(serial) { if (it.step == JobStep.CONFIRM) it else it.copy(step = JobStep.INSTALLING) }
                else -> Unit
            }
        }
        when (result) {
            is InstallState.Done -> Unit
            is InstallState.Failed -> throw Refused(failureWords(result.failure))
            else -> throw Refused("The installation did not finish.")
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
