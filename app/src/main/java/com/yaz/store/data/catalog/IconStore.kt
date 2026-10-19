package com.yaz.store.data.catalog

// The icons of apps not installed: the repo's own icon.png (asked again at each opening) or the release's, kept on disk once read (a tag's files do not change), one file per tag,
// as Catalog.details keeps a tag's date and sizes (this app's data/catalog/Catalog.kt) and as GrapheneOS' App Store keeps the icons of its repo
// (https://github.com/GrapheneOS/AppStore, app/src/main/java/app/grapheneos/apps/core/Repo.kt). The bytes are never decoded here:
// the screen hands them to the isolated DecoderService (core/security/SafeImages).
import com.yaz.store.core.apk.ApkInfoCodec
import com.yaz.store.data.net.NetError
import com.yaz.store.data.net.ReleaseNet
import java.io.File

class IconStore(
    private val net: ReleaseNet,
    private val dir: File,
    /** Debug builds only (AppModule): pictures a test pushes, see [CatalogOverlay]. Nothing in the release build. */
    private val overlay: CatalogOverlay? = null
) {

    // Repos asked for their icon since the process started: one conditional request per repo and opening.
    private val asked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * The bytes of the icon of [entry]: the repo's own (the newest), else icon.png of its release, or null when it has none or cannot be read
     * now. Blocking: off the main thread.
     */
    fun bytes(entry: StoreEntry): ByteArray? {
        overlay?.icon(entry)?.let { return it }
        repoIcon(entry.repo)?.let { return it }
        return releaseIcon(entry)
    }

    // Source: GitHub's conditional requests (If-None-Match with the ETag of the last answer, 304 keeps the stored file, 200 replaces it,
    // https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api#use-conditional-requests-if-appropriate), on the
    // F-Droid layout path every repo of ours publishes (https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots), kept per repo
    // on disk as releaseIcon keeps a tag's. Asked once per opening; offline or limited, the stored file stays; a 404 means no icon in the repo.
    @Synchronized
    private fun repoIcon(repo: String): ByteArray? {
        val name = ApkInfoCodec.sha256Hex("repo:$repo".toByteArray())
        val file = File(dir, "$name.png")
        val tag = File(dir, "$name.etag")
        val stored = if (file.isFile && file.length() in 1..MAX_ICON) runCatching { file.readBytes() }.getOrNull() else null
        if (!asked.add(repo)) return stored
        val sent = if (stored != null) runCatching { tag.readText() }.getOrNull() else null
        val fresh = try {
            net.getIfChanged(ReleaseNet.repoIconUrl(repo), MAX_ICON, sent) ?: return stored
        } catch (e: NetError) {
            when (e.kind) {
                NetError.Kind.NOT_FOUND -> {
                    file.delete()
                    tag.delete()
                }
                NetError.Kind.OFFLINE, NetError.Kind.RATE_LIMITED, NetError.Kind.METERED -> asked.remove(repo)
                NetError.Kind.REFUSED -> Unit
            }
            return if (e.kind == NetError.Kind.NOT_FOUND) null else stored
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
        return fresh.bytes
    }

    // The release's icon.png, kept on disk per repo and tag (a tag's files do not change).
    private fun releaseIcon(entry: StoreEntry): ByteArray? {
        val url = entry.iconUrl ?: return null
        val file = File(dir, ApkInfoCodec.sha256Hex("${entry.repo}/${entry.tag}".toByteArray()) + ".png")
        if (file.isFile && file.length() in 1..MAX_ICON) runCatching { file.readBytes() }.getOrNull()?.let { return it }
        val bytes = try {
            net.getBytes(url, MAX_ICON)
        } catch (e: NetError) {
            return null
        }
        runCatching {
            dir.mkdirs()
            val temporary = File(dir, file.name + ".part")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(file)) temporary.delete()
        }
        return bytes
    }

    companion object {
        /** An app icon is a few kilobytes; the decoder caps the pixels as well. */
        const val MAX_ICON = 512L * 1024
    }
}
