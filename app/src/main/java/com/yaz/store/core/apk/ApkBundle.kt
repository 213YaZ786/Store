package com.yaz.store.core.apk

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

// Ported from App Manager (GPL-3.0-or-later), ApkFile's constructor and its SUPPORTED_EXTENSIONS / SUPPORTED_MIMES:
// the type from the mime, else from the extension; an .apk alone, an .apks/.xapk/.apkm as a zip of .apk entries
// with the .obb entries left apart; an .apkm that is not a zip (encrypted) is refused:
// https://github.com/MuntashirAkon/AppManager/blob/master/app/src/main/java/io/github/muntashirakon/AppManager/apk/ApkFile.java
// Changes: the bundle is copied once to a folder of the app's cache with ZipGuard's caps and read nowhere else; no
// decryption of .apkm; entries are written under names of our own, after their names are checked.

/** A file that cannot be installed, with a plain sentence for the person. */
class BundleRefused(message: String) : IOException(message)

enum class BundleKind { APK, BUNDLE }

/** ApkFile.SUPPORTED_EXTENSIONS and SUPPORTED_MIMES, and the choice between them. */
object ApkTypes {
    val EXTENSIONS = listOf("apk", "apkm", "apks", "xapk")
    val MIMES = listOf(
        "application/x-apks",
        "application/vnd.android.package-archive",
        "application/vnd.apkm",
        "application/xapk-package-archive"
    )

    /** The extension a mime stands for, or [name]'s own when the mime is not one of ours; null if neither. */
    fun extensionOf(mime: String?, name: String?): String? {
        when (mime) {
            "application/x-apks" -> return "apks"
            "application/xapk-package-archive" -> return "xapk"
            "application/vnd.apkm" -> return "apkm"
            "application/vnd.android.package-archive" -> return "apk"
        }
        val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return ext.takeIf { it in EXTENSIONS }
    }

    fun kindOf(extension: String): BundleKind = if (extension == "apk") BundleKind.APK else BundleKind.BUNDLE
}

/** One APK copied to the install folder. */
class BundleApk(val file: File, val size: Long)

/** What a file opened: the APKs copied, and the names of what was left out. */
class BundleFiles(val folder: File, val apks: List<BundleApk>, val obbNames: List<String>)

object ApkBundle {
    /** The folder holding every attempt's copies, deleted at each start of the app and after each attempt. */
    const val FOLDER = "install"
    private const val ZIP_MAGIC = 0x504B0304
    private const val ZIP_EMPTY_MAGIC = 0x504B0506
    private const val COPY_BUFFER = 64 * 1024

    /** A new folder for one attempt, inside [cacheDir]/install. */
    fun newFolder(cacheDir: File): File = File(File(cacheDir, FOLDER), UUID.randomUUID().toString()).also { it.mkdirs() }

    /** Removes every copy a past attempt left. */
    fun sweep(cacheDir: File) {
        File(cacheDir, FOLDER).deleteRecursively()
    }

    /**
     * Copies [input], a file of type [extension], into [folder]: an .apk as is, a bundle entry by entry under
     * the caps of [limits]. [declaredSize] (or -1) is the size the provider gave, only used to refuse early
     * when the phone has less room.
     */
    fun copy(
        input: InputStream,
        extension: String,
        declaredSize: Long,
        folder: File,
        limits: ZipLimits = ZipLimits.BUNDLE,
        check: () -> Unit = {}
    ): BundleFiles {
        if (declaredSize > 0 && folder.usableSpace < declaredSize) {
            throw BundleRefused("There is not enough storage space to read this file.")
        }
        // [check] runs after every read, so a copy the person has left stops at once (it throws to stop). The proxy
        // stream is Apache Commons IO's ProxyInputStream with its afterRead hook:
        // https://github.com/apache/commons-io/blob/master/src/main/java/org/apache/commons/io/input/ProxyInputStream.java
        val source = object : java.io.FilterInputStream(input) {
            override fun read(): Int = super.read().also { check() }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also { check() }
        }
        return when (ApkTypes.kindOf(extension)) {
            BundleKind.APK -> copyApk(source, folder, limits)
            BundleKind.BUNDLE -> copyBundle(source, extension, folder, limits)
        }
    }

    private fun copyApk(input: InputStream, folder: File, limits: ZipLimits): BundleFiles {
        val file = File(folder, "base.apk")
        val size = copyCapped(input, file, limits.maxEntryBytes)
        if (size == 0L) throw BundleRefused("This file is empty.")
        return BundleFiles(folder, listOf(BundleApk(file, size)), emptyList())
    }

    private fun copyBundle(input: InputStream, extension: String, folder: File, limits: ZipLimits): BundleFiles {
        val buffered = java.io.BufferedInputStream(input, COPY_BUFFER)
        buffered.mark(4)
        val head = ByteArray(4)
        var read = 0
        while (read < 4) {
            val n = buffered.read(head, read, 4 - read)
            if (n < 0) break
            read += n
        }
        buffered.reset()
        val magic = if (read == 4) java.nio.ByteBuffer.wrap(head).int else 0
        // The signature of a zip's first header (PK 3 4), or of its end header (PK 5 6) for a zip with nothing in it,
        // as java.util.zip.ZipInputStream and ZipFile read them (https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/zip/ZipInputStream.html).
        val isZip = magic == ZIP_MAGIC || magic == ZIP_EMPTY_MAGIC
        if (!isZip) {
            if (extension == "apkm") {
                throw BundleRefused("This .apkm file is encrypted or not a bundle. Store cannot open it.")
            }
            throw BundleRefused("This file is not a bundle of APKs.")
        }
        val apks = mutableListOf<BundleApk>()
        val obbs = mutableListOf<String>()
        try {
            GuardedZip(buffered, limits).use { zip ->
                while (true) {
                    val entry = zip.nextEntry() ?: break
                    // The name is checked even when the entry is skipped: a bundle that names a path
                    // outside its folder is not an honest one.
                    val name = entry.name
                    ZipNames.resolve(folder, name)
                    if (entry.isDirectory) continue
                    val base = name.substringAfterLast('/').lowercase()
                    if (base.endsWith(".apk")) {
                        val file = File(folder, "split-${apks.size}.apk")
                        val size = copyCapped(zip.stream, file, limits.maxEntryBytes)
                        if (size > 0) apks += BundleApk(file, size)
                    } else {
                        if (base.endsWith(".obb")) obbs += name.substringAfterLast('/')
                        // Counted against the caps while it is read past.
                        zip.stream.skip(Long.MAX_VALUE)
                    }
                }
            }
        } catch (e: ZipRefusedException) {
            throw BundleRefused("This bundle is not safe to open: ${e.message}.")
        } catch (e: java.util.zip.ZipException) {
            throw BundleRefused("This bundle is damaged.")
        } catch (e: java.io.EOFException) {
            throw BundleRefused("This bundle is damaged or cut short.")
        }
        if (apks.isEmpty()) throw BundleRefused("There is no APK in this bundle.")
        return BundleFiles(folder, apks, obbs)
    }

    /** Copies [input] to [file], refusing past [max] bytes. Returns the bytes written. */
    private fun copyCapped(input: InputStream, file: File, max: Long): Long {
        var total = 0L
        java.io.FileOutputStream(file).use { out ->
            val buffer = ByteArray(COPY_BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > max) throw BundleRefused("This file is too large.")
                out.write(buffer, 0, n)
            }
            out.fd.sync()
        }
        return total
    }
}
