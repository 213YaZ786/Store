package com.yaz.store.core.install

import android.content.pm.PackageInstaller
import com.yaz.store.core.apk.ApkInfoCodec

// The install status codes in plain English. The cases are App Manager's getStringFromStatus
// (GPL-3.0-or-later, PackageInstallerActivity.java and strings.xml installer_error_*):
// https://github.com/MuntashirAkon/AppManager/blob/master/app/src/main/java/io/github/muntashirakon/AppManager/apk/installer/PackageInstallerActivity.java
// The status codes are PackageInstaller's STATUS_* and the messages of its EXTRA_STATUS_MESSAGE
// (INSTALL_FAILED_* of the package manager):
// https://developer.android.com/reference/android/content/pm/PackageInstaller#STATUS_FAILURE_INCOMPATIBLE

/** An install that did not go in: what to tell the person, and whether uninstalling the installed app first would help. */
data class InstallFailure(val message: String, val offerUninstall: Boolean = false)

object InstallMessages {
    /** The installer's own failures, before Android is asked (App Manager's STATUS_FAILURE_SESSION_*). */
    const val SESSION_CREATE = -3
    const val SESSION_WRITE = -4
    const val SESSION_COMMIT = -5

    private val CODE = Regex("INSTALL_[A-Z_]+")

    fun failure(status: Int, statusMessage: String?, otherPackage: String? = null): InstallFailure {
        val detail = detail(statusMessage)
        return when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallFailure("The installation was cancelled.")
            PackageInstaller.STATUS_FAILURE_BLOCKED -> InstallFailure(
                "Android blocked the installation" + (otherPackage?.let { " (${ApkInfoCodec.clean(it, 100)})" } ?: "") + "."
            )
            PackageInstaller.STATUS_FAILURE_CONFLICT -> InstallFailure(
                "This app conflicts with one already on the phone. If it is signed differently, uninstall it first.",
                offerUninstall = true
            )
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> InstallFailure(
                "The app is not compatible with this phone: its processor, its Android version or a feature it needs."
            )
            PackageInstaller.STATUS_FAILURE_INVALID -> InstallFailure("These files are not a valid app: damaged or incomplete.")
            PackageInstaller.STATUS_FAILURE_STORAGE -> InstallFailure("There is not enough storage space.")
            SESSION_CREATE -> InstallFailure("Could not start the installation.")
            SESSION_WRITE -> InstallFailure("Could not copy the files to the installer.")
            SESSION_COMMIT -> InstallFailure("Android did not accept the installation.")
            else -> byCode(detail) ?: InstallFailure("The installation failed." + (detail?.let { " ($it)" } ?: ""))
        }
    }

    /** The package manager's own reason, when it is one of the common ones. */
    private fun byCode(detail: String?): InstallFailure? = when (detail) {
        "INSTALL_FAILED_VERSION_DOWNGRADE" ->
            InstallFailure("Android refuses to install an older version over a newer one.")
        "INSTALL_FAILED_UPDATE_INCOMPATIBLE" ->
            InstallFailure("The installed app is signed with another key. Uninstall it first.", offerUninstall = true)
        "INSTALL_FAILED_NO_MATCHING_ABIS" ->
            InstallFailure("The app does not support this phone's processor.")
        "INSTALL_FAILED_OLDER_SDK" ->
            InstallFailure("The app needs a newer Android than this phone runs.")
        "INSTALL_FAILED_INSUFFICIENT_STORAGE" ->
            InstallFailure("There is not enough storage space.")
        "INSTALL_FAILED_MISSING_SHARED_LIBRARY" ->
            InstallFailure("The app needs a library this phone does not have.")
        else -> null
    }

    /** The first INSTALL_FAILED_* code in the system's message, if any; nothing else of it is shown. */
    private fun detail(message: String?): String? = message?.let { CODE.find(it)?.value }
}
