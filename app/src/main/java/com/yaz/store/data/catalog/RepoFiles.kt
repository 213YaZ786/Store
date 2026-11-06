package com.yaz.store.data.catalog

// A file of a repo's source tree (an icon, a list of layers, a short description) kept on disk and asked again once per opening with GitHub's
// conditional request. Taken out of IconStore (this app's data/catalog/IconStore.kt, its former private `conditional`) unchanged in behaviour so the
// icons and the descriptions share it; the one difference is the lock, per stored file instead of one for the whole class, so two repos' files
// are read side by side. The files are never decoded here.
import com.yaz.store.data.net.NetError
import com.yaz.store.data.net.ReleaseNet
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class RepoFiles(
    private val dir: File,
    /** The conditional read: ReleaseNet.getIfChanged (url, cap, last ETag) in the app, a canned answer in a test. */
    private val fetch: (url: String, max: Long, etag: String?) -> ReleaseNet.Fresh?
) {

    // One lock per stored file name, so a file is read and written by one caller at a time.
    private val locks = ConcurrentHashMap<String, Any>()

    // Source: GitHub's conditional requests (If-None-Match with the ETag of the last answer, 304 keeps the stored file, 200 replaces it,
    // https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api#use-conditional-requests-if-appropriate), on the
    // F-Droid layout path every repo of ours publishes (https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots), kept on disk
    // as IconStore.releaseIcon keeps a tag's. [key] names what is asked, [seen] the keys asked since the start: one request per key and opening.
    // Offline, rate-limited or refused: the stored file stays. A 404 means the repo has no such file: it is deleted and null comes back.
    // Body of IconStore.conditional (this app's data/catalog/IconStore.kt, before this class).
    fun conditional(key: String, name: String, extension: String, url: String, max: Long, seen: MutableSet<String>): ByteArray? =
        synchronized(locks.getOrPut(name) { Any() }) {
            val file = File(dir, "$name.$extension")
            val tag = File(dir, "$name.etag")
            val stored = if (file.isFile && file.length() in 1..max) runCatching { file.readBytes() }.getOrNull() else null
            if (!seen.add(key)) return@synchronized stored
            val sent = if (stored != null) runCatching { tag.readText() }.getOrNull() else null
            val fresh = try {
                fetch(url, max, sent) ?: return@synchronized stored
            } catch (e: NetError) {
                when (e.kind) {
                    NetError.Kind.NOT_FOUND -> {
                        file.delete()
                        tag.delete()
                    }
                    NetError.Kind.OFFLINE, NetError.Kind.RATE_LIMITED, NetError.Kind.METERED, NetError.Kind.ROAMING -> seen.remove(key)
                    NetError.Kind.REFUSED -> Unit
                }
                return@synchronized if (e.kind == NetError.Kind.NOT_FOUND) null else stored
            }
            runCatching {
                dir.mkdirs()
                val temporary = File(dir, file.name + ".part")
                temporary.writeBytes(fresh.bytes)
                if (temporary.renameTo(file)) {
                    if (fresh.etag != null) tag.writeText(fresh.etag) else tag.delete()
                } else {
                    temporary.delete()
                }
            }
            fresh.bytes
        }
}
