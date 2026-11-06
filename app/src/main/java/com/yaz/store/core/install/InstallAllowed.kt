package com.yaz.store.core.install

// Whether this phone lets an app install apps. Ported from GrapheneOS' App Store (MIT), app/src/main/java/app/grapheneos/apps/util/PackageManagerUtils.kt.
// not ported: isPrivilegedInstaller and its branch (only DISALLOW_INSTALL_APPS counts there): Store is never a privileged installer, it
// installs through a PackageInstaller session as an ordinary app, so the non-privileged branch is the only one.
// not ported: the file's other functions (getPackageInfoOrNull, getPackageInfo, getInstalledPackages, getApplicationInfo, resolveActivity,
// getPackageArchiveInfo, getSharedLibraries, hasSystemFeature, isSystemPackage, appDetailsIntent...): not this port's part.
import android.content.Context
import android.os.UserManager
import com.yaz.store.R

// https://github.com/GrapheneOS/AppStore/blob/main/app/src/main/java/app/grapheneos/apps/util/PackageManagerUtils.kt#UserRestrictionException
// The message is the plain sentence the person reads (strings.xml install_not_allowed).
class UserRestrictionException(message: String? = null) : SecurityException(message)

// https://github.com/GrapheneOS/AppStore/blob/main/app/src/main/java/app/grapheneos/apps/util/PackageManagerUtils.kt#throwIfAppInstallationNotAllowed
@Throws(UserRestrictionException::class)
fun throwIfAppInstallationNotAllowed(context: Context) {
    if (!isAppInstallationAllowed(context)) {
        throw UserRestrictionException(context.getString(R.string.install_not_allowed))
    }
}

// https://github.com/GrapheneOS/AppStore/blob/main/app/src/main/java/app/grapheneos/apps/util/PackageManagerUtils.kt#isAppInstallationAllowed
fun isAppInstallationAllowed(context: Context): Boolean {
    val um = context.getSystemService(UserManager::class.java)
    return !um.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
        && !um.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
        && !um.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS)
}
