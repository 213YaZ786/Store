package com.yaz.store.data.sparse

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

// The app's side of the isolated SparseInspectorService: bind once for the few steps of one APK, call it once per step with a timeout, unbind.
// Ported from core/apk/ApkInspector (the install kit): the same bind and transaction, the answer read by SparseWire, which caps and checks it.
// Android's bound services guide: https://developer.android.com/develop/background-work/services/bound-services
// The app process never parses an APK itself.

/** One step of the inspector on the file as it is now; null when it could not answer (died, too slow, an answer that does not pass). */
fun interface SparseStepper {
    suspend fun step(stage: Int, file: File): SparseAnswer?
}

/** Runs [block] with a stepper bound to the isolated process, released at the end. */
interface SparseSession {
    suspend fun <T> open(block: suspend (SparseStepper) -> T): T
}

class SparseInspector(private val context: Context) : SparseSession {

    override suspend fun <T> open(block: suspend (SparseStepper) -> T): T {
        val bound = bind() ?: return block { _, _ -> null }
        try {
            return block { stage, file -> stepOnce(bound.service, stage, file) }
        } finally {
            runCatching { context.unbindService(bound.connection) }
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
            val intent = Intent(context, SparseInspectorService::class.java)
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

    private suspend fun stepOnce(service: IBinder, stage: Int, file: File): SparseAnswer? {
        val pipe = withContext(Dispatchers.IO) { call(service, stage, file) } ?: return null
        return withTimeoutOrNull(CALL_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                // A plain thread: a read on a pipe cannot be interrupted, closing the pipe is what stops it.
                val thread = Thread {
                    val answer = try {
                        ParcelFileDescriptor.AutoCloseInputStream(pipe).use { SparseWire.read(it) }
                    } catch (e: Exception) {
                        null
                    }
                    if (cont.isActive) cont.resume(answer)
                }
                cont.invokeOnCancellation { runCatching { pipe.close() } }
                thread.start()
            }
        }.also { if (it == null) runCatching { pipe.close() } }
    }

    /** The transaction: the stage and the file in, the pipe of the answer out. */
    private fun call(service: IBinder, stage: Int, file: File): ParcelFileDescriptor? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        var input: ParcelFileDescriptor? = null
        try {
            input = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            data.writeInterfaceToken(SparseInspectorService.DESCRIPTOR)
            data.writeInt(stage)
            data.writeFileDescriptor(input.fileDescriptor)
            service.transact(SparseInspectorService.STEP, data, reply, 0)
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
        /** A step reads a few megabytes at most. */
        const val CALL_TIMEOUT_MS = 30_000L
    }
}
