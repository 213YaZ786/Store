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
        // A call can come before the application's start-up has built the door: nothing is done.
        val door = GlobalContext.getOrNull()?.get<Door>() ?: return Bundle()
        val state = door.handle(method, callingPackage) ?: return Bundle()
        return Bundle().apply { putString(Door.STATE, state) }
    }

    // Not a table: nothing can be read back through it.
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        /** Debug builds only: the lines of the door in logcat (no package of the user's, only ours). */
        const val TAG = "StoreDoor"

        fun debugLog(line: String) {
            if (com.yaz.store.BuildConfig.DEBUG) Log.d(TAG, line)
        }
    }
}
