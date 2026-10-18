package com.yaz.store.core.apk

import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

// Ported from Amehrug's ZipGuard (https://github.com/213YaZ786/Amehrug,
// app/src/main/java/com/amehrug/app/backup/ZipGuard.kt), cut to the guard itself and given the caps of an APK
// bundle. Its sources: Apache POI's ZipSecureFile
// (https://github.com/apache/poi/blob/trunk/poi-ooxml/src/main/java/org/apache/poi/openxml4j/util/ZipSecureFile.java)
// for the entry count, entry size and expansion ratio with a grace size, Apache Commons IO's CountingInputStream
// (https://github.com/apache/commons-io/blob/master/src/main/java/org/apache/commons/io/input/CountingInputStream.java)
// for the compressed count, and Android's zip path traversal recipe
// (https://developer.android.com/privacy-and-security/risks/zip-path-traversal).

/** A zip that breaks a cap or names a path outside its folder. A bad file, not a crash. */
class ZipRefusedException(message: String) : IllegalArgumentException(message)

/**
 * Caps on a zip read from outside: entry count, size of an entry, the total, and the ratio of expanded to
 * compressed bytes with a grace size below which the ratio is not judged.
 */
data class ZipLimits(
    val maxEntries: Int,
    val maxTotalBytes: Long,
    val maxEntryBytes: Long,
    val maxRatio: Long,
    val graceBytes: Long,
) {
    companion object {
        /**
         * An .apks, .xapk or .apkm: a few hundred APKs at most, one of them up to the 4 GiB Play allows,
         * and the .obb of an xapk, which is counted while it is skipped.
         */
        val BUNDLE = ZipLimits(
            maxEntries = 2_000,
            maxTotalBytes = 16L * 1024 * 1024 * 1024,
            maxEntryBytes = 4L * 1024 * 1024 * 1024,
            maxRatio = 100,
            graceBytes = 1L * 1024 * 1024,
        )
    }
}

/**
 * Reads a zip entry by entry and stops it at the first cap broken. Bytes are counted as they come out of
 * the inflater, never from the size an entry declares, which a hostile file writes as it likes.
 */
class GuardedZip(source: InputStream, private val limits: ZipLimits) : Closeable {
    private val counting = CountedInput(source)
    private val zip = ZipInputStream(counting)
    private var entries = 0
    private var total = 0L
    private var entryBytes = 0L
    private var entryStart = 0L

    /** The bytes of the current entry. The caller never closes it: the zip owns it. */
    val stream: InputStream = object : FilterInputStream(zip) {
        override fun read(): Int {
            val value = super.read()
            if (value >= 0) counted(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) counted(read.toLong())
            return read
        }

        override fun skip(n: Long): Long {
            // ZipInputStream.skip inflates too: read, so the caps hold.
            val scratch = ByteArray(8192)
            var left = n
            while (left > 0) {
                val read = read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
                if (read < 0) break
                left -= read
            }
            return n - left
        }
    }

    fun nextEntry(): ZipEntry? {
        val entry = zip.nextEntry ?: return null
        entries += 1
        if (entries > limits.maxEntries) throw ZipRefusedException("too many entries in the zip")
        entryBytes = 0
        entryStart = counting.count
        return entry
    }

    private fun counted(bytes: Long) {
        entryBytes += bytes
        total += bytes
        if (entryBytes > limits.maxEntryBytes) throw ZipRefusedException("an entry inflates past its cap")
        if (total > limits.maxTotalBytes) throw ZipRefusedException("the zip inflates past its cap")
        if (entryBytes > limits.graceBytes) {
            val packed = maxOf(1L, counting.count - entryStart)
            if (entryBytes / packed > limits.maxRatio) throw ZipRefusedException("an entry inflates too much")
        }
    }

    override fun close() = zip.close()

    private class CountedInput(source: InputStream) : FilterInputStream(source) {
        var count = 0L
            private set

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) count += 1
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) count += read
            return read
        }

        override fun skip(n: Long): Long {
            val skipped = super.skip(n)
            if (skipped > 0) count += skipped
            return skipped
        }
    }
}

/**
 * Zip path traversal: the destination of an entry must stay inside the folder. The canonical path test is
 * Android's recipe (https://developer.android.com/privacy-and-security/risks/zip-path-traversal). An absolute
 * name or a `..` part is refused first: File(folder, "/x") would land inside the folder, but such a name is
 * never an honest bundle's.
 */
object ZipNames {
    private const val MAX_NAME = 1024

    fun resolve(folder: File, name: String): File {
        if (name.isEmpty() || name.length > MAX_NAME) throw ZipRefusedException("bad entry name")
        if (name.startsWith("/") || name.startsWith("\\") || name.contains('\u0000')) {
            throw ZipRefusedException("entry outside the folder")
        }
        if (name.split('/', '\\').any { it == ".." }) throw ZipRefusedException("entry outside the folder")
        val file = File(folder, name)
        val root = folder.canonicalPath
        if (!file.canonicalPath.startsWith(root + File.separator)) {
            throw ZipRefusedException("entry outside the folder")
        }
        return file
    }
}
