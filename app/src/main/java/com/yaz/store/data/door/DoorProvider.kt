package com.yaz.store.data.door

// Store's door: the provider the update kit's StoreLink calls with "opened" and "left" (Modules shared/update).
// Sources: Contacts' CardsProvider (YAZ/Contacts at HEAD, data/contacts/CardsProvider.kt: a ContentProvider used only for call(),
// every table method empty, the caller checked inside call()), and Android's known-signer permission for the manifest
// (https://developer.android.com/guide/topics/permissions/defining, "knownSigner"). The caller is ContentProvider.getCallingPackage();
// Door.handle checks it again against our certificates. An answer without a "state" is a refusal; the extras are never read.
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import org.koin.core.context.GlobalContext

class DoorProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val door = door() ?: return Bundle()
        // A caller that cannot be named is refused by the guard (null); a provider not attached to a binder call has none to give.
        val caller = try { callingPackage } catch (e: RuntimeException) { null }
        val state = door.handle(method, caller) ?: return Bundle()
        return Bundle().apply { putString(Door.STATE, state) }
    }

    // Android publishes a provider before Application.onCreate has run, so a call that wakes Store from nothing can arrive while Koin is
    // still starting (seen live 2026-10-19: the first call after a force-stop got an empty answer). Wait for it, bounded; Koin's
    // GlobalContext.getOrNull() is null until startKoin has run (https://insert-koin.io/docs/reference/koin-core/context-isolation).
    private fun door(): Door? {
        // System.nanoTime is monotonic and not paused by Robolectric's clock, as SystemClock is.
        val end = System.nanoTime() + startWaitMs * 1_000_000
        while (true) {
            GlobalContext.getOrNull()?.let { return it.get<Door>() }
            if (System.nanoTime() >= end) return null
            Thread.sleep(POLL_MS)
        }
    }

    // Not a table: nothing can be read back through it.
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        /** How long a call waits for the application's start-up; a test shortens it. */
        internal var startWaitMs = 10_000L
        private const val POLL_MS = 25L

        /** Debug builds only: the lines of the door in logcat (no package of the user's, only ours). */
        const val TAG = "StoreDoor"

        fun debugLog(line: String) {
            if (com.yaz.store.BuildConfig.DEBUG) Log.d(TAG, line)
        }
    }
}
