package com.yaz.store.data.sparse

// What an APK says when only a few byte ranges of it are on disk: the file has the APK's full length and holes where nothing was fetched
// (a sparse file), so apksig reads it at the same offsets as the real one. Run inside the isolated SparseInspectorService and nowhere else.
// Sources, all in this app's install kit or apksig (Apache-2.0, https://android.googlesource.com/platform/tools/apksig/):
// - the end of central directory is found in the tail by ApkUtils.findZipSections and the entries listed by ZipUtils.parseZipCentralDirectory,
//   which read the tail and the central directory only (ApkUtils.java, ZipUtils.java), exactly as core/apk/ApkInspect does;
// - an entry's local header, name, extra field and data are read by LocalFileRecord.getRecord/getUncompressedData (LocalFileRecord.java),
//   so a range is asked for each entry from its local header offset to the end of its data, with room for the extra field;
// - the package, versionCode, versionName, label and minSdk come from core/apk/ApkInspect.inspect (AndroidBinXmlParser and ARSCLib), the very
//   reading the full install path uses; its signature check fails on a sparse file and is ignored here (the full install checks it again).
import com.android.apksig.apk.ApkUtils
import com.android.apksig.internal.zip.ZipUtils
import com.android.apksig.util.DataSources
import com.yaz.store.core.apk.ApkInspect
import java.nio.channels.FileChannel

/** The next bytes the inspector wants fetched, and what they are for (each kind has its own cap in [SparseInspect]). */
data class Want(val start: Long, val length: Long, val kind: Kind) {
    enum class Kind { DIRECTORY, MANIFEST, TABLE }
}

/** What the manifest and resources of an APK say, as far as the store lists them. */
data class SparseInfo(val packageName: String, val versionCode: Long, val versionName: String, val label: String?, val minSdk: Int)

sealed interface SparseAnswer {
    /** Fetch these ranges, then ask again with the next stage. */
    data class Need(val wants: List<Want>) : SparseAnswer
    data class Info(val info: SparseInfo) : SparseAnswer
    /** Not an APK, too large a directory, no manifest, a split: nothing to list. */
    data object Refused : SparseAnswer
}

object SparseInspect {
    /** The end-of-central-directory record is 22 bytes plus a comment of 65535 at most: this tail holds any of them. */
    const val TAIL = 65_535L + 22 + 64
    /** Caps per kind (the user's brief): a central directory of 1 MiB, resources.arsc of 4 MiB, a manifest of 1 MiB. */
    const val MAX_DIRECTORY = 1L shl 20
    const val MAX_TABLE = 4L shl 20
    const val MAX_MANIFEST = 1L shl 20
    /** Room after a local header's name for its extra field (zipalign's padding is a few bytes; a page-aligned library 4 KiB). */
    private const val EXTRA_ROOM = 1024L
    private const val LOCAL_HEADER = 30L
    private const val MANIFEST = "AndroidManifest.xml"
    private const val TABLE = "resources.arsc"

    /**
     * One step. [stage] is how much the caller has fetched: 1 the tail, 2 the central directory too, 3 the manifest and the table too.
     * Stage 1 names the central directory, stage 2 the manifest's and the table's records, stage 3 reads them.
     */
    fun step(stage: Int, channel: FileChannel): SparseAnswer {
        val apk = DataSources.asDataSource(channel)
        try {
            val sections = ApkUtils.findZipSections(apk)
            val directory = sections.zipCentralDirectoryOffset
            val size = sections.zipCentralDirectorySizeBytes
            if (stage == 1) {
                if (size <= 0 || size > MAX_DIRECTORY || directory < 0 || directory + size > apk.size()) return SparseAnswer.Refused
                return SparseAnswer.Need(listOf(Want(directory, size, Want.Kind.DIRECTORY)))
            }
            if (stage == 2) {
                val records = ZipUtils.parseZipCentralDirectory(apk, sections)
                val manifest = records.firstOrNull { it.name == MANIFEST } ?: return SparseAnswer.Refused
                val wants = ArrayList<Want>(2)
                wants += want(manifest.localFileHeaderOffset, manifest.nameSizeBytes, manifest.compressedSize, manifest.uncompressedSize, directory, MAX_MANIFEST, Want.Kind.MANIFEST)
                    ?: return SparseAnswer.Refused
                records.firstOrNull { it.name == TABLE }?.let { table ->
                    // A table too large is left out: the label then comes from the manifest's own text, else the repo's name.
                    want(table.localFileHeaderOffset, table.nameSizeBytes, table.compressedSize, table.uncompressedSize, directory, MAX_TABLE, Want.Kind.TABLE)?.let { wants += it }
                }
                return SparseAnswer.Need(wants)
            }
            val info = ApkInspect.inspect(channel)
            if (info.splitName != null) return SparseAnswer.Refused
            return SparseAnswer.Info(SparseInfo(info.packageName, info.versionCode, info.versionName, info.label, info.minSdk))
        } catch (e: Exception) {
            // Not a zip, damaged, cut: the caller lists the repo by its name.
            return SparseAnswer.Refused
        }
    }

    // The range of one entry: its local header, name and extra field, then its data, ending where the central directory begins at the latest.
    private fun want(offset: Long, nameLength: Int, compressed: Long, uncompressed: Long, directory: Long, cap: Long, kind: Want.Kind): Want? {
        if (offset < 0 || compressed < 0 || compressed > cap || uncompressed < 0 || uncompressed > cap) return null
        val end = minOf(offset + LOCAL_HEADER + nameLength + EXTRA_ROOM + compressed, directory)
        if (end <= offset || end - offset > cap + LOCAL_HEADER + nameLength + EXTRA_ROOM) return null
        return Want(offset, end - offset, kind)
    }

    /** The most bytes one [kind] may take, whatever the inspector asks (the reader enforces it again). */
    fun capOf(kind: Want.Kind): Long = when (kind) {
        Want.Kind.DIRECTORY -> MAX_DIRECTORY
        Want.Kind.MANIFEST -> MAX_MANIFEST + LOCAL_HEADER + 255 + EXTRA_ROOM
        Want.Kind.TABLE -> MAX_TABLE + LOCAL_HEADER + 255 + EXTRA_ROOM
    }
}
