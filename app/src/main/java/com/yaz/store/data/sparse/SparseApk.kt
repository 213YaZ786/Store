package com.yaz.store.data.sparse

// Reads what a release APK says without downloading it: a local file of the APK's length is filled, at the same offsets, with the few ranges the
// isolated inspector asks for (the tail for the end of central directory, then the central directory, then AndroidManifest.xml and resources.arsc),
// like apksig reading an APK through a DataSource that holds only some of its bytes (ApkUtils.findZipSections, ZipUtils.parseZipCentralDirectory:
// https://android.googlesource.com/platform/tools/apksig/+/refs/heads/main/src/main/java/com/android/apksig/internal/zip/ZipUtils.java).
// The file is a RandomAccessFile set to the full length, so the holes read as zeros and cost no space on a sparse-file filesystem
// (https://developer.android.com/reference/java/io/RandomAccessFile#setLength(long)).
// Caps (the brief): the central directory 1 MiB, resources.arsc 4 MiB, 8 MiB fetched in all; they are enforced here whatever the inspector asks.
import com.yaz.store.data.net.NetError
import com.yaz.store.data.net.RangeSource
import com.yaz.store.data.net.ReleaseNet
import java.io.File
import java.io.RandomAccessFile

class SparseApk(private val session: SparseSession, private val work: File) {

    /**
     * What the APK at [source] says, or null when it cannot be read as an app (not a zip, no manifest, a split, an answer over the caps, a server
     * that does not answer ranges with 206). A lost network is a [NetError] of kind OFFLINE.
     */
    suspend fun read(source: RangeSource): SparseInfo? {
        val total = try {
            source.length()
        } catch (e: NetError) {
            if (e.kind == NetError.Kind.OFFLINE || e.kind == NetError.Kind.RATE_LIMITED) throw e
            return null
        }
        if (total < MIN_ZIP || total > ReleaseNet.MAX_APK) return null
        work.mkdirs()
        val file = File.createTempFile("sparse", ".apk", work)
        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(total)
                var spent = 0L
                fun fetch(start: Long, length: Long): Boolean {
                    if (start < 0 || length <= 0 || start + length > total || spent + length > MAX_TOTAL) return false
                    val bytes = source.read(start, start + length - 1)
                    raf.seek(start)
                    raf.write(bytes)
                    spent += length
                    return true
                }
                try {
                    if (!fetch(maxOf(0L, total - SparseInspect.TAIL), minOf(total, SparseInspect.TAIL))) return null
                    return session.open { stepper ->
                        // Stage 1 names the central directory, 2 the manifest and the table, 3 reads them.
                        for (stage in 1..3) {
                            val answer = stepper.step(stage, file) ?: return@open null
                            if (answer is SparseAnswer.Info) return@open if (stage == 3) answer.info else null
                            if (answer !is SparseAnswer.Need || stage == 3 || !allowed(answer.wants, stage)) return@open null
                            for (w in answer.wants) if (!fetch(w.start, w.length)) return@open null
                        }
                        null
                    }
                } catch (e: NetError) {
                    // A server that does not do ranges, or answers another way, is a repo listed by its name; a lost network is the caller's.
                    if (e.kind == NetError.Kind.OFFLINE || e.kind == NetError.Kind.RATE_LIMITED) throw e
                    return null
                }
            }
        } finally {
            file.delete()
        }
    }

    // Stage 1 may ask for the central directory only; stage 2 for the manifest, once, and the table, once.
    private fun allowed(wants: List<Want>, stage: Int): Boolean {
        val kinds = wants.map { it.kind }
        if (kinds.size != kinds.toSet().size) return false
        // The caps again, whatever the inspector says (SparseWire checks them too).
        if (wants.any { it.start < 0 || it.length <= 0 || it.length > SparseInspect.capOf(it.kind) }) return false
        return if (stage == 1) kinds == listOf(Want.Kind.DIRECTORY)
        else Want.Kind.MANIFEST in kinds && Want.Kind.DIRECTORY !in kinds
    }

    companion object {
        /** The most the reader fetches for one APK. */
        const val MAX_TOTAL = 8L shl 20
        /** An end-of-central-directory record is 22 bytes. */
        private const val MIN_ZIP = 22L
    }
}
