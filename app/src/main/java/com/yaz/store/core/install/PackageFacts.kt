package com.yaz.store.core.install

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import com.yaz.store.core.apk.ApkInfoCodec
import com.yaz.store.core.apk.DeviceProfile

// What the phone says about itself and about the app being replaced.
// - The device: Build.SUPPORTED_ABIS in order, the screen density and the locale list in order, as App Manager's
//   StaticDataset reads them (GPL-3.0-or-later):
//   https://github.com/MuntashirAkon/AppManager/blob/master/app/src/main/java/io/github/muntashirakon/AppManager/StaticDataset.java
// - The installed app, its splits and its signers: PackageManager.getPackageInfo with GET_SIGNING_CERTIFICATES and
//   hasSigningCertificate, which follows a rotated key (APK Signature Scheme v3):
//   https://developer.android.com/reference/android/content/pm/PackageManager#hasSigningCertificate(java.lang.String,%20byte[],%20int)
//   as App Manager's PackageInstallerViewModel and AOSP's PackageInstaller app compare them.

object PackageFacts {

    /** This phone: ABIs, density, languages. */
    fun device(): DeviceProfile {
        val resources = Resources.getSystem()
        val locales = resources.configuration.locales
        return DeviceProfile(
            abis = Build.SUPPORTED_ABIS.toList(),
            density = resources.displayMetrics.densityDpi,
            languages = (0 until locales.size()).map { locales[it].language }.distinct()
        )
    }

    /**
     * The app named [packageName] if it is installed. [newSigners] are the SHA-256 of the signers of the files
     * to install; [InstalledApp.sameSigner] is whether the installed app is signed with every one of them.
     */
    fun installed(context: Context, packageName: String, newSigners: Set<String>): InstalledApp? {
        val pm = context.packageManager
        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        val same = newSigners.isNotEmpty() && newSigners.all { hex ->
            val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            pm.hasSigningCertificate(packageName, bytes, PackageManager.CERT_INPUT_SHA256)
        }
        return InstalledApp(
            versionCode = info.longVersionCode,
            versionName = ApkInfoCodec.clean(info.versionName.orEmpty(), 100),
            splitNames = info.splitNames?.toSet().orEmpty(),
            sameSigner = same
        )
    }

    /** The SHA-256 of the certificates Store itself is signed with. */
    fun ownSigners(context: Context): Set<String> {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, flags)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            return emptySet()
        }
        return info.signingInfo?.apkContentsSigners.orEmpty().map { ApkInfoCodec.sha256Hex(it.toByteArray()) }.toSet()
    }
}
