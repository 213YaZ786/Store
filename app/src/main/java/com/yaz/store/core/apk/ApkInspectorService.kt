package com.yaz.store.core.apk

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor

// The isolated process that reads every APK from outside. Pattern ported from the shared safemedia kit's
// DecoderService (Modules/shared/safemedia/main/core/security/DecoderService.kt.tpl):
// a plain Binder with no AIDL, the file coming in as a ParcelFileDescriptor and the answer going out as a pipe of
// plain values, nothing parceled from outside data. Android's isolated process documentation:
// https://developer.android.com/guide/topics/manifest/service-element#isolated
//
// Declared in the manifest, never exported:
// <service android:name=".core.apk.ApkInspectorService" android:exported="false"
//     android:isolatedProcess="true" android:process=":inspector" />
//
// It has no permission, no files and no network. The answer is ApkInfoCodec's bytes, written by a thread so the
// call returns at once: the client can stop waiting, and closing the pipe ends the work.

class ApkInspectorService : Service() {

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != INSPECT) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DESCRIPTOR)
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
            Thread { answer(input, pipe[1]) }.start()
            return true
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** Inspects [input] and writes the answer to [output]; on any failure the pipe closes with nothing in it. */
    private fun answer(input: ParcelFileDescriptor, output: ParcelFileDescriptor) {
        try {
            val info = ParcelFileDescriptor.AutoCloseInputStream(input).use { ApkInspect.inspect(it.channel) }
            ParcelFileDescriptor.AutoCloseOutputStream(output).buffered().use { ApkInfoCodec.write(info, it) }
        } catch (e: Exception) {
            runCatching { output.close() }
        }
    }

    companion object {
        internal const val DESCRIPTOR = "com.yaz.store.core.apk.Inspector"
        internal const val INSPECT = IBinder.FIRST_CALL_TRANSACTION
    }
}
