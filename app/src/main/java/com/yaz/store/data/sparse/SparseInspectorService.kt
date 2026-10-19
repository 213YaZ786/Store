package com.yaz.store.data.sparse

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor

// The isolated process that reads an APK of which only a few ranges were fetched. Pattern ported from this app's core/apk/ApkInspectorService
// (the install kit, itself the shared safemedia kit's DecoderService): a plain Binder with no AIDL, the file coming in as a ParcelFileDescriptor
// (here with the stage the caller has reached) and the answer going out as a pipe of plain values, written by a thread so the call returns at once.
// Android's isolated process documentation: https://developer.android.com/guide/topics/manifest/service-element#isolated
// A second service, not a second transaction of the kit's: the install kit is shared code and stays as it is.
//
// Declared in the manifest, never exported:
// <service android:name=".data.sparse.SparseInspectorService" android:exported="false"
//     android:isolatedProcess="true" android:process=":sparse" />
//
// It has no permission, no files and no network.

class SparseInspectorService : Service() {

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != STEP) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DESCRIPTOR)
            val stage = data.readInt()
            val input = data.readFileDescriptor()
            if (input == null || reply == null) {
                input?.close()
                reply?.writeInt(0)
                return true
            }
            val pipe = ParcelFileDescriptor.createPipe()
            reply.writeInt(1)
            reply.writeFileDescriptor(pipe[0].fileDescriptor)
            pipe[0].close()
            Thread { answer(stage, input, pipe[1]) }.start()
            return true
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** Runs one step on [input] and writes the answer to [output]; on any failure the pipe closes with nothing in it. */
    private fun answer(stage: Int, input: ParcelFileDescriptor, output: ParcelFileDescriptor) {
        try {
            // One step at a time: ARSCLib decodes the table's strings through a charset decoder it keeps between calls, and two tables read at once
            // (the catalog probes four repos together) broke it inside Android's ICU (SIGABRT, seen 2026-10-19 on the Pixel_9a).
            val answer = ParcelFileDescriptor.AutoCloseInputStream(input).use { synchronized(LOCK) { SparseInspect.step(stage, it.channel) } }
            ParcelFileDescriptor.AutoCloseOutputStream(output).buffered().use { SparseWire.write(answer, it) }
        } catch (e: Exception) {
            runCatching { output.close() }
        }
    }

    companion object {
        internal const val DESCRIPTOR = "com.yaz.store.data.sparse.Inspector"
        internal const val STEP = IBinder.FIRST_CALL_TRANSACTION
        private val LOCK = Any()
    }
}
