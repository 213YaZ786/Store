package com.yaz.store.data.catalog

// Store's own package, kept out of every list and offered as one dialog at launch. Sources:
// - Google Play and the App Store never list themselves among the apps to update; the store updates itself apart.
// - GrapheneOS' App Store updates itself through the same session path as any app, Store being its own installer
//   (https://github.com/GrapheneOS/AppStore, app/src/main/java/app/grapheneos/apps/core/InstallTask.kt, MIT): here the entry goes through
//   the same queue (data/install/InstallQueue).
// - The dialog at launch when a newer release exists is the update kit's UpdatePrompt (our code, GPL-3.0,
//   https://github.com/213YaZ786/Calculator/blob/main/app/src/main/java/com/yaz/calculator/ui/component/UpdatePrompt.kt); the state it
//   is decided from is Installed.stateOf (versionCode above the installed one, signed by our key, an APK for this phone).
import com.yaz.store.data.trust.OurKeys

/** What Store's own release means for this phone. */
object OwnUpdate {
    /** Store and its debug build. */
    val PACKAGES: Set<String> = setOf("com.yaz.store", "com.yaz.store.debug")

    /** The repo of Store on the account: a release of it that cannot be read is no entry of the list either. */
    const val REPO = "Store"

    /** Whether [entry] is Store itself: kept out of every list. */
    fun isOwn(entry: StoreEntry): Boolean = entry.packageName in PACKAGES || (entry.unread && entry.repo == REPO)

    /** Whether [packageName] is Store's: its queue entry shows in the update dialog only. */
    fun isOwn(packageName: String): Boolean = packageName in PACKAGES

    /** The newest release among the entries that are Store's own (read ones only), or null. */
    fun newest(entries: List<StoreEntry>): StoreEntry? =
        entries.filter { !it.unread && it.packageName in PACKAGES }.maxByOrNull { it.versionCode }

    /**
     * The release to offer: [own] when this build's [facts] say its state is UPDATE (versionCode above, signed by our key, an APK and a
     * minSdk that fit [sdk]). A debug build is signed with the debug key: it stands in as signed by ours, as the door's debug rule does
     * (data/door/Door.status).
     */
    fun pending(own: StoreEntry?, facts: InstalledFacts?, sdk: Int, store: String, debug: Boolean): StoreEntry? {
        if (own == null || facts == null) return null
        val shown = if (debug) facts.copy(signers = setOf(OurKeys.DIGESTS.first()), installer = store) else facts
        return own.takeIf { Installed.stateOf(it, shown, sdk, store) == AppState.UPDATE }
    }
}
