package com.yaz.store.data.door

// Who may knock on Store's door. Sources:
// - The check inside call(), on top of the manifest permission: Contacts' CardsProvider (YAZ/Contacts at HEAD,
//   data/contacts/CardsProvider.kt: "Android does not check a provider's permission on call(): checked here") and the phone kit's Sisters
//   (Modules shared/phone, core/dial/Sisters.kt, Dialer's copy: a sister's package is checked as ours, the ".debug" names count only in a debug build).
// - The caller's name is ContentProvider.getCallingPackage(), which Android verifies against the caller's uid
//   (https://developer.android.com/reference/android/content/ContentProvider#getCallingPackage()).
// - Our certificates: GrapheneOS' App Store compares the certificate digests of a package with the valid ones before it trusts it
//   (https://github.com/GrapheneOS/AppStore, app/src/main/java/app/grapheneos/apps/core/InstallTask.kt, findPackage); here OurKeys.allOurs.
// - A debug build: the caller signed like this Store (PackageManager.checkSignatures,
//   https://developer.android.com/reference/android/content/pm/PackageManager#checkSignatures(java.lang.String,%20java.lang.String)),
//   the way signature permissions are granted between debug builds; its ".debug" suffix is cut to find its catalog entry.

/** A caller that passed the guard. [base] is the package of its catalog entry; it differs from [pkg] by ".debug" under the debug rule only. */
data class Caller(val pkg: String, val base: String, val viaDebugRule: Boolean)

class DoorGuard(
    /** The SHA-256 digests (lower case) of the signers of a package, null when it is not installed or not visible. */
    private val signersOf: (String) -> Set<String>?,
    /** Whether the package is signed like this Store (PackageManager.checkSignatures == SIGNATURE_MATCH). */
    private val sameSignerAsStore: (String) -> Boolean,
    /** BuildConfig.DEBUG: the same-signer rule exists in a debug build only. */
    private val debug: Boolean
) {

    /** [pkg], the calling package as the provider sees it, as a [Caller], or null when it may not use the door. */
    fun check(pkg: String?): Caller? {
        if (pkg.isNullOrEmpty() || pkg.length > MAX_PACKAGE) return null
        // Release builds and debug builds alike: every signer one of ours.
        val signers = runCatching { signersOf(pkg) }.getOrNull()
        if (signers != null && com.yaz.store.data.trust.OurKeys.allOurs(signers)) return Caller(pkg, pkg, viaDebugRule = false)
        // A debug build only: an app with Store's own signer (the debug Store and the debug apps share a key).
        if (debug && runCatching { sameSignerAsStore(pkg) }.getOrDefault(false)) {
            return Caller(pkg, pkg.removeSuffix(DEBUG_SUFFIX), viaDebugRule = true)
        }
        return null
    }

    companion object {
        const val DEBUG_SUFFIX = ".debug"
        private const val MAX_PACKAGE = 255
    }
}
