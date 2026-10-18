package com.yaz.store.data.trust

// The certificates our apps are signed with, pinned in Store. Source: GrapheneOS' App Store pins each package's
// valid certificate digests as SHA-256 and compares them before and after an install
// (https://github.com/GrapheneOS/AppStore, app/src/main/java/app/grapheneos/apps/core/Repo.kt, RPackageContainer.validCertDigests,
// and core/InstallTask.kt, findPackage); the digest of a certificate's encoding is what apksigner prints
// (https://android.googlesource.com/platform/tools/apksig/+/refs/heads/main/src/apksigner/java/com/android/apksigner/ApkSignerTool.java).

/**
 * The SHA-256 digests of the signing certificates of our apps, lower case hex. Read 2026-10-18 from the released APKs
 * with apksigner. A new key comes only with a Store update; app.json never carries a certificate.
 */
object OurKeys {

    /** The pinned digests, one per key. */
    val DIGESTS: Set<String> = setOf(
        // YAZ apps (Dialer, SMS, Contacts, Calculator, Files, Keyboard, Passwords, Store, Voice, Browser)
        "f006b86dadc9be1c68deab3f53500dc04f7cb03a69d5bbd79daa0cad8160431b",
        // Amehrug
        "4691537a9fdad164e7c35af256565821c14bb657e518344209a5ac10c8a1d49a",
        // Mushaf
        "8b92df3096399ba496eac3b04cc43516f881165c8f6a7cd832ab5eb1f48179ec",
        // MTGA
        "ba03ab7dd2e037ea72478c6ebc3676e641da5adb642a9bec13bc978d3d81f395",
        // LinkedOut
        "2b47625e39d7b546c711ba4b2682d5cfa3cd08698a6f31517108a4620685d36d",
        // Frontr
        "56853fe216b763a7e63b83b2881db9502117960388a041236d54ac6e62b6ea1b",
        // Yid
        "5d936f4efa3e8db62b9f60aa007b60e31a56b3ff906e1f1c9122fd6800daf6b6"
    )

    /**
     * Whether every digest of [signers] is one of ours, compared case-insensitively. False for no signer at all.
     * GrapheneOS' findPackage accepts a package only when its certificate digests are among the valid ones.
     */
    fun allOurs(signers: Set<String>): Boolean =
        signers.isNotEmpty() && signers.all { it.lowercase() in DIGESTS }
}
