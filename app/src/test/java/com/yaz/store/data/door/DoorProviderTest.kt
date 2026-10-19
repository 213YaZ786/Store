package com.yaz.store.data.door

import android.os.Bundle
import com.yaz.store.data.catalog.AppState
import com.yaz.store.data.catalog.AppStatus
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The provider's own answers on the JVM: an empty Bundle when the application has not started (Koin's GlobalContext, as the provider waits for
// it) and for a caller the guard refuses; the table methods give nothing back. Koin's start and stop as in its test documentation
// (https://insert-koin.io/docs/reference/koin-test/testing). The call from another app is Robolectric's: no calling package, so refused.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class DoorProviderTest {

    private val provider = DoorProvider()

    @Before
    fun setUp() {
        DoorProvider.startWaitMs = 100
    }

    @After
    fun tearDown() {
        if (GlobalContext.getOrNull() != null) stopKoin()
        DoorProvider.startWaitMs = 10_000
    }

    @Test
    fun `before the application has started the answer is empty, after a bounded wait`() {
        val start = System.currentTimeMillis()
        val answer = provider.call("opened", null, null)
        assertTrue(answer.isEmpty)
        assertTrue(System.currentTimeMillis() - start < 5_000)
    }

    @Test
    fun `an unknown caller gets an empty answer even with the door built, and extras are not read`() {
        var loaded = 0
        val door = Door(
            DoorGuard({ null }, { false }, false),
            Staging(unusedBackend(), File(System.getProperty("java.io.tmpdir")), { null }),
            load = { loaded++; emptyList() },
            statusOf = { e, _ -> AppStatus(e, AppState.UPDATE, null, false) }
        )
        startKoin { modules(module { single { door } }) }
        val extras = Bundle().apply { putString("package", "com.yaz.calculator") }
        val answer = provider.call("opened", "com.yaz.calculator", extras)
        assertTrue(answer.isEmpty)
        assertFalse(answer.containsKey(Door.STATE))
        assertTrue(loaded == 0)
    }

    @Test
    fun `nothing can be read back through the table methods`() {
        val uri = android.net.Uri.parse("content://com.yaz.store.door")
        assertTrue(provider.query(uri, null, null, null, null) == null)
        assertTrue(provider.getType(uri) == null)
        assertTrue(provider.insert(uri, null) == null)
        assertTrue(provider.delete(uri, null, null) == 0)
        assertTrue(provider.update(uri, null, null, null) == 0)
    }

    private fun unusedBackend(): com.yaz.store.data.install.InstallBackend = object : com.yaz.store.data.install.InstallBackend {
        override val sdk = 36
        override fun sums(repo: String, tag: String) = error("not used")
        override fun download(repo: String, tag: String, apkName: String, into: File, onProgress: (Float) -> Unit, beforeRead: () -> Unit) = error("not used")
        override suspend fun inspect(file: File) = error("not used")
        override fun installedSigners(packageName: String) = error("not used")
        override suspend fun install(request: com.yaz.store.core.install.InstallRequest, onState: (com.yaz.store.core.install.InstallState) -> Unit) = error("not used")
        override fun cancelInstall() = error("not used")
    }
}
