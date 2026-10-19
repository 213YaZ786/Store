package com.yaz.store.data.catalog

// The one seam a debug build uses to change what the live catalog shows (description of an entry, icon of an app not installed). The release
// build has no implementation: AppModule gives Catalog and IconStore nothing but the identity. The implementation sits in src/debug
// (DebugMeta), found by name as Android's own debug-only hooks are (Class.forName, https://developer.android.com/reference/java/lang/Class#forName(java.lang.String)).
interface CatalogOverlay {
    /** [entries] with the overlay applied. */
    fun apply(entries: List<StoreEntry>): List<StoreEntry>

    /** The picture bytes the overlay has for [entry], or null; never decoded here (SafeImages does it). */
    fun icon(entry: StoreEntry): ByteArray?
}
