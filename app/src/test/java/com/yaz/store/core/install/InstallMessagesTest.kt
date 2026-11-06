package com.yaz.store.core.install

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The status codes of PackageInstaller (STATUS_FAILURE_*, EXTRA_STATUS_MESSAGE with INSTALL_FAILED_* codes,
// https://developer.android.com/reference/android/content/pm/PackageInstaller#STATUS_FAILURE) and the messages App Manager
// gives them (GPL-3.0-or-later, PackageInstallerActivity.getStringFromStatus):
// https://github.com/MuntashirAkon/AppManager/blob/master/app/src/main/java/io/github/muntashirakon/AppManager/apk/installer/PackageInstallerActivity.java
class InstallMessagesTest {

    private fun message(status: Int, text: String? = null, other: String? = null) = InstallMessages.failure(status, text, other)

    @Test
    fun `every status of the installer has a sentence`() {
        val statuses = listOf(
            PackageInstaller.STATUS_FAILURE, PackageInstaller.STATUS_FAILURE_ABORTED, PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID, PackageInstaller.STATUS_FAILURE_STORAGE,
            InstallMessages.SESSION_CREATE, InstallMessages.SESSION_WRITE, InstallMessages.SESSION_COMMIT, 12345
        )
        for (status in statuses) {
            val text = message(status).message
            assertTrue("status $status: '$text'", text.endsWith(".") && text.length > 10)
        }
    }

    @Test
    fun `incompatible says why`() {
        val text = message(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE).message
        assertTrue(text.contains("processor") && text.contains("Android version"))
    }

    @Test
    fun `a blocked install names who blocked it, cleaned`() {
        assertEquals("Android blocked the installation.", message(PackageInstaller.STATUS_FAILURE_BLOCKED).message)
        assertEquals(
            "Android blocked the installation (com.example.guard).",
            message(PackageInstaller.STATUS_FAILURE_BLOCKED, other = "com.example.guard").message
        )
        assertFalse(message(PackageInstaller.STATUS_FAILURE_BLOCKED, other = "x\u0000y\n").message.contains("\u0000"))
    }

    @Test
    fun `a conflict offers to uninstall first`() {
        assertTrue(message(PackageInstaller.STATUS_FAILURE_CONFLICT).offerUninstall)
        assertFalse(message(PackageInstaller.STATUS_FAILURE_STORAGE).offerUninstall)
    }

    @Test
    fun `the package manager's own codes are put in words`() {
        assertEquals(
            "Android refuses to install an older version over a newer one.",
            message(PackageInstaller.STATUS_FAILURE, "INSTALL_FAILED_VERSION_DOWNGRADE").message
        )
        val update = message(PackageInstaller.STATUS_FAILURE, "INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package signatures do not match")
        assertTrue(update.message.contains("signed with another key") && update.offerUninstall)
        assertTrue(message(PackageInstaller.STATUS_FAILURE, "INSTALL_FAILED_NO_MATCHING_ABIS").message.contains("processor"))
        assertTrue(message(PackageInstaller.STATUS_FAILURE, "INSTALL_FAILED_OLDER_SDK").message.contains("newer Android"))
    }

    @Test
    fun `a code that is not known is shown, nothing else of the system's text is`() {
        val text = message(PackageInstaller.STATUS_FAILURE, "INSTALL_FAILED_SOMETHING_NEW: details from the system <b>").message
        assertEquals("The installation failed. (INSTALL_FAILED_SOMETHING_NEW)", text)
        assertEquals("The installation failed.", message(PackageInstaller.STATUS_FAILURE, "free text only").message)
        assertEquals("The installation failed.", message(PackageInstaller.STATUS_FAILURE, null).message)
    }

    // The system's own text kept beside the plain message: App Manager's showInstallationFinishedDialog (statusMessage under
    // the message) and GrapheneOS's PackageInstallerError (message = EXTRA_STATUS_MESSAGE kept whole)
    @Test
    fun `the system's text is kept as detail, for a known code and for an unknown one`() {
        val known = message(PackageInstaller.STATUS_FAILURE, "INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package signatures do not match")
        assertEquals("INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package signatures do not match", known.detail)
        assertTrue(known.offerUninstall)
        val unknown = message(PackageInstaller.STATUS_FAILURE, "INSTALL_FAILED_SOMETHING_NEW: details from the system <b>")
        assertEquals("INSTALL_FAILED_SOMETHING_NEW: details from the system <b>", unknown.detail)
        assertEquals("The installation failed. (INSTALL_FAILED_SOMETHING_NEW)", unknown.message)
        assertEquals("free text only", message(PackageInstaller.STATUS_FAILURE, "free text only").detail)
        assertEquals("Session failed", message(PackageInstaller.STATUS_FAILURE_STORAGE, "Session failed").detail)
        assertEquals("Session failed", message(InstallMessages.SESSION_COMMIT, "Session failed").detail)
    }

    @Test
    fun `no detail when the system gave no text`() {
        assertNull(message(PackageInstaller.STATUS_FAILURE, null).detail)
        assertNull(message(PackageInstaller.STATUS_FAILURE, "").detail)
        assertNull(message(PackageInstaller.STATUS_FAILURE, "  \n\t ").detail)
        assertNull(message(PackageInstaller.STATUS_FAILURE_ABORTED).detail)
    }

    @Test
    fun `the detail drops control and bidi characters and is cut`() {
        assertEquals(
            "INSTALL_FAILED_X: abc",
            message(PackageInstaller.STATUS_FAILURE, "INSTALL_FAILED_X:\u0000 a\u202Eb\u2066c\u200F\u0007").detail
        )
        assertEquals(1000, message(PackageInstaller.STATUS_FAILURE, "a".repeat(5000)).detail?.length)
    }
}
