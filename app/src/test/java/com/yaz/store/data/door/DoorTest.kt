package com.yaz.store.data.door

import android.content.Intent
import com.yaz.store.core.apk.ApkInfo
import com.yaz.store.core.install.InstallOrigin
import com.yaz.store.core.install.InstallRequest
import com.yaz.store.core.install.InstallState
import com.yaz.store.data.catalog.AppState
import com.yaz.store.data.catalog.AppStatus
import com.yaz.store.data.catalog.InstalledFacts
import com.yaz.store.data.catalog.StoreEntry
import com.yaz.store.data.install.InstallBackend
import com.yaz.store.data.net.ReleaseNet
import com.yaz.store.data.trust.OurKeys
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The door on the JVM, with a fake phone and network. Protocol, sources:
// - this app's InstallQueueTest (data/install: a fake InstallBackend that counts every call and asserts that a file exists when it is read);
// - the cases of Contacts' CardsProvider (YAZ/Contacts at HEAD, data/contacts/CardsProvider.kt: a caller that is not ours gets nothing);
// - the states of Google Play's In-App Updates (https://developer.android.com/guide/playcore/in-app-updates: nothing, ready, up to date) and
//   GrapheneOS' App Store, which installs one package at a time (https://github.com/GrapheneOS/AppStore, core/InstallTask.kt).
// Robolectric only for android.content.Intent in the confirmation case.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class DoorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val ours = OurKeys.DIGESTS.first()
    private val bytes = ByteArray(4000) { (it % 251).toByte() }
    private val goodSum = run {
        val f = File.createTempFile("sum", ".apk").apply { writeBytes(bytes) }
        ReleaseNet.sha256(f).also { f.delete() }
    }
    private val pkg = "com.yaz.calculator"

    private lateinit var dir: File
    private lateinit var backend: Fake
    private lateinit var door: Door
    private lateinit var staging: Staging
    private val loads = AtomicInteger()
    private var state = AppState.UPDATE
    private var handOver = false
    private var installed: Long? = 3
    private var members = setOf(pkg)
    private val lines = mutableListOf<String>()

    private fun entry(version: Long = 4) = StoreEntry(
        repo = "Calculator", tag = "v1", packageName = pkg, label = "Calculator", description = "",
        versionCode = version, versionName = "1.0", minSdk = 31, apkName = "Calculator.apk", iconUrl = null
    )

    private fun info(version: Long = 4) = ApkInfo(
        packageName = pkg, versionCode = version, versionName = "1.0", splitName = null, isFeatureSplit = false, configForSplit = null,
        isSplitRequired = false, minSdk = 31, permissions = emptyList(), abis = emptyList(), verified = true, signers = listOf(ours), label = null
    )

    private inner class Fake : InstallBackend {
        override val sdk = 36
        var sums = "$goodSum  ./Calculator.apk\n"
        val downloads = AtomicInteger()
        val installs = mutableListOf<InstallRequest>()
        val cancelled = AtomicInteger()
        var installOutcome: suspend (InstallRequest, (InstallState) -> Unit) -> InstallState = { _, _ -> InstallState.Done(pkg) }

        override fun sums(repo: String, tag: String) = sums

        override fun download(repo: String, tag: String, apkName: String, into: File, onProgress: (Float) -> Unit, beforeRead: () -> Unit) {
            downloads.incrementAndGet()
            into.writeBytes(bytes)
        }

        var version = 4L

        override suspend fun inspect(file: File): ApkInfo? = info(version)

        override fun installedSigners(packageName: String): Set<String>? = null

        override suspend fun install(request: InstallRequest, onState: (InstallState) -> Unit): InstallState {
            installs += request
            assertTrue("installed a file that is not there", request.files.single().isFile)
            return installOutcome(request, onState)
        }

        override fun cancelInstall() {
            cancelled.incrementAndGet()
        }
    }

    @Before
    fun setUp() {
        dir = File(temp.root, "downloads")
        backend = Fake()
        staging = Staging(backend, dir, installedVersion = { installed }, commitTimeoutMs = 5_000)
        val guard = DoorGuard({ if (it in members) setOf(ours) else if (it == "evil.app") setOf("0".repeat(64)) else null }, { false }, debug = false)
        door = Door(
            guard, staging,
            load = { loads.incrementAndGet(); listOf(entry()) },
            statusOf = { e, _ -> AppStatus(e, state, InstalledFacts(3, "1.0", setOf(ours), "com.yaz.store", null), handOver) },
            log = { lines += it }
        )
    }

    private fun staged() = File(dir, "staged").list()?.toList().orEmpty()

    @Test
    fun `a caller that is not ours gets nothing and nothing is read or done`() {
        assertNull(door.handle("opened", "evil.app"))
        assertNull(door.handle("opened", "not.installed"))
        assertNull(door.handle("opened", null))
        assertNull(door.handle("left", "evil.app"))
        assertEquals(0, loads.get())
        assertEquals(0, backend.downloads.get())
        assertTrue(lines.all { "evil" !in it })
    }

    @Test
    fun `an unknown method gets nothing`() {
        assertNull(door.handle("delete", pkg))
        assertNull(door.handle("", pkg))
        assertEquals(0, loads.get())
    }

    @Test
    fun `opened with an update stages the checked file and left installs it silently as Store, once`() {
        assertEquals(Door.STAGED, door.handle("opened", pkg))
        assertEquals(1, backend.downloads.get())
        assertEquals(listOf("$pkg.apk"), staged())
        assertEquals(Door.INSTALLED, door.handle("left", pkg))
        val request = backend.installs.single()
        assertEquals(pkg, request.packageName)
        assertEquals(InstallOrigin.STORE, request.source)
        assertTrue(request.silent)
        assertTrue(request.claimOwnership)
        assertEquals(emptyList<String>(), staged())
        // A second left has nothing to commit.
        assertEquals(Door.NONE, door.handle("left", pkg))
        assertEquals(1, backend.installs.size)
    }

    @Test
    fun `an app that Store does not hold yet is left for Home, nothing is downloaded`() {
        handOver = true
        assertEquals(Door.NONE, door.handle("opened", pkg))
        assertEquals(0, backend.downloads.get())
        assertEquals(Door.NONE, door.handle("left", pkg))
        assertTrue(backend.installs.isEmpty())
    }

    @Test
    fun `an app up to date, and states Store does not act on, download nothing`() {
        state = AppState.UP_TO_DATE
        assertEquals(Door.UPTODATE, door.handle("opened", pkg))
        for (s in listOf(AppState.HAND_OVER, AppState.FOREIGN, AppState.TOO_NEW, AppState.NO_APK, AppState.NOT_INSTALLED)) {
            state = s
            assertEquals(Door.NONE, door.handle("opened", pkg))
        }
        assertEquals(0, backend.downloads.get())
    }

    @Test
    fun `an app that is not in the catalog gets none`() {
        members = setOf("com.yaz.other")
        assertEquals(Door.NONE, door.handle("opened", "com.yaz.other"))
        assertEquals(0, backend.downloads.get())
    }

    @Test
    fun `left commits only what opened staged, not another app's file`() {
        assertEquals(Door.NONE, door.handle("left", pkg))
        assertTrue(backend.installs.isEmpty())
        members = setOf(pkg, "com.yaz.other")
        door.handle("opened", pkg)
        assertEquals(Door.NONE, door.handle("left", "com.yaz.other"))
        assertTrue(backend.installs.isEmpty())
        assertEquals(listOf("$pkg.apk"), staged())
    }

    @Test
    fun `a refused caller cannot commit a staged file`() {
        door.handle("opened", pkg)
        assertNull(door.handle("left", "evil.app"))
        assertTrue(backend.installs.isEmpty())
    }

    @Test
    fun `opened twice downloads once and reads the catalog once`() {
        assertEquals(Door.STAGED, door.handle("opened", pkg))
        assertEquals(Door.STAGED, door.handle("opened", pkg))
        assertEquals(1, backend.downloads.get())
        assertEquals(1, loads.get())
        assertEquals(1, staged().size)
    }

    @Test
    fun `a wrong sum is refused, nothing stays, and left has nothing to install`() {
        backend.sums = "${"0".repeat(64)}  ./Calculator.apk\n"
        assertEquals(Door.REFUSED, door.handle("opened", pkg))
        assertEquals(emptyList<String>(), staged())
        assertEquals(Door.NONE, door.handle("left", pkg))
        assertTrue(backend.installs.isEmpty())
    }

    @Test
    fun `a network that fails stages nothing`() {
        val failing = object : InstallBackend by backend {
            override fun sums(repo: String, tag: String): String =
                throw com.yaz.store.data.net.NetError(com.yaz.store.data.net.NetError.Kind.OFFLINE, message = "offline")
        }
        val offline = Door(
            DoorGuard({ setOf(ours) }, { false }, false), Staging(failing, dir, installedVersion = { installed }), load = { listOf(entry()) },
            statusOf = { e, _ -> AppStatus(e, AppState.UPDATE, null, false) }
        )
        assertEquals(Door.NONE, offline.handle("opened", pkg))
        assertEquals(emptyList<String>(), staged())
    }

    @Test
    fun `an empty catalog is not kept, the next knock reads it again`() {
        var list = emptyList<StoreEntry>()
        val d = Door(
            DoorGuard({ setOf(ours) }, { false }, false), Staging(backend, dir, { installed }), load = { loads.incrementAndGet(); list },
            statusOf = { e, _ -> AppStatus(e, AppState.UPDATE, null, false) }
        )
        assertEquals(Door.NONE, d.handle("opened", pkg))
        list = listOf(entry())
        assertEquals(Door.STAGED, d.handle("opened", pkg))
        assertEquals(2, loads.get())
    }

    @Test
    fun `an update installed meanwhile is dropped at left`() {
        door.handle("opened", pkg)
        installed = 4
        assertEquals(Door.NONE, door.handle("left", pkg))
        assertTrue(backend.installs.isEmpty())
        assertEquals(emptyList<String>(), staged())
    }

    @Test
    fun `when Android wants a yes the update is dropped, nothing is shown, and Home keeps it`() {
        backend.installOutcome = { _, onState ->
            onState(InstallState.Confirm(Intent("android.intent.action.VIEW")))
            // The real installer waits for Android's end; here for ever, until the door stops waiting.
            kotlinx.coroutines.awaitCancellation()
        }
        assertEquals(Door.STAGED, door.handle("opened", pkg))
        assertEquals(Door.PENDING, door.handle("left", pkg))
        assertEquals(1, backend.cancelled.get())
        assertEquals(emptyList<String>(), staged())
    }

    @Test
    fun `a failed install is reported, the file is gone`() {
        backend.installOutcome = { _, _ -> InstallState.Failed(com.yaz.store.core.install.InstallFailure("no")) }
        door.handle("opened", pkg)
        assertEquals(Door.FAILED, door.handle("left", pkg))
        assertEquals(emptyList<String>(), staged())
    }

    @Test
    fun `a second left while the install runs does nothing`() {
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        backend.installOutcome = { _, _ ->
            inside.countDown()
            while (!release.await(5, TimeUnit.MILLISECONDS)) kotlinx.coroutines.yield()
            InstallState.Done(pkg)
        }
        door.handle("opened", pkg)
        var first: String? = null
        val t = thread { first = door.handle("left", pkg) }
        assertTrue(inside.await(10, TimeUnit.SECONDS))
        assertEquals(Door.NONE, door.handle("left", pkg))
        // And an opening meanwhile does not stage the same update again.
        assertEquals(Door.NONE, door.handle("opened", pkg))
        assertEquals(1, backend.downloads.get())
        release.countDown()
        t.join(10_000)
        assertEquals(Door.INSTALLED, first)
        assertEquals(1, backend.installs.size)
    }

    @Test
    fun `a newer release replaces the staged file, never adds one`() {
        door.handle("opened", pkg)
        assertFalse(staged().isEmpty())
        val newer = Door(
            DoorGuard({ setOf(ours) }, { false }, false),
            staging,
            load = { listOf(entry(5).copy(tag = "v2")) },
            statusOf = { e, _ -> AppStatus(e, AppState.UPDATE, null, false) }
        )
        backend.version = 5
        assertEquals(Door.STAGED, newer.handle("opened", pkg))
        assertEquals(listOf("$pkg.apk"), staged())
        assertEquals(2, backend.downloads.get())
    }

    @Test
    fun `the debug stand-in reads a debug app as signed by ours and held by Store, a real caller as it is`() {
        val e = entry(5)
        val facts = InstalledFacts(4, "1.0", setOf("0".repeat(64)), null, null)
        val debug = Door.status(e, Caller("$pkg.debug", pkg, true), facts, 36, "com.yaz.store.debug")
        assertEquals(AppState.UPDATE, debug.state)
        assertFalse(debug.handOver)
        val real = Door.status(e, Caller(pkg, pkg, false), facts, 36, "com.yaz.store")
        assertEquals(AppState.FOREIGN, real.state)
        assertEquals(AppState.NOT_INSTALLED, Door.status(e, Caller(pkg, pkg, false), null, 36, "com.yaz.store").state)
    }
}
