package com.yaz.store.core.install

import android.content.pm.PackageInstaller
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The session parameters a request asks for, by Android level, for the defaults (a file opened by the person) and for a store's
// install (GrapheneOS' App Store, InstallTask.makeSessionParams:
// https://github.com/GrapheneOS/AppStore/blob/main/app/src/main/java/app/grapheneos/apps/core/InstallTask.kt).
// Robolectric runs the platform's own SessionParams (https://robolectric.org/getting-started/) so the call that sets each field is
// the real one; the fields are read back by reflection, as the platform keeps them (SessionParams.requireUserAction, packageSource, installFlags).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class SessionChoicesTest {

    private val files = listOf(File("base.apk"))
    private val plain = InstallRequest("com.example.app", files)
    private val store = InstallRequest("com.example.app", files, InstallOrigin.STORE, silent = true, claimOwnership = true)

    @Test
    fun `a file opened by the person asks for nothing more than the local file source`() {
        assertEquals(SessionChoices(null, false, false), SessionChoices.of(plain, 31, installed = false))
        assertEquals(SessionChoices(null, false, false), SessionChoices.of(plain, 32, installed = false))
        assertEquals(SessionChoices(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE, false, false), SessionChoices.of(plain, 33, installed = false))
        assertEquals(SessionChoices(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE, false, false), SessionChoices.of(plain, 34, installed = true))
        assertEquals(SessionChoices(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE, false, false), SessionChoices.of(plain, 36, installed = false))
    }

    @Test
    fun `a store install asks for no question from 31, the store source from 33, the ownership from 34`() {
        assertEquals(SessionChoices(null, true, false), SessionChoices.of(store, 31, installed = false))
        assertEquals(SessionChoices(null, true, false), SessionChoices.of(store, 32, installed = false))
        assertEquals(SessionChoices(PackageInstaller.PACKAGE_SOURCE_STORE, true, false), SessionChoices.of(store, 33, installed = false))
        assertEquals(SessionChoices(PackageInstaller.PACKAGE_SOURCE_STORE, true, true), SessionChoices.of(store, 34, installed = false))
        assertEquals(SessionChoices(PackageInstaller.PACKAGE_SOURCE_STORE, true, true), SessionChoices.of(store, 36, installed = false))
    }

    @Test
    fun `the ownership is asked for on a first install only`() {
        assertFalse(SessionChoices.of(store, 36, installed = true).requestOwnership)
        assertTrue(SessionChoices.of(store, 36, installed = false).requestOwnership)
        // Without the request, never.
        assertFalse(SessionChoices.of(InstallRequest("com.example.app", files, InstallOrigin.STORE, silent = true), 36, installed = false).requestOwnership)
    }

    @Test
    fun `silent and ownership are independent of the source`() {
        val silentFile = InstallRequest("com.example.app", files, silent = true)
        assertEquals(SessionChoices(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE, true, false), SessionChoices.of(silentFile, 36, installed = false))
        val storeOnly = InstallRequest("com.example.app", files, InstallOrigin.STORE)
        assertEquals(SessionChoices(PackageInstaller.PACKAGE_SOURCE_STORE, false, false), SessionChoices.of(storeOnly, 36, installed = false))
    }

    private fun params(choices: SessionChoices): PackageInstaller.SessionParams =
        PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).also { choices.applyTo(it) }

    private fun field(params: PackageInstaller.SessionParams, name: String): Int =
        PackageInstaller.SessionParams::class.java.getField(name).getInt(params)

    @Test
    fun `a store request lands in the platform's session parameters`() {
        val p = params(SessionChoices.of(store, 36, installed = false))
        assertEquals(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED, field(p, "requireUserAction"))
        assertEquals(PackageInstaller.PACKAGE_SOURCE_STORE, field(p, "packageSource"))
        // INSTALL_REQUEST_UPDATE_OWNERSHIP, set by setRequestUpdateOwnership(true).
        assertTrue(field(p, "installFlags") and (1 shl 25) != 0)
    }

    @Test
    fun `a plain request leaves the platform's defaults`() {
        val p = params(SessionChoices.of(plain, 36, installed = false))
        assertEquals(PackageInstaller.SessionParams.USER_ACTION_UNSPECIFIED, field(p, "requireUserAction"))
        assertEquals(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE, field(p, "packageSource"))
        assertEquals(0, field(p, "installFlags") and (1 shl 25))
    }

    @Test
    fun `the defaults of a request keep today's behaviour`() {
        assertEquals(InstallOrigin.LOCAL_FILE, plain.source)
        assertFalse(plain.silent)
        assertFalse(plain.claimOwnership)
        assertNull(SessionChoices.of(plain, 30, installed = false).packageSource)
    }
}
