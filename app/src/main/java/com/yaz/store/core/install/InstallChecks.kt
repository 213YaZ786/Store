package com.yaz.store.core.install

import com.yaz.store.core.apk.ApkInfo
import com.yaz.store.core.apk.DeviceProfile
import com.yaz.store.core.apk.SplitTables

// What is checked before any session is opened.
// - One base, one package, the splits of one app: App Manager's ApkFile ("Duplicate base apk found", "No base apk
//   found", the splits and the base share a package) (GPL-3.0-or-later):
//   https://github.com/MuntashirAkon/AppManager/blob/master/app/src/main/java/io/github/muntashirakon/AppManager/apk/ApkFile.java
// - ABI fit: an APK with lib/<abi>/ folders installs only if one of them is in Build.SUPPORTED_ABIS, AOSP's
//   NativeLibraryHelper:
//   https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/com/android/internal/content/NativeLibraryHelper.java
// - minSdkVersion above the phone's SDK_INT is refused by the package installer (INSTALL_FAILED_OLDER_SDK):
//   https://developer.android.com/guide/topics/manifest/uses-sdk-element
// - Same signer for splits and for an update, and no downgrade without a debuggable app: the package manager's
//   rules, https://developer.android.com/studio/publish/app-signing and
//   https://developer.android.com/reference/android/content/pm/PackageManager#hasSigningCertificate(java.lang.String,%20byte[],%20int)

/** The app as it is on the phone now. */
data class InstalledApp(
    val versionCode: Long,
    val versionName: String,
    /** The names of its splits (PackageInfo.splitNames), to keep them chosen on an update. */
    val splitNames: Set<String>,
    /** Every signer of the new files is one the installed app is signed with, as PackageManager.hasSigningCertificate says. */
    val sameSigner: Boolean
)

/** What the person is told and can still go on. */
sealed interface InstallWarning {
    /** The files are older than the installed app: Android refuses unless the app is debuggable. */
    data class Downgrade(val installed: String, val offered: String) : InstallWarning

    /** The files are signed by another key than the installed app: Android refuses it. */
    data object SignerChanged : InstallWarning

    /** .obb files of the bundle that Store does not install. */
    data class ObbSkipped(val count: Int) : InstallWarning
}

sealed interface InstallVerdict {
    /** The files cannot be installed on this phone, whatever the person chooses. */
    data class Refused(val reason: String) : InstallVerdict

    data class Allowed(val warnings: List<InstallWarning>) : InstallVerdict
}

object InstallChecks {

    /**
     * [infos] are all the APKs of the file, in any order. [obbCount] is how many .obb the bundle held.
     */
    fun verdict(
        infos: List<ApkInfo>,
        device: DeviceProfile,
        sdkInt: Int,
        installed: InstalledApp?,
        obbCount: Int
    ): InstallVerdict {
        val bases = infos.filter { it.splitName == null }
        if (bases.isEmpty()) return InstallVerdict.Refused("There is no base APK in this file.")
        if (bases.size > 1) return InstallVerdict.Refused("This file holds more than one base APK.")
        val base = bases.single()
        if (infos.any { it.packageName != base.packageName }) {
            return InstallVerdict.Refused("The APKs of this file are for different apps.")
        }
        val splitNames = infos.mapNotNull { it.splitName }
        if (splitNames.size != splitNames.toSet().size) {
            return InstallVerdict.Refused("The same split is twice in this file.")
        }
        // Whether the app fits this phone comes first: it is the answer the user needs, whatever else is wrong.
        if (infos.any { it.minSdk > sdkInt }) {
            val needed = infos.maxOf { it.minSdk }
            return InstallVerdict.Refused("This app needs Android API level $needed or newer. This phone runs API level $sdkInt.")
        }
        abiProblem(infos, device)?.let { return InstallVerdict.Refused(it) }
        if (infos.any { !it.verified || it.signers.isEmpty() }) {
            return InstallVerdict.Refused("An APK of this file is not signed, or its signature is broken.")
        }
        val signer = base.signers.toSet()
        if (infos.any { it.signers.toSet() != signer }) {
            return InstallVerdict.Refused("The APKs of this file are signed with different keys.")
        }

        val warnings = mutableListOf<InstallWarning>()
        if (installed != null) {
            if (base.versionCode < installed.versionCode) {
                warnings += InstallWarning.Downgrade(installed.versionName, base.versionName)
            }
            if (!installed.sameSigner) warnings += InstallWarning.SignerChanged
        }
        if (obbCount > 0) warnings += InstallWarning.ObbSkipped(obbCount)
        return InstallVerdict.Allowed(warnings)
    }

    /**
     * The NativeLibraryHelper rule: native libraries in the files, in lib/<abi>/ folders or in ABI splits of the
     * base app, need one ABI the phone runs. Files without any are for every processor.
     */
    private fun abiProblem(infos: List<ApkInfo>, device: DeviceProfile): String? {
        val inLibs = infos.flatMap { it.abis }.toSet()
        val inSplits = infos.mapNotNull { info ->
            val split = info.splitName ?: return@mapNotNull null
            if (info.isFeatureSplit || !info.configForSplit.isNullOrEmpty()) return@mapNotNull null
            val suffix = split.substringAfterLast("config.", "")
            SplitTables.ABIS[suffix]
        }.toSet()
        val offered = inLibs + inSplits
        if (offered.isEmpty()) return null
        if (offered.any { it in device.abis }) return null
        return "This app does not support this phone's processor. It runs on ${offered.sorted().joinToString(", ")}, the phone needs ${device.abis.joinToString(" or ")}."
    }
}
