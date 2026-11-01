package com.yaz.store.data.install

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
import com.yaz.store.data.net.ReleaseNet
import com.yaz.store.data.trust.OurKeys
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The queue on the JVM, with a fake phone and network. Its protocol is the one of Files' FileJobs tests (YAZ/Files at HEAD,
// FileJobsTest: wait on the jobs' StateFlow with a timeout, a gate that holds a job where a cancel must reach it); the cases are the ones
// of Google Play's "Update all" (downloads together, installs one by one, a cancel, a failure that does not stop the others) and of
// GrapheneOS' App Store, which starts every package at once and installs its own update last
// (https://github.com/GrapheneOS/AppStore, app/src/main/java/app/grapheneos/apps/core/InstallStart.kt, startPackageUpdate).
// Robolectric only for android.content.Intent, in the confirmation case.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class InstallQueueTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val ours = OurKeys.DIGESTS.first()
    private val bytes = ByteArray(5000) { (it % 251).toByte() }
    private val goodSum = run {
        val f = File.createTempFile("sum", ".apk").apply { writeBytes(bytes) }
        ReleaseNet.sha256(f).also { f.delete() }
    }

    private lateinit var dir: File
    private lateinit var scope: CoroutineScope
    private lateinit var backend: Fake
    private lateinit var queue: InstallQueue
    private val queued = AtomicInteger()

    private fun entry(pkg: String, version: Long = 14, apk: String? = "$pkg.apk") = StoreEntry(
        repo = pkg.substringAfterLast('.'), tag = "v1", packageName = pkg, label = pkg.substringAfterLast('.'), description = "",
        versionCode = version, versionName = "1.0", minSdk = 31, apkName = apk, iconUrl = null
    )

    /** A phone and a network that answer from fields; every call is counted. */
    private inner class Fake : InstallBackend {
        override var sdk = 36
        var sumsFor: (String) -> String = { repo -> "$goodSum  com.yaz.$repo.apk\n" }
        var sumsError: NetError? = null
        val inspected = AtomicInteger()
        val installs: MutableList<InstallRequest> = Collections.synchronizedList(mutableListOf())
        val cancelled = AtomicInteger()
        val concurrent = AtomicInteger()
        @Volatile var maxConcurrent = 0
        val installing = AtomicInteger()
        @Volatile var maxInstalling = 0
        var bodyFor: (String) -> ByteArray = { bytes }
        /** A repo whose download waits here until released, polling the way ReleaseNet does before each read. */
        var hold: Pair<String, CountDownLatch>? = null
        val holding = CountDownLatch(1)
        /** Repos whose download waits for its own latch, several at once; [started] says when each is in its download. */
        val gates = ConcurrentHashMap<String, CountDownLatch>()
        private val starts = ConcurrentHashMap<String, CountDownLatch>()
        // The gate of `hold`, one per repo; GrapheneOS' App Store tests its parallel tasks the same way, with gates
        // (https://github.com/GrapheneOS/AppStore, app/src/main/java/app/grapheneos/apps/core/InstallStart.kt).
        fun started(repo: String): CountDownLatch = starts.computeIfAbsent(repo) { CountDownLatch(1) }
        var inspectResult: (String) -> ApkInfo? = { pkg -> info(pkg) }
        var signers: Set<String>? = null
        var installOutcome: suspend (InstallRequest, (InstallState) -> Unit) -> InstallState = { _, _ -> InstallState.Done("x") }
        var existedAtDownload = false

        override fun sums(repo: String, tag: String): String {
            sumsError?.let { throw it }
            return sumsFor(repo)
        }

        override fun download(repo: String, tag: String, apkName: String, into: File, onProgress: (done: Long, total: Long) -> Unit, beforeRead: () -> Unit) {
            val now = concurrent.incrementAndGet()
            synchronized(this) { maxConcurrent = maxOf(maxConcurrent, now) }
            try {
                val body = bodyFor(repo)
                into.writeBytes(body.copyOf(body.size / 2))
                onProgress((body.size / 2).toLong(), body.size.toLong())
                started(repo).countDown()
                gates[repo]?.let { while (!it.await(5, TimeUnit.MILLISECONDS)) beforeRead() }
                val gate = hold?.takeIf { it.first == repo }?.second
                if (gate != null) {
                    holding.countDown()
                    while (!gate.await(5, TimeUnit.MILLISECONDS)) beforeRead()
                }
                beforeRead()
                into.writeBytes(body)
                onProgress(body.size.toLong(), body.size.toLong())
            } finally {
                concurrent.decrementAndGet()
            }
        }

        override suspend fun inspect(file: File): ApkInfo? {
            inspected.incrementAndGet()
            assertTrue("inspected a file that is not there", file.isFile)
            return inspectResult(file.name.removeSuffix(".apk"))
        }

        override fun installedSigners(packageName: String): Set<String>? = signers

        override suspend fun install(request: InstallRequest, onState: (InstallState) -> Unit): InstallState {
            val now = installing.incrementAndGet()
            synchronized(this) { maxInstalling = maxOf(maxInstalling, now) }
            try {
                installs += request
                assertTrue("installed a file that is not there", request.files.single().isFile)
                return installOutcome(request, onState)
            } finally {
                installing.decrementAndGet()
            }
        }

        override fun cancelInstall() {
            cancelled.incrementAndGet()
        }
    }

    private fun info(pkg: String, version: Long = 14) = ApkInfo(
        packageName = pkg, versionCode = version, versionName = "1.0", splitName = null, isFeatureSplit = false, configForSplit = null,
        isSplitRequired = false, minSdk = 31, permissions = emptyList(), abis = emptyList(), verified = true, signers = listOf(ours), label = null
    )

    @Before
    fun setUp() {
        dir = File(temp.root, "downloads")
        // IO, as in the app: the fake downloads block their thread, several at once.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        backend = Fake()
        queue = InstallQueue(backend, dir, scope, ownPackage = "com.yaz.store", onQueued = { queued.incrementAndGet() })
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun waitFor(what: String = "queue", predicate: (List<InstallJob>) -> Boolean): List<InstallJob> = runBlocking {
        withTimeout(10_000) { queue.jobs.first(predicate) }
    }

    private fun job(pkg: String): InstallJob? = queue.jobs.value.firstOrNull { it.entry.packageName == pkg }

    private fun settled(vararg pkgs: String) = waitFor { list -> pkgs.all { p -> list.any { it.entry.packageName == p && !it.step.active } } }

    // A cancelled entry deletes its file as it winds down, a moment after the cancel: wait for it, with the timeout of waitFor above
    // (Files' FileJobsTest, https://github.com/zhanghai/MaterialFiles).
    private fun noFilesLeft() {
        val end = System.currentTimeMillis() + 5_000
        while (dir.list()?.isNotEmpty() == true && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals(emptyList<String>(), dir.list()?.toList().orEmpty())
    }

    // Waits until [what] holds, up to ten seconds: waitFor's timeout, for fields that are not in the StateFlow
    // (Files' FileJobsTest, https://github.com/zhanghai/MaterialFiles).
    private fun until(what: String, predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!predicate() && System.currentTimeMillis() < end) Thread.sleep(5)
        assertTrue("never: $what", predicate())
    }

    @Test
    fun `an honest release is downloaded, checked and installed as a store would, then the file is gone`() {
        assertTrue(queue.enqueue(entry("com.yaz.alpha")))
        settled("com.yaz.alpha")
        assertEquals(JobStep.DONE, job("com.yaz.alpha")!!.step)
        val request = backend.installs.single()
        assertEquals("com.yaz.alpha", request.packageName)
        assertEquals(InstallOrigin.STORE, request.source)
        assertTrue(request.silent)
        assertTrue(request.claimOwnership)
        assertEquals(1, queued.get())
        noFilesLeft()
    }

    @Test
    fun `a wrong sum is never inspected nor installed, and says why`() {
        backend.sumsFor = { "${"0".repeat(64)}  com.yaz.alpha.apk\n" }
        queue.enqueue(entry("com.yaz.alpha"))
        settled("com.yaz.alpha")
        val job = job("com.yaz.alpha")!!
        assertEquals(JobStep.FAILED, job.step)
        assertEquals(StoreChecks.SUM_WRONG, job.why)
        assertEquals(0, backend.inspected.get())
        assertTrue(backend.installs.isEmpty())
        noFilesLeft()
    }

    @Test
    fun `a sums file that does not list the apk stops before any download`() {
        backend.sumsFor = { "$goodSum  another.apk\n" }
        queue.enqueue(entry("com.yaz.alpha"))
        settled("com.yaz.alpha")
        assertEquals(StoreChecks.SUM_MISSING, job("com.yaz.alpha")!!.why)
        assertEquals(0, backend.inspected.get())
        noFilesLeft()
    }

    @Test
    fun `a file the inspector cannot read is refused and deleted`() {
        backend.inspectResult = { null }
        queue.enqueue(entry("com.yaz.alpha"))
        settled("com.yaz.alpha")
        assertEquals(StoreChecks.UNREADABLE, job("com.yaz.alpha")!!.why)
        assertTrue(backend.installs.isEmpty())
        noFilesLeft()
    }

    @Test
    fun `each check that fails after the sum stops the install`() {
        // package, versionCode, foreign signer, signer differing from the installed app's, minSdk: StoreChecksTest has every message.
        val cases = listOf<Pair<(String) -> ApkInfo, (Fake) -> Unit>>(
            { _: String -> info("com.evil.app") } to { _: Fake -> },
            { p: String -> info(p, version = 99) } to { _: Fake -> },
            { p: String -> info(p).copy(signers = listOf("0".repeat(64))) } to { _: Fake -> },
            { p: String -> info(p) } to { f: Fake -> f.signers = setOf(OurKeys.DIGESTS.last()) },
            { p: String -> info(p).copy(minSdk = 99) } to { _: Fake -> }
        )
        for ((make, prepare) in cases) {
            backend.inspectResult = make
            backend.signers = null
            prepare(backend)
            queue.enqueue(entry("com.yaz.alpha"))
            settled("com.yaz.alpha")
            assertEquals(JobStep.FAILED, job("com.yaz.alpha")!!.step)
            assertNotNull(job("com.yaz.alpha")!!.why)
            noFilesLeft()
        }
        assertTrue(backend.installs.isEmpty())
        assertEquals(cases.size, backend.inspected.get())
    }

    @Test
    fun `a second tap on the same app is ignored while it is queued or running`() {
        backend.hold = "alpha" to CountDownLatch(1)
        assertTrue(queue.enqueue(entry("com.yaz.alpha")))
        assertFalse(queue.enqueue(entry("com.yaz.alpha")))
        assertTrue(backend.holding.await(10, TimeUnit.SECONDS))
        assertFalse(queue.enqueue(entry("com.yaz.alpha")))
        assertEquals(1, queue.jobs.value.size)
        backend.hold!!.second.countDown()
        settled("com.yaz.alpha")
        assertEquals(1, backend.installs.size)
        // Once it has ended a tap queues it again, and replaces the finished row.
        assertTrue(queue.enqueue(entry("com.yaz.alpha")))
        settled("com.yaz.alpha")
        assertEquals(2, backend.installs.size)
        assertEquals(1, queue.jobs.value.size)
    }

    @Test
    fun `a failure does not stop the others, and only one installs at a time`() {
        backend.sumsFor = { repo -> if (repo == "alpha") "" else "$goodSum  com.yaz.$repo.apk\n" }
        backend.installOutcome = { _, _ -> delay(30); InstallState.Done("x") }
        queue.enqueue(entry("com.yaz.alpha"))
        queue.enqueue(entry("com.yaz.beta"))
        queue.enqueue(entry("com.yaz.gamma"))
        settled("com.yaz.alpha", "com.yaz.beta", "com.yaz.gamma")
        assertEquals(JobStep.FAILED, job("com.yaz.alpha")!!.step)
        assertEquals(JobStep.DONE, job("com.yaz.beta")!!.step)
        assertEquals(JobStep.DONE, job("com.yaz.gamma")!!.step)
        assertEquals(setOf("com.yaz.beta", "com.yaz.gamma"), backend.installs.map { it.packageName }.toSet())
        assertEquals(1, backend.maxInstalling)
        noFilesLeft()
    }

    // The next cases are those of GrapheneOS' App Store start (https://github.com/GrapheneOS/AppStore, app/src/main/java/app/grapheneos/apps/
    // core/InstallStart.kt, startPackageUpdate: every package started at once, the self-update committed after all the others), with
    // Google Play's one install at a time on top.
    @Test
    fun `every entry downloads at the same time`() {
        backend.gates["alpha"] = CountDownLatch(1)
        backend.gates["beta"] = CountDownLatch(1)
        backend.gates["gamma"] = CountDownLatch(1)
        queue.enqueue(entry("com.yaz.alpha"))
        queue.enqueue(entry("com.yaz.beta"))
        queue.enqueue(entry("com.yaz.gamma"))
        for (repo in listOf("alpha", "beta", "gamma")) assertTrue(backend.started(repo).await(10, TimeUnit.SECONDS))
        assertEquals(3, backend.maxConcurrent)
        assertEquals(3, queue.jobs.value.count { it.step == JobStep.DOWNLOADING })
        assertTrue(backend.installs.isEmpty())
        backend.gates.values.forEach { it.countDown() }
        settled("com.yaz.alpha", "com.yaz.beta", "com.yaz.gamma")
        assertEquals(3, backend.installs.size)
        noFilesLeft()
    }

    @Test
    fun `the entry that is ready first is installed first, and an entry waiting for its turn shows as checking`() {
        backend.gates["alpha"] = CountDownLatch(1)
        backend.gates["beta"] = CountDownLatch(1)
        val turn = CountDownLatch(1)
        backend.installOutcome = { _, _ -> turn.await(10, TimeUnit.SECONDS); InstallState.Done("x") }
        queue.enqueue(entry("com.yaz.alpha"))
        queue.enqueue(entry("com.yaz.beta"))
        assertTrue(backend.started("alpha").await(10, TimeUnit.SECONDS))
        assertTrue(backend.started("beta").await(10, TimeUnit.SECONDS))
        // Beta is released first although it was queued second.
        backend.gates["beta"]!!.countDown()
        until("beta installing") { job("com.yaz.beta")?.step == JobStep.INSTALLING }
        backend.gates["alpha"]!!.countDown()
        // Alpha is checked, and waits behind beta's install.
        until("alpha waiting") { job("com.yaz.alpha")?.let { it.step == JobStep.CHECKING && it.progress == 1f } == true }
        Thread.sleep(100)
        assertEquals(listOf("com.yaz.beta"), backend.installs.map { it.packageName })
        assertEquals(JobStep.CHECKING, job("com.yaz.alpha")!!.step)
        turn.countDown()
        settled("com.yaz.alpha", "com.yaz.beta")
        assertEquals(listOf("com.yaz.beta", "com.yaz.alpha"), backend.installs.map { it.packageName })
        assertEquals(1, backend.maxInstalling)
        noFilesLeft()
    }

    @Test
    fun `never two installs at the same time`() {
        backend.installOutcome = { _, _ -> delay(60); InstallState.Done("x") }
        val names = (1..6).map { "com.yaz.app$it" }
        names.forEach { queue.enqueue(entry(it)) }
        settled(*names.toTypedArray())
        assertTrue(names.all { job(it)!!.step == JobStep.DONE })
        assertEquals(6, backend.installs.size)
        assertEquals(1, backend.maxInstalling)
        noFilesLeft()
    }

    @Test
    fun `the stores own update is installed after every other entry, whatever got ready first`() {
        backend.gates["alpha"] = CountDownLatch(1)
        queue.enqueue(entry("com.yaz.store"))
        queue.enqueue(entry("com.yaz.alpha"))
        assertTrue(backend.started("alpha").await(10, TimeUnit.SECONDS))
        until("store checked") { job("com.yaz.store")?.let { it.step == JobStep.CHECKING && it.progress == 1f } == true }
        Thread.sleep(150)
        assertTrue(backend.installs.isEmpty())
        backend.gates["alpha"]!!.countDown()
        settled("com.yaz.alpha", "com.yaz.store")
        assertEquals(listOf("com.yaz.alpha", "com.yaz.store"), backend.installs.map { it.packageName })
        noFilesLeft()
    }

    @Test
    fun `cancelling the entry that installs abandons the session, once, and the next one installs`() {
        val inSession = CountDownLatch(1)
        backend.installOutcome = { request, _ ->
            if (request.packageName == "com.yaz.alpha") {
                inSession.countDown()
                awaitCancellation()
            }
            InstallState.Done("x")
        }
        queue.enqueue(entry("com.yaz.alpha"))
        assertTrue(inSession.await(10, TimeUnit.SECONDS))
        queue.enqueue(entry("com.yaz.beta"))
        until("beta waiting") { job("com.yaz.beta")?.step == JobStep.CHECKING }
        queue.cancel("com.yaz.alpha")
        settled("com.yaz.beta")
        assertEquals(JobStep.DONE, job("com.yaz.beta")!!.step)
        assertNull(job("com.yaz.alpha"))
        assertEquals(1, backend.cancelled.get())
        noFilesLeft()
    }

    @Test
    fun `no connection fails the entry in plain words and the next goes on`() {
        backend.sumsError = NetError(NetError.Kind.OFFLINE, message = "offline")
        queue.enqueue(entry("com.yaz.alpha"))
        settled("com.yaz.alpha")
        assertEquals("No connection.", job("com.yaz.alpha")!!.why)
        backend.sumsError = null
        queue.enqueue(entry("com.yaz.beta"))
        settled("com.yaz.beta")
        assertEquals(JobStep.DONE, job("com.yaz.beta")!!.step)
    }

    @Test
    fun `a cancel in the middle of a download abandons it, deletes the file, never touches the install session and lets the others go on`() {
        backend.hold = "alpha" to CountDownLatch(1)
        queue.enqueue(entry("com.yaz.alpha"))
        queue.enqueue(entry("com.yaz.beta"))
        assertTrue(backend.holding.await(10, TimeUnit.SECONDS))
        assertEquals(JobStep.DOWNLOADING, job("com.yaz.alpha")!!.step)
        assertTrue(File(dir, "com.yaz.alpha.apk").isFile)
        queue.cancel("com.yaz.alpha")
        assertNull(job("com.yaz.alpha"))
        settled("com.yaz.beta")
        assertEquals(JobStep.DONE, job("com.yaz.beta")!!.step)
        assertEquals(listOf("com.yaz.beta"), backend.installs.map { it.packageName })
        assertEquals(0, backend.cancelled.get())
        assertNull(job("com.yaz.alpha"))
        noFilesLeft()
    }

    @Test
    fun `a cancel of a waiting entry drops it, cancel all stops everything`() {
        val repos = listOf("alpha", "beta", "gamma")
        repos.forEach { backend.gates[it] = CountDownLatch(1) }
        repos.forEach { queue.enqueue(entry("com.yaz.$it")) }
        repos.forEach { assertTrue(backend.started(it).await(10, TimeUnit.SECONDS)) }
        queue.cancel("com.yaz.beta")
        assertNull(job("com.yaz.beta"))
        queue.cancelAll()
        assertTrue(queue.jobs.value.none { it.step.active })
        assertTrue(queue.jobs.value.isEmpty())
        // The queue is still usable afterwards.
        backend.gates.clear()
        assertTrue(queue.enqueue(entry("com.yaz.delta")))
        settled("com.yaz.delta")
        assertEquals(listOf("com.yaz.delta"), backend.installs.map { it.packageName })
        noFilesLeft()
    }

    @Test
    fun `an app cancelled and tapped again at once starts clean`() {
        backend.hold = "alpha" to CountDownLatch(1)
        queue.enqueue(entry("com.yaz.alpha"))
        assertTrue(backend.holding.await(10, TimeUnit.SECONDS))
        queue.cancel("com.yaz.alpha")
        backend.hold = null
        assertTrue(queue.enqueue(entry("com.yaz.alpha")))
        settled("com.yaz.alpha")
        assertEquals(JobStep.DONE, job("com.yaz.alpha")!!.step)
        assertEquals(1, backend.installs.size)
        noFilesLeft()
    }

    @Test
    fun `update all queues the entries that have an update, once each`() {
        fun status(pkg: String, state: AppState) = AppStatus(entry(pkg), state, null)
        val list = listOf(
            status("com.yaz.alpha", AppState.UPDATE), status("com.yaz.beta", AppState.UP_TO_DATE),
            status("com.yaz.gamma", AppState.UPDATE), status("com.yaz.delta", AppState.NOT_INSTALLED), status("com.yaz.eps", AppState.HAND_OVER)
        )
        assertEquals(2, queue.enqueueUpdates(list))
        settled("com.yaz.alpha", "com.yaz.gamma")
        assertEquals(setOf("com.yaz.alpha", "com.yaz.gamma"), backend.installs.map { it.packageName }.toSet())
    }

    @Test
    fun `an entry with no apk for this phone is not queued`() {
        assertFalse(queue.enqueue(entry("com.yaz.alpha", apk = null)))
        assertTrue(queue.jobs.value.isEmpty())
        assertEquals(0, queued.get())
    }

    @Test
    fun `the phone's own refusal is shown as the entry's reason`() {
        backend.installOutcome = { _, _ -> InstallState.Failed(InstallFailure("There is not enough storage space.")) }
        queue.enqueue(entry("com.yaz.alpha"))
        settled("com.yaz.alpha")
        assertEquals("There is not enough storage space.", job("com.yaz.alpha")!!.why)
        noFilesLeft()
    }

    @Test
    fun `android asking for the person's yes shows as a step with its screen, then ends well`() {
        val gate = CountDownLatch(1)
        val screen = Intent("confirm")
        backend.installOutcome = { _, onState ->
            onState(InstallState.Confirm(screen))
            gate.await(10, TimeUnit.SECONDS)
            InstallState.Done("com.yaz.alpha")
        }
        queue.enqueue(entry("com.yaz.alpha", version = 14), handOver = true)
        val asking = waitFor { list -> list.any { it.step == JobStep.CONFIRM } }.single()
        assertEquals(screen, asking.confirm)
        assertTrue(asking.handOver)
        gate.countDown()
        settled("com.yaz.alpha")
        assertEquals(JobStep.DONE, job("com.yaz.alpha")!!.step)
        assertNull(job("com.yaz.alpha")!!.confirm)
    }

    @Test
    fun `a crash in the backend fails that entry and the next one still runs`() {
        backend.inspectResult = { pkg -> if (pkg.endsWith("alpha")) error("boom") else info(pkg) }
        queue.enqueue(entry("com.yaz.alpha"))
        queue.enqueue(entry("com.yaz.beta"))
        settled("com.yaz.alpha", "com.yaz.beta")
        assertEquals(JobStep.FAILED, job("com.yaz.alpha")!!.step)
        assertEquals(JobStep.DONE, job("com.yaz.beta")!!.step)
        noFilesLeft()
    }

    @Test
    fun `a finished row can be dismissed, a running one cannot`() {
        queue.enqueue(entry("com.yaz.alpha"))
        settled("com.yaz.alpha")
        queue.dismiss("com.yaz.alpha")
        assertNull(job("com.yaz.alpha"))
    }
}
