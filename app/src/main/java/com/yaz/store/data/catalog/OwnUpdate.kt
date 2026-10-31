package com.yaz.store.data.catalog

// Store's own package. Decision (the user, 2026-10-31): Store shows itself in its own lists (Installed, Updates) and updates itself from its
// row or from "Update all", through the same queue as any app; the dialog at launch is gone. Sources:
// - GrapheneOS' App Store (MIT, https://github.com/GrapheneOS/AppStore) keeps its own package in its lists (no filter in
//   app/src/main/java/app/grapheneos/apps/core/PackageStates.kt, ui/PackagesScreen.kt, ui/UpdatesScreen.kt) and installs itself
//   last in an update of several apps, in the same flow (core/InstallStart.kt, startPackageUpdate, selfUpdateGroup_): data/install/InstallQueue's
//   ownPackage rule.
// - Google Play and Apple's App Store update themselves apart and never list themselves; F-Droid's client and Aurora Store list themselves
//   among the updates. GrapheneOS' way wins, as the user asked.

/** What Store's own release means for this phone. */
object OwnUpdate {
    /** Store and its debug build. */
    val PACKAGES: Set<String> = setOf("com.yaz.store", "com.yaz.store.debug")

    /** Whether [packageName] is Store's: its row has no Open (it would only reopen Store) and the door never serves it. */
    fun isOwn(packageName: String): Boolean = packageName in PACKAGES
}
