package com.yaz.store.data.sparse

// The isolated inspector's answer as plain bytes, and the app's reading of them. The shape (a magic number, then fixed fields and counted lists
// read with DataInputStream, every count and every text capped before use, a bounded input) is core/apk/ApkInfo.kt's ApkInfoCodec, which follows the
// DecoderService pattern of the shared safemedia kit (a pipe of plain values, no Parcelable made from outside data); DataInputStream:
// https://developer.android.com/reference/java/io/DataInputStream and https://developer.android.com/reference/java/io/FilterInputStream.
import com.yaz.store.core.apk.ApkInfoCodec
import com.yaz.store.core.apk.InspectRefused
import com.yaz.store.data.catalog.CatalogRules
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream

object SparseWire {
    private const val MAGIC = 0x59535031
    private const val REFUSED = 0
    private const val NEED = 1
    private const val INFO = 2
    /** A real answer is a few hundred bytes. */
    const val MAX_WIRE = 64 * 1024
    private const val MAX_WANTS = 4

    fun write(answer: SparseAnswer, to: OutputStream) {
        val out = DataOutputStream(to)
        out.writeInt(MAGIC)
        when (answer) {
            SparseAnswer.Refused -> out.writeByte(REFUSED)
            is SparseAnswer.Need -> {
                out.writeByte(NEED)
                out.writeInt(answer.wants.size)
                for (w in answer.wants) {
                    out.writeLong(w.start)
                    out.writeLong(w.length)
                    out.writeByte(w.kind.ordinal)
                }
            }
            is SparseAnswer.Info -> {
                out.writeByte(INFO)
                val i = answer.info
                out.writeUTF(i.packageName)
                out.writeLong(i.versionCode)
                out.writeUTF(i.versionName)
                out.writeUTF(i.label.orEmpty())
                out.writeBoolean(i.label != null)
                out.writeInt(i.minSdk)
            }
        }
        out.flush()
    }

    /** Reads an answer, refusing anything cut, too large or malformed. */
    fun read(from: InputStream): SparseAnswer {
        try {
            val input = DataInputStream(Bounded(from, MAX_WIRE.toLong()))
            if (input.readInt() != MAGIC) throw InspectRefused("not an inspector answer")
            return when (input.readUnsignedByte()) {
                REFUSED -> SparseAnswer.Refused
                NEED -> {
                    val count = input.readInt()
                    if (count !in 1..MAX_WANTS) throw InspectRefused("bad count")
                    SparseAnswer.Need(List(count) {
                        val start = input.readLong()
                        val length = input.readLong()
                        val kind = Want.Kind.entries.getOrNull(input.readUnsignedByte()) ?: throw InspectRefused("bad kind")
                        if (start < 0 || length <= 0 || length > SparseInspect.capOf(kind)) throw InspectRefused("bad range")
                        Want(start, length, kind)
                    })
                }
                INFO -> {
                    val packageName = input.readUTF()
                    if (!CatalogRules.validPackage(packageName)) throw InspectRefused("bad package name")
                    val versionCode = input.readLong()
                    if (versionCode <= 0) throw InspectRefused("bad versionCode")
                    val versionName = ApkInfoCodec.clean(input.readUTF(), CatalogRules.MAX_VERSION_NAME)
                    val label = input.readUTF()
                    val hasLabel = input.readBoolean()
                    val minSdk = input.readInt()
                    if (minSdk !in 1..1000) throw InspectRefused("bad minSdkVersion")
                    SparseAnswer.Info(
                        SparseInfo(
                            packageName, versionCode, versionName,
                            if (hasLabel) ApkInfoCodec.clean(label, CatalogRules.MAX_LABEL).ifEmpty { null } else null,
                            minSdk
                        )
                    )
                }
                else -> throw InspectRefused("bad answer")
            }
        } catch (e: EOFException) {
            throw InspectRefused("answer cut short")
        }
    }

    /** At most [left] bytes of the source, then a refusal. */
    private class Bounded(source: InputStream, private var left: Long) : FilterInputStream(source) {
        override fun read(): Int {
            if (left <= 0) throw InspectRefused("answer too large")
            val value = super.read()
            if (value >= 0) left--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (left <= 0) throw InspectRefused("answer too large")
            val read = super.read(buffer, offset, minOf(length.toLong(), left).toInt())
            if (read > 0) left -= read
            return read
        }
    }
}
