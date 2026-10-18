package com.yaz.store.core.apk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// The app's side of the isolated ApkInspectorService: bind, call once per APK with a timeout, unbind.
// Same client side as the shared safemedia kit's decoder calls (Modules/shared/safemedia, DecoderService.kt.tpl):
// the file goes over as a ParcelFileDescriptor, the answer comes back as a pipe of plain values. The Binder
// bind/unbind pattern is Android's bound services guide:
// https://developer.android.com/develop/background-work/services/bound-services
// The app process never parses an APK itself: what comes back is read by ApkInfoCodec, which caps and checks it.

class ApkInspector(private val context: Context) {

    /**
     * The inspector's answer for each of [files], in order; null for a file it could not read (not an APK,
     * damaged, too slow). One binding for the whole batch, released at the end.
     */
    suspend fun inspect(files: List<File>): List<ApkInfo?> {
        val binder = bind() ?: return files.map { null }
        try {
            return files.map { inspectOne(binder.service, it) }
        } finally {
            runCatching { context.unbindService(binder.connection) }
        }
    }

    private class Bound(val service: IBinder, val connection: ServiceConnection)

    private suspend fun bind(): Bound? = withTimeoutOrNull(BIND_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    if (cont.isActive) cont.resume(Bound(service, this))
                }

                override fun onServiceDisconnected(name: ComponentName) = Unit

                override fun onBindingDied(name: ComponentName) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onNullBinding(name: ComponentName) {
                    if (cont.isActive) cont.resume(null)
                }
            }
            val intent = Intent(context, ApkInspectorService::class.java)
            val started = try {
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (e: SecurityException) {
                false
            }
            if (!started) {
                runCatching { context.unbindService(connection) }
                cont.resume(null)
            }
            cont.invokeOnCancellation { runCatching { context.unbindService(connection) } }
        }
    }

    private suspend fun inspectOne(service: IBinder, file: File): ApkInfo? {
        val pipe = withContext(Dispatchers.IO) { call(service, file) } ?: return null
        return withTimeoutOrNull(CALL_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                // A plain thread: a read on a pipe cannot be interrupted, closing the pipe is what stops it.
                val thread = Thread {
                    val info = try {
                        ParcelFileDescriptor.AutoCloseInputStream(pipe).use { ApkInfoCodec.read(it) }
                    } catch (e: Exception) {
                        null
                    }
                    if (cont.isActive) cont.resume(info)
                }
                cont.invokeOnCancellation { runCatching { pipe.close() } }
                thread.start()
            }
        }.also { if (it == null) runCatching { pipe.close() } }
    }

    /** The transaction: the APK in, the pipe of the answer out. */
    private fun call(service: IBinder, file: File): ParcelFileDescriptor? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        var input: ParcelFileDescriptor? = null
        try {
            input = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            data.writeInterfaceToken(ApkInspectorService.DESCRIPTOR)
            data.writeFileDescriptor(input.fileDescriptor)
            service.transact(ApkInspectorService.INSPECT, data, reply, 0)
            reply.setDataPosition(0)
            return if (reply.readInt() == 1) reply.readFileDescriptor() else null
        } catch (e: Exception) {
            return null
        } finally {
            data.recycle()
            reply.recycle()
            input?.close()
        }
    }

    private companion object {
        const val BIND_TIMEOUT_MS = 10_000L
        /** One APK: reading its signatures goes through the whole file. */
        const val CALL_TIMEOUT_MS = 120_000L
    }
}
